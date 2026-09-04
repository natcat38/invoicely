package com.invoicely;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security, replaced wholesale in Task 4.
 *
 * <p>Every endpoint is open and there is no authentication at all. That is
 * deliberate for this slice: Task 3 builds and proves the CRUD shape, and the
 * Tech Scope allows it to run against a permit-all stub so the two pieces of
 * work do not have to land together.
 *
 * <p><b>This must not reach a deployed environment.</b> Task 4 replaces this
 * class with a JWT resource-server configuration, deletes
 * {@link com.invoicely.web.CurrentRequest}'s header-reading stub, and puts
 * {@code @PreAuthorize} on the owner-only endpoints.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // No cookies and no sessions yet, so there is no CSRF vector to
                // protect. Task 4 keeps this disabled for the same reason: the
                // API authenticates with a bearer token, not a cookie.
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
