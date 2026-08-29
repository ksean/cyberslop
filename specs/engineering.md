# Engineering Specification

## Technology

- **ENG-001:** Production game code must be written in Kotlin and compiled for the `wasmJs` browser
  target. Additional targets may be declared for verification only; no deployable artifact may be
  produced from them. The `jvm()` target exists for this reason alone.
- **ENG-002:** The build must use the checked-in Gradle wrapper and Kotlin DSL, with versions pinned
  in the version catalog. A JDK toolchain is pinned and resolved automatically so any JDK 17–26 can
  run the checks.
- **ENG-003:** Deployed output must be static browser assets served without an application server.
  GitHub Pages serves the project under `/cyberslop/`, so every asset reference must be relative.
- **ENG-004:** Dependencies must be introduced only when they provide clear value that is
  impractical to implement with Kotlin or browser platform APIs.
- **ENG-005:** Generated dependency lockfiles must be committed.
- **ENG-006:** The `binaryen` version the local build uses must match the one CI resolves; the
  local workaround is documented in `scripts/local-binaryen.md`.

## Architecture

- **ENG-010:** Platform-independent state and game rules must live in `commonMain` and must not
  depend on browser APIs.
- **ENG-011:** Browser DOM, rendering, input and persistence integrations must live in `wasmJsMain`
  behind small interfaces where game rules depend on them.
- **ENG-012:** The browser entry point must remain a composition root that delegates behaviour.
- **ENG-013:** No rendering framework or game engine may be added. Rendering uses the browser's own
  `CanvasRenderingContext2D` through the existing browser bindings.

```
commonMain/io/github/ksean/cyberslop/
  core/      Rng (SplitMix64), Vec2, TrigTable
  world/     Level, TileMap, tile kinds, masks, hazards
  physics/   Physics constants, MovementModel, IntentFilter, MovementEnvelope
  gen/       ThemeProfile, DifficultyCurve, SpineWalker, Decorator, Populator, StaticDrops, LevelGenerator
  verify/    Reachability (UnderReach / OverReach), Witness, WitnessReplay
  combat/    WeaponSpec, Weapons, DamagePipeline, Targeting, AutoFire, WeaponScore
  loot/      Powerup, Powerups, PowerupSlots, Loadout, DropTable, LootFloor
  progression/  PlayerProfile, UpgradeCatalog, DiscoveryCatalog
  entity/    Enemies, Boss, Bosses, Balance
  sim/       GameSimulation, Entities
  render/    Palette, Rig, Actor, EnemyLook, Backdrop, Scene, DrawList, Icon*, Hud, Camera
  run/       RunState, SaveCodec
  screen/    ScreenState
  title/     TitleScreenState, ShopScreenState
wasmJsMain/io/github/ksean/cyberslop/
  render/CanvasRenderer  input/BrowserInput  loop/RafLoop  save/LocalStorageSaveStore
  game/GameHost  title/BrowserTitleScreen, BrowserShopScreen  Main.kt
```

## Simulation

- **ENG-050:** The simulation must be a pure function of its previous state and one input frame,
  reading no clock, ambient randomness or DOM.
- **ENG-051:** Player position and velocity must be changed only by the movement model.
- **ENG-052:** The movement model must be the single source of truth for motion, shared by the game
  loop and by map verification; verification never re-implements it.
- **ENG-053:** Randomness must come from a first-party seeded generator with per-phase derived
  streams, not from `kotlin.random.Random`.
- **ENG-054:** Code reachable from the simulation tick must use only IEEE-754 basic arithmetic and
  comparisons; transcendental functions come from a first-party lookup table; physics state is
  `Double`; non-finite values never enter hashed state.
- **ENG-055:** Map generation must derive every distance from the movement model's measured
  envelope; traversal distances must not be literals in generation code.
- **ENG-056:** Map verification must run in the shipping build; a map whose witness fails replay
  must not be presented.

## Presentation

- **ENG-060:** Platform-independent presentation state — palettes, poses, animation selection,
  enemy appearance, backdrops and the frame's draw list — must live in `commonMain` and be testable
  without a browser. Browser rendering issues the primitives the draw list names and holds no rule
  about what a frame looks like.
- **ENG-061:** Drawing-state changes per frame must be bounded by the number of style batches and
  must not grow with the number of entities drawn. Per-sprite canvas transform state must not be
  used.
- **ENG-062:** Animation must be a pure function of simulation state and elapsed simulation time,
  and no animation state may change what the simulation does.
- **ENG-063:** Presentation must not add a runtime asset dependency; everything drawn is produced
  by code from the 2D context.
- **ENG-064:** Item icon geometry must live in one `commonMain` registry that every drawing site
  resolves from. An icon must be expressible in the draw list's existing primitives such that
  orienting it needs neither a canvas transform nor a trigonometric call, and icons must add a
  constant number of style batches to a frame.

## Code quality

- **ENG-020:** Functions and types must have one clear responsibility and meaningful domain names.
- **ENG-021:** Prefer immutable data, explicit state transitions and composition over inheritance.
- **ENG-022:** Avoid speculative abstractions, global mutable state and dependencies that only save
  a few lines.
- **ENG-023:** Gradle deprecation warnings caused by project configuration are failures
  (`--warning-mode=fail`).

## Verification

- **ENG-030:** Functional work follows red-green-refactor: a failing test first, the smallest
  passing change, then refactoring under green tests.
- **ENG-031:** Platform-independent behaviour is tested without a browser where practical. A test
  in `commonTest` runs on **every** declared target, including the browser runner whose per-test
  timeout is 2000 ms; work that cannot fit inside that lives in `jvmTest`.
- **ENG-032:** `./scripts/check.sh` runs the complete local verification suite and produces the
  production browser distribution.
- **ENG-033:** CI executes the same script for every pull request and push to `main`.
- **ENG-034:** A successful push to `main` publishes the verified distribution through GitHub Pages
  (`.github/workflows/pages.yml`, artifact `build/dist/wasmJs/productionExecutable`, published only
  after the check script succeeds).

| Layer | Source set | Runs on | Covers |
|---|---|---|---|
| Unit | `commonTest` | JVM and Wasm | Damage formula, caps, RNG streams, tile queries, poses, icons, small witness cohorts |
| Heavy sweeps | `jvmTest` | JVM only | Seed cohorts, reachability oracle, balance harness, loot simulation, route survival, sheets |
| Browser integration | `wasmJsTest` | Firefox headless | Canvas mount, input wiring, screen routing, accessibility |
| Production smoke | `scripts/title-screen-smoke.cjs` | Node | Bundle boots, mounts the canvas, no root-relative assets, stroke budget |

Numbered properties (`P-nn`) are listed in the specification they verify. Human validation — is a
map fair, is a telegraph readable, does a shotgun look like a shotgun — is scheduled work with a
written rubric, never pretended to be a test. Development sheets (`IconSheet`, `WorldFrameSheet`)
render what a person must judge and live in `jvmTest` so they ship in no production path.

## Development integration

- **ENG-040:** Contributors should use the local IntelliJ MCP server for semantic navigation,
  inspections, refactoring and build diagnostics whenever it is available.
- **ENG-041:** All build and verification commands must also be executable from a terminal and CI.

## Adversarial review

- **ENG-070:** Where the user directs adversarial review of a change, the change is reviewed by an
  independent read-only reviewer — `codex exec --model gpt-5.6-sol -c model_reasoning_effort=high
  --sandbox read-only`, briefed from a file on stdin — after `./scripts/check.sh` is green.
- **ENG-071:** The brief carries the original request verbatim, the diff range, the requirements the
  change is held to, and asks for three lenses: **specification** (does the spec say what was asked
  and does the code satisfy it), **implementation** (correctness, edge cases, layering, invariants)
  and **absence** (the missing test, the unstated behaviour, the guarantee claimed but not
  discharged).
- **ENG-072:** Every finding is verified before it is acted on and ends as *fixed*, *rejected* with
  the evidence, or *deferred* by the user's decision. Rounds continue until a round returns nothing
  load-bearing, up to three per gate. Dispositions are recorded in `tasks.md` while the task is
  open and removed with it.
- **ENG-073:** Review processes are launched with their pid recorded and waited on by pid, never
  by matching `codex exec` in a process list (a waiter written that way matches itself). Each
  round's shells are closed before the next starts (`.claude/skills/adversarial-review/close-agents.sh`).
