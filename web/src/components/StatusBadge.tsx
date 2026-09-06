import type { InvoiceStatus } from "@/lib/types";
import { statusLabel } from "@/lib/format";

/**
 * An invoice's status, in the app chrome.
 *
 * <p>Only two statuses carry a fill: OVERDUE and PAID, the two that are
 * actually news. DRAFT, awaiting-approval and SENT are quiet outlines, because
 * an invoice moving normally through its lifecycle should not shout — that is
 * the Design Direction's rule, and it is what makes an overdue row findable in
 * a list of thirty.
 *
 * <p>The document layer has its own, louder treatment of the same two states:
 * a rotated stamp. This badge never appears on the document, and the stamp
 * never appears in the chrome.
 */
export function StatusBadge({ status }: { status: InvoiceStatus }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium ${TONE[status]}`}
    >
      {statusLabel(status)}
    </span>
  );
}

const TONE: Record<InvoiceStatus, string> = {
  DRAFT: "border-app-border bg-transparent text-app-muted",
  PENDING_APPROVAL: "border-app-warning/40 bg-app-warning-bg text-app-warning",
  SENT: "border-app-border bg-transparent text-app-text",
  OVERDUE: "border-stamp-overdue/30 bg-stamp-overdue/10 text-stamp-overdue",
  PAID: "border-app-paid/30 bg-app-paid-bg text-app-paid",
};
