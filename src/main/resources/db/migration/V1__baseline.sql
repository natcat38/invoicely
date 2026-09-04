-- Invoicely baseline schema: the six tables from Tech Scope §3.
--
-- This file previously held only "SELECT 1". Editing a migration that has
-- already shipped is normally forbidden — Flyway records a checksum and will
-- refuse to run against a database that applied the old version. It is safe
-- here because no environment is durable yet: local Postgres and CI both come
-- from throwaway containers. From V2 onwards, migrations are append-only.
--
-- Conventions used throughout:
--   * ids are `bigint generated always as identity` — Postgres' standard
--     auto-increment. Simple, and the numbers are only ever seen by the API.
--   * money is numeric(19,4); never float. See knowledge/domain/money.md.
--   * enums live as text + a CHECK constraint rather than a Postgres ENUM type,
--     because adding a value to a Postgres ENUM needs its own migration while a
--     CHECK is a plain ALTER. Java maps them with @Enumerated(EnumType.STRING).
--   * every business-owned table carries business_id: the ownership boundary.
--     See docs/adr/0001-business-as-ownership-boundary.md.

create table businesses (
    id                         bigint       generated always as identity primary key,
    name                       text         not null,
    gst_registered             boolean      not null default false,
    -- Kept even while gst_registered is false, so toggling registration back on
    -- remembers the rate. 0.0900 = the Singapore GST rate at time of writing.
    gst_rate                   numeric(5,4) not null default 0.0900
                               check (gst_rate >= 0 and gst_rate < 1),
    default_payment_terms_days integer      not null default 30
                               check (default_payment_terms_days in (7, 14, 30)),
    created_at                 timestamptz  not null default now()
);

create table users (
    id                   bigint      generated always as identity primary key,
    business_id          bigint      not null references businesses (id),
    name                 text        not null,
    email                text        not null,
    password_hash        text        not null,
    role                 text        not null check (role in ('OWNER', 'STAFF')),
    must_change_password boolean     not null default false,
    -- Deactivation is a flag, not a delete: invoices keep pointing at the user
    -- who created or sent them.
    active               boolean     not null default true,
    created_at           timestamptz not null default now()
);
-- Email uniqueness is case-insensitive at the database level, so it holds even
-- if a future code path forgets to normalise. Look users up with
-- findByEmailIgnoreCase so the query can use this index.
create unique index users_email_lower_key on users (lower(email));
create index users_business_id_idx on users (business_id);

create table clients (
    id             bigint  generated always as identity primary key,
    business_id    bigint  not null references businesses (id),
    name           text    not null,
    contact_person text,
    email          text,
    phone          text,
    address        text,
    -- Singapore Unique Entity Number, printed on the invoice document.
    uen            text,
    -- Free text such as PayNow or bank details; also printed on the document.
    payment_notes  text,
    -- Clients are archived rather than deleted once they have invoices.
    archived       boolean not null default false
);
create index clients_business_id_idx on clients (business_id);

create table invoices (
    id                bigint       generated always as identity primary key,
    business_id       bigint       not null references businesses (id),
    client_id         bigint       not null references clients (id),
    -- Audit attribution only. Access control is business_id, never these.
    created_by        bigint       not null references users (id),
    number            text         not null,
    status            text         not null check (status in
                                   ('DRAFT', 'PENDING_APPROVAL', 'SENT', 'OVERDUE', 'PAID')),
    issue_date        date         not null,
    due_date          date         not null check (due_date >= issue_date),
    -- Written once, when the invoice is sent, from the business' gst_rate.
    -- Null until then, and never rewritten afterwards, so changing the business
    -- setting later cannot alter an invoice a client has already received.
    gst_rate_snapshot numeric(5,4),
    rejection_note    text,
    sent_at           timestamptz,
    sent_by           bigint       references users (id),
    created_at        timestamptz  not null default now(),
    -- INV-<year>-<seq> is unique per business, not globally: two businesses may
    -- both have an INV-2026-0001.
    unique (business_id, number)
);
-- Covers the invoice list, which always filters by business and usually by status.
create index invoices_business_status_idx on invoices (business_id, status);
create index invoices_client_id_idx on invoices (client_id);

create table line_items (
    id          bigint        generated always as identity primary key,
    -- Line items belong to their invoice's lifecycle: deleting the invoice
    -- deletes them, and they are never addressed independently.
    invoice_id  bigint        not null references invoices (id) on delete cascade,
    description text          not null,
    quantity    numeric(19,4) not null check (quantity > 0),
    unit_price  numeric(19,4) not null check (unit_price >= 0),
    -- Display order within the invoice. Deliberately not unique: reordering
    -- would otherwise collide part-way through the update.
    position    integer       not null
);
create index line_items_invoice_id_idx on line_items (invoice_id);

create table payments (
    id          bigint        generated always as identity primary key,
    invoice_id  bigint        not null references invoices (id) on delete cascade,
    amount      numeric(19,4) not null check (amount > 0),
    -- The date the money arrived, as entered by the owner — not a system clock
    -- reading, which is why it is a date and not a timestamp.
    paid_at     date          not null,
    method      text          not null check (method in
                              ('BANK_TRANSFER', 'PAYNOW', 'CASH', 'CHEQUE')),
    note        text,
    recorded_by bigint        not null references users (id)
);
create index payments_invoice_id_idx on payments (invoice_id);
