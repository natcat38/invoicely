import { useState } from "react";
import { ApiError } from "@/lib/api";
import type { CreatedStaff } from "@/lib/types";
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
import { useAddStaff } from "./useTeam";

const BLANK_FORM = { name: "", email: "" };

/**
 * Adds a staff member — Product Scope §5.1: the owner supplies a name and
 * email, the server generates the temporary password, and that password is
 * shown back exactly once. There is no edit form here; `/team` only ever
 * creates or flips `active`, both handled elsewhere.
 *
 * <p>Radix unmounts `DialogContent` while `open` is false (there is no
 * `forceMount` here), so closing this dialog already discards `AddStaffForm`'s
 * state for free — reopening it always starts from the blank form below,
 * with no `useEffect` needed to force that.
 */
export function AddStaffDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <AddStaffForm onClose={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

function AddStaffForm({ onClose }: { onClose: () => void }) {
  const [form, setForm] = useState(BLANK_FORM);
  const [error, setError] = useState<ApiError | null>(null);
  // Set once the API answers 201. Its presence, not a separate "step" flag,
  // is what switches this dialog from the form to the password screen — so
  // there is only one thing to keep in sync, not two that could disagree.
  const [created, setCreated] = useState<CreatedStaff | null>(null);

  const addStaff = useAddStaff();

  function fieldHasError(field: string): boolean {
    return (
      error !== null &&
      error.fieldErrors.some((fieldError) => fieldError.field === field)
    );
  }

  function update(field: keyof typeof BLANK_FORM, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const result = await addStaff.mutateAsync({
        name: form.name.trim(),
        email: form.email.trim(),
      });
      setCreated(result);
    } catch (caught) {
      setError(caught as ApiError);
    }
  }

  if (created) {
    return <TemporaryPasswordScreen staff={created} onClose={onClose} />;
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <DialogHeader>
        <DialogTitle>Add staff</DialogTitle>
      </DialogHeader>

      <FormError error={error} />

      <div className="space-y-2">
        <Label htmlFor="staff-name">Name</Label>
        <Input
          id="staff-name"
          name="staffName"
          autoComplete="name"
          required
          maxLength={255}
          aria-invalid={fieldHasError("name")}
          value={form.name}
          onChange={(event) => update("name", event.target.value)}
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="staff-email">Email</Label>
        <Input
          id="staff-email"
          name="staffEmail"
          autoComplete="email"
          spellCheck={false}
          type="email"
          required
          maxLength={255}
          aria-invalid={fieldHasError("email")}
          value={form.email}
          onChange={(event) => update("email", event.target.value)}
        />
        <p className="text-xs text-app-muted">
          They sign in with this address and the temporary password shown after
          you add them.
        </p>
      </div>

      <DialogFooter>
        <DialogClose asChild>
          <Button type="button" variant="outline" disabled={addStaff.isPending}>
            Cancel
          </Button>
        </DialogClose>
        <Button type="submit" disabled={addStaff.isPending}>
          {addStaff.isPending ? "Adding…" : "Add staff"}
        </Button>
      </DialogFooter>
    </form>
  );
}

/**
 * The one screen this dialog only ever shows once: the temporary password,
 * right after the server generates it. Product Scope §5.1 and `CreatedStaff`
 * (`lib/types.ts`) are both explicit that the server never stores this in a
 * readable form again — losing it here means creating a new account, not
 * looking it up — so this screen has no "skip" or "remind me later", only a
 * way to close it once it has been copied down.
 */
function TemporaryPasswordScreen({
  staff,
  onClose,
}: {
  staff: CreatedStaff;
  onClose: () => void;
}) {
  const [copyFailed, setCopyFailed] = useState(false);
  const [copied, setCopied] = useState(false);

  async function copyPassword() {
    try {
      await navigator.clipboard.writeText(staff.temporaryPassword);
      setCopied(true);
      setCopyFailed(false);
    } catch {
      // `navigator.clipboard.writeText` rejects without clipboard permission
      // or outside a secure context (e.g. plain http). The password is still
      // selectable in the field below either way, so this is a reason to
      // fall back to copying it by hand — not a reason to suggest the staff
      // account itself was not created.
      setCopyFailed(true);
    }
  }

  return (
    <div className="space-y-4">
      <DialogHeader>
        <DialogTitle>{staff.name} was added</DialogTitle>
      </DialogHeader>

      <div className="space-y-2">
        <Label htmlFor="staff-temp-password">Temporary password</Label>
        <div className="flex items-center gap-2">
          <Input
            id="staff-temp-password"
            readOnly
            className="font-mono"
            value={staff.temporaryPassword}
            // Selects the whole password on focus, so a click into the field
            // is enough to select it for a manual copy — the fallback for
            // when the button below fails.
            onFocus={(event) => event.currentTarget.select()}
          />
          <Button type="button" variant="outline" onClick={copyPassword}>
            {copied ? "Copied" : "Copy"}
          </Button>
        </div>
        {copyFailed ? (
          <p className="text-xs text-destructive">
            Could not copy automatically. Click the password above to select it,
            then copy it by hand.
          </p>
        ) : null}
      </div>

      <p className="text-sm text-app-text">
        This password is shown once and cannot be shown again. Share it with{" "}
        {staff.name} — they must change it the first time they sign in.
      </p>

      <DialogFooter>
        <Button type="button" onClick={onClose}>
          Done
        </Button>
      </DialogFooter>
    </div>
  );
}
