/**
 * The API response shapes this slice needs, hand-written to match the Java
 * records they come from.
 *
 * Hand-written rather than generated from the OpenAPI document on purpose, for
 * now: the API publishes `/v3/api-docs`, so generation is available the moment
 * these drift often enough to be annoying. Until then a generator would add a
 * build step and a lot of machine-written types for the seven fields Task 7b
 * actually reads.
 */

export type Role = "OWNER" | "STAFF";

/** `POST /auth/login`, `/auth/register` and `/auth/change-password`. */
export type AuthResponse = {
  token: string;
  expiresAt: string;
  userId: number;
  name: string;
  email: string;
  role: Role;
  businessId: number;
  businessName: string;
  /**
   * True only from `/auth/login`, and only for a staff member who has not yet
   * replaced the temporary password their owner generated. While it is true
   * the API refuses every endpoint but `/auth/change-password`, so the UI must
   * show the interstitial rather than the app.
   */
  mustChangePassword: boolean;
};

/** `GET /auth/me` — the same identity, minus the token that carried it. */
export type MeResponse = Omit<AuthResponse, "token" | "expiresAt">;
