import { useApiMutation, useApiQuery } from "@/lib/hooks";
import type { CreatedStaff, StaffMember } from "@/lib/types";

/**
 * Matches the one cached `/team` list, whatever it currently holds.
 *
 * <p>Unlike `keys.clients`/`keys.invoices` in `lib/hooks.ts`, `/team` takes no
 * query parameters — it is always the same list for the whole business — so
 * there is only ever one key to invalidate. It is declared here rather than
 * added to the shared `keys` object because this slice is the only caller.
 */
const TEAM = ["team"] as const;

/** `GET /team` — every staff member plus the owner themselves. */
export function useTeamList() {
  return useApiQuery<StaffMember[]>(TEAM, "/team");
}

/** The body of `POST /team`. */
export type AddStaffInput = { name: string; email: string };

/**
 * `POST /team`. The server generates the temporary password and hands it
 * back exactly once on `CreatedStaff` — see the type's own comment in
 * `lib/types.ts` for why the UI cannot ask for it again later.
 */
export function useAddStaff() {
  return useApiMutation<CreatedStaff, AddStaffInput>(
    (input) => ({ path: "/team", options: { method: "POST", body: input } }),
    [TEAM],
  );
}

/**
 * `PATCH /team/{id}` with `{active}` — the one field this endpoint can
 * change. Used for both directions: deactivating a staff member and
 * reactivating one.
 *
 * <p>The API itself answers 409 if this is used to deactivate the owner's own
 * account (Product Scope §5.1) — this hook does not special-case that, it
 * just forwards whatever the caller asks for and lets the request fail if the
 * server refuses it.
 */
export function useSetActive() {
  return useApiMutation<StaffMember, { id: number; active: boolean }>(
    ({ id, active }) => ({
      path: `/team/${id}`,
      options: { method: "PATCH", body: { active } },
    }),
    [TEAM],
  );
}
