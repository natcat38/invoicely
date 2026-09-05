/**
 * A named, empty page for a route Task 8 or 9 will fill in.
 *
 * <p>These exist so Task 7b can prove the thing it is actually responsible
 * for — that routing, roles and the session all work — without pretending to
 * have built screens it has not. Each one says plainly which task owns it, so
 * nobody mistakes an unfinished route for a broken one.
 */
export function PlaceholderScreen({ title, task }: { title: string; task: string }) {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold text-app-text">{title}</h1>
      <div className="rounded-lg border border-dashed border-app-border bg-app-surface p-8 text-center">
        <p className="text-app-muted">Not built yet — {task}.</p>
      </div>
    </div>
  );
}
