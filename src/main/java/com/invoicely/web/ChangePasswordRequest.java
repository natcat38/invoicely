package com.invoicely.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body for {@code POST /auth/change-password}.
 *
 * <p>The current password is required even though the caller is already
 * authenticated: a bearer token proves "this request carries a valid token",
 * not "the person typing right now is the account owner". Requiring the
 * current password is what stops someone who has grabbed an unlocked, signed-
 * in device from locking the real owner out.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Enter your current password.")
        String currentPassword,

        @NotBlank(message = "Enter a new password.")
        @Size(min = 8, message = "Passwords need at least 8 characters.")
        String newPassword) {
}
