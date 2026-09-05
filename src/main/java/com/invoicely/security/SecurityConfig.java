package com.invoicely.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * The real security configuration, replacing the permit-all stub Task 3 ran
 * behind.
 *
 * <p>Requests authenticate with a bearer token and nothing else: no sessions,
 * no cookies, no login form. That is why CSRF protection stays off — CSRF
 * exists because browsers attach cookies to cross-site requests automatically,
 * and they do not do that for an {@code Authorization} header. See
 * docs/adr/0002-jwt-shape-and-storage.md.
 *
 * <p>Authorisation happens on two levels. This class decides which paths need a
 * token at all; {@code @PreAuthorize} on the owner-only endpoints decides who
 * may use them, because that rule belongs next to the method it protects rather
 * than in a path list that would drift away from it.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** HS256 needs at least 256 bits of key. */
    private static final int KEY_BITS = 256;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccountStateFilter accountStateFilter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                // Nothing is kept between requests: the token carries the whole
                // of the caller's identity, so there is no session to create.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Registering and logging in are how a caller obtains a
                        // token, so they cannot require one.
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                        // Deliberately open, and cheap, so a load balancer can poll it.
                        .requestMatchers(HttpMethod.GET, "/ping").permitAll()
                        // The API documentation describes the API; it exposes no
                        // data of its own, and a reviewer should be able to read
                        // it without first obtaining a token.
                        .requestMatchers(HttpMethod.GET,
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter())))
                // After authentication, before anything acts on it: the token
                // has been verified by now, so the user id in it can be trusted
                // enough to look the account up.
                .addFilterAfter(accountStateFilter, BasicAuthenticationFilter.class)
                .build();
    }

    /**
     * Turns the {@code role} claim into a {@code ROLE_OWNER} / {@code ROLE_STAFF}
     * authority, which is the form {@code hasRole('OWNER')} looks for. Without
     * this, Spring would look for a {@code scope} claim and find nothing.
     */
    private JwtAuthenticationConverter authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * The key that signs and verifies access tokens.
     *
     * <p>If {@code invoicely.security.secret} is not configured, a random key is
     * generated at startup. That keeps local development to zero setup and makes
     * it impossible to ship a secret that was committed to this repository — but
     * it means tokens do not survive a restart, and two instances would each
     * sign with a different key. Anything beyond one developer's laptop must set
     * the property.
     */
    @Bean
    SecretKey jwtSigningKey(SecurityProperties properties) {
        String configured = properties.secret();
        if (configured != null && !configured.isBlank()) {
            byte[] keyBytes = configured.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length * 8 < KEY_BITS) {
                throw new IllegalStateException(
                        "invoicely.security.secret must be at least 32 characters for HS256.");
            }
            return new SecretKeySpec(keyBytes, "HmacSHA256");
        }

        log.warn("No invoicely.security.secret configured, so a random signing key was generated. "
                + "Access tokens will not survive a restart. Set the property outside development.");
        try {
            KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
            generator.init(KEY_BITS);
            return generator.generateKey();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM", impossible);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey signingKey) {
        return NimbusJwtDecoder.withSecretKey(signingKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * BCrypt at its default strength. It is deliberately slow, which is the
     * point: it makes guessing a stolen hash expensive.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
