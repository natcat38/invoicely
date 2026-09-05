package com.invoicely.domain;

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
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Someone who logs in: the business owner, or a staff member the owner created.
 *
 * <p>A user belongs to one business for life — there is no moving between
 * businesses, and no user without one.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Lazy, like every association in this codebase: loading a user must not
     * silently drag its business (and eventually half the database) along.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false, updatable = false)
    private Business business;

    @Column(nullable = false)
    private String name;

    /** Unique across the whole application, case-insensitively — see V1. */
    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * When this user's password was last changed, used to reject access
     * tokens issued before that moment
     * (docs/adr/0010-session-invalidation-and-login-throttling.md).
     *
     * <p>Null means "never changed since the account was created" — every
     * existing account and every freshly registered one starts this way, and
     * stays this way until its first password change, so no token can predate
     * a change that never happened. Only
     * {@code AuthService.changePassword} ever writes this field, and nothing
     * ever clears it back to null.
     */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /** Stored as text ('OWNER'/'STAFF'), not an ordinal: ordinals break the
     *  moment someone reorders the enum. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    /** True for a freshly created staff account: blocks every other endpoint
     *  until the temporary password has been replaced. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /** Deactivated users keep their rows so invoice attribution survives. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected User() {
    }

    public User(Business business, String name, String email, String passwordHash, Role role) {
        this.business = business;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public Role getRole() {
        return role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
