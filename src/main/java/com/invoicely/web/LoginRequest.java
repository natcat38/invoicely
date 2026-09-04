package com.invoicely.web;

import jakarta.validation.constraints.NotBlank;

/**
 * The body for {@code POST /auth/login}.
 *
 * <p>Deliberately unconstrained beyond "present": a login attempt is not the
 * moment to tell a caller their password is too short — that would leak
 * information about which failures mean "wrong password" versus "not even a
 * password we'd accept". {@link AuthService#login} answers every kind of
 * failure the same way.
 */
public record LoginRequest(
        @NotBlank(message = "Enter your email address.")
        String email,

        @NotBlank(message = "Enter your password.")
        String password) {
}
