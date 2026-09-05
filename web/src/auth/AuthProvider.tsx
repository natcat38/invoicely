import { useCallback, useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError, api } from "@/lib/api";
import type { AuthResponse, MeResponse } from "@/lib/types";
import {
  AuthContext,
  type AuthContextValue,
  type RegisterInput,
  type Session,
  sessionFrom,
} from "./AuthContext";

/**
 * Where the token lives between page loads.
 *
 * `localStorage` is ADR-0002's decision, made with its downside stated: any
 * XSS on the page can read it, where an httpOnly cookie could not. The ADR
 * accepted that in exchange for a stateless API with no CSRF surface, and
 * relies on React's default escaping to keep script out of the page. This is
 * the only module that touches it.
 */
const TOKEN_KEY = "invoicely.token";

function readStoredToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    // Safari in private mode, and any browser with site data blocked, throws
    // rather than returning null. Treated as "not signed in": the app still
    // works, it just cannot remember anyone across a reload.
    return null;
  }
}

function writeStoredToken(token: string | null) {
  try {
    if (token === null) window.localStorage.removeItem(TOKEN_KEY);
    else window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Same as above — failing to persist is survivable, so it must not take
    // the sign-in down with it.
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  /**
   * Seeded from storage rather than starting at `true` and being corrected in
   * the effect: with no stored token there is nothing to restore, so starting
   * at `true` would render one throwaway "Loading…" frame and immediately
   * re-render — for every visitor who is not signed in, which is all of them
   * on a first visit.
   */
  const [restoring, setRestoring] = useState(() => readStoredToken() !== null);
  const [mustChangePassword, setMustChangePassword] = useState(false);

  const queryClient = useQueryClient();

  const clearSession = useCallback(() => {
    writeStoredToken(null);
    setSession(null);
    setMustChangePassword(false);
    // Throw away every cached response as well as the token. TanStack Query
    // keys by query, not by user, so without this the next person to sign in
    // on this browser would be served the previous user's cached clients and
    // invoices until each query refetched — a different business's data on
    // screen, which is the one thing ADR-0001 exists to prevent. It costs
    // nothing today (no screen fetches yet) and is easy to forget once Task 8
    // adds the queries that would make it visible.
    queryClient.clear();
  }, [queryClient]);

  const acceptToken = useCallback((response: AuthResponse) => {
    writeStoredToken(response.token);
    setSession(sessionFrom(response));
    setMustChangePassword(response.mustChangePassword);
  }, []);

  /**
   * Re-identifies the caller from a stored token on a fresh page load.
   *
   * The token itself says who it belongs to, but this app cannot read it: it
   * is signed, not encrypted, and trusting an unverified payload in the
   * browser would mean the UI believing whatever a tampered token claimed.
   * `GET /auth/me` asks the API, which is the only party that can actually
   * check the signature.
   */
  useEffect(() => {
    const token = readStoredToken();
    // `restoring` is already false in this case — see its initialiser.
    if (!token) return;

    const aborter = new AbortController();
    api<MeResponse>("/auth/me", { token, signal: aborter.signal })
      .then((user) => setSession({ token, user }))
      .catch((error: unknown) => {
        if (aborter.signal.aborted) return;
        // A user on a temporary password gets 403 from /auth/me, because the
        // API lets them reach nothing but the change-password endpoint. That
        // is a valid session that owes one action, not a dead one — so the
        // token is kept and the interstitial is shown.
        if (error instanceof ApiError && error.requiresPasswordChange) {
          setMustChangePassword(true);
          return;
        }
        // Anything else means the stored token is no longer usable. Dropping
        // it here is what stops a superseded or expired token from producing
        // a failed request on every screen the user visits.
        writeStoredToken(null);
      })
      .finally(() => {
        if (!aborter.signal.aborted) setRestoring(false);
      });

    return () => aborter.abort();
  }, []);

  const signIn = useCallback(
    async (email: string, password: string) => {
      acceptToken(
        await api<AuthResponse>("/auth/login", {
          method: "POST",
          body: { email, password },
        }),
      );
    },
    [acceptToken],
  );

  const register = useCallback(
    async (input: RegisterInput) => {
      acceptToken(
        await api<AuthResponse>("/auth/register", { method: "POST", body: input }),
      );
    },
    [acceptToken],
  );

  const changePassword = useCallback(
    async (currentPassword: string, newPassword: string) => {
      const token = session?.token ?? readStoredToken();
      // The response carries a *fresh* token, stamped after the change, so the
      // user stays signed in. Every other token they hold is now rejected as
      // `token-superseded` — that asymmetry is the whole point of ADR-0010,
      // and it is why the forced-change screen needs no "now sign in again".
      acceptToken(
        await api<AuthResponse>("/auth/change-password", {
          method: "POST",
          token,
          body: { currentPassword, newPassword },
        }),
      );
    },
    [acceptToken, session],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      restoring,
      mustChangePassword,
      signIn,
      register,
      changePassword,
      signOut: clearSession,
    }),
    [session, restoring, mustChangePassword, signIn, register, changePassword, clearSession],
  );

  return <AuthContext value={value}>{children}</AuthContext>;
}
