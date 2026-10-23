import { useRef, useState } from "react";
import { useNavigate } from "react-router";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import { money } from "@/lib/format";
import type { Invoice, Role } from "@/lib/types";
import { useInvoiceLifecycle } from "./useInvoice";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
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
  recordPaymentRef,
}: {
  invoice: Invoice;
  role: Role;
  onRecordPayment: () => void;
  /** Attached to "Record payment" so the screen's payment dialog can return focus to it. */
  recordPaymentRef?: React.Ref<HTMLButtonElement>;
}) {
  const navigate = useNavigate();
  const lifecycle = useInvoiceLifecycle(invoice.id);
  const [rejecting, setRejecting] = useState(false);
  const rejectButton = useRef<HTMLButtonElement>(null);

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
            <ConfirmSend
              invoice={invoice}
              label="Send"
              disabled={lifecycle.isPending}
              onConfirm={() => run("send", `${invoice.number} sent.`)}
            />
          ) : null}
        </>
      ) : null}

      {status === "PENDING_APPROVAL" && isOwner ? (
        <>
          <Button
            ref={rejectButton}
            variant="outline"
            onClick={() => setRejecting(true)}
            disabled={lifecycle.isPending}
          >
            Reject
          </Button>
          <ConfirmSend
            invoice={invoice}
            label="Approve & send"
            disabled={lifecycle.isPending}
            onConfirm={() => run("send", `${invoice.number} approved and sent.`)}
          />
        </>
      ) : null}

      {(status === "SENT" || status === "OVERDUE") && isOwner ? (
        <Button ref={recordPaymentRef} onClick={onRecordPayment}>
          Record payment
        </Button>
      ) : null}

      <RejectDialog
        invoice={invoice}
        open={rejecting}
        onOpenChange={setRejecting}
        returnFocusTo={rejectButton}
      />
    </div>
  );
}

/**
 * A send button that asks first.
 *
 * <p>Sending is the one step that cannot be taken back: it locks the GST rate
 * and the invoice number, and the invoice can no longer be edited. On a phone
 * the button sits next to Reject, so one mis-tap is enough. The dialog says
 * what will be sent, to whom and for how much.
 *
 * <p>Built on `AlertDialog`, which traps focus inside, closes on Escape and
 * returns focus to the button that opened it. It opens on Cancel, so pressing
 * Enter straight away does nothing irreversible.
 */
function ConfirmSend({
  invoice,
  label,
  disabled,
  onConfirm,
}: {
  invoice: Invoice;
  /** The button's text, which is also the confirm button's text. */
  label: string;
  disabled: boolean;
  /** Does the send. Resolves when it has finished, whether or not it worked. */
  onConfirm: () => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [sending, setSending] = useState(false);

  async function confirm() {
    setSending(true);
    await onConfirm();
    setSending(false);
    setOpen(false);
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger asChild>
        <Button disabled={disabled}>{label}</Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Send {invoice.number}?</AlertDialogTitle>
          <AlertDialogDescription>
            Send {invoice.number} to {invoice.client.name} for {money(invoice.total)}? The GST and
            invoice number will lock.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={sending}>Cancel</AlertDialogCancel>
          <AlertDialogAction
            disabled={sending}
            onClick={(event) => {
              // The primitive closes the dialog on any click; it has to stay
              // open until the request finishes, so `confirm` closes it.
              event.preventDefault();
              confirm();
            }}
          >
            {sending ? "Sending…" : label}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
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
  returnFocusTo,
}: {
  invoice: Invoice;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  returnFocusTo: React.RefObject<HTMLElement | null>;
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
      <DialogContent returnFocusTo={returnFocusTo}>
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
