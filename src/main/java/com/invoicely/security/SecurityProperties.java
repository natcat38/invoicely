package com.invoicely.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security settings, all overridable per environment.
 *
 * @param secret        the HMAC key that signs access tokens. Leave it unset
 *                      and one is generated at startup, which is right for
 *                      local development and wrong for anything else — see
 *                      {@link SecurityConfig}.
 * @param tokenLifetime how long an access token stays valid. There are no
 *                      refresh tokens (docs/adr/0002-jwt-shape-and-storage.md),
 *                      so this is also how long a session lasts.
 */
@ConfigurationProperties(prefix = "invoicely.security")
public record SecurityProperties(String secret, Duration tokenLifetime) {

    public SecurityProperties {
        if (tokenLifetime == null) {
            tokenLifetime = Duration.ofHours(12);
        }
    }
}
