import { Loader2Icon, TriangleAlertIcon } from "lucide-react";
import type { ServerState } from "@/lib/serverWake";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

/**
 * Explains why sign-in is slow while the free demo API wakes up.
 *
 * <p>`role="status"` rather than the `alert` the Alert component defaults to:
 * this is progress, not an error, so a screen reader should announce it
 * politely instead of interrupting.
 */
export function WakeNotice({ state }: { state: ServerState }) {
  if (state === "ready") return null;

  if (state === "unresponsive") {
    return (
      <Alert role="status" variant="destructive">
        <TriangleAlertIcon aria-hidden="true" />
        <AlertTitle>The demo server is not responding</AlertTitle>
        <AlertDescription>
          It has not answered after several minutes. Reload the page to try again.
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <Alert role="status">
      <Loader2Icon aria-hidden="true" className="animate-spin" />
      <AlertTitle>Waking the demo server</AlertTitle>
      <AlertDescription>
        This demo runs on a free host that sleeps when idle, so the first load can take a few
        minutes. You can sign in as soon as this message goes away.
      </AlertDescription>
    </Alert>
  );
}
