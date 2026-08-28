# Engineering Specification

## Technology

- **ENG-001:** Production game code must be written in Kotlin and compiled for the `wasmJs` browser target. Additional Kotlin targets may be declared for verification only; no deployable artifact may be produced from them. *(Amended by [change 0003](changes/0003-game-core.md).)*
- **ENG-002:** The build must use the checked-in Gradle wrapper and Kotlin DSL. Versions are pinned in the version catalog.
- **ENG-003:** Deployed output must be static browser assets and must not require an application server at runtime.
- **ENG-004:** Dependencies must be introduced only when they provide clear value that is impractical to implement with Kotlin or browser platform APIs.
- **ENG-005:** Generated dependency lockfiles must be committed so browser tooling resolves reproducibly.

The initial toolchain is Kotlin 2.4.10 and Gradle 9.4.0, a current pairing within Kotlin's fully supported compatibility range. Upgrades require a specification change when they alter compatibility or architecture; patch-only maintenance may be recorded directly in `tasks.md`.

## Architecture

- **ENG-010:** Platform-independent state and game rules must live in `commonMain` and must not depend on browser APIs.
- **ENG-011:** Browser DOM, rendering, input, and persistence integrations must live in `wasmJsMain` behind small interfaces when game rules depend on them.
- **ENG-012:** The browser entry point must remain a composition root; it must delegate behavior rather than contain game rules.
- **ENG-013:** A rendering framework or game engine must not be added. Rendering must use the browser's own `CanvasRenderingContext2D` through the existing browser bindings. *(Amended by [change 0003](changes/0003-game-core.md), which records the measurements supporting this choice.)*

## Simulation

- **ENG-050:** The game simulation must be a pure function of its previous state and one input frame. It must not read a clock, ambient randomness, or the DOM.
- **ENG-051:** Player position and velocity must be changed only by the movement model. No weapon effect, enemy interaction, hazard or platform may displace the player.
- **ENG-052:** The movement model must be the single source of truth for motion and must be shared by the game loop and by map verification. Verification must never re-implement or approximate it.
- **ENG-053:** Randomness must come from a first-party seeded generator with per-phase derived streams, not from `kotlin.random.Random`, so that recorded seeds remain stable across toolchain upgrades.
- **ENG-054:** Code reachable from the simulation tick must use only IEEE-754 basic arithmetic and comparisons. Transcendental functions must be served by a first-party lookup table. Physics state must be `Double`; non-finite values must never enter hashed state.
- **ENG-055:** Map generation must derive every distance from the movement model's measured envelope. Traversal distances must not be written as literals in generation code.
- **ENG-056:** Map verification must run in the shipping build. A map whose witness fails replay must not be presented to the player.

## Presentation

*Added by [change 0005](changes/0005-visual-identity-and-loot-density.md).*

- **ENG-060:** Platform-independent presentation state — palettes, character poses, animation selection, enemy appearance, backdrops, and the draw list describing a frame — must live in `commonMain` and must be testable without a browser. Browser rendering must contain no rule about what a frame looks like beyond issuing the primitives the draw list names.
- **ENG-061:** The number of drawing-state changes issued per frame must be bounded by the number of style batches in the frame and must not grow with the number of entities drawn. Per-sprite canvas transform state must not be used.
- **ENG-062:** Animation must be a pure function of simulation state and elapsed simulation time. It must not read a clock or ambient randomness, and no animation state may change what the simulation does.
- **ENG-063:** Presentation must not add a runtime asset dependency. Everything drawn must be produced by code from the browser's own 2D context.
- **ENG-064:** Item icon geometry must live in `commonMain` as one registry, and every place an item is drawn must resolve its shape from that registry rather than describe it again. An icon must be expressible in the draw list's existing primitives such that orienting it requires neither a canvas transform nor a trigonometric call; a direction supplied as a unit vector must be applied by arithmetic on that vector. The number of style batches icons contribute to a frame must be a constant, independent of how many icons the frame draws and of how many distinct icons exist. *(Added by [change 0006](changes/0006-weapon-and-pickup-iconography.md).)*

## Code quality

- **ENG-020:** Functions and types must have one clear responsibility and meaningful domain names.
- **ENG-021:** Prefer immutable data, explicit state transitions, and composition over inheritance.
- **ENG-022:** Avoid speculative abstractions, global mutable state, and dependencies used only to save a few lines of code.
- **ENG-023:** Gradle deprecation warnings caused by project configuration must be treated as failures. Third-party toolchain warnings must be reviewed and tracked when actionable.

## Verification

- **ENG-030:** Functional work must follow red-green-refactor: first demonstrate the expected failing test, then make the smallest implementation pass, then refactor under green tests.
- **ENG-031:** Platform-independent behavior must be tested without a browser where practical. Browser integration must have focused browser tests. Tests in the common source set execute on every declared target; work that cannot complete inside the browser test runner's per-test timeout must live in a target-specific test source set. *(Clarified by [change 0003](changes/0003-game-core.md).)*
- **ENG-032:** `./scripts/check.sh` must run the complete local verification suite and create a production browser distribution.
- **ENG-033:** CI must execute the same check script for every pull request and push to `main`.
- **ENG-034:** A successful push to `main` must make the verified production browser distribution available through GitHub Pages.

## Development integration

- **ENG-040:** Contributors should use the local IntelliJ MCP server for semantic navigation, inspections, refactoring, and build diagnostics whenever it is available.
- **ENG-041:** All required build and verification commands must also be executable from a terminal and CI.
