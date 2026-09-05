import { Navigate, Outlet, useLocation } from "react-router";
import type { Role } from "@/lib/types";
import { useAuth } from "./useAuth";

/**
 * Gates every route that needs a signed-in user, and optionally a role.
 *
 * <p>This is UX, not security. The API enforces the same rules and would
 * answer 403 regardless — hiding a page the server would refuse anyway just
 * spares the user a dead end. Nothing here may ever be the *only* thing
 * standing between a staff member and an owner-only action.
 */
export function RequireAuth({ role }: { role?: Role }) {
  const { session, restoring, mustChangePassword } = useAuth();
  const location = useLocation();

  // Until the first /auth/me settles we genuinely do not know who this is.
  // Redirecting now would sign out every user who refreshes the page.
  if (restoring) return <FullPageMessage>Loading…</FullPageMessage>;

  if (!session && !mustChangePassword) {
    // `state` remembers where they were headed so signing in can continue the
    // journey instead of always dumping them on the landing page.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  // A temporary password blocks everything else, so there is no point routing
  // anywhere until it is replaced — the API would refuse every request.
  if (mustChangePassword) return <Navigate to="/change-password" replace />;

  if (role && session!.user.role !== role) {
    // Wrong role, but a real session: send them somewhere they can actually
    // use rather than to the login screen, which would imply signing in again
    // would help. It would not.
    return <Navigate to="/" replace />;
  }

  return <Outlet />;
}

export function FullPageMessage({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid min-h-dvh place-items-center p-6 text-app-muted" role="status">
      {children}
    </div>
  );
}
