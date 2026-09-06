import { useState } from "react";
import { toast } from "sonner";
import { useAuth } from "@/auth/useAuth";
import { ApiError } from "@/lib/api";
import type { StaffMember } from "@/lib/types";
import { dateOfInstant } from "@/lib/format";
import { EmptyState, Loading, LoadError, PageHeader } from "@/components/Page";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { AddStaffDialog } from "./AddStaffDialog";
import { useSetActive, useTeamList } from "./useTeam";

/** The Product Scope writes roles in prose, not in the enum's own spelling. */
const ROLE_LABELS: Record<StaffMember["role"], string> = {
  OWNER: "Owner",
  STAFF: "Staff",
};

/**
 * The owner's Team page (Product Scope §5.1): every staff member, plus the
 * owner themselves, with how much each has done and the two actions this
 * screen offers — add one, deactivate one. The route already restricts this
 * screen to owners; staff never reach it.
 */
export function TeamScreen() {
  const { session } = useAuth();
  const team = useTeamList();
  const setActive = useSetActive();

  const [addOpen, setAddOpen] = useState(false);
  const [deactivating, setDeactivating] = useState<StaffMember | null>(null);

  async function reactivate(member: StaffMember) {
    // Unlike deactivating, reactivating has no side effect worth pausing to
    // confirm — it only restores access someone already had, so there is no
    // AlertDialog on this path.
    try {
      await setActive.mutateAsync({ id: member.id, active: true });
      toast.success(`${member.name} reactivated.`);
    } catch (caught) {
      toast.error((caught as ApiError).message);
    }
  }

  async function confirmDeactivate() {
    if (!deactivating) return;
    try {
      await setActive.mutateAsync({ id: deactivating.id, active: false });
      toast.success(`${deactivating.name} deactivated.`);
      setDeactivating(null);
    } catch (caught) {
      // The Deactivate button is hidden on the owner's own row precisely so
      // this 409 should not happen from this screen — but the API is what
      // actually enforces it, so a race (another tab, a second owner) still
      // needs a message, and its own sentence is the most specific one
      // available here.
      toast.error((caught as ApiError).message);
      setDeactivating(null);
    }
  }

  return (
    <div>
      <PageHeader
        title="Team"
        actions={<Button onClick={() => setAddOpen(true)}>Add staff</Button>}
      />

      {team.isPending ? <Loading label="Loading team…" /> : null}

      {team.isError ? <LoadError error={team.error} onRetry={() => team.refetch()} /> : null}

      {team.data && team.data.length === 0 ? (
        // The owner is always a row in this list, so this should never
        // actually render — it exists as a fallback for an unexpected
        // response, not a state the product designs around.
        <EmptyState title="No team members." />
      ) : null}

      {team.data && team.data.length > 0 ? (
        // The Table component scrolls its own overflow (ui/table.tsx); the
        // min-width keeps seven columns from being squeezed unreadable at
        // 375px instead of just scrolling sideways inside the table.
        <Table className="min-w-[760px]">
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Email</TableHead>
              <TableHead>Role</TableHead>
              <TableHead className="text-right">Invoices created</TableHead>
              <TableHead>Last active</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {team.data.map((member) => {
              // The API answers 409 if the owner tries to deactivate their
              // own account — there would be nobody left who could run the
              // business. Hiding the button on that one row is UX on top of
              // that rule, not a replacement for it: the API still refuses
              // the request regardless of what this screen renders.
              const isSelf = member.id === session?.user.userId;
              return (
                <TableRow key={member.id}>
                  <TableCell className="font-medium text-app-text">{member.name}</TableCell>
                  <TableCell>{member.email}</TableCell>
                  <TableCell>{ROLE_LABELS[member.role]}</TableCell>
                  <TableCell className="text-right">{member.invoicesCreated}</TableCell>
                  <TableCell>{member.lastActive ? dateOfInstant(member.lastActive) : "—"}</TableCell>
                  <TableCell>
                    <TeamStatusBadge active={member.active} />
                  </TableCell>
                  <TableCell className="text-right">
                    {isSelf ? null : member.active ? (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-destructive"
                        onClick={() => setDeactivating(member)}
                      >
                        Deactivate
                      </Button>
                    ) : (
                      <Button
                        variant="ghost"
                        size="sm"
                        // Shared across every row, like the archive button on
                        // ClientsScreen: one mutation instance, so
                        // reactivating one person briefly disables the button
                        // on all rows rather than tracking per-row state for
                        // a request this quick.
                        disabled={setActive.isPending}
                        onClick={() => reactivate(member)}
                      >
                        Reactivate
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      ) : null}

      <AddStaffDialog open={addOpen} onOpenChange={setAddOpen} />

      <AlertDialog
        open={deactivating !== null}
        onOpenChange={(open) => {
          if (!open) setDeactivating(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Deactivate {deactivating?.name}?</AlertDialogTitle>
            <AlertDialogDescription>
              They will no longer be able to sign in. Their invoice history is unaffected, and
              reactivating them later needs no confirmation.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                // The primitive would otherwise close the dialog immediately
                // on click; that has to wait for the request to finish, so
                // the mutation's own success/error handling above is what
                // closes it instead.
                event.preventDefault();
                confirmDeactivate();
              }}
              disabled={setActive.isPending}
            >
              {setActive.isPending ? "Deactivating…" : "Deactivate"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/**
 * Active vs. deactivated, in the same "quiet outline unless it's news"
 * language as `StatusBadge` (`components/StatusBadge.tsx`) — reactivated
 * accounts should look ordinary, and a deactivated one should stand out the
 * way OVERDUE does on an invoice list.
 *
 * <p>Built locally rather than by importing `StatusBadge`: that component's
 * prop is typed `InvoiceStatus`, and bending it to accept a staff status too
 * would make it respond to two unrelated ideas of "status".
 */
function TeamStatusBadge({ active }: { active: boolean }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium ${
        active
          ? "border-app-border bg-transparent text-app-muted"
          : "border-app-warning/40 bg-app-warning-bg text-app-warning"
      }`}
    >
      {active ? "Active" : "Deactivated"}
    </span>
  );
}
