# FILE-MAP

The directory index agents jump to instead of crawling the tree.
Hand-maintained — update it in the same commit that adds or moves a top-level
directory.

| Directory | Purpose |
| --------- | ------- |
| `docs` | Scope docs (product, tech, design direction), build-source list, and `docs/adr/` decision records. |
| `knowledge` | OKF knowledge bundle: domain concepts (invoice lifecycle, money rules) validated against the house standard. |
| `src` | Spring Boot application (`src/main/java`), config and Flyway migrations (`src/main/resources/db/migration`), tests (`src/test/java`). |
| `.github/workflows` | CI: `okf.yml` validates the knowledge bundle, `ci.yml` runs `./mvnw verify` (Testcontainers Postgres). |
| `.githooks` | Pre-commit hook that runs the OKF validator locally (`git config core.hooksPath .githooks`). |
| `reports` | Prior audit outputs (security, money/lifecycle, domain, tests, docs, architecture) and the fix plans that track them. `reports/phase1-audit/FIX-PLAN.md` also records how each owner decision it raised was answered. |

Top-level build files: `pom.xml` (Maven, Spring Boot 4.1), `mvnw`/`mvnw.cmd` +
`.mvn/` (Maven wrapper — no local Maven install needed), `compose.yaml`
(PostgreSQL 16 for local development).
