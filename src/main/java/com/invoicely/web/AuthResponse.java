package com.invoicely.web;

import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.security.JwtService.IssuedToken;
import java.time.Instant;

/**
 * What every {@code /auth} endpoint hands back: a token to authenticate with,
 * plus enough about the caller that the UI does not need a second round trip
 * before it can render a name in the header or decide which nav items to show.
 *
 * <p>{@code mustChangePassword} is the one field that is not just "nice to
 * have" — the UI reads it to decide whether to show the forced-change
 * interstitial instead of the normal app (Product Scope §5.1). It is only ever
 * {@code true} coming out of {@code /auth/login}: register and change-password
 * both leave the caller with nothing left to do.
 */
public record AuthResponse(
        String token,
        Instant expiresAt,
        Long userId,
        String name,
        String email,
        Role role,
        Long businessId,
        String businessName,
        boolean mustChangePassword) {

    /** Builds the response from the user a token was just issued for. */
    public static AuthResponse of(User user, IssuedToken issued) {
        return new AuthResponse(
                issued.token(),
                issued.expiresAt(),
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getBusiness().getId(),
                user.getBusiness().getName(),
                user.isMustChangePassword());
    }
}
