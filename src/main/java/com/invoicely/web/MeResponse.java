package com.invoicely.web;

import com.invoicely.domain.Role;
import com.invoicely.domain.User;

/**
 * The caller's own identity, for {@code GET /auth/me} — the UI's answer to
 * "who is signed in?" after a page reload, when all it still has is a token
 * pulled out of storage and nothing else about the user who owns it.
 *
 * <p>Mirrors {@link AuthResponse}'s identity fields, minus {@code token} and
 * {@code expiresAt}. It is a separate record rather than {@code AuthResponse}
 * with the token field left null, because that would force a choice between
 * two bad options: return a null token from a response type every other
 * caller expects to carry a working one, or actually issue a fresh token here
 * too. The second option is the more tempting bug — it looks harmless, since
 * the caller already had a valid token to get this far — but it would let
 * every {@code GET /auth/me} silently push the 12-hour session ADR-0002
 * deliberately chose further into the future. This is a read; it must not be
 * able to extend anything.
 */
public record MeResponse(
        Long userId,
        String name,
        String email,
        Role role,
        Long businessId,
        String businessName,
        boolean mustChangePassword) {

    /** Builds the response from the user a valid token names. */
    public static MeResponse of(User user) {
        return new MeResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getBusiness().getId(),
                user.getBusiness().getName(),
                user.isMustChangePassword());
    }
}
