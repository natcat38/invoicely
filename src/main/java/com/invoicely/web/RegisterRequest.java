package com.invoicely.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body for {@code POST /auth/register}.
 *
 * <p>Product Scope §5.1: registering creates the business and its OWNER
 * account in one step — there is no separate "create a business" screen, and
 * no invite flow, because the owner is the first person to ever use the
 * account.
 *
 * <p>Notice what is absent: no role (the first user is always OWNER), no
 * {@code mustChangePassword} (that flag exists for staff on a temporary
 * password set by someone else — the owner chose their own), and no GST or
 * payment-terms settings (the {@link com.invoicely.domain.Business}
 * constructor already defaults those, and re-stating them here would just be
 * another place they could drift out of sync).
 *
 * @param businessName the business being registered
 * @param ownerName    the owner's display name
 * @param email        the owner's login email; must be unique, see
 *                     {@link AuthService#register}
 * @param password     the owner's own choice, so no forced change follows
 */
public record RegisterRequest(
        @NotBlank(message = "Enter a business name.")
        @Size(max = 255, message = "Keep the business name under 255 characters.")
        String businessName,

        @NotBlank(message = "Enter your name.")
        @Size(max = 255, message = "Keep your name under 255 characters.")
        String ownerName,

        @NotBlank(message = "Enter an email address.")
        @Email(message = "Enter a valid email address.")
        @Size(max = 255, message = "Keep the email address under 255 characters.")
        String email,

        // NIST-style: length is the only rule. No composition requirements
        // (no forced digit/symbol) — those are known to push people toward
        // weaker, more predictable passwords, not stronger ones.
        @NotBlank(message = "Enter a password.")
        @Size(min = 8, max = AuthService.MAX_PASSWORD_BYTES,
                message = "Passwords must be between 8 and 72 characters.")
        String password) {
}
