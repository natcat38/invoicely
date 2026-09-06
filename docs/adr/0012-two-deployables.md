# ADR-0012 — The API and the UI deploy as two things, not one

**Status:** accepted · **Date:** 2026-09-06 · **Decided by:** Claude, recorded for the owner

## Context

M10 deploys the product. The obvious cheap shape for a demo is one container:
build the React app, drop `web/dist` into `src/main/resources/static`, and let
Spring serve both the API and the page. One service to pay for, one URL, and
no CORS at all because everything is same-origin.

That was the intended approach until the route table was checked against it.

## The problem that decided it

**The API and the UI want the same paths.** The React app routes on
`/invoices`, `/clients`, `/team`, `/settings` and `/dashboard`. The API serves
JSON at `/invoices`, `/clients`, `/team`, `/settings` and `/dashboard`.

Served from one origin, a browser navigating to `/invoices` would be handed the
invoice list as JSON instead of the application. Making it work would mean one
of:

- **Move the API under `/api`.** Correct in the abstract, and the shape most
  applications would have started with. But it changes every controller path,
  every test's MockMvc URL, the OpenAPI document, and the README's API tour —
  a wide, mechanical change late in the project, made to serve a hosting
  convenience rather than anything a user experiences.
- **Content-negotiate.** Return HTML for `Accept: text/html` and JSON
  otherwise, on the same path. This works right up until something sends the
  wrong `Accept` header, and then it fails in a way that is genuinely hard to
  read.

## Decision

Deploy two things:

1. **The API** as a container, from the `Dockerfile` at the repository root.
2. **The UI** as static files (`web/dist`), on any static host.

They talk over CORS, which Task 7a already built:
`invoicely.security.allowed-origins` is an explicit allow-list read from the
environment, and the token travels in an `Authorization` header rather than a
cookie (ADR-0002), so `allowCredentials` stays off.

## Consequences

- **No path collision, and no code changed to avoid one.** The API keeps the
  URLs its tests, its OpenAPI document and its README already describe.
- **One more thing to configure**, and it is the thing most likely to be got
  wrong: the UI needs `VITE_API_BASE_URL` pointing at the API, and the API
  needs that UI's origin in its allow-list. Get either wrong and every request
  fails in the browser with an opaque CORS error. The README's deploy section
  states both, together, for that reason.
- **The static host must fall back to `index.html`** for unknown paths, or a
  deep link like `/invoices/2` — exactly what the dashboard now links to — is a
  404 on a hard refresh. `web/public/_redirects` covers Netlify and Cloudflare
  Pages; Vercel and Render need their own one-line equivalent, and the README
  says so.
- **The CORS configuration is now load-bearing rather than a formality**, which
  is an argument in its favour: it is exercised on every request in production,
  not just in development, so a mistake in it surfaces immediately rather than
  at some later point when a second origin appears.
- **This is reversible.** If the app ever wants one origin — for a cookie-based
  session, say, or to drop CORS — the change is to move the API under `/api`
  and bundle the built UI into the image. That is a mechanical change, and this
  ADR is where to start reading.

## Alternatives rejected

- **One container, API under `/api`** — the better long-term architecture, and
  the one to adopt if this ever stops being a demo. Rejected now only because
  the cost lands entirely on the existing test suite and documentation to buy
  something no user can see.
- **A reverse proxy in front of both**, routing by path prefix. Solves it
  without touching either codebase, and adds a third deployable to a project
  whose whole hosting budget is "cheapest tier with a Postgres".
