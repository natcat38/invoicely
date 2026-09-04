package com.invoicely.domain;

/** How a payment reached the business. Recorded for the owner's own reference. */
public enum PaymentMethod {
    BANK_TRANSFER,
    PAYNOW,
    CASH,
    CHEQUE
}
