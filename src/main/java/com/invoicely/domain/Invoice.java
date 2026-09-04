package com.invoicely.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An invoice, with its line items and payments.
 *
 * <p>Two collections hang off this entity, and they behave differently on
 * purpose:
 *
 * <ul>
 *   <li><b>line items</b> are part of the invoice. They are created and deleted
 *       with it ({@code cascade = ALL}, {@code orphanRemoval}) and have no
 *       meaning on their own.
 *   <li><b>payments</b> are appended facts. They cascade on save so a new
 *       payment is persisted along with its invoice, but dropping one from the
 *       list must never quietly delete the record — so no {@code orphanRemoval}.
 * </ul>
 *
 * <p>Both are mutated only through the helper methods below, which also set the
 * back-reference on the child. Setting just one side leaves the in-memory graph
 * disagreeing with the database until the next reload, which is the most common
 * bug in bidirectional JPA associations.
 */
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The ownership boundary. Never taken from a request body — see ADR-0001. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false, updatable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** Audit attribution, not access control. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    /** {@code INV-<year>-<seq>}, unique per business. Assigned at creation. */
    @Column(nullable = false, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    /**
     * Defaults to the issue date plus the payment terms of the business, and is
     * editable per invoice.
     */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /**
     * The GST rate of the business, copied here when the invoice is sent, or
     * null while it is still a draft. Once written it is never changed, so a
     * later change to the business setting cannot rewrite history.
     */
    @Column(name = "gst_rate_snapshot", precision = 5, scale = 4)
    private BigDecimal gstRateSnapshot;

    /** Why the owner sent it back to DRAFT. Shown to the staff member. */
    @Column(name = "rejection_note")
    private String rejectionNote;

    @Column(name = "sent_at")
    private Instant sentAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sent_by")
    private User sentBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<LineItem> lineItems = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST)
    @OrderBy("paidAt ASC, id ASC")
    private List<Payment> payments = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(Business business, Client client, User createdBy, String number,
                   LocalDate issueDate, LocalDate dueDate) {
        this.business = business;
        this.client = client;
        this.createdBy = createdBy;
        this.number = number;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
    }

    /** Appends a line item at the end and returns it, so the caller can adjust it. */
    public LineItem addLineItem(String description, BigDecimal quantity, BigDecimal unitPrice) {
        LineItem item = new LineItem(this, description, quantity, unitPrice);
        item.setPosition(lineItems.size());
        lineItems.add(item);
        return item;
    }

    /**
     * Removes a line item and closes the gap it left, so positions stay
     * {@code 0..n-1}. Because of {@code orphanRemoval} the row is deleted on
     * the next flush.
     */
    public void removeLineItem(LineItem item) {
        if (lineItems.remove(item)) {
            for (int i = 0; i < lineItems.size(); i++) {
                lineItems.get(i).setPosition(i);
            }
        }
    }

    /**
     * Drops every line item. Used when a draft is replaced wholesale: working
     * out which of the submitted lines are edits of existing rows and which are
     * new would buy nothing, since only drafts can be edited and nothing yet
     * refers to a line item by id.
     */
    public void clearLineItems() {
        lineItems.clear();
    }

    /** Records money received. Returns the payment so a note can be attached. */
    public Payment addPayment(BigDecimal amount, LocalDate paidAt, PaymentMethod method, User recordedBy) {
        Payment payment = new Payment(this, amount, paidAt, method, recordedBy);
        payments.add(payment);
        return payment;
    }

    public Long getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public String getNumber() {
        return number;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public BigDecimal getGstRateSnapshot() {
        return gstRateSnapshot;
    }

    public void setGstRateSnapshot(BigDecimal gstRateSnapshot) {
        this.gstRateSnapshot = gstRateSnapshot;
    }

    public String getRejectionNote() {
        return rejectionNote;
    }

    public void setRejectionNote(String rejectionNote) {
        this.rejectionNote = rejectionNote;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public User getSentBy() {
        return sentBy;
    }

    public void setSentBy(User sentBy) {
        this.sentBy = sentBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Unmodifiable: the helper methods above are the only way to change these. */
    public List<LineItem> getLineItems() {
        return Collections.unmodifiableList(lineItems);
    }

    public List<Payment> getPayments() {
        return Collections.unmodifiableList(payments);
    }
}
