import { createContext } from "react";
import type { AuthResponse, MeResponse } from "@/lib/types";

/** What the app knows about whoever is using it. */
export type Session = {
  token: string;
  user: MeResponse;
};

export type AuthContextValue = {
  /** Null when nobody is signed in. */
  session: Session | null;
  /**
   * True only while the very first `/auth/me` is in flight after a page load.
   * The app must render nothing route-dependent until this is false, or a
   * signed-in user reloading the page would be bounced to the login screen for
   * the split second before their token is confirmed.
   */
  restoring: boolean;
  /** True when the signed-in user is still on a temporary password. */
  mustChangePassword: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  signOut: () => void;
  /** Called by data hooks when the API says this token is finished. */
  onAuthenticationLost: () => void;
};

export type RegisterInput = {
  businessName: string;
  ownerName: string;
  email: string;
  password: string;
};

/** Shared by the provider and the `useAuth` hook; not exported to screens. */
export const AuthContext = createContext<AuthContextValue | null>(null);

/** Everything a fresh token tells us about its owner. */
export function sessionFrom(response: AuthResponse): Session {
  const { token, expiresAt: _expiresAt, ...user } = response;
  return { token, user };
}
