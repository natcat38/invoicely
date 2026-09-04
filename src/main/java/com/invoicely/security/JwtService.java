package com.invoicely.security;

import com.invoicely.domain.User;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

/**
 * Issues the access tokens {@code POST /auth/login} hands out.
 *
 * <p>The token carries three claims and nothing else:
 *
 * <ul>
 *   <li>{@code sub} — the user id, for audit attribution
 *   <li>{@code biz} — the business id, the ownership boundary every query is
 *       scoped by (ADR-0001)
 *   <li>{@code role} — OWNER or STAFF, which becomes a Spring Security
 *       authority so {@code @PreAuthorize} can read it
 * </ul>
 *
 * <p>Nothing that can change between logins is in the token — not the email,
 * not {@code active}, not {@code must_change_password}. Those are read from the
 * database on every request by {@link AccountStateFilter}, which is what makes
 * deactivating a staff member take effect on their next request rather than
 * their next login. See docs/adr/0002-jwt-shape-and-storage.md.
 */
@Service
public class JwtService {

    public static final String BUSINESS_CLAIM = "biz";
    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final Duration tokenLifetime;

    JwtService(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.tokenLifetime = properties.tokenLifetime();
    }

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenLifetime);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("invoicely")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim(BUSINESS_CLAIM, user.getBusiness().getId())
                .claim(ROLE_CLAIM, user.getRole().name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }

    /**
     * @param token     the bearer token, to be sent as {@code Authorization: Bearer <token>}
     * @param expiresAt when it stops working, so a client can log out ahead of
     *                  the first failed request rather than after it
     */
    public record IssuedToken(String token, Instant expiresAt) {
    }
}
