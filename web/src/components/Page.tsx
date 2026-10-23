import { useEffect, useState } from "react";
import { ApiError } from "@/lib/api";
import { usePageTitle } from "@/lib/usePageTitle";

/**
 * The header every screen inside the app shell starts with: title on the
 * left, actions right-aligned, per the Design Direction's layout.
 */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
}) {
  usePageTitle(title);
  return (
    <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold text-app-text">{title}</h1>
        {description ? <p className="mt-1 text-sm text-app-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </header>
  );
}

/**
 * Nothing to show yet, and what to do about it.
 *
 * <p>The Design Direction asks empty states to invite action rather than just
 * report absence, which is why `action` is part of the shape rather than
 * something each caller remembers to add.
 */
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-dashed border-app-border bg-app-surface px-6 py-12 text-center">
      <p className="font-medium text-app-text">{title}</p>
      {description ? <p className="mt-1 text-sm text-app-muted">{description}</p> : null}
      {action ? <div className="mt-4 flex justify-center">{action}</div> : null}
    </div>
  );
}

/**
 * A plain "Loading…". `role="status"` so a screen reader announces the wait
 * instead of falling silent.
 *
 * <p>It reserves about the height of a short list, so the page does not jump
 * when the data arrives, and after a second it says "Still loading…" so a slow
 * request (a sleeping demo server, a bad connection) does not look frozen.
 */
export function Loading({ label = "Loading…" }: { label?: string }) {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), 1000);
    return () => clearTimeout(timer);
  }, []);

  return (
    <p role="status" className="grid min-h-64 place-content-center px-6 text-center text-app-muted">
      {slow ? "Still loading…" : label}
    </p>
  );
}

/**
 * A failed load, showing the API's own Problem Details sentence.
 *
 * <p>Distinct from `FormError`, which sits inside a form: this replaces the
 * content that could not be fetched, so it offers a retry rather than
 * assuming the user will resubmit something.
 */
export function LoadError({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  return (
    <div role="alert" className="rounded-lg border border-destructive/30 bg-destructive/5 px-6 py-8 text-center">
      <p className="font-medium text-destructive">Could not load this</p>
      <p className="mt-1 text-sm text-app-text">
        {error.status >= 500 || error.status === 0
          ? "Something went wrong on our side. Please try again."
          : error.message}
      </p>
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 inline-flex min-h-11 items-center text-sm font-medium text-app-accent underline-offset-4 hover:underline md:min-h-0"
        >
          Try again
        </button>
      ) : null}
    </div>
  );
}
