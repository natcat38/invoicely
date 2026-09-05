package com.invoicely.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The client fields a caller may set, whether creating one or replacing one
 * wholesale with PUT.
 *
 * <p>Everything but {@code name} is optional (Product Scope §5.2) — a client is
 * often created in a hurry while drafting the first invoice, with the rest
 * filled in later. The size caps below are an application-level sanity check,
 * not a database limit: the {@code clients} columns are plain {@code text}, so
 * nothing stops a huge value at the schema level.
 *
 * <p>PUT reuses this same shape, {@code archived} included: Product Scope §5.2
 * treats archiving as an edit rather than a separate action, so there is no
 * dedicated archive/unarchive endpoint. On POST, {@code archived} is ignored —
 * a client cannot be born archived — so it only matters on PUT.
 */
public record ClientRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String contactPerson,
        @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 500) String address,
        // Singapore UEN is at most 10 characters; the cap leaves headroom
        // rather than rejecting a real one on an edge case.
        @Size(max = 20) String uen,
        @Size(max = 2000) String paymentNotes,

        /**
         * Boxed, and optional. Creating a client with
         * {@code {"name": "Bright Cafe"}} is the obvious request to send, and a
         * primitive here made that a 400 — Jackson cannot bind a missing value
         * to a {@code boolean}, and the caller got "Failed to read request"
         * with no clue which field it meant.
         *
         * <p>Absent means false. On create that is the only possibility — a
         * client is never born archived — and on PUT it follows from the verb:
         * a full replace that does not say "archived" is describing a client
         * that is not archived.
         */
        Boolean archived) {
}
