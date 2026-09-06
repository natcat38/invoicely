import { useState } from "react";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import type { SettingsResponse } from "@/lib/types";
import { FormError } from "@/screens/FormError";
import { Loading, LoadError, PageHeader } from "@/components/Page";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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
import {
  useSettings,
  useUpdateSettings,
  type SettingsInput,
} from "./useSettings";

/** The only three values `defaultPaymentTermsDays` may hold (`SettingsRequest` validation). */
const PAYMENT_TERMS_OPTIONS = [7, 14, 30] as const;

/**
 * The business settings page (Product Scope §5.2a). Owner only — the route is
 * already gated, and `GET /settings` would answer 403 for staff regardless.
 *
 * <p>Fetch, then hand the loaded settings to an inner form component. Kept
 * separate so the form's state can be initialised straight from the response
 * with `useState(() => ...)` rather than a `useEffect` that copies the fetch
 * result in after the fact — the same shape `ClientFormDialog` uses for the
 * same reason: an effect-based sync is one render behind, and it is easy to
 * forget a dependency and have it silently stop firing.
 */
export function SettingsScreen() {
  const settings = useSettings();

  if (settings.isPending) return <Loading label="Loading settings…" />;
  if (settings.isError) {
    return (
      <LoadError error={settings.error} onRetry={() => settings.refetch()} />
    );
  }

  // `settings.data.id` never changes for a given business, so this key never
  // actually forces a remount in practice — there is only one settings row to
  // load. It is here anyway to name the pattern this screen follows, and it
  // would matter the moment the API grew a "reset to defaults" action that
  // swapped the row out from under an open form.
  return <SettingsForm key={settings.data.id} settings={settings.data} />;
}

function SettingsForm({ settings }: { settings: SettingsResponse }) {
  const [name, setName] = useState(settings.name);
  const [address, setAddress] = useState(settings.address ?? "");
  const [uen, setUen] = useState(settings.uen ?? "");
  const [gstRegistered, setGstRegistered] = useState(settings.gstRegistered);
  const [gstPercentText, setGstPercentText] = useState(() =>
    fractionToPercentText(settings.gstRate),
  );
  const [defaultPaymentTermsDays, setDefaultPaymentTermsDays] = useState(
    settings.defaultPaymentTermsDays,
  );
  const [error, setError] = useState<ApiError | null>(null);

  const updateSettings = useUpdateSettings();

  function fieldHasError(field: string): boolean {
    return (
      error !== null &&
      error.fieldErrors.some((fieldError) => fieldError.field === field)
    );
  }

  // Mirrors `PaymentDialog`'s amount validation: computed straight from the
  // text the owner is typing, so the button disables and re-enables as they
  // type rather than only after a failed submit. 99.99 is the practical
  // ceiling, not a rounded-down 100 — `SettingsRequest.gstRate` is capped at
  // 0.9999 (`@DecimalMax("0.9999")`), which is 99.99% once converted.
  const gstPercentNumber = Number(gstPercentText);
  const gstPercentInvalid =
    gstPercentText.trim().length === 0 ||
    !Number.isFinite(gstPercentNumber) ||
    gstPercentNumber < 0 ||
    gstPercentNumber > 99.99;

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (gstPercentInvalid) return;

    // A full replace (`SettingsController.update` has no patch endpoint), so
    // every field goes back even though this form only ever changes some of
    // them.
    const body: SettingsInput = {
      name,
      address: blankToNull(address),
      uen: blankToNull(uen),
      gstRegistered,
      gstRate: percentTextToFraction(gstPercentText),
      defaultPaymentTermsDays,
    };

    try {
      await updateSettings.mutateAsync(body);
      toast.success("Settings updated.");
    } catch (caught) {
      setError(caught as ApiError);
    }
  }

  return (
    <div>
      <PageHeader
        title="Settings"
        description="Business details, GST and the payment terms new invoices use."
      />

      <form onSubmit={onSubmit} noValidate className="space-y-6">
        <FormError error={error} />

        <Card>
          <CardHeader>
            <CardTitle>Business details</CardTitle>
            <CardDescription>
              These print on the invoice document — the letterhead a client sees
              at the top of every invoice.
            </CardDescription>
          </CardHeader>
          {/*
            ADR-0011: the letterhead is rendered live from this row, not
            snapshotted the way the GST rate is (see the note below the GST
            rate field). That is a deliberate trade, not an oversight — fixing
            a typo in the business name or address here corrects every
            invoice at once, including ones already sent to a client. The
            mirror case is that reprinting an old invoice shows today's
            details rather than the ones that were true when it was sent. The
            ADR accepts that asymmetry because an address is presentation,
            not a fact about how much money is owed, so there is nothing here
            worth the complexity a snapshot would add.
          */}
          <CardContent className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="settings-name">Business name</Label>
              <Input
                id="settings-name"
                name="businessName"
                autoComplete="organization"
                required
                maxLength={255}
                aria-invalid={fieldHasError("name")}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="settings-address">Address</Label>
              <Textarea
                id="settings-address"
                name="businessAddress"
                autoComplete="street-address"
                maxLength={500}
                aria-invalid={fieldHasError("address")}
                value={address}
                onChange={(event) => setAddress(event.target.value)}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="settings-uen">UEN</Label>
              <Input
                id="settings-uen"
                name="businessUen"
                autoComplete="off"
                spellCheck={false}
                maxLength={20}
                aria-invalid={fieldHasError("uen")}
                value={uen}
                onChange={(event) => setUen(event.target.value)}
              />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>GST and payment terms</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="flex items-center gap-2">
              {/* shadcn's Switch isn't installed, and this screen doesn't
                  get to add components — a native checkbox is genuinely the
                  right call here: it is keyboard-operable and announced by a
                  screen reader for free, and the only thing a styled Switch
                  would add is a shape, not a behaviour. */}
              <input
                id="settings-gst-registered"
                type="checkbox"
                className="h-4 w-4 cursor-pointer rounded border border-app-border accent-app-accent"
                checked={gstRegistered}
                onChange={(event) => setGstRegistered(event.target.checked)}
              />
              <Label
                htmlFor="settings-gst-registered"
                className="cursor-pointer"
              >
                GST-registered
              </Label>
            </div>

            <div className="space-y-2">
              <Label htmlFor="settings-gst-rate">GST rate</Label>
              <div className="flex max-w-[10rem] items-center gap-2">
                <Input
                  id="settings-gst-rate"
                  name="gstRate"
                  autoComplete="off"
                  inputMode="decimal"
                  required
                  aria-invalid={gstPercentInvalid || fieldHasError("gstRate")}
                  aria-describedby="settings-gst-rate-note"
                  value={gstPercentText}
                  onChange={(event) => setGstPercentText(event.target.value)}
                />
                <span className="text-sm text-app-muted">%</span>
              </div>
              {!gstRegistered ? (
                <p className="text-xs text-app-muted">
                  Not applied while GST-registered is off. The rate is kept, not
                  cleared, so switching registration back on will not lose it.
                </p>
              ) : null}
              <p id="settings-gst-rate-note" className="text-xs text-app-muted">
                Applied to every new invoice as a percentage — 9% is entered as{" "}
                <span className="font-mono">9</span>, not{" "}
                <span className="font-mono">0.09</span>. It is snapshotted onto
                an invoice the moment it is sent, so changing it here never
                rewrites an invoice already sent.
              </p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="settings-payment-terms">
                Default payment terms
              </Label>
              <Select
                value={String(defaultPaymentTermsDays)}
                onValueChange={(value) =>
                  setDefaultPaymentTermsDays(Number(value))
                }
              >
                <SelectTrigger
                  id="settings-payment-terms"
                  className="w-full sm:w-48"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PAYMENT_TERMS_OPTIONS.map((days) => (
                    <SelectItem key={days} value={String(days)}>
                      {days} days
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-app-muted">
                A new invoice's due date defaults to its issue date plus this
                many days — editable per invoice.
              </p>
            </div>
          </CardContent>
        </Card>

        <div className="flex justify-end">
          <Button
            type="submit"
            disabled={updateSettings.isPending || gstPercentInvalid}
          >
            {updateSettings.isPending ? "Saving…" : "Save changes"}
          </Button>
        </div>
      </form>
    </div>
  );
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * `gstRate` on the wire is a fraction (`0.09`), but nobody thinks in
 * fractions when they mean 9% — so the input works in percent and this pair
 * of functions converts at the edges.
 *
 * <p>Both directions round to whole hundredths of a percent (four decimal
 * places of the underlying fraction, which is exactly what
 * `SettingsRequest.gstRate`'s `@Digits(integer = 1, fraction = 4)` accepts)
 * before turning the result into a display string or a number to send.
 * That rounding is not just cosmetic: `9 / 100` is `0.09`, but `0.09 * 100`
 * comes back as `9.000000000000002` in IEEE 754 binary floating point, and
 * without rounding first, a value the owner never touched could round-trip
 * into a display string like "9.000000000000002" or a request the API's
 * 4-decimal-place check rejects.
 */
function fractionToPercentText(fraction: number): string {
  return String(Math.round(fraction * 10000) / 100);
}

function percentTextToFraction(percentText: string): number {
  return Math.round(Number(percentText) * 100) / 10000;
}
