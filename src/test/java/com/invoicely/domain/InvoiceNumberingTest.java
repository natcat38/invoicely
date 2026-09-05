package com.invoicely.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The numbering format, checked without a database.
 *
 * <p>{@link InvoiceNumbering} asks the repository one question — "what is the
 * highest number this business has used this year?" — and everything else is
 * string handling. Stubbing that one answer lets the interesting cases be
 * written directly, including the ones that are tedious to reach for real: the
 * first invoice a business ever issues, the roll from 0009 to 0010, and the
 * 9,999th.
 *
 * <p>That the numbers are actually unique per business is a database
 * guarantee, and is proved against real PostgreSQL in
 * {@code DomainPersistenceTest}. See docs/adr/0004-invoice-numbering.md.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceNumberingTest {

    private static final Long BUSINESS = 1L;
    private static final LocalDate IN_2026 = LocalDate.of(2026, 2, 1);

    @Mock
    private InvoiceRepository invoices;

    @Test
    @DisplayName("a business that has never invoiced starts at 0001")
    void theFirstInvoiceOfTheYear() {
        when(invoices.highestNumber(BUSINESS, "INV-2026-")).thenReturn(null);

        assertThat(numbering().next(BUSINESS, IN_2026)).isEqualTo("INV-2026-0001");
    }

    @Test
    @DisplayName("the sequence continues from the highest already issued")
    void theNextInvoice() {
        when(invoices.highestNumber(BUSINESS, "INV-2026-")).thenReturn("INV-2026-0041");

        assertThat(numbering().next(BUSINESS, IN_2026)).isEqualTo("INV-2026-0042");
    }

    @Test
    @DisplayName("padding holds as the sequence gains a digit")
    void paddingSurvivesTheRollover() {
        when(invoices.highestNumber(BUSINESS, "INV-2026-")).thenReturn("INV-2026-0009");
        assertThat(numbering().next(BUSINESS, IN_2026)).isEqualTo("INV-2026-0010");

        when(invoices.highestNumber(BUSINESS, "INV-2026-")).thenReturn("INV-2026-0999");
        assertThat(numbering().next(BUSINESS, IN_2026)).isEqualTo("INV-2026-1000");
    }

    @Test
    @DisplayName("the year comes from the issue date, so backdating files it under that year")
    void theYearComesFromTheIssueDate() {
        // Written in January, dated back into December: it belongs to the old
        // year's sequence, and the prefix the repository is asked for says so.
        when(invoices.highestNumber(BUSINESS, "INV-2025-")).thenReturn("INV-2025-0120");

        assertThat(numbering().next(BUSINESS, LocalDate.of(2025, 12, 31)))
                .isEqualTo("INV-2025-0121");
    }

    @Test
    @DisplayName("past 9,999 in a year it refuses rather than issuing a wider number")
    void itRefusesToOverflowTheFormat() {
        when(invoices.highestNumber(BUSINESS, "INV-2026-")).thenReturn("INV-2026-9999");

        // A five-digit number would sort before every four-digit one, quietly
        // breaking the "highest so far" lookup this whole scheme rests on.
        // Failing loudly is the only safe answer.
        assertThatThrownBy(() -> numbering().next(BUSINESS, IN_2026))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9,999");
    }

    private InvoiceNumbering numbering() {
        return new InvoiceNumbering(invoices);
    }
}
