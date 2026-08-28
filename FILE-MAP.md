# FILE-MAP

The directory index agents jump to instead of crawling the tree.
Hand-maintained — update it in the same commit that adds or moves a top-level
directory.

| Directory | Purpose |
| --------- | ------- |
| `docs` | Scope docs (product, tech, design direction), build-source list, and `docs/adr/` decision records. |
| `knowledge` | OKF knowledge bundle: domain concepts (invoice lifecycle, money rules) validated against the house standard. |
| `.github/workflows` | CI: OKF bundle validation; the Maven build workflow is added with the project skeleton (Task 1). |
| `.githooks` | Pre-commit hook that runs the OKF validator locally (`git config core.hooksPath .githooks`). |
