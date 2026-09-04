package com.invoicely.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single small business — the root of everything else and the ownership
 * boundary of the whole application. Users, clients and invoices all belong to
 * exactly one business, and every query filters by it.
 *
 * <p>See {@code docs/adr/0001-business-as-ownership-boundary.md}.
 */
@Entity
@Table(name = "businesses")
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "gst_registered", nullable = false)
    private boolean gstRegistered;

    /**
     * Stored as a fraction, so 9% is {@code 0.0900}. Kept even while the
     * business is not GST-registered, so switching registration back on does
     * not lose the rate.
     */
    @Column(name = "gst_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal gstRate = new BigDecimal("0.0900");

    /** 7, 14 or 30. New invoices default their due date to issue date + this. */
    @Column(name = "default_payment_terms_days", nullable = false)
    private int defaultPaymentTermsDays = 30;

    /**
     * Set once, when the object is first constructed. Hibernate overwrites it
     * with the stored value when loading an existing row, so this initialiser
     * only ever applies to genuinely new businesses.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** For JPA only — it instantiates entities reflectively before populating them. */
    protected Business() {
    }

    public Business(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isGstRegistered() {
        return gstRegistered;
    }

    public void setGstRegistered(boolean gstRegistered) {
        this.gstRegistered = gstRegistered;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public void setGstRate(BigDecimal gstRate) {
        this.gstRate = gstRate;
    }

    public int getDefaultPaymentTermsDays() {
        return defaultPaymentTermsDays;
    }

    public void setDefaultPaymentTermsDays(int defaultPaymentTermsDays) {
        this.defaultPaymentTermsDays = defaultPaymentTermsDays;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
