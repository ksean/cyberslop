# Change 0001: Initial title screen

- **Status:** Implemented on 2026-08-24
- **Implementation approval:** Approved on 2026-08-24
- **Created:** 2026-08-23

## Intent

Give players a minimal entry screen while game-start and save-loading behavior remain future work.

## Requirements

- **TITLE-001:** When the game URL loads, the visible page must identify the game with the exact title `Cyberslop`.
- **TITLE-002:** A button with the exact accessible name `New game` must always be present.
- **TITLE-003:** A button with the exact accessible name `Continue game` must be present when a valid previous game save exists.
- **TITLE-004:** `Continue game` must not be rendered when no valid previous game save exists.
- **TITLE-005:** Activating either button is a placeholder in this change. Starting a new game and loading a save require later specifications.
- **TITLE-006:** Save-format ownership belongs to the future persistence specification. The title screen must consume save availability through a boundary rather than interpret serialized save data.

## Acceptance examples

1. Given no previous save, loading the URL shows `Cyberslop` and `New game`, but not `Continue game`.
2. Given a previous save is available, loading the URL shows `Cyberslop`, `Continue game`, and `New game`.
3. Given either state, all visible actions can receive keyboard focus and expose their exact required accessible names.
4. Activating either action does not start, resume, or mutate a game in this slice.

## Out of scope

Visual art, animation, audio, save serialization, new-game creation, and save loading are not part of this change.
