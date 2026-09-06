import { useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { toast } from "sonner";
import { useAuth } from "@/auth/useAuth";
import { InvoiceDocument } from "@/components/document/InvoiceDocument";
import { EmptyState, Loading, LoadError } from "@/components/Page";
import { FormError } from "@/screens/FormError";
import { ApiError } from "@/lib/api";
import { todayInSingapore } from "@/lib/format";
import { useApiQuery, keys } from "@/lib/hooks";
import { previewLineTotal, previewTotals } from "@/lib/preview-totals";
import type { Client, Invoice, Page, SettingsResponse } from "@/lib/types";
import { useCreateInvoice, useInvoice, useUpdateInvoice } from "./useInvoice";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/**
 * The client dropdown's options. Shared verbatim with the invoice list's own
 * client filter so both resolve to one cached request.
 */
const ACTIVE_CLIENTS_QUERY = "archived=false&size=200";

type LineDraft = { description: string; quantity: string; unitPrice: string };

const BLANK_LINE: LineDraft = { description: "", quantity: "1", unitPrice: "" };

/**
 * The builder — the screen the Design Direction calls the showcase.
 *
 * <p>A split view: app-layer form on the left, the live paper document on the
 * right, updating as line items are typed. The point is that the maker sees
 * exactly what the client will get, rather than filling in a form and hoping.
 * It stacks on mobile, form first, because on a phone you cannot see both and
 * the form is the thing you came to use.
 *
 * <p>Only drafts are editable. Product Scope §5.3 is explicit that a sent
 * invoice cannot be edited, and the API answers 409 — this screen refuses
 * earlier, with the Product Scope's own sentence.
 */
export function InvoiceBuilderScreen() {
  const { id } = useParams();
  // Two components rather than one with a conditional fetch: a hook cannot be
  // called conditionally, so a single component would have to call
  // useInvoice(0) on the "new invoice" route and request /invoices/0 just to
  // throw the 404 away. Splitting means the query only exists when there is
  // something to load.
  return id ? <EditExisting id={Number(id)} /> : <Builder />;
}

function EditExisting({ id }: { id: number }) {
  const existing = useInvoice(id);

  if (existing.isPending) return <Loading />;
  if (existing.isError) {
    return <LoadError error={existing.error} onRetry={() => existing.refetch()} />;
  }

  // Product Scope §5.3, in its own words. The API would answer 409 anyway;
  // refusing here means the user is not told after filling the form in.
  if (existing.data.status !== "DRAFT") {
    return (
      <EmptyState
        title="Sent invoices can't be edited. Create a new invoice."
        action={
          <Button asChild>
            <Link to={`/invoices/${existing.data.id}`}>Back to invoice</Link>
          </Button>
        }
      />
    );
  }

  return <Builder existing={existing.data} />;
}

function Builder({ existing }: { existing?: Invoice }) {
  const navigate = useNavigate();
  const { session } = useAuth();
  const isOwner = session!.user.role === "OWNER";

  const [clientId, setClientId] = useState<string>(existing ? String(existing.client.id) : "");
  const [issueDate, setIssueDate] = useState(existing?.issueDate ?? todayInSingapore());
  const [dueDate, setDueDate] = useState(existing?.dueDate ?? "");
  const [lines, setLines] = useState<LineDraft[]>(
    existing
      ? existing.lineItems.map((line) => ({
          description: line.description,
          quantity: String(line.quantity),
          unitPrice: line.unitPrice.toFixed(2),
        }))
      : [{ ...BLANK_LINE }],
  );
  const [error, setError] = useState<ApiError | null>(null);

  // The same key the invoice list uses for this exact request, so the two
  // screens share one cache entry instead of fetching the identical list
  // twice. Keys built from a query string have to agree character for
  // character — a stray leading "?" is enough to split them.
  const clients = useApiQuery<Page<Client>>(
    keys.clients(ACTIVE_CLIENTS_QUERY),
    `/clients?${ACTIVE_CLIENTS_QUERY}`,
  );

  /**
   * The business's settings, for the default due date. Owner-only on the API,
   * so staff do not get it — and for them the due date is simply left empty,
   * which the server then fills in from the same payment terms. The GST rate
   * no longer comes from here (see `gstRate` below).
   */
  const settings = useApiQuery<SettingsResponse>(["settings"], "/settings", { enabled: isOwner });

  /**
   * A new invoice's due date follows the business's payment terms.
   *
   * <p>Derived during render rather than written into state by an effect. The
   * terms arrive asynchronously, so an effect would mean the field renders
   * empty, then fills in a moment later — and it would need care not to
   * overwrite a date the user had already typed in the meantime. Deriving it
   * means `dueDate` state holds only what the user actually chose, and this
   * value is what the field shows and what gets submitted.
   */
  const defaultDueDate =
    settings.data && issueDate
      ? addDays(issueDate, settings.data.defaultPaymentTermsDays)
      : "";
  const effectiveDueDate = dueDate || defaultDueDate;

  /**
   * The rate the preview should show.
   *
   * <p>For a draft that already exists, the API has already worked this out
   * and `gstRate` is its answer. For one being typed for the first time it
   * comes from the session, which carries the business's live setting for
   * every member — staff included, who cannot read `/settings` at all. Before
   * that field existed, a staff member saw a preview with no GST line and
   * then a saved invoice with one.
   *
   * <p>Null rather than zero when the business is not registered: the
   * document omits the line entirely rather than printing "GST 0.00".
   */
  const gstRate = existing
    ? existing.gstRate
    : session!.user.businessGstRegistered
      ? session!.user.businessGstRate
      : null;

  const totals = useMemo(() => previewTotals(lines, gstRate), [lines, gstRate]);

  const selectedClient = clients.data?.content.find((client) => String(client.id) === clientId);

  const create = useCreateInvoice();
  const update = useUpdateInvoice(existing?.id ?? 0);
  const saving = create.isPending || update.isPending;

  function updateLine(index: number, patch: Partial<LineDraft>) {
    setLines((current) =>
      current.map((line, position) => (position === index ? { ...line, ...patch } : line)),
    );
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    const body = {
      clientId: Number(clientId),
      issueDate,
      dueDate: effectiveDueDate || undefined,
      lineItems: lines.map((line) => ({
        description: line.description,
        quantity: line.quantity,
        unitPrice: line.unitPrice,
      })),
    };

    try {
      const saved = existing
        ? await update.mutateAsync(body)
        : await create.mutateAsync(body);
      toast.success(existing ? `${saved.number} saved.` : `${saved.number} created.`);
      navigate(`/invoices/${saved.id}`, { replace: true });
    } catch (caught) {
      setError(caught as ApiError);
    }
  }

  if (clients.isPending) return <Loading />;
  if (clients.isError) return <LoadError error={clients.error} onRetry={() => clients.refetch()} />;

  // An invoice needs a client, and there is no way to add one from here — so
  // say what is missing and link to where it is fixed, rather than presenting
  // a select with nothing in it.
  if (clients.data.content.length === 0) {
    return (
      <EmptyState
        title="No clients yet."
        description="An invoice needs someone to bill. Add a client first."
        action={
          <Button asChild>
            <Link to="/clients">Go to clients</Link>
          </Button>
        }
      />
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold text-app-text">
          {existing ? `Edit ${existing.number}` : "New invoice"}
        </h1>
        <div className="flex gap-2">
          <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" disabled={saving}>
            {saving ? "Saving…" : existing ? "Save changes" : "Create invoice"}
          </Button>
        </div>
      </header>

      <FormError error={error} />

      {/* The split. `items-start` so the document does not stretch to match the
          form's height — it is a sheet of paper, not a column. */}
      <div className="grid items-start gap-6 lg:grid-cols-2">
        <Card>
          <CardContent className="space-y-4 p-6">
            <div className="space-y-2">
              <Label htmlFor="client">Client</Label>
              <Select value={clientId} onValueChange={setClientId}>
                <SelectTrigger id="client" className="w-full">
                  <SelectValue placeholder="Choose a client" />
                </SelectTrigger>
                <SelectContent>
                  {clients.data.content.map((client) => (
                    <SelectItem key={client.id} value={String(client.id)}>
                      {client.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="issueDate">Issue date</Label>
                <Input
                  id="issueDate"
                  type="date"
                  value={issueDate}
                  onChange={(event) => setIssueDate(event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="dueDate">Due date</Label>
                <Input
                  id="dueDate"
                  type="date"
                  value={effectiveDueDate}
                  onChange={(event) => setDueDate(event.target.value)}
                />
              </div>
            </div>

            <fieldset className="space-y-3">
              <legend className="text-sm font-medium text-app-text">Lines</legend>
              {lines.map((line, index) => (
                <div key={index} className="grid gap-2 sm:grid-cols-[1fr_5rem_7rem_auto]">
                  <Input
                    aria-label={`Description, line ${index + 1}`}
                    placeholder="What is being billed"
                    value={line.description}
                    onChange={(event) => updateLine(index, { description: event.target.value })}
                  />
                  <Input
                    aria-label={`Quantity, line ${index + 1}`}
                    className="font-mono"
                    inputMode="decimal"
                    value={line.quantity}
                    onChange={(event) => updateLine(index, { quantity: event.target.value })}
                  />
                  <Input
                    aria-label={`Unit price, line ${index + 1}`}
                    className="font-mono"
                    inputMode="decimal"
                    placeholder="0.00"
                    value={line.unitPrice}
                    onChange={(event) => updateLine(index, { unitPrice: event.target.value })}
                  />
                  <Button
                    type="button"
                    variant="ghost"
                    // The last line cannot be removed: the API requires at
                    // least one, so removing it would only produce a 400.
                    disabled={lines.length === 1}
                    aria-label={`Remove line ${index + 1}`}
                    onClick={() => setLines((current) => current.filter((_, p) => p !== index))}
                  >
                    Remove
                  </Button>
                </div>
              ))}
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setLines((current) => [...current, { ...BLANK_LINE }])}
              >
                Add line
              </Button>
            </fieldset>
          </CardContent>
        </Card>

        {/* The live document. Everything above is app chrome; this is the
            thing the client will actually receive. */}
        <div>
          <p className="mb-2 text-sm text-app-muted">Preview</p>
          <InvoiceDocument
            invoice={{
              // A number is assigned by the server at creation, so a draft
              // that has never been saved has none yet. Saying so is better
              // than inventing one the server will not agree with.
              number: existing?.number ?? "Draft",
              status: "DRAFT",
              issueDate: issueDate || todayInSingapore(),
              dueDate: effectiveDueDate || issueDate || todayInSingapore(),
              business: existing?.business ?? {
                name: session!.user.businessName,
                // Address and UEN are owner-only, so a staff member's preview
                // shows a thinner letterhead than the saved invoice will. That
                // is cosmetic and self-corrects on save, unlike the GST line,
                // which changes the figures and so is read from the session.
                address: settings.data?.address ?? null,
                uen: settings.data?.uen ?? null,
                gstRegistered: session!.user.businessGstRegistered,
              },
              client: selectedClient
                ? {
                    name: selectedClient.name,
                    address: selectedClient.address,
                    contactPerson: selectedClient.contactPerson,
                    email: selectedClient.email,
                    uen: selectedClient.uen,
                    paymentNotes: selectedClient.paymentNotes,
                  }
                : {
                    name: "",
                    address: null,
                    contactPerson: null,
                    email: null,
                    uen: null,
                    paymentNotes: null,
                  },
              lines: lines.map((line) => ({
                description: line.description,
                quantity: Number(line.quantity) || 0,
                unitPrice: Number(line.unitPrice) || 0,
                lineTotal: previewLineTotal(line),
              })),
              subtotal: totals.subtotal,
              gstRate: totals.gstRate,
              gst: totals.gst,
              total: totals.total,
              // A draft has never been paid: it cannot be, until it is sent.
              amountPaid: 0,
              balance: totals.total,
            }}
          />
        </div>
      </div>
    </form>
  );
}

/**
 * `2026-02-01` plus n days, as another `yyyy-mm-dd`.
 *
 * <p>Built from a UTC date on purpose: constructing it in local time and
 * reading it back with `toISOString` would shift the answer by a day for
 * anyone west of Greenwich. The business day is Asia/Singapore (ADR-0008),
 * and a plain date has no time to convert anyway.
 */
function addDays(isoDate: string, days: number): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day + days));
  return shifted.toISOString().slice(0, 10);
}
