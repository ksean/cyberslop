# Contributor Instructions

## Priorities

Write concise, maintainable Kotlin. Prefer small functions with meaningful names, explicit boundaries, immutable state, and the standard library over unnecessary abstractions or dependencies.

## Spec-driven workflow

Requirements live in `specs/`; `README.md` is only a short human introduction. Every functional change follows two distinct phases:

1. Update or add the relevant specification and add implementation work to `tasks.md`. Stop after phase one.
2. Begin the recorded tasks only after the user explicitly approves implementation after reviewing phase one.

Do not infer phase-two approval from a request to draft, revise, or discuss a specification. Record the approval in `tasks.md` before starting implementation.

## Test-driven changes

For each functional behavior or bug fix:

1. Add the smallest test that demonstrates the missing or incorrect behavior.
2. Run it and preserve evidence that it fails for the expected reason.
3. Implement the smallest production change that makes it pass.
4. Run the focused test, then `./scripts/check.sh`.
5. Refactor only while the tests remain green.

Do not add a failing test outside an approved implementation phase.

## Project boundaries

Keep platform-independent game rules in `commonMain`. Keep browser APIs, rendering, persistence adapters, and the application entry point in `wasmJsMain`. Tests should mirror the production source set and package.

Useful automation belongs in `scripts/`. Generated output and local IDE state must not be committed.

## IntelliJ integration

Use the local IntelliJ MCP server when available for project navigation, symbol-aware analysis, refactoring, inspections, run configurations, and build verification. Keep command-line checks reproducible so the project is not dependent on the IDE.
