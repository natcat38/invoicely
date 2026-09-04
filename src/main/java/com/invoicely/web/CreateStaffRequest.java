package com.invoicely.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the owner supplies to add a staff member. Product Scope §5.1: no
 * invite emails — the server generates a temporary password and hands it back
 * once in {@link CreatedStaffResponse}, rather than the owner choosing one.
 */
public record CreateStaffRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email @Size(max = 255) String email) {
}
