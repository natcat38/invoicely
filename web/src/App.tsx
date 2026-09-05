import { Navigate, Route, Routes } from "react-router";
import { RequireAuth } from "@/auth/RequireAuth";
import { useAuth } from "@/auth/useAuth";
import { AppShell } from "@/shell/AppShell";
import { ChangePasswordScreen } from "@/screens/ChangePasswordScreen";
import { LoginScreen } from "@/screens/LoginScreen";
import { PlaceholderScreen } from "@/screens/PlaceholderScreen";
import { RegisterScreen } from "@/screens/RegisterScreen";

/**
 * Every route in the app.
 *
 * <p>Three tiers: open (sign in, register), signed-in, and owner-only. The
 * owner-only tier is nested inside the signed-in one, so a staff member who
 * reaches `/team` is sent somewhere they can use rather than to the login
 * screen — signing in again would not help them.
 *
 * <p>`/` is not a page. It is a redirect that answers "where does this role
 * belong?", which is what keeps the owner-only guard from ever bouncing a
 * staff member to a route that bounces them straight back.
 */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<SignedOutOnly><LoginScreen /></SignedOutOnly>} />
      <Route path="/register" element={<SignedOutOnly><RegisterScreen /></SignedOutOnly>} />

      {/* Outside RequireAuth: reaching it needs a token but *not* a usable
          session, which is precisely the state a staff member on a temporary
          password is in. */}
      <Route path="/change-password" element={<ChangePasswordScreen />} />

      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route index element={<HomeForRole />} />

          <Route path="/invoices" element={<PlaceholderScreen title="Invoices" task="Task 8" />} />
          <Route path="/clients" element={<PlaceholderScreen title="Clients" task="Task 8" />} />

          <Route element={<RequireAuth role="OWNER" />}>
            <Route path="/dashboard" element={<PlaceholderScreen title="Dashboard" task="Task 9" />} />
            <Route path="/team" element={<PlaceholderScreen title="Team" task="Task 9" />} />
            <Route path="/settings" element={<PlaceholderScreen title="Settings" task="Task 9" />} />
          </Route>
        </Route>
      </Route>

      {/* Unknown path: send them home rather than to a dead end. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

/**
 * The landing route, resolved per role.
 *
 * <p>An owner lands on the dashboard — the money summary is the reason they
 * open the app. A staff member has no dashboard at all (the API would answer
 * 403), so they land on the invoice list, which is where their work is.
 */
function HomeForRole() {
  const { session } = useAuth();
  return <Navigate to={session!.user.role === "OWNER" ? "/dashboard" : "/invoices"} replace />;
}

/**
 * Keeps a signed-in user off the sign-in and registration screens.
 *
 * <p>Without this, a signed-in user visiting `/login` would see a form that
 * cannot tell them they are already signed in — and registering from there
 * would silently replace their session with a different business.
 */
function SignedOutOnly({ children }: { children: React.ReactNode }) {
  const { session, restoring, mustChangePassword } = useAuth();
  if (restoring) return null;
  if (mustChangePassword) return <Navigate to="/change-password" replace />;
  if (session) return <Navigate to="/" replace />;
  return children;
}
