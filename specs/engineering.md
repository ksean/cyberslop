# Engineering Specification

## Technology

- **ENG-001:** Production game code must be written in Kotlin and compiled for the `wasmJs` browser target.
- **ENG-002:** The build must use the checked-in Gradle wrapper and Kotlin DSL. Versions are pinned in the version catalog.
- **ENG-003:** Deployed output must be static browser assets and must not require an application server at runtime.
- **ENG-004:** Dependencies must be introduced only when they provide clear value that is impractical to implement with Kotlin or browser platform APIs.
- **ENG-005:** Generated dependency lockfiles must be committed so browser tooling resolves reproducibly.

The initial toolchain is Kotlin 2.4.10 and Gradle 9.4.0, a current pairing within Kotlin's fully supported compatibility range. Upgrades require a specification change when they alter compatibility or architecture; patch-only maintenance may be recorded directly in `tasks.md`.

## Architecture

- **ENG-010:** Platform-independent state and game rules must live in `commonMain` and must not depend on browser APIs.
- **ENG-011:** Browser DOM, rendering, input, and persistence integrations must live in `wasmJsMain` behind small interfaces when game rules depend on them.
- **ENG-012:** The browser entry point must remain a composition root; it must delegate behavior rather than contain game rules.
- **ENG-013:** A rendering framework or game engine must not be added until its tradeoffs and requirements are specified.

## Code quality

- **ENG-020:** Functions and types must have one clear responsibility and meaningful domain names.
- **ENG-021:** Prefer immutable data, explicit state transitions, and composition over inheritance.
- **ENG-022:** Avoid speculative abstractions, global mutable state, and dependencies used only to save a few lines of code.
- **ENG-023:** Gradle deprecation warnings caused by project configuration must be treated as failures. Third-party toolchain warnings must be reviewed and tracked when actionable.

## Verification

- **ENG-030:** Functional work must follow red-green-refactor: first demonstrate the expected failing test, then make the smallest implementation pass, then refactor under green tests.
- **ENG-031:** Platform-independent behavior must be tested without a browser where practical. Browser integration must have focused browser tests.
- **ENG-032:** `./scripts/check.sh` must run the complete local verification suite and create a production browser distribution.
- **ENG-033:** CI must execute the same check script for every pull request and push to `main`.

## Development integration

- **ENG-040:** Contributors should use the local IntelliJ MCP server for semantic navigation, inspections, refactoring, and build diagnostics whenever it is available.
- **ENG-041:** All required build and verification commands must also be executable from a terminal and CI.
