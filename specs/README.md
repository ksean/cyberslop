# Specifications

This directory is the source of truth for Cyberslop's product and engineering requirements. `README.md` stays intentionally short and is not normative.

Requirement words such as **must**, **should**, and **may** are interpreted as mandatory, recommended, and optional respectively. Stable requirement IDs make specifications and tests traceable.

## Change workflow

Every functional change has two phases:

1. **Specify:** update the relevant specification, add a change record under `specs/changes/`, and add all implementation work to `tasks.md`.
2. **Implement:** after the user gives explicit implementation approval, record that approval in `tasks.md`, create a failing test, and complete the approved tasks using red-green-refactor.

Phase-one approval does not authorize phase two. No intentionally failing tests or partial production behavior should be committed while implementation approval is pending.

## Documents

- [product.md](product.md) defines the game and player-facing requirements.
- [engineering.md](engineering.md) defines technology, architecture, and quality requirements.
- [changes](changes/) contains reviewable requirement changes and their acceptance criteria.
