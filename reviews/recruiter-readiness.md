# Repo review — recruiter readiness

Run at M10, against `main` plus the M10 branch. One page, findings first.

## Passes run

| Pass | How |
|---|---|
| Orient | README, scope docs, ADRs, CI, `FILE-MAP.md` |
| Design & UI | `web-design-guidelines` — **already run this session** during Task 9; findings applied there (color-scheme, theme-color, touch-action, autocomplete on every field, dialog overflow, focus-to-error) |
| Code review | `/code-review high --fix` — **run per slice** across Tasks 7a, 7b, 8, 9; 18 findings, all applied |
| Over-engineering | Manual scan (below) rather than a fresh `ponytail-audit`; the Phase 1 audit already ran one and its deletions are merged |
| Docs & README | This pass |
| Security & hygiene | This pass |
| Recruiter skim | This pass |

Re-running the design and code-review skills whole-repo would have re-derived
findings already applied and merged in the last four PRs. Where a pass was
substituted, it is said so above rather than claimed.

## Hygiene — clean

- **No secrets.** Nothing matching a credential pattern in non-test sources.
  The only tracked env file is `web/.env.example`. The demo password has no
  default and fails startup if unset; the JWT secret does the same under `prod`.
- **Size is fine.** Largest tracked files are `package-lock.json` (288K) and
  three screenshots (~110K each). No binaries needing LFS.
- **`.gitignore` covers** `target/`, `node_modules/`, `dist/`, `.env`, IDE dirs.

## Findings

### 1. `reports/` leaks the build process — owner's call (highest impact)

`reports/task-7a/`, `task-8/`, `task-9/` and `m10/` are handover notes written
by subagents. They contain phrases like *"no deviations from the brief"*,
*"per the hard rules"* and *"the parent agent"*. A reader skimming the repo
finds internal AI-workflow artifacts sitting beside the source.

`reports/phase1-audit/` is different: a genuine self-audit (security,
money/lifecycle, domain, test-gaps, architecture) with a fix plan showing what
was found and what was done. That is a **positive** signal and worth keeping —
it is the sort of thing most portfolio repos do not have.

**Recommendation:** delete the four per-task directories, keep
`reports/phase1-audit/`, and link it from the README so it reads as
deliberate rigour rather than leftover scaffolding. Not done unilaterally —
this is about how the work is presented, which is the owner's call, and git
history keeps them either way.

### 2. README is strong; two gaps remain

Now opens with a hero screenshot, "Why I built this", an architecture diagram,
deploy instructions and an environment table. Remaining:

- **No live demo link** — blocked on the deploy, which needs hosting accounts.
- **No test/coverage badge beyond CI.** The CI badge is there; a count ("137
  API tests, 13 frontend") appears in prose but not where a skimmer sees it.

### 3. The GitHub About block cannot be set from here

Description, website and topics are repo settings. Not verifiable locally —
the owner needs to set them. Suggested topics: `java`, `spring-boot`,
`postgresql`, `react`, `typescript`, `testcontainers`, `singapore`.

### 4. Over-engineering — nothing to cut

Scanned for dead files and stale artifacts. `PlaceholderScreen` was deleted
when its routes were filled. No unused components, no commented-out blocks, no
orphaned config. `web/src/lib/preview-totals.ts` is the only client-side
arithmetic and is justified and tested. The Phase 1 ponytail audit's deletions
are merged.

## Recruiter skim test — 90 seconds

**Works:** the hero screenshot is a real, populated dashboard rather than an
empty shell. "Why I built this" names three concrete engineering problems
instead of listing technologies. The ADR index is unusual and reads as
seniority. The document/builder screenshots show something visually designed.

**The one weakness that remains** is the absence of a live link. Everything
else on the page invites someone to try it, and there is currently nowhere to
send them. That is the single highest-impact remaining item, and it is
blocked on hosting rather than on code.
