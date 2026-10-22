import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/auth/useAuth";
import { useServerWake } from "@/lib/useServerWake";
import { SLOW_MS } from "@/lib/serverWake";
import { AuthLayout } from "./AuthLayout";
import { FormError } from "./FormError";
import { WakeNotice } from "./WakeNotice";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export function LoginScreen() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<ApiError | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const serverState = useServerWake();
  // A sign-in that has been pending for a few seconds is waiting on a sleeping
  // API even if the background probe has not said so yet (it could have been
  // answered by an instance that then went away), so the notice and the button
  // label must not depend on the probe alone.
  const [slowSubmit, setSlowSubmit] = useState(false);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    const slowTimer = setTimeout(() => setSlowSubmit(true), SLOW_MS);
    try {
      await signIn(email, password);
      // Continue wherever RequireAuth interrupted them, if it did. A staff
      // member on a temporary password is redirected again by RequireAuth --
      // this does not need to know about that case.
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from ?? "/", { replace: true });
    } catch (caught) {
      setError(caught as ApiError);
    } finally {
      clearTimeout(slowTimer);
      setSlowSubmit(false);
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="Sign in"
      subtitle="Welcome back."
      footer={
        <p className="text-sm text-app-muted">
          No account yet?{" "}
          <Link to="/register" className="font-medium text-app-accent underline-offset-4 hover:underline">
            Register your business
          </Link>
        </p>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <WakeNotice state={serverState === "ready" && slowSubmit ? "waking" : serverState} />
        <FormError error={error} />

        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            name="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </div>

        <Button type="submit" className="w-full" disabled={submitting}>
          {submitting ? (slowSubmit ? "Waiting for the server…" : "Signing in…") : "Sign in"}
        </Button>
      </form>
    </AuthLayout>
  );
}
