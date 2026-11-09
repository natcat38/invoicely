# Public repo audit, October 2026: findings and outcomes

A read-only audit of the public `main` on 2026-10-22 raised the findings below.
Each was rechecked against `main` on 2026-11-09. The UI audit from the same
month is in [ux-audit-2026-10/](ux-audit-2026-10/README.md).

| ID | Finding | Outcome |
|---|---|---|
| 1 | API cold start measured about 200 s; README said about 30 s, and login looked hung. | Fixed earlier in [#21](https://github.com/natcat38/invoicely/pull/21): the login page shows a "waking the demo server" notice and the README states the measured figure. |
| 2 | The builder preview rounded unit prices to 2 decimals while the API keeps 4, so 10 x 0.125 previewed as 1.30 against the server's 1.25. Editing a saved 4-decimal price also rounded it on save (`toFixed(2)`). | Fixed: `web/src/lib/preview-totals.ts` scales prices to 4 decimals and does the arithmetic in BigInt; the builder keeps every stored digit (`priceForInput`). Tests cover 10 x 0.125, 3 x 0.3333 and a 1,000,000-unit line. |
| 3 | Any visitor could change the shared demo password and lock everyone out. | Fixed earlier in [#21](https://github.com/natcat38/invoicely/pull/21) ([ADR-0013](../docs/adr/0013-protect-demo-accounts.md)). |
| 4 | The login throttle keys on the proxy address on Render, so all callers share one bucket; a success clears the count. README called it per-IP. | Owner decision (2026-11-09): README states the behaviour plainly; no code change. The gap is documented in ADR-0010 and ADR-0013. |
| 5 | The owner can send their own draft; the README did not say so. | Fixed: the README's maker-checker bullet says the owner may send their own draft and why; staff never can. |
| 6 | README layout said the Phase 2 UI was "not yet built" and omitted `web/` and `reports/`. | Fixed. |
| 7 | `reviews/recruiter-readiness.md` (a pre-publication self-review) and `CLAUDE.md` were public. | Owner decision (2026-11-09): the self-review is deleted; `CLAUDE.md` stays. FILE-MAP updated. |
| 8 | The test badge counts are hard-coded. | No change: the badge (142 API + 18 web) matches the annotated test methods within one; it counts methods, not parameterized runs. |
| 9 | The README never named the live API or its Swagger docs. | Fixed: the Live demo block links the API, `/swagger-ui.html` and `/v3/api-docs` (all returned 200 on 2026-11-09). |

The OKF validator is now pinned to a full commit SHA (`# v1.4.0`), with
`.github/dependabot.yml` opening weekly bump PRs for pinned Actions.
