import { useApiMutation, useApiQuery } from "@/lib/hooks";
import type { SettingsResponse } from "@/lib/types";

/**
 * The only query key this screen needs.
 *
 * <p>It is not in `lib/hooks.ts`'s `keys` object because that table exists to
 * stop two different callers from disagreeing about a key's shape — and
 * Settings has exactly one caller. A shared entry would be a level of
 * indirection with no second reader to justify it.
 */
const SETTINGS_KEY = ["settings"] as const;

/** `GET /settings` — owner only (Product Scope §5.2a). */
export function useSettings() {
  return useApiQuery<SettingsResponse>(SETTINGS_KEY, "/settings");
}

/** The body of `PUT /settings` — every field but the `id`, which the full replace doesn't need. */
export type SettingsInput = Omit<SettingsResponse, "id">;

/**
 * A full replace of the business's settings.
 *
 * <p>There is no patch endpoint (`SettingsController.update`), so the form
 * this backs must always send every field, not just the one the owner
 * changed.
 */
export function useUpdateSettings() {
  return useApiMutation<SettingsResponse, SettingsInput>(
    (input) => ({ path: "/settings", options: { method: "PUT", body: input } }),
    [SETTINGS_KEY],
  );
}
