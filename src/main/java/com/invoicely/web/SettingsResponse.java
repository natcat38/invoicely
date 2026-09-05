package com.invoicely.web;

import com.invoicely.domain.Business;
import java.math.BigDecimal;

/** The business settings, as the owner's Settings page shows them. */
public record SettingsResponse(
        Long id,
        String name,
        boolean gstRegistered,
        BigDecimal gstRate,
        int defaultPaymentTermsDays) {

    static SettingsResponse from(Business business) {
        return new SettingsResponse(
                business.getId(),
                business.getName(),
                business.isGstRegistered(),
                business.getGstRate(),
                business.getDefaultPaymentTermsDays());
    }
}
