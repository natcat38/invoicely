/**
 * The one place this app talks to the API.
 *
 * Everything goes through {@link api} so that three rules are applied in a
 * single place rather than remembered at every call site: attach the bearer
 * token, turn a Problem Details body into a typed error, and never let a
 * failed response look like a successful one.
 */

// The trailing-slash strip matters: paths are appended as "/auth/login", so a
// base of "https://api.example/" would produce "//auth/login" — a path Spring's
// CORS mapping does not match, which surfaces as an opaque preflight failure.
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080")
  .replace(/\/+$/, "");

/** One invalid field, exactly as `GlobalExceptionHandler.FieldProblem` sends it. */
export type FieldError = { field: string; message: string };

/**
 * A failed request, carrying the parts of RFC 9457 Problem Details the UI
 * actually branches on.
 *
 * `problemType` is the important one. The API deliberately answers with
 * several different 401s and 403s that mean different things — an expired
 * token, a token invalidated by a password change, a deactivated account, an
 * unreplaced temporary password, a plain role refusal — and the status code
 * alone cannot tell them apart. The `type` field exists for exactly that, so
 * the UI branches on it instead of pattern-matching on English prose that
 * might be reworded tomorrow. See ADR-0010.
 */
export class ApiError extends Error {
  readonly status: number;
  /** The trailing segment of the problem type, e.g. `token-superseded`. */
  readonly problemType: string | null;
  /**
   * Field-level validation messages, when the API sent any.
   *
   * A list, not a map, because that is what the API sends:
   * `GlobalExceptionHandler` writes `errors` as an array of
   * `{ field, message }` records, sorted so responses are stable. One field
   * can therefore appear more than once, which a map could not represent.
   */
  readonly fieldErrors: FieldError[];
  /** Seconds to wait, from `Retry-After` — only ever set on a 429. */
  readonly retryAfterSeconds: number | null;

  constructor(
    status: number,
    detail: string,
    problemType: string | null,
    fieldErrors: FieldError[] = [],
    retryAfterSeconds: number | null = null,
  ) {
    super(detail);
    this.name = "ApiError";
    this.status = status;
    this.problemType = problemType;
    this.fieldErrors = fieldErrors;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /**
   * True when this token can never work again, whatever the user does next:
   * it expired, a password change superseded it, or the account was switched
   * off. All three mean "throw the token away and show the login screen",
   * which is why they are asked about together.
   *
   * Deactivation is a 403 rather than a 401 (the token is genuine; the
   * authorisation is not), so a status check alone would miss it — this is the
   * concrete reason `problemType` is carried at all.
   */
  get requiresReauthentication(): boolean {
    if (this.problemType === "account-deactivated") return true;
    if (this.problemType === "token-superseded") return true;
    // A bare 401 with no problem type is an expired or malformed token: Spring
    // Security rejects it before any handler can attach a body.
    return this.status === 401 && this.problemType !== "invalid-credentials";
  }

  /** The caller is signed in but still on their temporary password. */
  get requiresPasswordChange(): boolean {
    return this.problemType === "password-change-required";
  }
}

type ProblemDetails = {
  detail?: string;
  title?: string;
  type?: string;
  errors?: FieldError[];
};

/** `"/problems/token-superseded"` → `"token-superseded"`. */
function problemTypeOf(type: string | undefined): string | null {
  if (!type || type === "about:blank") return null;
  const lastSlash = type.lastIndexOf("/");
  return lastSlash === -1 ? type : type.slice(lastSlash + 1);
}

/**
 * `Retry-After` as a number of seconds, or null if it is not one.
 *
 * RFC 9110 allows the header to be either a delay in seconds or an HTTP date,
 * and `Number("Wed, 21 Oct 2026 07:28:00 GMT")` is `NaN`. Passing that through
 * would surface to the user as "try again in about NaN minutes", so anything
 * that is not a plain non-negative number is treated as absent — the caller
 * already has a sensible message for that case. Our own API always sends
 * seconds; this guards against a proxy rewriting it.
 */
function retryAfterSeconds(header: string | null): number | null {
  if (header === null) return null;
  const seconds = Number(header);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

export type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** The bearer token, when the endpoint needs one. */
  token?: string | null;
  signal?: AbortSignal;
};

/**
 * Performs one API call and returns its parsed body.
 *
 * @throws ApiError for any non-2xx response — including ones the browser
 * itself considers fine. `fetch` only rejects on a network failure, so without
 * this an expired token would arrive at a component as a perfectly ordinary
 * "value" and be rendered as if it were data.
 */
export async function api<T>(
  path: string,
  { method = "GET", body, token, signal }: RequestOptions = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    // The request never reached the API: it is down, the wrong origin, or the
    // machine is offline. Distinguished from an API error because there is no
    // status and no problem type to reason about, and the advice to the user
    // is completely different.
    if (signal?.aborted) throw cause;
    throw new ApiError(0, "Could not reach the server. Is the API running?", null);
  }

  if (response.status === 204) return undefined as T;

  // A 4xx from Spring is Problem Details JSON; a 5xx or a proxy error may not
  // be JSON at all, so parsing is allowed to fail without masking the status.
  const text = await response.text();
  let parsed: unknown = null;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = null;
    }
  }

  if (!response.ok) {
    const problem = (parsed ?? {}) as ProblemDetails;
    const retryAfter = response.headers.get("Retry-After");
    throw new ApiError(
      response.status,
      problem.detail ?? problem.title ?? `Request failed (${response.status}).`,
      problemTypeOf(problem.type),
      // Defended rather than trusted: a proxy or a future handler could send
      // something that is not a list, and mapping over a non-array would take
      // the whole screen down with it.
      Array.isArray(problem.errors) ? problem.errors : [],
      retryAfterSeconds(retryAfter),
    );
  }

  return parsed as T;
}
