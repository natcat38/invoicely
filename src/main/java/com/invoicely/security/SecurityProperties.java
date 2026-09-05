package com.invoicely.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security settings, all overridable per environment.
 *
 * @param secret         the HMAC key that signs access tokens. Leave it unset
 *                       and one is generated at startup, which is right for
 *                       local development and wrong for anything else — see
 *                       {@link SecurityConfig}.
 * @param tokenLifetime  how long an access token stays valid. There are no
 *                       refresh tokens (docs/adr/0002-jwt-shape-and-storage.md),
 *                       so this is also how long a session lasts.
 * @param allowedOrigins the browser origins CORS allows to call this API.
 *                       Always an explicit list, never {@code *}: a wildcard
 *                       would let any page on the internet call this API from
 *                       a visitor's browser, which is exactly what CORS
 *                       exists to prevent. Defaults to Vite's dev server so
 *                       local development needs no setup; a real deployment
 *                       overrides it with {@code INVOICELY_SECURITY_ALLOWED_ORIGINS}.
 */
@ConfigurationProperties(prefix = "invoicely.security")
public record SecurityProperties(String secret, Duration tokenLifetime, List<String> allowedOrigins) {

    public SecurityProperties {
        if (tokenLifetime == null) {
            tokenLifetime = Duration.ofHours(12);
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("http://localhost:5173");
        }
    }
}
