# Tasks

Implementation work is tracked here. A task may move from **Waiting for approval** to **In progress** only after the user explicitly approves phase two after reviewing the linked specification. Record that approval before writing a failing test or production behavior.

## Open

None.

## Completed

### CYB-003 — Verify the title-screen slice end to end

- **Status:** Completed on 2026-08-24
- **Specification:** [Change 0001](specs/changes/0001-title-screen.md)
- **Depends on:** CYB-001, CYB-002

TDD checkpoints:

- [x] Add a failing production-bundle smoke test for each save-availability state.
- [x] Make only the harness or integration changes required for those tests to pass.
- [x] Run `./scripts/check.sh` and inspect changed Kotlin files with IntelliJ.

### CYB-002 — Render the title screen in the browser

- **Status:** Completed on 2026-08-24
- **Specification:** [Change 0001](specs/changes/0001-title-screen.md)
- **Depends on:** CYB-001

TDD checkpoints:

- [x] Add failing browser tests for the title, exact button names, conditional continue action, and keyboard-focusable controls.
- [x] Render the smallest accessible DOM that passes the tests.
- [x] Keep button activation side-effect free for this placeholder slice.
- [x] Refactor browser integration under green tests.

### CYB-001 — Model title-screen state

- **Status:** Completed on 2026-08-24
- **Specification:** [Change 0001](specs/changes/0001-title-screen.md)
- **Depends on:** Nothing

TDD checkpoints:

- [x] Add a focused failing test proving that title-screen state omits `Continue game` when no save is available.
- [x] Add a focused failing test proving that title-screen state includes `Continue game` when a save is available.
- [x] Implement the smallest platform-independent state model that passes both tests.
- [x] Refactor under green tests.

### CYB-000 — Establish the project foundation

- **Status:** Completed on 2026-08-24
- **Authorization:** Initial project-scaffolding request
- **Outcome:** Kotlin/Wasm build structure, reproducible Gradle tooling, specifications, task tracking, checks, CI, and contributor guidance.
