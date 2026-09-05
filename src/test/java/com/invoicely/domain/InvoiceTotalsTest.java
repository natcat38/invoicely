package com.invoicely.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The money arithmetic, checked against the worked examples in Tech Scope §2.
 *
 * <p>No Spring and no database: {@link InvoiceTotals} is a pure function of an
 * invoice, so testing it needs nothing but objects. That is worth preserving —
 * money rules are the thing most worth being able to test quickly.
 */
class InvoiceTotalsTest {

    @Test
    @DisplayName("a GST-registered business charges 9% on top of the subtotal")
    void registeredBusinessChargesGst() {
        Invoice invoice = invoiceFor(gstRegisteredAt("0.0900"));
        invoice.addLineItem("Design", new BigDecimal("2"), new BigDecimal("400.00"));
        invoice.addLineItem("Build", new BigDecimal("1"), new BigDecimal("620.00"));

        InvoiceTotals totals = InvoiceTotals.of(invoice);

        assertThat(totals.subtotal()).isEqualByComparingTo("1420.00");
        assertThat(totals.gst()).isEqualByComparingTo("127.80");
        assertThat(totals.total()).isEqualByComparingTo("1547.80");
        assertThat(totals.balance())
                .as("nothing paid yet, so the whole total is outstanding")
                .isEqualByComparingTo("1547.80");
        assertThat(totals.hasGst()).isTrue();
    }

    @Test
    @DisplayName("a business that is not GST-registered shows no GST line at all")
    void unregisteredBusinessChargesNoGst() {
        Invoice invoice = invoiceFor(new Business("Sole Trader"));
        invoice.addLineItem("Design", new BigDecimal("2"), new BigDecimal("400.00"));
        invoice.addLineItem("Build", new BigDecimal("1"), new BigDecimal("620.00"));

        InvoiceTotals totals = InvoiceTotals.of(invoice);

        assertThat(totals.total()).isEqualByComparingTo("1420.00");
        assertThat(totals.gstRate())
                .as("null rather than zero, so the document omits the line instead of printing 0.00")
                .isNull();
        assertThat(totals.hasGst()).isFalse();
    }

    @Test
    @DisplayName("payments reduce the balance, and a full payment brings it to exactly zero")
    void paymentsReduceTheBalance() {
        Invoice invoice = invoiceFor(gstRegisteredAt("0.0900"));
        invoice.addLineItem("Design", new BigDecimal("2"), new BigDecimal("400.00"));
        invoice.addLineItem("Build", new BigDecimal("1"), new BigDecimal("620.00"));
        User owner = invoice.getCreatedBy();

        invoice.addPayment(new BigDecimal("500.00"), LocalDate.of(2026, 3, 1),
                PaymentMethod.PAYNOW, owner);
        assertThat(InvoiceTotals.of(invoice).balance()).isEqualByComparingTo("1047.80");

        invoice.addPayment(new BigDecimal("1047.80"), LocalDate.of(2026, 3, 8),
                PaymentMethod.BANK_TRANSFER, owner);
        assertThat(InvoiceTotals.of(invoice).balance())
                .as("exactly zero is what the Task 5 lifecycle will read as PAID")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("once sent, the snapshotted rate is used and the live setting is ignored")
    void theSnapshotWinsOverTheLiveSetting() {
        Business business = gstRegisteredAt("0.0900");
        Invoice invoice = invoiceFor(business);
        invoice.addLineItem("Consulting", new BigDecimal("1"), new BigDecimal("1000.00"));

        // The invoice went out at 9%. sentAt is what marks it as issued — the
        // snapshot alone is not enough, precisely so that an unregistered
        // business (which snapshots null) is still treated as having sent.
        invoice.setGstRateSnapshot(new BigDecimal("0.0900"));
        invoice.setSentAt(Instant.now());
        invoice.setStatus(InvoiceStatus.SENT);
        // The business later re-registers at a different rate.
        business.setGstRate(new BigDecimal("0.1100"));

        InvoiceTotals totals = InvoiceTotals.of(invoice);

        assertThat(totals.gstRate())
                .as("the client already has this document; its numbers must not move")
                .isEqualByComparingTo("0.0900");
        assertThat(totals.total()).isEqualByComparingTo("1090.00");
    }

    @Test
    @DisplayName("rounding happens once on the subtotal, not once per line")
    void roundingIsAppliedOnceToTheSubtotal() {
        Invoice invoice = invoiceFor(new Business("Sole Trader"));
        // Three lines of 0.005. Rounded per line they would be 0.01 each and sum
        // to 0.03; rounded once, the true 0.015 becomes 0.02. The second is
        // correct, and the gap is what grows into a real discrepancy on a long
        // invoice.
        for (int line = 0; line < 3; line++) {
            invoice.addLineItem("Fractional", new BigDecimal("1"), new BigDecimal("0.005"));
        }

        assertThat(InvoiceTotals.of(invoice).subtotal()).isEqualByComparingTo("0.02");
    }

    @Test
    @DisplayName("an invoice with no lines is worth nothing rather than failing")
    void anEmptyInvoiceTotalsZero() {
        InvoiceTotals totals = InvoiceTotals.of(invoiceFor(gstRegisteredAt("0.0900")));

        assertThat(totals.subtotal()).isEqualByComparingTo("0.00");
        assertThat(totals.total()).isEqualByComparingTo("0.00");
        assertThat(totals.balance()).isEqualByComparingTo("0.00");
    }

    private Business gstRegisteredAt(String rate) {
        Business business = new Business("Acme Renovations");
        business.setGstRegistered(true);
        business.setGstRate(new BigDecimal(rate));
        return business;
    }

    private Invoice invoiceFor(Business business) {
        User owner = new User(business, "Ada Owner", "ada@acme.example", "hash", Role.OWNER);
        Client client = new Client(business, "Bright Cafe");
        LocalDate issued = LocalDate.of(2026, 2, 1);
        return new Invoice(business, client, owner, "INV-2026-0001", issued, issued.plusDays(30));
    }
}
