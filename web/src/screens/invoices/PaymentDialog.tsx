import { useState } from "react";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import { money, todayInSingapore, methodLabel, PAYMENT_METHODS } from "@/lib/format";
import type { Invoice, PaymentMethod } from "@/lib/types";
import { useRecordPayment } from "./useInvoice";
import { FormError } from "@/screens/FormError";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

/**
 * Recording a payment. Owner only — the API enforces that too, and would
 * answer 403 if this dialog were ever reachable by staff.
 *
 * <p>Product Scope §5.4: the amount pre-fills with the remaining balance,
 * because paying the invoice off in full is overwhelmingly the common case and
 * typing the figure again is just an opportunity to get it wrong.
 */
export function PaymentDialog({
  invoice,
  open,
  onOpenChange,
}: {
  invoice: Invoice;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  // Keyed by the balance so that reopening the dialog after a partial payment
  // pre-fills the *new* remaining balance rather than the stale one.
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <PaymentForm key={invoice.balance} invoice={invoice} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

function PaymentForm({ invoice, onDone }: { invoice: Invoice; onDone: () => void }) {
  const [amountText, setAmountText] = useState(invoice.balance.toFixed(2));
  const [paidAt, setPaidAt] = useState(todayInSingapore());
  const [method, setMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [note, setNote] = useState("");
  const [error, setError] = useState<ApiError | null>(null);

  const record = useRecordPayment(invoice.id);

  // Compared in cents, not as floats: 1047.80 parses to 1047.7999999999999 in
  // binary floating point, and a naive `entered > balance` would reject paying
  // the exact balance off. Rounding both to whole cents first makes the
  // comparison the same one the server does.
  const enteredCents = Math.round(Number(amountText) * 100);
  const balanceCents = Math.round(invoice.balance * 100);
  const overpaying = Number.isFinite(enteredCents) && enteredCents > balanceCents;
  const nonPositive = !Number.isFinite(enteredCents) || enteredCents <= 0;

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    // The API rejects an overpayment with a 409 anyway (Product Scope §5.5:
    // every rule enforced twice). Blocking it here just means the user finds
    // out while typing rather than after a round trip.
    if (overpaying || nonPositive) return;

    try {
      await record.mutateAsync({ amount: amountText, paidAt, method, note: note || undefined });
      // The invoice this dialog was opened from is already stale — the
      // mutation invalidated it — so the toast reads from the number, which
      // does not change.
      const settled = enteredCents === balanceCents;
      toast.success(
        settled
          ? `Invoice ${invoice.number} marked as paid.`
          : `Payment recorded against ${invoice.number}.`,
      );
      onDone();
    } catch (caught) {
      setError(caught as ApiError);
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate>
      <DialogHeader>
        <DialogTitle>Record a payment</DialogTitle>
        <DialogDescription>
          {invoice.number} · balance {money(invoice.balance)}
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        <FormError error={error} />

        <div className="space-y-2">
          <Label htmlFor="amount">Amount received (SGD)</Label>
          <Input
            id="amount"
            className="font-mono"
            inputMode="decimal"
            required
            aria-invalid={overpaying}
            aria-describedby={overpaying ? "amount-error" : undefined}
            value={amountText}
            onChange={(event) => setAmountText(event.target.value)}
          />
          {overpaying ? (
            // Product Scope §5.4 specifies this sentence, including the
            // formatted balance.
            <p id="amount-error" className="text-xs text-destructive">
              Amount exceeds the remaining balance ({money(invoice.balance)}).
            </p>
          ) : null}
        </div>

        <div className="space-y-2">
          <Label htmlFor="paidAt">Date received</Label>
          {/* A native date input rather than a picker component: it is
              keyboard-accessible, localised and validated by the browser for
              free, and the API wants exactly the yyyy-mm-dd it produces. */}
          <Input
            id="paidAt"
            type="date"
            required
            value={paidAt}
            onChange={(event) => setPaidAt(event.target.value)}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="method">Method</Label>
          <Select value={method} onValueChange={(value) => setMethod(value as PaymentMethod)}>
            <SelectTrigger id="method" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PAYMENT_METHODS.map((value) => (
                <SelectItem key={value} value={value}>
                  {methodLabel(value)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="note">Note (optional)</Label>
          <Textarea
            id="note"
            rows={2}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </div>
      </div>

      <DialogFooter>
        <Button type="button" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={record.isPending || overpaying || nonPositive}>
          {record.isPending ? "Recording…" : "Record payment"}
        </Button>
      </DialogFooter>
    </form>
  );
}
