package com.invoicely.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The state half of the lifecycle rules, checked against the matrix in Product
 * Scope §4 cell by cell.
 *
 * <p>No Spring and no database — a transition table is a pure function, and
 * keeping the test that way means the whole matrix is verified in milliseconds
 * rather than through 25 HTTP requests.
 *
 * <p>The role half is tested separately, over HTTP, in
 * {@code InvoiceLifecycleApiTest}. That split is the point: an invoice can
 * refuse a request because of its state (409) or because of who is asking
 * (403), and conflating them would hide either one.
 */
class InvoiceStatusTest {

    @Test
    @DisplayName("a draft may be submitted for approval or sent directly")
    void draftTransitions() {
        assertThat(legalFrom(InvoiceStatus.DRAFT))
                .containsExactlyInAnyOrder(InvoiceStatus.PENDING_APPROVAL, InvoiceStatus.SENT);
    }

    @Test
    @DisplayName("a submitted invoice may be rejected back to draft or approved and sent")
    void pendingApprovalTransitions() {
        assertThat(legalFrom(InvoiceStatus.PENDING_APPROVAL))
                .containsExactlyInAnyOrder(InvoiceStatus.DRAFT, InvoiceStatus.SENT);
    }

    @Test
    @DisplayName("a sent invoice may only fall overdue or be paid")
    void sentTransitions() {
        assertThat(legalFrom(InvoiceStatus.SENT))
                .containsExactlyInAnyOrder(InvoiceStatus.OVERDUE, InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("an overdue invoice may only be paid — it never goes back")
    void overdueTransitions() {
        assertThat(legalFrom(InvoiceStatus.OVERDUE))
                .containsExactly(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("paid is terminal")
    void paidIsTerminal() {
        assertThat(legalFrom(InvoiceStatus.PAID)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(InvoiceStatus.class)
    @DisplayName("no status may transition to itself")
    void nothingTransitionsToItself(InvoiceStatus status) {
        // Re-sending an already-sent invoice, or re-submitting one that is
        // already awaiting approval, is a 409 rather than a silent no-op: the
        // caller believed something was true that was not.
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = InvoiceStatus.class, names = {"SENT", "OVERDUE", "PAID"})
    @DisplayName("nothing that has been issued can be un-sent")
    void nothingReturnsToAnUnsentState(InvoiceStatus issued) {
        // Restricted by names rather than skipped with an `if`: a parameterised
        // case that runs no assertion still reports as passing, which overstates
        // what the suite actually checks.
        assertThat(issued.canTransitionTo(InvoiceStatus.DRAFT)).isFalse();
        assertThat(issued.canTransitionTo(InvoiceStatus.PENDING_APPROVAL)).isFalse();
    }

    @Test
    @DisplayName("only a rejection returns an invoice to DRAFT")
    void onlyRejectionReturnsToDraft() {
        assertThat(InvoiceStatus.PENDING_APPROVAL.canTransitionTo(InvoiceStatus.DRAFT)).isTrue();
        assertThat(InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.DRAFT)).isFalse();
    }

    @Test
    @DisplayName("issued means the client has it: sent, overdue or paid")
    void issuedCoversEverythingTheClientHasSeen() {
        assertThat(EnumSet.allOf(InvoiceStatus.class).stream().filter(InvoiceStatus::isIssued))
                .containsExactlyInAnyOrder(
                        InvoiceStatus.SENT, InvoiceStatus.OVERDUE, InvoiceStatus.PAID);
    }

    private Set<InvoiceStatus> legalFrom(InvoiceStatus from) {
        EnumSet<InvoiceStatus> legal = EnumSet.noneOf(InvoiceStatus.class);
        for (InvoiceStatus target : InvoiceStatus.values()) {
            if (from.canTransitionTo(target)) {
                legal.add(target);
            }
        }
        return legal;
    }
}
