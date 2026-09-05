package com.invoicely.domain;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * What day it is, according to the business rather than the server.
 *
 * <p>Invoicely serves Singapore only — SGD, GST, UEN — so "today", "past the
 * due date" and "this month" all mean what they mean in Singapore. A container
 * almost always runs on UTC, which is eight hours behind: an invoice that fell
 * due at midnight in Singapore would keep reporting as merely SENT until 08:00
 * local, so the overdue total and the top of the invoice list would be wrong
 * every morning, and a payment recorded late on the last day of a month would
 * land in the wrong month's revenue.
 *
 * <p>Setting {@code TZ} on the deployment would also fix it, and that is the
 * problem: a correctness rule would then depend on environment configuration
 * that fails silently when someone forgets it. It is stated here instead, where
 * it can be read and tested.
 *
 * <p>Multi-region is out of scope (Product Scope §6 — single currency). Should
 * that change, this constant becomes a per-business setting, and this class is
 * the one place that has to learn about it.
 */
public final class BusinessCalendar {

    public static final ZoneId ZONE = ZoneId.of("Asia/Singapore");

    private BusinessCalendar() {
    }

    /** Today, in the business's own timezone. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
