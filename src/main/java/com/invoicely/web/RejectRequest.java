package com.invoicely.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body for rejecting an invoice.
 *
 * <p>The note is required, not optional. Product Scope §5.3 shows it back to
 * the staff member alongside their returned draft, and "rejected" with no
 * reason gives them nothing to act on — which would make the queue a way of
 * losing work rather than a way of checking it.
 */
public record RejectRequest(
        @NotBlank(message = "Say why you are sending this back.")
        @Size(max = 1000, message = "Keep the note under 1000 characters.")
        String note) {
}
