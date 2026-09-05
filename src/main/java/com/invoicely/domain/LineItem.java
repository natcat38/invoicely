package com.invoicely.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One billable line on an invoice. Created and removed only through
 * {@link Invoice#addLineItem} and {@link Invoice#removeLineItem}, which keep
 * both ends of the association and the {@link #position} ordering consistent.
 */
@Entity
@Table(name = "line_items")
public class LineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Column(nullable = false)
    private String description;

    /** Decimal, so half-days and part-hours are billable. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    /** Zero-based display order, maintained by the Invoice helper methods. */
    @Column(nullable = false)
    private int position;

    protected LineItem() {
    }

    LineItem(Invoice invoice, String description, BigDecimal quantity, BigDecimal unitPrice) {
        this.invoice = invoice;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    /**
     * {@code quantity × unitPrice}, unrounded. Rounding to two decimals happens
     * once, at the API boundary — rounding here would compound the error across
     * every line. See knowledge/domain/money.md.
     */
    public BigDecimal lineTotal() {
        return quantity.multiply(unitPrice);
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /** Zero-based display order — read back by tests proving positions stay dense after a removal. */
    public int getPosition() {
        return position;
    }

    void setPosition(int position) {
        this.position = position;
    }
}
