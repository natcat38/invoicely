import { useState } from "react";
import { Navigate, useNavigate } from "react-router";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/auth/useAuth";
import { AuthLayout } from "./AuthLayout";
import { FormError } from "./FormError";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * The forced password change a new staff member meets on their first sign-in.
 *
 * <p>An interstitial rather than a page they can navigate away from: while
 * `mustChangePassword` is true the API refuses every other endpoint, so any
 * other screen would render nothing but errors. There is deliberately no
 * "skip" and no nav — the only ways out are completing the form or signing
 * out.
 *
 * <p>Completing it does <em>not</em> require signing in again. The API returns
 * a fresh token stamped after the change, so this session continues while
 * every other token for the account is rejected (ADR-0010).
 */
export function ChangePasswordScreen() {
  const { changePassword, mustChangePassword, session, signOut } = useAuth();
  const navigate = useNavigate();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const [mismatch, setMismatch] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // Reached with nothing to change — someone typed the URL, or came back after
  // finishing. Nobody is stranded here.
  if (!mustChangePassword && !session) return <Navigate to="/login" replace />;
  if (!mustChangePassword) return <Navigate to="/" replace />;

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    // Checked here rather than server-side: the confirmation field exists only
    // to catch a typo, so the API has no reason to know about it.
    if (newPassword !== confirmation) {
      setMismatch(true);
      return;
    }
    setMismatch(false);

    setSubmitting(true);
    try {
      await changePassword(currentPassword, newPassword);
      navigate("/", { replace: true });
    } catch (caught) {
      setError(caught as ApiError);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="Set a new password"
      /* Product Scope §5.1 specifies this sentence exactly. */
      subtitle="Set a new password to continue."
      footer={
        <button
          type="button"
          onClick={signOut}
          className="text-sm text-app-muted underline-offset-4 hover:underline"
        >
          Sign out instead
        </button>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <FormError error={error} />

        <div className="space-y-2">
          <Label htmlFor="currentPassword">Temporary password</Label>
          <Input
            id="currentPassword"
            type="password"
            autoComplete="current-password"
            required
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="newPassword">New password</Label>
          <Input
            id="newPassword"
            type="password"
            autoComplete="new-password"
            required
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
          />
          <p className="text-xs text-app-muted">At least 8 characters.</p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="confirmation">Confirm new password</Label>
          <Input
            id="confirmation"
            type="password"
            autoComplete="new-password"
            required
            aria-invalid={mismatch}
            aria-describedby={mismatch ? "confirmation-error" : undefined}
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
          {mismatch ? (
            <p id="confirmation-error" className="text-xs text-destructive">
              Those two passwords do not match.
            </p>
          ) : null}
        </div>

        <Button type="submit" className="w-full" disabled={submitting}>
          {submitting ? "Saving…" : "Set password"}
        </Button>
      </form>
    </AuthLayout>
  );
}
