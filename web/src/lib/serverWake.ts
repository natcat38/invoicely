/**
 * Tells the login screen whether the API is awake.
 *
 * <p>The live demo's API runs on a free Render instance that sleeps when idle.
 * Waking it took about 200 seconds when measured (October 2026), during which
 * a login request just hangs. The static UI is served from a different
 * host and loads instantly, so without this the visitor sees a working page
 * whose Sign in button appears to do nothing.
 *
 * <p>{@link watchServer} polls `GET /ping` (public, no database) until it
 * answers, and reports one of three states. It is written as a plain function
 * with its dependencies passed in so it can be tested without a browser or
 * real waiting; `useServerWake` is the thin React wrapper around it.
 */
export type ServerState =
  /** The API answered, or has not been slow enough yet to say anything. */
  | "ready"
  /** No answer yet: show the "waking the demo server" notice. */
  | "waking"
  /** Still no answer after {@link GIVE_UP_MS}: stop polling and say so. */
  | "unresponsive";

/** How long the first probe may take before the notice appears. */
export const SLOW_MS = 3_000;
/** Pause between probes while the server is not answering. */
export const RETRY_MS = 3_000;
/**
 * Stop polling after this long. The measured wake was about 200 s, so 6
 * minutes leaves room for a slower one without polling a dead server forever.
 */
export const GIVE_UP_MS = 6 * 60_000;

export type WatchOptions = {
  /** Resolves true only for a successful answer; never rejects. */
  ping: (signal: AbortSignal) => Promise<boolean>;
  report: (state: ServerState) => void;
  signal: AbortSignal;
  slowMs?: number;
  retryMs?: number;
  giveUpMs?: number;
};

/** Resolves after `ms`, or as soon as `signal` aborts. */
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const timer = setTimeout(done, ms);
    function done() {
      clearTimeout(timer);
      signal.removeEventListener("abort", done);
      resolve();
    }
    signal.addEventListener("abort", done);
  });
}

export async function watchServer({
  ping,
  report,
  signal,
  slowMs = SLOW_MS,
  retryMs = RETRY_MS,
  giveUpMs = GIVE_UP_MS,
}: WatchOptions): Promise<void> {
  const startedAt = Date.now();
  let answered = false;

  // A warm API answers in well under a second, and flashing a "waking up"
  // notice at every visitor would be wrong. Only a probe that is still
  // pending after `slowMs` earns the notice.
  const slowTimer = setTimeout(() => {
    if (!answered && !signal.aborted) report("waking");
  }, slowMs);

  try {
    while (!signal.aborted) {
      if (await ping(signal)) {
        answered = true;
        if (!signal.aborted) report("ready");
        return;
      }
      if (signal.aborted) return;
      if (Date.now() - startedAt >= giveUpMs) {
        report("unresponsive");
        return;
      }
      // A probe that failed outright (rather than being slow) means the API
      // is not up, so the notice goes up now instead of waiting for slowMs.
      report("waking");
      await pause(retryMs, signal);
    }
  } finally {
    clearTimeout(slowTimer);
  }
}
