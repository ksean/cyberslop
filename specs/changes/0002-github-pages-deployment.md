# Change 0002: Restore GitHub Pages deployment

- **Status:** Implemented on 2026-08-24
- **Implementation approval:** Approved on 2026-08-24
- **Created:** 2026-08-24

## Intent

Make each successful `main` build available at the repository's GitHub Pages URL without weakening the existing verification gate.

## Original failure

The Pages workflow was invalid YAML because its root keys were unexpectedly indented. GitHub rejected the workflow before creating any jobs, while the independent CI workflow remained green.

## Requirements

- **PAGES-001:** `.github/workflows/pages.yml` must be valid GitHub Actions YAML with the workflow name `Deploy game`.
- **PAGES-002:** The workflow must run for pushes to `main` and support manual dispatch.
- **PAGES-003:** The production artifact must be published only after `./scripts/check.sh` succeeds.
- **PAGES-004:** The uploaded Pages artifact must be `build/dist/wasmJs/productionExecutable`.
- **PAGES-005:** Deployment must use the `github-pages` environment and the minimum permissions required by the configured Pages actions.
- **PAGES-006:** The repository must use GitHub Actions as its Pages publishing source before the corrected workflow is run.

## Acceptance examples

1. A YAML parser and IntelliJ report no structural errors in `pages.yml`.
2. A push to `main` creates `build` and `deploy` jobs instead of failing before job creation.
3. A failed project check prevents artifact upload and deployment.
4. A successful deployment serves the Cyberslop title screen from `https://ksean.github.io/cyberslop/`.

## Out of scope

Custom domains, pull-request preview deployments, and changes to game behavior are not part of this change.
