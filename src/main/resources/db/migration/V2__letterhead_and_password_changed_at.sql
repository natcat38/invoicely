-- Phase 2 preparation: two unrelated columns that happen to land in the same
-- slice. From here on migrations are append-only — V1 could be rewritten only
-- because no durable database had ever applied it (see the note at the top of
-- V1__baseline.sql). This one may not be edited after it merges.

-- The invoice document's "from" block. The Design Direction renders the
-- invoice as a paper artifact, and a paper invoice has a letterhead: who is
-- billing, from where, and — in Singapore — under which UEN. Without these the
-- document could only ever print the business name, which is the one thing
-- that makes a rendered invoice look like a mock-up rather than a document.
--
-- Both are nullable: an existing business has neither, and a business that
-- never fills them in still produces a perfectly valid invoice with a thinner
-- letterhead. Nothing computes from them; they are printed and nothing else.
alter table businesses
    add column address text,
    -- Singapore Unique Entity Number. clients.uen already exists for the
    -- bill-to side; this is the same value for the billing side.
    add column uen     text;

-- When this user last changed their password, used to reject access tokens
-- issued before that moment (docs/adr/0010-session-invalidation-and-login-throttling.md).
--
-- Null means "never changed since the account was created", which is the
-- correct starting state for every existing row: there is no earlier password,
-- so there is no token to distrust. Only AuthService.changePassword ever writes
-- it, and it is never cleared.
alter table users
    add column password_changed_at timestamptz;
