import { useState } from "react";
import { useNavigate } from "react-router";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import type { Invoice, Role } from "@/lib/types";
import { useInvoiceLifecycle } from "./useInvoice";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

/**
 * The maker-checker buttons, chosen by status and role together.
 *
 * <p>Those are two separate axes and the API treats them as two separate
 * failures — an illegal transition is a 409, an insufficient role is a 403
 * (Product Scope §4). This component only decides what to *offer*; it is not a
 * security control, and the server refuses the same actions regardless of what
 * is rendered here.
 *
 * <p>The mapping, from the Product Scope's lifecycle table:
 *
 * <ul>
 *   <li>DRAFT — anyone may submit for approval; an owner may also send directly
 *   <li>PENDING_APPROVAL — read-only to staff; the owner approves & sends, or
 *       rejects with a note
 *   <li>SENT / OVERDUE — the owner records payments (that button lives on the
 *       detail screen, next to the balance)
 *   <li>PAID — nothing left to do
 * </ul>
 */
export function InvoiceActions({
  invoice,
  role,
  onRecordPayment,
}: {
  invoice: Invoice;
  role: Role;
  onRecordPayment: () => void;
}) {
  const navigate = useNavigate();
  const lifecycle = useInvoiceLifecycle(invoice.id);
  const [rejecting, setRejecting] = useState(false);

  const isOwner = role === "OWNER";
  const { status } = invoice;

  async function run(action: "submit" | "send", success: string) {
    try {
      await lifecycle.mutateAsync({ action });
      toast.success(success);
    } catch (caught) {
      const error = caught as ApiError;
      // A 409 means someone else moved this invoice on while this page was
      // open — the owner sent it from another tab, say. The API's sentence
      // names the status it is actually in, which is more useful than
      // anything this component could invent.
      toast.error(error.message);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      {status === "DRAFT" ? (
        <>
          <Button variant="outline" onClick={() => navigate(`/invoices/${invoice.id}/edit`)}>
            Edit
          </Button>
          <Button
            variant={isOwner ? "outline" : "default"}
            disabled={lifecycle.isPending}
            onClick={() => run("submit", `${invoice.number} submitted for approval.`)}
          >
            Submit for approval
          </Button>
          {/* An owner is both maker and checker, so the queue is optional for
              them — Product Scope §5.3 gives them a direct Send. */}
          {isOwner ? (
            <Button
              disabled={lifecycle.isPending}
              onClick={() => run("send", `${invoice.number} sent.`)}
            >
              Send
            </Button>
          ) : null}
        </>
      ) : null}

      {status === "PENDING_APPROVAL" && isOwner ? (
        <>
          <Button variant="outline" onClick={() => setRejecting(true)} disabled={lifecycle.isPending}>
            Reject
          </Button>
          <Button
            disabled={lifecycle.isPending}
            onClick={() => run("send", `${invoice.number} approved and sent.`)}
          >
            Approve &amp; send
          </Button>
        </>
      ) : null}

      {(status === "SENT" || status === "OVERDUE") && isOwner ? (
        <Button onClick={onRecordPayment}>Record payment</Button>
      ) : null}

      <RejectDialog
        invoice={invoice}
        open={rejecting}
        onOpenChange={setRejecting}
      />
    </div>
  );
}

/**
 * Rejecting sends the invoice back to DRAFT with a note the drafter sees.
 *
 * <p>The note is required by the API, and rightly: "sent back" without a
 * reason gives whoever drafted it nothing to act on. Enforced here as well so
 * the requirement is visible before the round trip (Product Scope §5.5).
 */
function RejectDialog({
  invoice,
  open,
  onOpenChange,
}: {
  invoice: Invoice;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [note, setNote] = useState("");
  const lifecycle = useInvoiceLifecycle(invoice.id);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!note.trim()) return;
    try {
      await lifecycle.mutateAsync({ action: "reject", note });
      toast.success(`${invoice.number} sent back to draft.`);
      onOpenChange(false);
      setNote("");
    } catch (caught) {
      toast.error((caught as ApiError).message);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form onSubmit={onSubmit} noValidate>
          <DialogHeader>
            <DialogTitle>Send {invoice.number} back?</DialogTitle>
            <DialogDescription>
              It returns to draft, and whoever created it sees your note.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-2 py-4">
            <Label htmlFor="rejection-note">What needs changing?</Label>
            <Textarea
              id="rejection-note"
              rows={3}
              required
              value={note}
              onChange={(event) => setNote(event.target.value)}
            />
          </div>

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!note.trim() || lifecycle.isPending}>
              {lifecycle.isPending ? "Sending back…" : "Reject"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
