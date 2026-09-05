# ADR-0008 — The business's timezone is a hardcoded constant, not configuration

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

## Context

Several correctness rules depend on "what day is it": whether an invoice due
date has passed (`OverdueInvoices`, ADR-0007), which month a payment's revenue
falls into for the dashboard, and what "today" means when computing or
displaying dates generally. All of these need one authoritative definition of
"today," and a container almost always runs on UTC regardless of where its
users are.

Invoicely serves a single Singapore business — SGD, GST, UEN, and no
multi-currency or multi-region support (Product Scope §6) — so "today," "past
the due date," and "this month" all mean what they mean in Singapore (UTC+8),
not UTC. Two ways to fix the UTC-default mismatch:

- **Set `TZ` on the deployment environment.** No code change, but it makes a
  correctness rule depend on operational configuration that fails silently
  when someone forgets to set it: an unset `TZ` does not error, it just quietly
  reintroduces the eight-hour skew — an invoice that falls due at midnight
  Singapore time would keep reading as merely `SENT` until 08:00 local, and a
  payment recorded late on the last day of a month would land in the wrong
  month's revenue, with nothing in the codebase to catch it.
- **State it in code**, where it is guaranteed to apply the same way in every
  environment, can be read by anyone working on the code, and can be asserted
  in a test.

## Decision

**`BusinessCalendar.ZONE` hardcodes `ZoneId.of("Asia/Singapore")`
(`BusinessCalendar.java:28`), and `BusinessCalendar.today()` is the one method
that returns "today" in that zone.** Every correctness rule that needs to know
the current date — the overdue job and its read-time counterpart
(`OverdueInvoices`), and anywhere else "today" matters — calls this method
rather than `LocalDate.now()` directly.

1. The constant lives in exactly one class, so a real multi-region requirement
   later has exactly one place to learn about it: `BusinessCalendar.ZONE`
   becomes a per-business setting instead of a constant, and the rest of the
   codebase — which only ever calls `BusinessCalendar.today()` — does not need
   to change.
2. This is deliberately *not* solved by setting `TZ` at the OS/container
   level, even though that would also produce the right answer when configured
   correctly: an environment variable that is silently absent produces silent
   wrong answers, while a class that is silently absent does not compile.

## Consequences

- Every deployment computes "today" identically regardless of the host
  machine's or container's own configured timezone — there is nothing to set,
  and nothing to forget to set, in `application.properties`, the Dockerfile, or
  the hosting platform.
- Multi-region support, if it is ever added, is a real code change (a
  per-business zone, threaded through `BusinessCalendar` and everywhere it is
  called), not a configuration toggle — Product Scope §6 already places that
  out of scope, so this is accepted as the cost of the simpler single-region
  design.
- Tests that exercise date-sensitive behaviour (the overdue boundary, in
  particular) can rely on `Asia/Singapore` being the zone in every environment
  CI runs in, including a CI runner that itself defaults to UTC.
- A server clock reporting a wrong wall-clock time (unlikely, but not
  impossible on misconfigured infrastructure) still corrupts `today()`, since
  `ZoneId` only reinterprets an instant into a calendar date — it does not
  protect against the underlying instant itself being wrong. That risk exists
  independently of this decision and is not something a timezone constant can
  fix.
