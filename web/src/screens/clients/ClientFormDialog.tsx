import { useState } from "react";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import type { Client } from "@/lib/types";
import { FormError } from "@/screens/FormError";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  useCreateClient,
  useUpdateClient,
  type ClientInput,
} from "./useClients";

const BLANK_FORM: ClientInput = {
  name: "",
  contactPerson: "",
  email: "",
  phone: "",
  address: "",
  uen: "",
  paymentNotes: "",
  archived: false,
};

/**
 * One dialog, used for both creating and editing a client — the Product
 * Scope draws no distinction between the two forms, only between the button
 * that opens them. `client` present means "editing that client"; absent
 * means "creating a new one".
 */
export function ClientFormDialog({
  open,
  onOpenChange,
  client,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  client: Client | null;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        {/*
          Keyed by which client is being edited, so React discards the form and
          builds a fresh one whenever that changes. This is what stops the
          previous client's details showing up in the next dialog, and it does
          the job without an effect that resets state after the fact: the state
          below can simply be initialised from the prop, because a different
          key means a different component instance.
        */}
        <ClientForm
          key={client ? `client-${client.id}` : "new"}
          client={client}
          onDone={() => onOpenChange(false)}
        />
      </DialogContent>
    </Dialog>
  );
}

function ClientForm({
  client,
  onDone,
}: {
  client: Client | null;
  onDone: () => void;
}) {
  const isEditing = client !== null;
  const [form, setForm] = useState<ClientInput>(() =>
    client
      ? {
          name: client.name,
          contactPerson: client.contactPerson ?? "",
          email: client.email ?? "",
          phone: client.phone ?? "",
          address: client.address ?? "",
          uen: client.uen ?? "",
          paymentNotes: client.paymentNotes ?? "",
          archived: client.archived,
        }
      : BLANK_FORM,
  );
  const [error, setError] = useState<ApiError | null>(null);

  const createClient = useCreateClient();
  const updateClient = useUpdateClient();
  const pending = createClient.isPending || updateClient.isPending;

  function fieldHasError(field: string): boolean {
    return (
      error !== null &&
      error.fieldErrors.some((fieldError) => fieldError.field === field)
    );
  }

  function update(field: keyof ClientInput, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    // The optional fields hold "" while someone is typing (or has typed and
    // deleted), but the API's idea of "not set" is null, per the `Client`
    // type this form is filling in — so blank goes back to null on the way
    // out rather than teaching the API a second meaning for the same thing.
    const body: ClientInput = {
      ...form,
      contactPerson: blankToNull(form.contactPerson),
      email: blankToNull(form.email),
      phone: blankToNull(form.phone),
      address: blankToNull(form.address),
      uen: blankToNull(form.uen),
      paymentNotes: blankToNull(form.paymentNotes),
    };

    try {
      if (isEditing) {
        await updateClient.mutateAsync({ id: client.id, input: body });
      } else {
        await createClient.mutateAsync(body);
      }
      onDone();
      toast.success(isEditing ? "Client updated." : "Client created.");
    } catch (caught) {
      setError(caught as ApiError);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <DialogHeader>
        <DialogTitle>{isEditing ? "Edit client" : "New client"}</DialogTitle>
      </DialogHeader>

      <FormError error={error} />

      <div className="space-y-2">
        <Label htmlFor="client-name">Name</Label>
        <Input
          id="client-name"
          name="clientName"
          autoComplete="organization"
          required
          maxLength={255}
          aria-invalid={fieldHasError("name")}
          value={form.name}
          onChange={(event) => update("name", event.target.value)}
        />
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="client-contact">Contact person</Label>
          <Input
            id="client-contact"
            name="contactPerson"
            autoComplete="name"
            maxLength={255}
            aria-invalid={fieldHasError("contactPerson")}
            value={form.contactPerson ?? ""}
            onChange={(event) => update("contactPerson", event.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="client-email">Email</Label>
          <Input
            id="client-email"
            name="clientEmail"
            autoComplete="email"
            spellCheck={false}
            type="email"
            maxLength={255}
            aria-invalid={fieldHasError("email")}
            value={form.email ?? ""}
            onChange={(event) => update("email", event.target.value)}
          />
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="client-phone">Phone</Label>
          <Input
            id="client-phone"
            name="clientPhone"
            autoComplete="tel"
            inputMode="tel"
            type="tel"
            maxLength={50}
            aria-invalid={fieldHasError("phone")}
            value={form.phone ?? ""}
            onChange={(event) => update("phone", event.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="client-uen">UEN</Label>
          <Input
            id="client-uen"
            name="clientUen"
            autoComplete="off"
            spellCheck={false}
            maxLength={20}
            aria-invalid={fieldHasError("uen")}
            value={form.uen ?? ""}
            onChange={(event) => update("uen", event.target.value)}
          />
          <p className="text-xs text-app-muted">
            Prints on the invoice document.
          </p>
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="client-address">Address</Label>
        <Textarea
          id="client-address"
          name="clientAddress"
          autoComplete="street-address"
          maxLength={500}
          aria-invalid={fieldHasError("address")}
          value={form.address ?? ""}
          onChange={(event) => update("address", event.target.value)}
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="client-payment-notes">Payment notes</Label>
        <Textarea
          id="client-payment-notes"
          name="paymentNotes"
          autoComplete="off"
          maxLength={2000}
          aria-invalid={fieldHasError("paymentNotes")}
          value={form.paymentNotes ?? ""}
          onChange={(event) => update("paymentNotes", event.target.value)}
        />
        <p className="text-xs text-app-muted">
          PayNow number, bank details, or anything else that should print on the
          invoice.
        </p>
      </div>

      <DialogFooter>
        <DialogClose asChild>
          <Button type="button" variant="outline" disabled={pending}>
            Cancel
          </Button>
        </DialogClose>
        <Button type="submit" disabled={pending}>
          {pending ? "Saving…" : isEditing ? "Save changes" : "Create client"}
        </Button>
      </DialogFooter>
    </form>
  );
}

function blankToNull(value: string | null): string | null {
  if (value === null) return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}
