import { useEffect, useState } from "react";
import { ApiError } from "@/lib/api";
import { Skeleton } from "@/components/ui/skeleton";
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
  const slow = useSlow();

  return (
    <p role="status" className="grid min-h-64 place-content-center px-6 text-center text-app-muted">
      {slow ? "Still loading…" : label}
    </p>
  );
}

/** True once a wait has lasted a second, so a slow request can say so. */
function useSlow(): boolean {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), 1000);
    return () => clearTimeout(timer);
  }, []);
  return slow;
}

/**
 * Placeholder rows for a table that is still loading (decided 2026-10-24:
 * skeletons on tables only; everything else keeps the plain `Loading` line).
 * The rows are about as tall as the real ones, so the list does not jump when
 * it arrives, and there are no columns to match because the table is a table
 * on a laptop and cards on a phone.
 *
 * <p>The words stay for screen readers and for the slow case: the bars are
 * `aria-hidden`, and a line under them changes to "Still loading…" after a
 * second. Its space is always reserved.
 */
export function TableSkeleton({ rows = 5, label = "Loading…" }: { rows?: number; label?: string }) {
  const slow = useSlow();

  return (
    <div role="status">
      <div className="rounded-lg border border-app-border bg-app-surface">
        {Array.from({ length: rows }, (_, index) => (
          <div
            key={index}
            className="flex items-center gap-4 border-b border-app-border px-4 py-5 last:border-b-0"
          >
            <Skeleton className="h-4 w-28" />
            <Skeleton className="h-5 w-20 rounded-full" />
            <Skeleton className="h-4 flex-1" />
            <Skeleton className="hidden h-4 w-20 sm:block" />
          </div>
        ))}
      </div>
      <p className="mt-3 min-h-5 text-center text-sm text-app-muted">{slow ? "Still loading…" : ""}</p>
      <span className="sr-only">{label}</span>
    </div>
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
