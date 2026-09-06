import { useEffect, useRef } from "react";
import { ApiError } from "@/lib/api";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

/**
 * Shows a failed request the way the Design Direction asks: the API's own
 * Problem Details text, where that text is fit for a user to read.
 *
 * <p>The API writes its `detail` messages as plain sentences aimed at people
 * ("Incorrect email or password.", "Set a new password to continue."), so
 * showing them directly keeps one wording rather than two that drift. The
 * exceptions are handled below, where the raw detail would be unhelpful.
 */
export function FormError({ error }: { error: ApiError | null }) {
  const container = useRef<HTMLDivElement>(null);

  /**
   * Moves focus to the summary when an error appears.
   *
   * <p>Without it, a failed submit leaves focus on the submit button at the
   * bottom of a form whose error is at the top — a sighted user sees the red
   * box, and a screen-reader user is told nothing they can navigate to. The
   * container is `tabIndex={-1}` so it can receive focus programmatically
   * without joining the tab order afterwards.
   *
   * <p>Keyed on the message rather than on `error` itself: two consecutive
   * failed submits create two different `ApiError` objects, so depending on
   * the object would re-focus on every attempt, while depending on the text
   * re-announces only when what is wrong actually changes.
   */
  useEffect(() => {
    if (error) container.current?.focus();
  }, [error?.message, error]);

  if (!error) return null;

  return (
    <Alert ref={container} tabIndex={-1} variant="destructive" role="alert">
      <AlertTitle>{titleFor(error)}</AlertTitle>
      <AlertDescription>
        <p>{messageFor(error)}</p>
        {/* Bean Validation failures name the field they came from. Listing
            them beats a single vague "check your input", and the API has
            already written each message as a sentence.

            The key includes the message because one field can fail two rules
            at once (a password can be both too short and blank), and the API
            sends one entry per rule. */}
        {error.fieldErrors.length > 0 ? (
          <ul className="mt-2 list-disc space-y-1 pl-4">
            {error.fieldErrors.map(({ field, message }) => (
              <li key={`${field}:${message}`}>{message}</li>
            ))}
          </ul>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}

function titleFor(error: ApiError): string {
  if (error.status === 0) return "Cannot reach the server";
  if (error.problemType === "too-many-attempts") return "Too many attempts";
  return "That did not work";
}

function messageFor(error: ApiError): string {
  // The throttle answers with a generic sentence plus a Retry-After header;
  // turning the header into minutes is more use than "try again later".
  if (error.problemType === "too-many-attempts" && error.retryAfterSeconds !== null) {
    const minutes = Math.ceil(error.retryAfterSeconds / 60);
    return `Too many failed attempts. Try again in about ${minutes} minute${minutes === 1 ? "" : "s"}.`;
  }
  // A 500 has no message worth showing: whatever it says is about the server's
  // internals, not about anything the user can do.
  if (error.status >= 500) {
    return "Something went wrong on our side. Please try again.";
  }
  return error.message;
}
