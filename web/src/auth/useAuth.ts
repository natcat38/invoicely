import { useContext } from "react";
import { AuthContext, type AuthContextValue } from "./AuthContext";

/**
 * The session, and the four things you can do to it.
 *
 * Throws rather than returning null when used outside the provider: that is a
 * wiring mistake, and a clear error at the first render beats a component
 * quietly deciding nobody is signed in.
 */
export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>.");
  return value;
}
