package com.invoicely.web;

import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import com.invoicely.security.TemporaryPasswords;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Team page's rules, scoped to the caller's business throughout — see
 * ADR-0001. {@link TeamController} stays thin, exactly like
 * {@link ClientController}/{@link ClientService}: HTTP shapes go in and out
 * here, and everything that is actually a rule about the team lives in this
 * class.
 *
 * <p>Every method returns a response record rather than an entity, the same
 * reason {@link ClientService} does: with {@code spring.jpa.open-in-view} off,
 * an entity cannot be touched once its transaction has closed, so the mapping
 * happens here while it is still open.
 */
@Service
@Transactional
public class TeamService {

    private final UserRepository users;
    private final BusinessRepository businesses;
    private final InvoiceRepository invoices;
    private final CurrentRequest currentRequest;
    private final PasswordEncoder passwordEncoder;

    TeamService(UserRepository users,
                BusinessRepository businesses,
                InvoiceRepository invoices,
                CurrentRequest currentRequest,
                PasswordEncoder passwordEncoder) {
        this.users = users;
        this.businesses = businesses;
        this.invoices = invoices;
        this.currentRequest = currentRequest;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Every user in the caller's business, alphabetical (Product Scope §2),
     * each carrying an invoices-created count and a last-active date.
     *
     * <p>Those two aggregates come from exactly two queries
     * ({@link InvoiceRepository#countAndLastCreatedByUser} and
     * {@link InvoiceRepository#lastSentByUser}), regardless of how many people
     * are on the team — not one query per staff member. This method's whole
     * job is to zip those rows onto the user list.
     */
    @Transactional(readOnly = true)
    List<StaffResponse> list() {
        Long businessId = currentRequest.businessId();
        List<User> team = users.findByBusinessIdOrderByNameAsc(businessId);

        Map<Long, Object[]> created = index(invoices.countAndLastCreatedByUser(businessId));
        Map<Long, Object[]> sent = index(invoices.lastSentByUser(businessId));

        return team.stream()
                .map(user -> StaffResponse.from(user).withActivity(
                        invoicesCreated(created, user.getId()),
                        lastActive(created, sent, user.getId())))
                .toList();
    }

    /** Turns a list of {@code [userId, ...]} rows into a lookup by that id. */
    private Map<Long, Object[]> index(List<Object[]> rows) {
        Map<Long, Object[]> byUserId = new HashMap<>();
        for (Object[] row : rows) {
            byUserId.put((Long) row[0], row);
        }
        return byUserId;
    }

    /** Zero for a user who has never created an invoice, not null — Product Scope §2. */
    private long invoicesCreated(Map<Long, Object[]> created, Long userId) {
        Object[] row = created.get(userId);
        return row == null ? 0 : (Long) row[1];
    }

    /**
     * The more recent of this user's own {@code created_at} (drafting an
     * invoice) and {@code sent_at} (sending one) — there is no separate
     * activity-tracking table, per the Tech Scope. Null when neither happened.
     */
    private Instant lastActive(Map<Long, Object[]> created, Map<Long, Object[]> sent, Long userId) {
        Object[] createdRow = created.get(userId);
        Instant lastCreated = createdRow == null ? null : (Instant) createdRow[2];

        Object[] sentRow = sent.get(userId);
        Instant lastSent = sentRow == null ? null : (Instant) sentRow[1];

        if (lastCreated == null) {
            return lastSent;
        }
        if (lastSent == null) {
            return lastCreated;
        }
        return lastCreated.isAfter(lastSent) ? lastCreated : lastSent;
    }

    /**
     * Adds a STAFF account. The owner never chooses the password: one is
     * generated here, hashed for storage, and handed back once in the response
     * — see {@link TemporaryPasswords} and {@link CreatedStaffResponse}.
     */
    CreatedStaffResponse create(CreateStaffRequest request) {
        // Email uniqueness is global, not per-business (User.getEmail()), so
        // this check has to look at the whole table rather than just this one.
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("email-taken",
                    "A user with this email already exists.");
        }

        Business business = businesses.findById(currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Business"));

        String temporaryPassword = TemporaryPasswords.generate();
        User staff = new User(
                business,
                request.name(),
                request.email(),
                passwordEncoder.encode(temporaryPassword),
                Role.STAFF);
        // Forces the change-password screen before this account can do
        // anything else — enforced by AccountStateFilter, not here.
        staff.setMustChangePassword(true);

        return CreatedStaffResponse.from(users.save(staff), temporaryPassword);
    }

    /**
     * Deactivates or reactivates a team member.
     *
     * @throws ConflictException if the owner is targeting their own account for
     *         deactivation — a business with no active owner would be locked
     *         out of everything owner-only, including undoing this
     */
    StaffResponse setActive(Long id, boolean active) {
        User user = load(id);
        if (!active && user.getId().equals(currentRequest.userId())) {
            throw new ConflictException("cannot-deactivate-self",
                    "You cannot deactivate your own account.");
        }
        user.setActive(active);
        // No save() call: user is managed inside this transaction, so
        // Hibernate writes the change back at commit — same pattern as
        // ClientService.update.
        return StaffResponse.from(user);
    }

    /** Ownership-scoped lookup, same shape every other service in this app uses. */
    private User load(Long id) {
        return users.findByIdAndBusinessId(id, currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Team member"));
    }
}
