package com.invoicely.web;

import com.invoicely.domain.Business;
import java.math.BigDecimal;

/**
 * The business settings, as the owner's Settings page shows them.
 *
 * <p>{@code address} and {@code uen} are the invoice document's letterhead —
 * see {@code docs/adr/0011-invoice-document-data-contract.md}. Either may be
 * null, meaning the owner has not filled it in yet.
 */
public record SettingsResponse(
        Long id,
        String name,
        boolean gstRegistered,
        BigDecimal gstRate,
        int defaultPaymentTermsDays,
        String address,
        String uen) {

    static SettingsResponse from(Business business) {
        return new SettingsResponse(
                business.getId(),
                business.getName(),
                business.isGstRegistered(),
                business.getGstRate(),
                business.getDefaultPaymentTermsDays(),
                business.getAddress(),
                business.getUen());
    }
}
