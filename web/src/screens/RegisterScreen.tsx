import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/auth/useAuth";
import { AuthLayout } from "./AuthLayout";
import { FormError } from "./FormError";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * Registration is one screen, not two: Product Scope §5.1 treats "create a
 * business" and "create its owner" as a single act, and the API's
 * `/auth/register` creates both in one transaction and signs the owner
 * straight in.
 */
export function RegisterScreen() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({
    businessName: "",
    ownerName: "",
    email: "",
    password: "",
  });
  const [error, setError] = useState<ApiError | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function update(field: keyof typeof form) {
    return (event: React.ChangeEvent<HTMLInputElement>) =>
      setForm((current) => ({ ...current, [field]: event.target.value }));
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(form);
      navigate("/", { replace: true });
    } catch (caught) {
      setError(caught as ApiError);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="Register your business"
      subtitle="This creates your business and your owner account."
      footer={
        <p className="text-sm text-app-muted">
          Already registered?{" "}
          <Link to="/login" className="font-medium text-app-accent underline-offset-4 hover:underline">
            Sign in
          </Link>
        </p>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <FormError error={error} />

        <div className="space-y-2">
          <Label htmlFor="businessName">Business name</Label>
          <Input
            id="businessName"
            autoComplete="organization"
            required
            value={form.businessName}
            onChange={update("businessName")}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="ownerName">Your name</Label>
          <Input
            id="ownerName"
            autoComplete="name"
            required
            value={form.ownerName}
            onChange={update("ownerName")}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            autoComplete="username"
            required
            value={form.email}
            onChange={update("email")}
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            required
            value={form.password}
            onChange={update("password")}
          />
          {/* The API's rule, stated up front rather than discovered through a
              rejected form. Eight characters, no composition rules. */}
          <p className="text-xs text-app-muted">At least 8 characters.</p>
        </div>

        <Button type="submit" className="w-full" disabled={submitting}>
          {submitting ? "Creating your business…" : "Create business"}
        </Button>
      </form>
    </AuthLayout>
  );
}
