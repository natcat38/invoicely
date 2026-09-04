package com.invoicely.domain;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Allocates the next invoice number for a business: {@code INV-<year>-<seq>},
 * with the sequence restarting at 0001 each January and running independently
 * per business, so two businesses may both hold an INV-2026-0001.
 *
 * <p>The number is derived from the highest one already issued rather than kept
 * in a counter column. That means there is no counter to drift out of step with
 * the invoices it is supposed to describe, and nothing extra to migrate. It
 * relies on the caller holding the per-business lock — see
 * {@link BusinessRepository#findByIdForUpdate} and
 * docs/adr/0004-invoice-numbering.md.
 *
 * <p><b>This depends on PostgreSQL's default READ COMMITTED isolation.</b> A
 * second create blocks on the business lock, and once it acquires it, its next
 * statement takes a fresh snapshot and therefore sees the invoice the first one
 * just committed. Raising the isolation level to REPEATABLE READ would break
 * that: the query below would still read the pre-lock snapshot, miss the new
 * invoice, and hand out a number that already exists. The unique constraint
 * would catch it, but as an intermittent failure rather than a correct answer.
 */
@Component
public class InvoiceNumbering {

    private static final String PREFIX = "INV-";
    /** Four digits, as in the INV-2026-0042 example in Product Scope §5.4. */
    private static final String SEQUENCE_FORMAT = "%04d";
    private static final int SEQUENCE_LIMIT = 10_000;

    private final InvoiceRepository invoices;

    InvoiceNumbering(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    /**
     * The next number for this business in the year the invoice is issued —
     * issue date, not today, so backdating an invoice into December keeps it in
     * that year's sequence.
     */
    public String next(Long businessId, LocalDate issueDate) {
        String yearPrefix = PREFIX + issueDate.getYear() + "-";
        String highest = invoices.highestNumber(businessId, yearPrefix);
        int nextSequence = highest == null ? 1 : sequenceOf(highest) + 1;

        if (nextSequence >= SEQUENCE_LIMIT) {
            // Four digits caps a business at 9,999 invoices per year. Failing
            // loudly beats issuing a five-digit number, which would sort before
            // every four-digit one and quietly break the "highest so far"
            // lookup above. Widen the format and backfill if anyone nears this.
            throw new IllegalStateException(
                    "Business " + businessId + " has issued 9,999 invoices in "
                            + issueDate.getYear() + ", the most this numbering format allows.");
        }
        return yearPrefix + SEQUENCE_FORMAT.formatted(nextSequence);
    }

    /** The trailing digits of INV-2026-0042. */
    private int sequenceOf(String number) {
        return Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
    }
}
