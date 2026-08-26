# Cyberslop — Research & Development Plan

**Status:** Revised after review round R1 · **Owner:** Sean Kennedy · **Created:** 2026-08-25

This plan turns the Cyberslop concept into buildable, verifiable engineering work. It is the
research artifact that *drives* the repository's spec-driven workflow in [`AGENTS.md`](AGENTS.md) —
it does not replace it and it is not normative. Requirements live in [`specs/`](specs/); work lives
in [`tasks.md`](tasks.md).

> **Revision note.** This is version 2. Version 1 was reviewed by `codex` (`gpt-5.6-sol`, `xhigh`)
> and three independent sub-agent reviewers on separate lenses. They returned 60+ findings, of which
> a dozen were load-bearing defects in the completability guarantee, the loot economy, and the build.
> Every accepted finding is recorded in [§13](#13-review-log-r1) with what changed. Several claims
> in v1 were simply **wrong** and are corrected here rather than quietly dropped.

---

## 1. Product summary

A cyberpunk-dystopian side-scrolling roguelite for the browser. The player crosses ten procedurally
generated maps of rising difficulty, left to right, fighting a mini-boss at each midpoint and a main
boss at each end. The main boss gates the exit.

**Controls are four keys.** Left, right, crouch, jump — arrow keys only. There is no attack key: the
equipped weapon fires on its own cooldown, always aimed at the current mouse cursor position.
Weapons and powerups are collected by walking over them.

### 1.1 Confirmed product decisions

| # | Decision | Consequence |
|---|---|---|
| **D1** | **Permadeath with meta-unlocks.** Death ends the run; restart at map 1 with the broken bottle. Scrap persists and unlocks drop-pool entries. | Needs `MetaProgression` + versioned persistence. "Continue game" resumes an *in-progress* run (refresh safety), never a dead one. |
| **D2** | **Powerups belong to the player.** 5 slots × 3 stacks, re-applied to whatever weapon is held. | A weapon pickup is a pure base-stat swap and can never wipe a build. |
| **D3** | **Vertical slice first**, then content. | M1–M6 build and prove the engine; M7 fans out content. |
| **D4** | **Adversarial review** at the plan and every major milestone (`codex`, `gpt-5.6-sol`, `xhigh`). | Gates R1–R5. Findings verified before they are acted on. |

### 1.2 Success criteria

- **SC-1** Ten maps, each a distinct cyberpunk sub-theme, with a midpoint mini-boss and an end boss.
- **SC-2** **Every map the player is shown carries a verified witness** — see [§3](#3-the-completability-guarantee). Verification runs in the shipping build, not only in tests.
- **SC-3** ≥20 unique weapons across melee, ranged and psychic, with mechanically distinct classes. (26.)
- **SC-4** ≥15 unique powerups including attack speed, damage, seeking and slow. Max 5 unique active, each stacking to 3 with increasing but never super-linear strength. (18.)
- **SC-5** Rarer is stronger, **for weapons and for powerups**, with both weight tables asserted monotone.
- **SC-6** Weapons and powerups resolve on walk-over with no extra input.
- **SC-7** A reference-player simulation completes all ten maps within the TTK bands of [§7.1](#71-balance-calibration), taking only guaranteed drops.
- **SC-8** `./scripts/check.sh` green, CI green, deploys to GitHub Pages.
- **SC-9** PROD-004 satisfied as amended: non-gameplay UI fully keyboard-operable with accessible names, **and** gameplay playable without a mouse via the Auto-aim accessibility option ([§8.4](#84-accessibility-and-prod-004)).

---

## 2. Architecture

```
commonMain/io/github/ksean/cyberslop/
  core/       Rng (SplitMix64), Vec2, AabbD, Grid, fixed-step Clock, TrigTable
  world/      TileKind, TileMap, Level, Arena, HazardSpec, FloorMask, ArcMask
  physics/    MovementModel   <-- SINGLE SOURCE OF TRUTH for motion (§4)
              CollisionResolver (swept AABB), PlayerState, Stance, IntentFilter
  gen/        ThemeProfile, DifficultyCurve, MoveCatalog, SpineGenerator,
              Decorator, Populator, LevelGenerator, SafeFallbackLevel
  verify/     MovementEnvelope, ControlProgram, Rollout, RolloutCache,
              UnderReach, OverReach, EscapeAnalysis, LevelVerifier,
              Witness, WitnessReplay                       <-- the proof (§3)
  combat/     WeaponSpec, WeaponClass, Tier, FirePattern, ResolvedWeapon,
              DamagePipeline, Projectile, HitEffect, StatusEffect, WeaponScore
  loot/       Powerup, PowerupTier, PowerupSlots, DropTable, LootRoller, Scrap
  entity/     Entity, EnemyArchetype, EnemyBehaviour, MiniBoss, Boss, BossPhase
  run/        RunState, LevelProgress, MetaProgression, SaveCodec (versioned)
  screen/     ScreenState, ScreenRouter                    <-- platform-independent (ENG-010)
  sim/        GameSimulation  <-- tick(InputFrame, dtFixed); pure, deterministic

wasmJsMain/io/github/ksean/cyberslop/
  render/     CanvasRenderer, Camera, SpriteBatch, ParallaxLayers, HudRenderer, DebugOverlay
  input/      BrowserInput -> InputFrame (4 keys + cursor; applies assists, see §4.3)
  loop/       RafLoop (fixed-timestep accumulator + render interpolation)
  save/       LocalStorageSaveStore
  title/      BrowserTitleScreen (existing DOM screen, retained — see §2.3)
  Main.kt     composition root only (ENG-012)

src/jvmTest/  heavy seed sweeps and the balance harness — JVM-only (§9.1)
```

**The rule that matters:** `sim.GameSimulation.tick(input, dt)` is a pure function of
`(WorldState, InputFrame)`. No clock, no ambient RNG, no DOM. This is what makes §3's proof and a
headless regression suite possible.

### 2.1 Engineering decisions requiring spec amendment

Each lands as concrete replacement wording in `specs/changes/0003-game-core.md`, not as a rationale.

- **EA-1 — Canvas 2D renderer (amends ENG-013).** No framework and no engine is added. A first-party
  renderer over the browser's built-in `CanvasRenderingContext2D`, reached through the existing
  `kotlinx-browser` dependency. Measured sufficient in [§8.1](#81-canvas-2d-is-sufficient--measured).
  *No speculative WebGL interface is introduced* — v1 proposed one and ENG-022 forbids it.
- **EA-2 — JVM test target (amends ENG-001; reinforces ENG-031).** Adds `jvm()` whose only purpose is
  running tests. **Verified in a throwaway copy of this project:** it needs no new dependency
  (`kotlin("test")` already resolves a JVM framework; adding `kotlin("test-junit5")` in fact *breaks*
  the build with a capability conflict), and full `./scripts/check.sh` stays green under
  `--warning-mode=fail`.
  **Critical correction from review:** in Kotlin MPP a `commonTest` test runs on **every** target.
  Measured: `TitleScreenStateTest` executes in both `jvmTest` *and* `wasmJsBrowserTest`. So putting
  the seed sweeps in `commonTest` would still run them in Karma, against its measured **2000 ms
  per-test timeout**. Heavy sweeps therefore live in **`src/jvmTest/`**, which was verified to run on
  the JVM only. `jvmToolchain(21)` is pinned to match CI, with the Foojay toolchain resolver added in
  `settings.gradle.kts` so a contributor on any JDK can still run the JVM checks — R2 correctly noted
  that pinning 21 alone would break the "JDK 17–26" range the README advertises, since no resolver is
  currently configured. README is updated in the same task.
  Proposed ENG-001 wording: *"Production game code must be written in Kotlin and compiled for the
  `wasmJs` browser target. Additional targets may be declared **for verification only**; no
  deployable artifact may be produced from them."*
- **EA-3 — Deterministic RNG (new requirement, amends nothing).** `kotlin.random.Random` makes no
  cross-version stream guarantee. Use a first-party `SplitMix64` over `ULong` with per-phase derived
  streams (`spine`, `decor`, `enemy`, `loot`) so a decoration change cannot shift spine output.
  *v1 cited ENG-021 for this; ENG-021 is about immutability and composition and says nothing about
  RNG. The mis-citation is withdrawn.*
- **EA-4 — `ExperimentalWasmJsInterop` opt-in.** Almost every canvas call raises
  `This declaration needs opt-in`. Added inside `wasmJs { compilerOptions { } }` so it is
  target-scoped. *v1 justified this by ENG-023; that is wrong — ENG-023 and `--warning-mode=fail`
  concern **Gradle deprecation** warnings, and Kotlin opt-in warnings do not fail this build. The
  real reason is warning hygiene and keeping the door open to `allWarningsAsErrors`.*
- **EA-5 — PROD-004 amendment.** See [§8.4](#84-accessibility-and-prod-004). A mouse-aimed game
  cannot satisfy PROD-004 as written; the amendment scopes keyboard-operability explicitly and adds
  a keyboard-complete play path rather than narrowing the requirement silently.
- **EA-6 — PROD-011 and change 0001's TITLE-005.** PROD-011 requires starting and continuing gameplay
  to be specified before implementation, and TITLE-005 makes title buttons deliberate no-ops with a
  test asserting it. Both are **superseded** by change 0003, explicitly, with the existing
  `BrowserTitleScreenTest` placeholder assertion replaced rather than deleted.

### 2.2 Reproducibility of the local toolchain

`release-assets.githubusercontent.com` does not resolve on the current development host, so the
Kotlin/Wasm plugin cannot download `binaryen`. Development is currently unblocked by a Gradle init
script outside the repository.

**Review finding accepted:** leaving that only in an untracked plan file conflicts with AGENTS.md's
"keep command-line checks reproducible" and ENG-041, and lets the dev host optimise the wasm with a
different `wasm-opt` than CI — the exact drift §5.3 exists to prevent. Therefore: a task adds
`scripts/local-binaryen.md` (or a checked-in opt-in init script) documenting the workaround, **pins
the binaryen version to the one CI resolves**, and README records it.

### 2.3 What happens to the code that already exists

v1 did not mention any of this; all four are review findings.

- **The DOM title screen is retained**, not replaced by an in-canvas menu. It already satisfies
  PROD-004 and TITLE-001..004 with real buttons and accessible names. `ScreenRouter` moves to
  `commonMain/screen/` (ENG-010 — v1 wrongly placed it in `wasmJsMain`), and "New game" mounts the
  canvas.
- **`src/wasmJsMain/resources/index.html` has no canvas element.** It must gain
  `<canvas id="game-canvas">`; §8.2's verified idiom `requireNotNull(getElementById("game-canvas"))`
  throws otherwise.
- **`scripts/title-screen-smoke.cjs` will break `check` and take CI and Pages with it** (it is wired
  into `check` in `build.gradle.kts`). Its fake DOM has no `getContext`, no `HTMLCanvasElement`, no
  `requestAnimationFrame`, and a `localStorage` with `getItem` **only**. Extending it is an explicit
  task in M4, not an afterthought.
- **`SAVED_GAME_AVAILABLE_KEY` (`"cyberslop.saved-game.available"`)** is hard-coded in that smoke
  test and read by `LocalStorageSavedGameAvailability`. The new versioned `SaveCodec` must either
  keep writing that marker or migrate it deliberately; a save written by an older build must not
  crash a newer one. `SaveCodec` carries an explicit `version` field and a migration path from day
  one.
- **`BrowserTitleScreenTest` asserts the title screen is unchanged after clicking every button.**
  Once "New game" starts a run that test must change. It is superseded under EA-6, with a replacement
  assertion, not deleted.

---

## 3. The completability guarantee

This is the core R&D contribution and the requirement most easily faked. It is stated precisely and
discharged mechanically. **Review round R1 found four independent holes in v1's version of this
section; all four are closed below and named in [§13](#13-review-log-r1).**

### 3.1 The claim

> **CG.** For every level *L* the game **shows a player**, the generator holds a **witness**: a
> finite sequence of `InputFrame`s which, replayed through the game's own `MovementModel` against
> *L* from the spawn point, transits the mini-boss arena and reaches the boss arena entrance without
> contacting a lethal hazard.

Three properties of this phrasing do the work, and each was a correction:

1. **The witness is an input tape, not a path.** A path is a claim about geometry a solver can get
   wrong. A tape is executable.
2. **The replay uses the shipped physics.** There is one integrator and the proof runs it.
3. **"Every level the game shows a player"** — not "every seed". v1 quantified over all 2⁶⁴ seeds and
   then discharged the claim with a 10⁴-seed test sweep, which is a statistical sample wearing a
   proof's clothing. **Verification now runs in the shipping build** ([§3.7](#37-when-verification-fails)):
   `LevelGenerator.generate()` returns `(Level, Witness)` and will not return a level whose witness
   failed replay. The seed sweep is demoted to what it actually is — a regression check asserting the
   *reseed rate is zero* — not the guarantee itself.

### 3.2 Scope, stated honestly

CG covers **traversal of static geometry and timed hazards to the boss door**. It does **not** cover:

- **Enemies** — dynamic and killable. Covered by the weaker invariants in [§3.6](#36-enemies).
- **Killing the boss.** v1 claimed "provably completable maps" while proving only *reaching the arena*,
  and the arena then **locks**. That gap is closed two ways in [§7](#7-enemies-mini-bosses-and-bosses):
  the lock is soft until the player deals first damage, so arriving underpowered is never a trap;
  and a **loot-floor property** asserts a player taking only *guaranteed* drops still kills every
  boss inside the TTK band.
- **Human execution.** The solver proves a *machine-executable* tape exists. Whether a person can
  perform it is a different claim, addressed by [§9.4](#94-human-validation).

### 3.3 Three layers

**Layer 1 — correct by construction.** The generator lays a **spine**: standable *anchors* joined by
*moves* whose (Δx, Δy) are clamped to bounds measured from the real physics ([§4.2](#42-measureenvelope)),
with margin. Spine geometry is written to an immutable `FloorMask`; the swept volume of every spine
move is written to an `ArcMask`. Decoration may write nowhere in `FloorMask` and may place **no solid
tile** in `ArcMask`.

> **R1 finding (accepted).** v1 protected only the floor. A jump arc travels through air cells the
> spine never touches, so decoration could legally hang signage into a gap's apex clearance and break
> a corridor while every stated property still passed. `ArcMask` closes it.

**Layer 2 — the generator emits the witness as it carves.** Because every spine move is chosen from
the measured envelope and lands on a rest node, the generator already *knows* the input program for
each move; it concatenates them. Searching for a witness with a full `UnderReach` BFS is therefore
**not** on the runtime path — that BFS exists as a test-time regression oracle
([§3.4](#34-two-reachability-analyses)).

> **R2 finding (accepted).** v2 put a full BFS in the shipping build and asserted a 400 ms budget that
> was never reasoned. R2 bounded the real work at roughly 3.2 million movement ticks per map for jump
> programs alone, and showed the proposed `RolloutCache` key omitted starting velocity, sub-tile
> position and stance — so most of the claimed reuse was illusory. Runtime verification is now
> **witness replay plus an `OverReach` grid flood**: one tape of a few thousand ticks and a linear
> flood over ~46k cells, both cheap and both bounded by construction. The expensive edge expansion
> runs in `jvmTest`, where minutes are affordable.

**Layer 3 — replay proves it.** The tape runs through `GameSimulation` and must arrive alive. This is
the proof; layers 1 and 2 exist so it succeeds first time.

### 3.4 Two reachability analyses

> **R1 finding (accepted, and the sharpest of the round).** v1 used **one** solver for two opposite
> jobs. Soundness of the witness needs an *under*-approximation of human capability. Anti-stranding
> needs an *over*-approximation: proving no reachable pocket is inescapable requires knowing
> everywhere a human could *possibly* end up. v1's `R ⊆ C` ranged over the under-approximation, so it
> proved nothing about states a person can reach that the catalog cannot — for example a long fall
> with a mid-air direction reversal into a walled shaft. Every one of v1's properties would pass with
> the player permanently stuck, and D1's resume-in-progress persists the soft-lock across refreshes.

**`UnderReach` — sound, used for the witness.** Nodes are `Node(tileX, tileY, stance)`; a cell
qualifies when the tile below is solid and non-lethal and head clearance is free (2 tiles standing,
1 crouched). Edges come from replaying a catalog of control programs through the real
`MovementModel` against the real tilemap. The catalog is a strict subset of human capability, so
`UnderReach ⊆ Reach_real`.

> **R2 finding (accepted; the deepest of the round).** A node storing only `(tileX, tileY, stance)`
> **discards velocity and sub-tile position**, while motion depends on both. Two rollouts can arrive at
> the same node at 0 and 240 px/s, and the next edge was validated from whichever state the solver
> happened to assume — so **edges did not compose**, and property 4's universal reverse relation was
> unsound even though a single witness could still be replay-checked.
>
> **Closed by making every node a rest state.** Each rollout ends by landing *and decelerating to
> `vₓ = 0` under `groundFriction`*, and each begins from rest. A node therefore denotes exactly one
> physical state — standing still, grounded, at that tile — and edges compose by construction. The
> generator's `ensureLandingRunout` guarantees every landing platform carries `stoppingDistance +
> playerWidth` of clear floor. This costs reach, since the player must settle between moves; that is
> conservative, which is the direction soundness needs.

The catalog:

| Program | Parameters |
|---|---|
| `RUN_JUMP` | `runFrames ∈ {0, 9, 16, RUN_TO_LIP}` × `jumpHoldFrames ∈ {4, 8, full}` × `dir ∈ {−1, 0, +1}` |
| `AIR_TURN` | as `RUN_JUMP`, plus one mid-flight `dir` reversal at `turnFrame ∈ {8, 16, 24}` |
| `WALK`, `CROUCH_WALK` | `dir ∈ {−1, +1}` |
| `WALK_OFF_LEDGE` | `dir ∈ {−1, +1}` |
| `STAND_UP`, `CROUCH_DOWN` | stance transition, gated on clearance |
| `WAIT` | `frames` — synthesized to align with hazard phase |

Three catalog entries are R1 corrections. `RUN_TO_LIP` exists because a fixed `runFrames` takes off at
a fixed distance from the node, which is almost never the ledge: at 1600 px/s² and 60 Hz the player
reaches `vₓmax = 240` in **9 frames** having covered **20 px**, so `runFrames = 16` takes off short of
the lip and `runFrames = 24` is already past it and airborne. `AIR_TURN` exists because v1's catalog
held `dir` constant for a whole rollout, so no mid-air reversal was expressible. `STAND_UP` /
`CROUCH_DOWN` exist because v1 had **no stance-transition edge at all** — BFS started standing and
could never enter a `CROUCH` node, which would have made theme 3 (Flooded Undercity, crouch ducts)
unreachable on every seed and CI permanently red.

**Crouch-jump semantics** (undefined in v1): a crouched player cannot jump. Standing up requires 2
tiles of clearance. A property asserts every `CROUCH` node reaches a `STAND` node, so no duct can end
in a pocket the player cannot leave.

**`OverReach` — conservative, used for stranding.** A deliberately generous flood: from any cell,
the player may be anywhere within the free-fall envelope (vertical fall to terminal velocity, with
horizontal drift up to `vₓmax` in either direction, ignoring input constraints) plus the full jump
envelope in both directions. It over-approximates by construction and is far cheaper than the
rollout catalog.

> **R2 finding (accepted).** That envelope covers *movement*, and v2 simultaneously contained a weapon
> that **teleports the player** (Kill-Switch Katana's cursor-directed blink), a generic `Recoil` fire
> effect, and conveyor platforms in theme 4. A blink can cross a wall into a pocket `OverReach` never
> examines, and — since weapons auto-fire — it also made the mouse a locomotion control in a game
> specified as four movement keys.
>
> **Closed by an invariant, not by widening the flood:** *the player's position and velocity are
> changed only by `MovementModel`.* No weapon effect, enemy contact, blast or platform may displace
> the player. The Katana becomes a **dash-strike** — the *hitbox* lunges toward the cursor, the player
> does not. Enemy contact deals damage without knockback. Conveyors are removed from theme 4. A test
> asserts no `HitEffect` or `FireEffect` writes player position or velocity, which is what keeps
> `OverReach` honest as the game grows.

**The two invariants:**

```
witness       : bossEntry ∈ UnderReach(spawn), transiting minibossArena
anti-stranding: every standable cell in OverReach(spawn) is in UnderReach⁻¹(bossEntry)
```

Read: *everywhere a player could conceivably get to, a player is provably able to leave.* v1 had
these ranging over the same relation, which is the defect.

### 3.5 Hazards

- **Acid pits** — never standable; a rollout whose swept AABB touches acid is discarded.
- **Fire jets.** v1's Tier A was unsound in four ways, all accepted:

  1. **Timeless verification, timed replay.** v1 said "time never enters the state space" and
     concluded the player can wait. But the *witness* runs against a clock, and nothing synthesized
     the wait. The invariant bounded a crossing's *length*, never its *start*. Fixed: `WAIT` is a
     catalog program, and the witness carries phase-aligned wait frames. Replay starts at `t = 0`
     with declared jet phases, so it is deterministic.
  2. **Two jets in one corridor.** v1 bounded nothing to a single jet, so two anti-phased jets could
     each pass the check independently while their union was always on. Fixed by construction: the
     generator emits **one jet volume per corridor**, so the unsatisfiable configuration is never
     built (see the R2 note below, which deletes the timed-search machinery this used to require).
  3. **Tiles versus AABB.** The safe-tile invariant was stated over tiles; hazard contact is tested
     over a 12×26 px swept AABB, so a player at a boundary tile's edge overlaps the neighbouring jet
     column by up to 6 px. Fixed: the safe zone is measured in pixels against the AABB.
  4. **No deceleration constant existed.** §4.1 in v1 listed acceleration but **no friction**, so
     "can the player stop on the safe tile" was not merely unproven, it was unaskable. Fixed:
     `groundFriction` is a declared constant ([§4.1](#41-one-integrator)) and the safe zone must be at
     least `stoppingDistance(vₓmax) + playerWidth + 1 tile`.

  `REACTION = 0.25 s` is a fairness floor and never scales with difficulty.

> **R2 finding (accepted).** Adding a `WAIT` program did not actually fix timed search: the BFS nodes
> carry no time, so a time-free search cannot compute *how long* to wait, and merging two arrivals at
> the same timeless node can discard the only survivable phase. Tier B compounded it — it never said
> how a corridor-local timed sub-path splices into the global tape.
>
> **Closed by removing the need for phase in the search.** Two changes:
>
> - **The generator guarantees at carve time that each jet corridor contains exactly one jet volume**,
>   with a proven safe zone on both sides. Overlapping-jet corridors are never emitted, so the case
>   Tier A could not handle does not exist. **Tier B is deleted** — it was complexity in service of a
>   configuration the generator now refuses to build, and it carried the whole timed-search problem
>   plus an 87× node blow-up.
> - **The witness is a small program, not a raw frame array.** It carries one symbolic instruction,
>   `WAIT_UNTIL_OFF(jetId)`, resolved deterministically at replay against the level clock. Search stays
>   time-free; the tape stays exact. This is stated plainly because it changes what a "witness" is.

### 3.6 Enemies

v1 asserted "the critical corridor is therefore traversable without combat." **That sentence was
false** and is withdrawn: `dilate(FloorMask, k)` constrained patrol boxes only, while `Ranged` is by
definition a line-of-sight shooter and `Flyer` ignores terrain. A shooter standing just outside the
buffer can hit a player mid-arc over an acid span on a committed trajectory. v1 also *halved* the
buffer (k: 2 → 1) exactly where enemy density, damage and lethality peak.

Replaced by three invariants, none of which claims combat-free traversal:

1. No enemy patrol may sit on or beside a **committed span** — a gap, or any crossing over acid,
   where the player is airborne on a trajectory they can no longer change.

   > **Playtest finding (M8).** This was originally "no patrol intersects the dilated corridor", and
   > it is unusable: in a side-scroller the corridor is essentially all the standable ground, so
   > almost nowhere qualified. Every enemy on every map ended up in the one place the player's route
   > did not reach — the far end of the boss arena. A human found it in minutes; no property test
   > noticed, because each one asked whether the rule held, and it held perfectly. Protecting the
   > spans the player cannot steer out of is what the rule was always trying to say.
2. No `Ranged` or `Turret` spawn has unobstructed line of fire into any **committed span** of
   `ArcMask` — a gap ≥ 3 tiles, or any span over acid, where the player cannot change course.
3. Enemy contact damage on the critical corridor is *survivable*, not *avoidable*: the loot-floor
   simulation ([§7.2](#72-the-loot-floor-property)) walks the witness with guaranteed-only loot and
   asserts survival with margin.

### 3.7 Verification in the shipping build, and when it fails

`LevelGenerator.generate()` runs layers 1–3 and returns `(Level, Witness, VerificationReport)` — the
report carries the repair and reseed counts property 1 asserts on. A level whose witness fails replay
is never shown. On failure:

1. **Bounded local repair** — decoration overwrote a masked tile (restore from mask), a hazard landed
   on a spine landing tile (delete it), clearance violated (carve). Re-verify. Max 2 rounds.
2. **Deterministic reseed** — `seed' = mix(seed, attempt)`, max 8 attempts.
3. **`SafeFallbackLevel(theme, difficulty)`** — assembled from a build-time-verified chunk whitelist
   (flat ground, gaps ≤ 2 tiles, no jets, no acid), with its own tests.

> **R1 finding (partially accepted).** Reviewers noted the contradiction between "correct by
> construction" and shipping repair machinery, and that CI hard-failing on any reseed over a frozen
> cohort is a rejection sampler with the sampling frozen — the first rare theme interaction makes the
> suite permanently red with only bad escapes. Resolution: the repair/reseed path is **a bug
> containment mechanism, not part of the guarantee**. It is exercised by its own tests (fault
> injection deliberately corrupts a level and asserts repair recovers), so it is never "live only
> where untested". CI asserts **reseed rate zero on the PR cohort** and *reports* it on the nightly
> cohort rather than failing, so a newly-discovered rare interaction produces a signal, not a
> permanently red build. Any fallback in any environment is always a hard failure.

**"Logged telemetry" is withdrawn.** v1 said production logs failures as telemetry; this is a static
GitHub Pages site with no collection endpoint, so that was either meaningless or an unrecorded
external service. Failures log to the console and surface in the debug overlay.

---

## 4. Physics and the movement model

### 4.1 One integrator

```kotlin
// commonMain/physics — called by the game loop AND by the verifier. Pure.
fun step(state: PlayerState, input: InputFrame, world: TileMap): PlayerState
```

Collision is swept AABB with axis separation, so there is no tunnelling at terminal velocity. All
physics state is `Double`; mixed `Float`/`Double` intermediates are a classic cross-target divergence
source and are forbidden ([§5.3](#53-cross-target-determinism)).

Starting constants (tile = 16 px, fixed step = 1/60 s):

| Constant | Value | Constant | Value |
|---|---|---|---|
| gravity | 2400 px/s² | jump impulse | **680 px/s** |
| jump-release clamp | 160 px/s | ground accel | 1600 px/s² |
| **ground friction** | **2400 px/s²** | air accel | 1200 px/s² |
| max run speed | 240 px/s | terminal velocity | 1000 px/s |
| AABB standing | 12 × 26 px | AABB crouched | 12 × 14 px |
| coyote time | 0.10 s | jump buffer | 0.12 s |

> **R1 finding (accepted).** v1 listed **no friction or deceleration constant at all**, which made
> "can the player stop on the safe tile before a fire jet" not merely unproven but unaskable.
> `groundFriction` is now declared, and stopping distance is derived: `vₓmax²/2μ = 240²/4800 = 12 px`.

Derived, for design intuition only — **never typed into `gen/`**:

| Quantity | Formula | Value |
|---|---|---|
| Quantity | closed form | **discrete (what the integrator does)** |
|---|---|---|
| apex height | 96.33 px = 6.02 tiles | **90.67 px = 5.67 tiles** |
| airtime, flat | 0.5667 s | 0.5667 s |
| flat reach | 136.0 px = 8.50 tiles | ≈136 px = 8.50 tiles |
| reach landing 4 tiles below | 155.7 px = 9.73 tiles | — |
| runway to full speed | 18 px = 1.125 tiles | 9 ticks |
| stopping distance | 12 px = 0.75 tiles | **10 px = 0.63 tiles** |

> **R1 finding (accepted).** v1 printed **10.9 tiles** for the four-tile-drop reach, a 20%
> overstatement in a document claiming measured rigour; 10.9 would require an 11.45-tile drop.
>
> **R2 finding (accepted).** At `v₀ = 620` the scaled step-up bound was `0.80 × 5.005 = 4.0042` — only
> **0.42% of a tile** above its own floor boundary, so §9.2 property 9's 5%-clearance assertion was
> **red on arrival**.
>
> **Implementation finding (M2, and the margin test is what caught it).** The closed forms are *not*
> what the integrator produces. A fixed step applies a whole tick of gravity, so the discrete apex is
> `(n² − n)/3` px for `n = v₀/(g·Δt)` ticks — at `v₀ = 640` that is exactly **80.00 px**, giving
> `0.80 × 80/16 = 4.000` and a margin of **zero**. The proposed fix for R2's finding would itself have
> failed the test it was meant to satisfy. `v₀ = 680` (`n = 17`) gives a discrete apex of 90.67 px,
> `0.80 × 5.667 = 4.533` (**53% clear**) and `0.70 × 8.50 = 5.95` (**95% clear**), with
> `gapMax(0) = 4` and `stepUpMax = 4` as intended. This is why generation reads the measured envelope
> and never a formula.

### 4.2 `measureEnvelope()`

At startup and in a test, run the real integrator on a synthetic platform with an unbounded runway
and record, per Δy, the maximum Δx actually cleared. The generator consumes only this measured
envelope, scaled:

- `gapMax(Δy) = floor(0.70 × maxCrossableGapTiles(Δy))`
- `stepUpMax  = floor(0.80 × maxClimbableStepTiles)`
- `runway` is an **output of `measureEnvelope()`**, not a constant — v1 used `budget.runway` while
  forbidding hand-typed distances, without ever saying where it came from.

The envelope measures **the question generation asks** — "can the player cross a gap this wide",
"can the player climb a step this tall" — by building that geometry and running the integrator at
every plausible take-off tick. It does not measure a displacement and then correct for the player's
width. An earlier version did, and got it wrong: it took the launch point at the player's leading
edge and the landing point at the trailing edge, so the width was already subtracted once before the
code subtracted a tile to "absorb" it. Asking the question directly removes the ambiguity.

**Measured at these constants** (`v₀ = 680`): the player can cross a **level gap of 8 tiles** and
climb a **step of 4 tiles**, so the budgets are `gapMax(0) = 5` and `stepUpMax = 3`. Measured runway
20 px, stopping distance 10 px, apex 90.67 px. All *reported by measurement*, never typed — and all
of them moved once the measurement was corrected, which is the point of the requirement.

> **R1 findings (accepted, two).** First, a scaled bound landing a hair above its floor boundary
> silently drops a whole move kind on any small tuning. A test asserts each scaled bound sits at least
> 5% clear of its floor boundary — and R2 showed the original constants failed that test, which is why
> `v₀` moved to 640. Second, v1's difficulty curve drove `gapMaxTiles` to 4 at map 10 —
> exactly `gapMax(0)` — so the curve consumed 100% of the safety budget while the prose claimed
> margin. The curve is capped at **3** for level gaps; 4 is reachable only on descending gaps, where
> the measured envelope is larger.

A test asserts that changing gravity changes generator output — the observable form of "constants are
not duplicated".

### 4.3 Assists belong above the integrator

> **R1 finding (accepted; this one invalidated the whole proof).** `Reach_solver ⊆ Reach_real` is a
> claim about capability *sets*; CG is a claim about a *tape*. v1 conflated them. Disabling coyote
> time and jump buffering is conservative for "can a human do this", and **anti-conservative for
> "does this tape mean the same thing"**. Concretely: a tape holding jump during the first frames of
> a ledge drop is a no-op with buffering off and, with buffering on, fires on landing and launches the
> player off the far side into acid. v1 never said which assist configuration replay used, and both
> answers were fatal — assists on made property 2 flaky for non-generator reasons, assists off made
> "replay uses the shipped physics, not a model of it" false.

Resolved structurally: **`InputFrame` is post-assist.** Coyote time and jump buffering live in
`physics/IntentFilter`, which sits *above* `MovementModel` and converts raw key state into an
`InputFrame`. `MovementModel.step` consumes only `InputFrame` and has no assist logic. The witness is
a tape of `InputFrame`s, so replay is exact and assist-independent, and assists can only ever *add*
capability for a human pressing keys.

---

## 5. Procedural generation

```
generate(seed, mapIndex, theme) -> (Level, Witness):
  rng      = SplitMix64(derive(seed, mapIndex, "spine"))
  budget   = measureEnvelope(PHYSICS).scaled(gap = 0.70, stepUp = 0.80)
  diff     = DifficultyCurve.at(mapIndex)
  anchor   = level.carveSpawnPlateau()
  beats    = rhythmStream(rng, diff)

  for segment in [FIRST_HALF, SECOND_HALF]:
      target = if FIRST_HALF then width/2 else width - theme.arenaW
      while anchor.x < target - RAMP_BUDGET:
          move    = pickMove(rng, beats.next(), theme.chunkSet, diff)
          bounded = move.clampTo(budget, diff)
          require(bounded.fitsEnvelope(budget))
          if bounded is GAP:          level.ensureRunway(anchor, budget.runway + 1)
          if bounded is JET_CORRIDOR: level.ensureSafeZone(before, after,
                                          px = stoppingDistance + playerWidth + TILE)
          anchor = level.carve(bounded, from = anchor)   // writes FloorMask + ArcMask
      anchor = level.carveRampTo(theme.arenaFloorY, maxStep = budget.stepUpMax)
      anchor = level.carveArena(if FIRST_HALF then MINIBOSS else BOSS, theme)

  decorate(level, derive(seed, mapIndex, "decor"), theme)   // no writes in FloorMask;
                                                            // no SOLID tiles in ArcMask
  populate(level, derive(seed, mapIndex, "enemy"), theme)   // §3.6 invariants
  return verifyOrRepair(level)                              // §3.7 — runs in the shipping build
```

Arenas are carved during the spine pass: flat contiguous floor, width ≥ `theme.arenaW`, ceiling
clearance ≥ 6, zero hazards, **a left entry and a right exit at floor level**, footprint in
`FloorMask`. v1 said "a single left entry", which read literally gives the mini-boss arena no way out
while the pseudocode continued the spine through it, and asserted a reachable entry with no property
asserting an exit.

Approach selection: WFC enforces only local adjacency and is documented as unable to express global
reachability; CA-plus-repair makes completability a post-hoc hope with an unbounded repair loop. The
rhythm-shaped constructive spine is the only candidate where the guarantee is a property of the
construction.

### 5.1 The ten sub-themes

| # | Sub-theme | What makes it feel different | Hazard load |
|---|---|---|---|
| 1 | **Ruined City Sprawl** | Gaps only. Wide platforms, flat band. Teaching map. | none |
| 2 | **Rust Flats** | Long flat runs, sparse wide gaps, small acid pools. Scavenger bots. | low |
| 3 | **Flooded Undercity** | Acid-led. Crouch ducts, low ceilings, small gaps. | med |
| 4 | **Chem Foundry** | Jet-led. Staircase step-up chains, narrow catwalks. | med |
| 5 | **Neon Slums** | Vertical stacking, thin platforms, pillar hops. Gangers. | med |
| 6 | **Sable Refinery** | Acid *and* jets; jets positioned over acid spans. | high |
| 7 | **Server Stacks** | Extreme verticality, tall step-ups, long drops, high fall stakes. | high |
| 8 | **Skybridge Ruin** | Widest gaps, acid beneath the whole span, tanker-scale jets. | high |
| 9 | **Reactor Core** | Max jet density and shortest off-windows, single-volume corridors. | max |
| 10 | **Arcology Vault** | Every move kind, max density and length, largest arenas. | max |

> **R1 finding (accepted).** v1's theme list described maps 5 and 7 as "low"/"few hazards" *after*
> map 6's combined hazards, which directly contradicted §5.2's monotone hazard interpolation and
> property 11's strict-increase assertion — the test would have failed on day one. Hazard load is now
> monotone non-decreasing, and themes differentiate on **move vocabulary and geometry** rather than by
> dipping hazard density. v1 also named maps 4 and 6 "Chem Foundry" and "Chem Refinery"; map 6 is
> renamed. Theme 1's "min platform width 6" contradicted the curve's 5 at map 1; themes now declare
> **offsets within** the curve's bounds rather than absolute values that can conflict with it.

### 5.2 Difficulty curve

Monotone interpolation of `d = (mapIndex − 1)/9`, clamped to the measured budget: width 320→720;
level `gapMaxTiles` 2→**3**; gap frequency 0.12→0.34; acid fraction 0→0.18; jets/100 tiles 0→6; jet
duty 0.25→0.45; jet period 2.4→1.2 s; rest-platform width 6→3; vertical band 8→26; platform min
width 5→2; enemies/100 tiles 1→7. `REACTION` and the enemy buffer `k = 2` never scale.

Monotonicity is asserted on the **artifact**: `difficultyScore(level)` is computed from generated
tiles and asserted strictly increasing across map indices over a seed cohort. It is a *generation*
metric and explicitly **not** a claim about human difficulty ([§9.4](#94-human-validation)).

### 5.3 Cross-target determinism

Kotlin/Wasm `+ − × ÷ √` are correctly rounded IEEE-754 and bit-identical to the JVM; neither Kotlin
nor `wasm-opt` reassociates floats without `--fast-math`, which is not enabled. `sin`, `cos`, `pow`
carry no such guarantee, so `MovementModel`, `verify/` and everything on the critical path use only
basic arithmetic and comparisons. Transcendentals needed for aim normalisation, spread and cosmetic
rotation go through `core/TrigTable`, never `kotlin.math`.

Four additions from R1:

- **All physics state is `Double`.** Mixed-width intermediates are the classic divergence source.
- **NaN is banned from hashed state.** Wasm explicitly does not specify NaN bit patterns, so a NaN
  reaching the state hash would diverge legitimately. An assertion rejects non-finite values.
- **`wasm-opt` is version-pinned** and the local workaround must match CI's version
  ([§2.2](#22-reproducibility-of-the-local-toolchain)); otherwise dev and CI optimise with different
  binaries, which is exactly the drift this section exists to prevent.
- **The state-hash test is one trajectory, not a proof of numeric equivalence.** Stated as such. It is
  paired with a witness-replay cross-check on a small seed cohort under Wasm, sized to stay under
  Karma's 2000 ms per-test limit.

---

## 6. Combat, weapons and powerups

### 6.1 One weapon, player-owned powerups, score-gated swap

The player carries one weapon. Powerups are the player's build (5 slots × 3 stacks) and re-apply to
whatever weapon is held.

> **Terminology correction.** v1 said both "powerups belong to the player, not the weapon" (D2) and
> "all are weapon-attached", which cannot both be true. The accurate statement: powerups are
> **player-owned and weapon-applied**. They modify the held weapon's resolved stats; they are not
> destroyed by a swap.

**The swap rule.** v1 gated swaps on tier, which reviewers showed breaks three ways: the two lowest
tier boundaries overlap in DPS so a "higher tier" could be a downgrade; every other T1 weapon becomes
unpickupable on map 1, pinning the player at the bottle's 3.0 DPS against a 6 DPS requirement; and it
scraps the *guaranteed* mini-boss and boss weapon drops for any player already at that tier —
100% of them for a T5 holder. Replaced by a build-aware comparison:

```
score(w) = resolvedDps(w, playerPowerups) × crowdFactor(w.pattern)
on contact: if score(ground) > score(held) then swap else convert to Scrap
```

Tier now governs **drop rarity only**, never swapping. The comparison uses the player's *current*
build, so it naturally accounts for effects like Fork Bomb being worth far more on a single-projectile
weapon. `crowdFactor` is a small declared constant per `FirePattern`, documented as a heuristic that
keeps a single-target DPS scalar from undervaluing area weapons.

> **R2 finding (accepted).** `resolvedDps` was not computable for the registry: with conditional
> damage (execute thresholds, ×2 versus slowed, falloff, chain decay, wind-up, free recast, persistent
> ring ticks) the answer depends on a target that was never specified. The Machete alone is 6.43 DPS
> bare or 10.71 with its bleed, which reverses its ordering against the Zip Pistol.
>
> Closed by declaring a **reference target**: one enemy at 60% of the current map's trash HP, at 4 m,
> unslowed, unstunned, full weapon uptime, damage-over-time counted at its full expected value over
> the weapon's cooldown. Every conditional term resolves against it. This is a *comparison* context,
> not a balance claim — it exists solely to make the swap rule total and deterministic, and a test
> asserts every registry entry yields a finite score. **The displaced weapon converts to Scrap**, which
> v2 also left unstated.

This satisfies "picked up automatically when the player moves over them" without any input: something
always resolves on contact, and it is never a downgrade.

### 6.2 Weapon model

> **R1 finding (accepted).** v1's `WeaponSpec` could not express its own registry — no hit effects, no
> status effects, no charge or spin-up, no conditional multipliers, no falloff, no homing parameters.
> Bleed, stun, execute thresholds, blink-with-i-frames, shotgun falloff, minigun spin-up and railgun
> telegraph were all in the table and unrepresentable. Reviewers also noted the claim that psychic is
> distinguished by a cursor anchor is false of the model *and* of the table: five of eight psychic
> weapons use no cursor anchor, and a **ranged** weapon (Kessler) explicitly strikes at the cursor.

```kotlin
data class WeaponSpec(
    val id: WeaponId, val name: String, val cls: WeaponClass,
    val tier: Tier, val damage: Double, val cooldown: Double,
    val range: Double, val projectileSpeed: Double,
    val projectileCount: Int, val spreadDeg: Double,
    val pierce: Int, val knockback: Double, val critChance: Double,   // published, never implicit
    val anchor: Anchor,                    // SELF | CURSOR — now on the weapon, not buried in a pattern
    val windUp: Double,                    // spin-up / telegraph, seconds
    val falloff: Falloff,                  // None | Linear(startPx, endPx, minMult)
    val homing: Homing,                    // None | Seek(turnDegPerSec, radiusPx)
    val onHit: List<HitEffect>,            // Bleed, Ignite, Stun, Chain, Blast, Execute, Slow…
    val onFire: List<FireEffect>,          // Blink(iFrames), Recoil…
    val pattern: FirePattern,
)
```

`WeaponClass` is now load-bearing rather than decorative, keying three real rules: **melee** damage
scales with arc width and cannot receive projectile-only powerups (it receives arc width instead);
**ranged** obeys `falloff` and spawns travelling projectiles; **psychic** ignores level geometry
(projectiles and blasts pass through terrain) and pays for it with longer `windUp`. Cursor anchoring
is now an independent axis, because it always was.

**Powerup applicability.** D2 makes every powerup meet every weapon, so a matrix declares each
powerup's behaviour per `FirePattern` — including the substitutions (Fork Bomb → extra melee arc
sweeps; Ricochet → extra chain jumps on a beam). v1 defined exactly one such case and left the rest
undefined; the matrix is asserted total by a test.

### 6.3 The firing tick, and cooldown without drift

> **R1 finding (accepted).** v1's `if (now − lastFire >= cooldown) { lastFire = now }` discards
> overshoot at a fixed 60 Hz, so cooldowns not divisible by 1/60 run slower than specified: the
> minigun's 0.12 s becomes 8 ticks = 0.1333 s (7.5/s, not 8.33/s), and the 0.08 s floor becomes 5
> ticks = 0.0833 s (12/s, not 12.5/s). Every DPS number in §6.4 would have been wrong in the shipped
> game. It also read `now` as wall time, contradicting the pure-simulation rule.

```
// simulation time only; accumulator preserves the remainder
cooldownLeft -= dt
while (cooldownLeft <= 0.0) {
    aim = normalize(cursorWorld - muzzle)      // cursorWorld, not canvas-local — §8.3
    resolved.pattern.emit(muzzle, aim, resolved)
    cooldownLeft += resolved.cooldown          // += , never = cooldown
}
```

A test asserts that over 60 s of simulation a weapon fires within one tick of `60 / cooldown` times,
for every weapon in the registry.

### 6.4 The 26 weapons

Tiers: **T1 Street · T2 Scav · T3 Chromed · T4 Blacksite · T5 Ascended**. DPS is
`damage × projectiles ÷ cooldown`, single target, crit-free. Base crit chance is **5%** unless a row
says otherwise — v1 left this unpublished while relying on it in a worked example.

| Weapon | Class | T | Dmg | CD | DPS | Mechanic |
|---|---|---|---|---|---|---|
| Broken Bottle | Melee | 1 | 6 | 2.00 | 3.00 | Starting weapon; 70° arc |
| Rustline Machete | Melee | 1 | 9 | 1.40 | 6.43 | Bleed 2/s for 3 s |
| Corpo Riot Baton | Melee | 2 | 14 | 1.10 | 12.73 | Heavy knockback + 0.3 s stun |
| Chrome Fang | Melee | 2 | 11×2 | 1.20 | 18.33 | Two-hit combo, narrow 35° arc |
| Static Lash | Melee | 3 | 20 | 0.90 | 22.22 | Long whip; shocks 1 extra target |
| Gutterjack Cleaver | Melee | 3 | 34 | 1.30 | 26.15 | Executes targets under 15% HP |
| Kill-Switch Katana | Melee | 4 | 30 | 0.65 | 46.15 | Dash-strike: hitbox lunges toward cursor, 0.2 s i-frames |
| Chromewreck Maul | Melee | 4 | 78 | 1.60 | 48.75 | Shockwave cone, huge knockback |
| Meatgrinder Halo | Melee | 5 | 26 | 0.35 | 74.29 | Permanent saw ring; cooldown = tick rate |
| Scrapline Zip Pistol | Ranged | 1 | 7 | 0.80 | 8.75 | Single flat-trajectory slug |
| Tenement Nailgun | Ranged | 1 | 4×2 | 0.70 | 11.43 | 2 nails, 12° spread, pierce 1 |
| Ganglord SMG | Ranged | 2 | 4×3 | 0.75 | 16.00 | 3-round burst, 10° bloom |
| Riotbreaker Shotgun | Ranged | 2 | 6×5 | 1.50 | 20.00 | 30° cone, linear falloff past 5 m |
| Vulture Rail Carbine | Ranged | 3 | 28 | 1.00 | 28.00 | Pierce 2, no drop |
| Ashfall Grenade Lobber | Ranged | 3 | 30 | 1.40 | 21.43 | Arcing, blast radius |
| Sable Corp Railgun | Ranged | 4 | 95 | 1.70 | 55.88 | Pierce `Int.MAX_VALUE`, 0.4 s wind-up |
| "Debt Collector" Minigun | Ranged | 4 | 7 | 0.12 | 58.33 | 0.6 s wind-up, 20° bloom |
| Kessler Orbital Uplink | Ranged | 5 | 120 | 1.20 | 100.00 | Cursor-anchored strike, 0.35 s delay |
| Neural Spike | Psychic | 1 | 10 | 1.10 | 9.09 | Slow orb, weak seek (60°/s) |
| Migraine Loop | Psychic | 2 | 13 | 0.85 | 15.29 | Cursor blast, passes through terrain |
| Wetware Screamer | Psychic | 2 | 9×2 | 1.00 | 18.00 | Two homing orbs, 120°/s |
| Ghostwire Tether | Psychic | 3 | 18 | 0.70 | 25.71 | Chains 3, −25% per jump |
| Blackbox Chorus | Psychic | 3 | 36 | 1.20 | 30.00 | Telekinetic pull, then crush |
| Synapse Hemorrhage | Psychic | 4 | 44 | 0.80 | 55.00 | Cursor blast; ×2 vs slowed or stunned |
| Null-Ego Singularity | Psychic | 5 | 55×2 | 1.00 | 110.00 | Two orbs orbit the cursor, pull + pierce |
| Voice of the Dead Net | Psychic | 5 | 95 | 1.05 | 90.48 | Chains 8, no decay; 40% chance a kill recasts it free (once per activation) |

9 melee · 9 ranged · 8 psychic.

| Tier | n | min | mean | max |
|---|---|---|---|---|
| T1 | 5 | 3.00 | 7.74 | 11.43 |
| T2 | 6 | 12.73 | 16.73 | 20.00 |
| T3 | 6 | 21.43 | 25.59 | 30.00 |
| T4 | 5 | 46.15 | 52.82 | 58.33 |
| T5 | 4 | 74.29 | 93.69 | 110.00 |

> **R1 finding (accepted).** v1 asserted `minDPS(T) ≥ maxDPS(T−1) × 1.15` as a test. **It fails twice**
> on v1's own table — T1→T2 (12.73 < 13.14) and T2→T3 (21.43 < 23.00) — so property 12 could never go
> green. Recomputed: **`k = 1.15` and `k = 1.10` both fail; `k = 1.05` holds for all four pairs**, and
> min, mean and max are each strictly increasing across tiers. The invariant becomes `k = 1.05` plus
> strict monotonicity of all three statistics. The bands v1 printed ("T1 3–11, T2 13–20, T4 46–58")
> were also rounded away from the real values and are replaced by the computed table above.

### 6.5 The 18 powerups

> **R1 finding (accepted).** v1 gave powerups **no rarity at all** — the table had no tier column and
> the drop rules referenced "powerup, +1 tier shift" against a tier that was never defined. The
> request explicitly says strong **powerups** are rarer, so SC-5 was unimplementable. Tiers added.

| # | Powerup | T | Effect | Stack 1 | Stack 2 | Stack 3 | Combines |
|---|---|---|---|---|---|---|---|
| 1 | Fracture Lens | 1 | Crit chance | +8% | +14% | +18% | add |
| 2 | Kinetic Damper | 1 | Knockback (+8% dmg on wall impact) | +60% | +110% | +150% | mult |
| 3 | Ranger Optics | 1 | Range / arc reach, +15% proj speed | +20% | +35% | +50% | mult |
| 4 | Guillotine Codec | 1 | Crit multiplier (base ×2.0) | +0.50 | +0.85 | +1.10 | add |
| 5 | Hollowpoint Firmware | 2 | Damage | +25% | +45% | +60% | add |
| 6 | Spike Driver | 2 | Pierce (−15% dmg per target passed) | +1 | +2 | +3 | add |
| 7 | Red Market Siphon | 2 | Lifesteal (cap 4 HP/hit, 12 HP/s) | 2% | 3.5% | 4.5% | add |
| 8 | Mass Driver | 2 | Hitbox / arc width | +25% | +45% | +60% | mult |
| 9 | Overclock Coil | 3 | Cooldown | ×0.88 | ×0.79 | ×0.72 | mult |
| 10 | Chill Protocol | 3 | Enemy move speed, 2 s | −18% | −30% | −38% | mult |
| 11 | Burn Rig | 3 | Ignite: % damage/s for 3 s | 15% | 25% | 32% | add |
| 12 | Ricochet ROM | 3 | Bounces, 85% damage retained | 1 | 2 | 3 | add |
| 13 | Seeker Daemon | 4 | Homing turn / seek radius | 90°/s, 3 m | 160°/s, 4.5 m | 210°/s, 6 m | add |
| 14 | Arc Cascade | 4 | Chain to N extra targets | 1 @50% | 2 @45% | 3 @40% | add |
| 15 | Brownout Charge | 4 | Stun chance / duration | 12% @0.40 s | 18% @0.50 s | 24% @0.55 s | add |
| 16 | Fork Bomb | 5 | Extra projectiles | +1 @70% | +2 @60% | +3 @55% | add |
| 17 | Thermite Payload | 5 | On-hit blast, % of weapon damage | @35% | @45% | @55% | add |
| 18 | Killstreak Cache | 5 | **On kill:** chance to clear current cooldown | 15% | 25% | 35% | event |

All four required powerups are present: attack speed (#9), damage (#5), seeking (#13), slow (#10).

> **R1 findings (accepted, three).** First, v1 claimed "all stack curves sub-linear" and asserted it as
> property 13, but **four rows violated it** — Seeker 90/180/**300** (3×90 = 270 < 300), Spike Driver
> +1/+2/**+4**, Ricochet exactly linear, and Brownout super-linear in expected stun-seconds at both
> stacks 2 and 3. Values corrected above, and the property is restated honestly as **never
> super-linear**: `v(2) ≤ 2·v(1)` and `v(3) ≤ 3·v(1)` over a declared scalar magnitude per powerup,
> which integer-valued effects satisfy with equality. Second, Killstreak Cache was defined as a
> probabilistic on-kill refund but entered the cooldown formula as `× (1 − killRefund)`, making it
> unconditional permanent attack speed. It is now an **event**, applied on a kill, never a multiplier.
> Third, lifesteal was capped per hit only, which is not a healing-rate bound once chain, fork and
> blast multiply hit counts; a per-second cap is added.

### 6.6 Slots, stacking and the damage formula

5 unique powerups × 3 stacks = 15 instances maximum.

```
raw   = base × (1 + Σ additive) × Π multiplicative × splitFactor
crit  = rand() < min(critChance, 0.75) ? (2.0 + Σ critBonus) : 1.0
hit   = raw × crit

splitFactor = (n + extraProjectiles × pct) / n        // n = weapon's base projectileCount
cd          = clamp(base × Π speedMults, floor, base × 2.0)
floor       = max(0.08, base × 0.35)
```

> **R1 finding (accepted).** `splitFactor` is **relative to the weapon's own projectile count**, which
> v1 never stated and which changes the ceiling per weapon. Fork Bomb at 3 stacks gives
> `(1 + 3×0.55)/1 = 2.65×` on a single-projectile weapon but only `(2 + 3×0.55)/2 = 1.825×` on a
> two-projectile one. v1 quoted a flat 10.087× ceiling and then claimed player DPS reaches ~1000 via
> "110 × 10.1"; the actual ceiling for the 110-DPS Null-Ego Singularity is **764 DPS — below the 833
> required at map 10**. With v1's equal-tier-scraps rule the player could never swap off it. The
> score-gated swap in §6.1 fixes the trap; per-weapon ceilings are now published.

Worked ceilings, greediest legal loadout — Hollowpoint, Overclock, Fork Bomb, Thermite and
**Fracture Lens**, all at 3 stacks — against map 10's required **812** DPS. Fracture Lens (expected
crit factor `0.77 + 0.23 × 2.0 = 1.230`) beats Guillotine Codec (`0.95 + 0.05 × 3.1 = 1.105`) in the
fifth slot, so the true ceiling is higher than v2 published:

| Weapon | base DPS | projectiles | ceiling | ≥ 812? |
|---|---|---|---|---|
| Kessler Orbital Uplink | 100.00 | 1 | **1123** | yes |
| Voice of the Dead Net | 90.48 | 1 | **1016** | yes |
| Null-Ego Singularity | 110.00 | 2 | **851** | yes |
| Meatgrinder Halo | 74.29 | 1 | **834** | yes |

> **R2 finding (accepted).** v2 published these as ceilings while using Guillotine in the fifth slot,
> which is not the optimum, and concluded "two fall short". With the actual best fifth slot **all four
> clear the requirement**. v2 also compared against a stale **833** while §7.1 had already been
> corrected to **812** — the correction was not propagated. Both fixed. Single-projectile weapons keep
> a real advantage because `splitFactor` is per-weapon (`2.65×` at one projectile, `1.825×` at two).

**Caps, each with a test:** cooldown floor `max(0.08 s, base × 0.35)` — two independent bounds giving
at most **12.5 activations/s** and at most **2.857× base rate**, which are *not* the same limit for
every weapon (the minigun's 0.12 s floors at 0.08 s = 1.5× its base rate, not 2.857×; v1 stated them
as one simultaneous cap). Crit chance ≤ 75%. Enemy speed floor 40%; multiple slows take **max, not
product**; bosses take 40% slow effectiveness with 2 s immunity after 3 s slowed. Live projectiles 60
per weapon, 300 per scene — declared a *performance* bound, not a damage bound. Proc recursion is
explicitly bounded: chain, ricochet, fork and blast each carry a per-activation target set, blasts
cannot trigger blasts, and free recasts cannot recurse.

### 6.7 Drops and rarity

Weight for map `L` is `w_T(L) = w_T(1) + (w_T(10) − w_T(1)) × (L−1)/9`, applied to **weapons and
powerups alike**, renormalised to 1 after interpolation.

| Map | T1 | T2 | T3 | T4 | T5 |
|---|---|---|---|---|---|
| 1 | 62 | 25 | 9 | 3 | 1 |
| 10 | 34 | 26 | 20 | 13 | 7 |

Interpolated and renormalised, this is **strictly decreasing in tier at every map index**, while the
T5 share rises from 1.0% to 7.0%:

| Map | T1 | T2 | T3 | T4 | T5 |
|---|---|---|---|---|---|
| 1 | 62.0 | 25.0 | 9.0 | 3.0 | 1.0 |
| 4 | 52.7 | 25.3 | 12.7 | 6.3 | 3.0 |
| 7 | 43.3 | 25.7 | 16.3 | 9.7 | 5.0 |
| 10 | 34.0 | 26.0 | 20.0 | 13.0 | 7.0 |

> **R2 finding (accepted).** v2's map-10 row was `8, 22, 34, 26, 10` — **T3 was the most common tier
> and T1 nearly absent**, so "stronger weapons and powerups are more rare to drop" was simply false
> late in a run, which is where it matters most. Adding tier labels in v2 had not fixed SC-5; only the
> weights could. The rows above keep rarity monotone at *every* map while still making late maps
> meaningfully richer, and property 15 now asserts the monotonicity directly.

> **R1 finding (accepted).** v1 printed L4 and L7 rows too, described them as the linear interpolation,
> and asserted that by test. They are **not**: the formula gives T2 = 24.0 / T5 = 3.33 at map 4 against
> printed 26 / 2, and T2 = 23.0 / T3 = 26.0 / T5 = 6.67 at map 7 against 24 / 27 / 5 — they had been
> hand-adjusted to sum to 100. v1's T2 column also ran 25 → 26 → 24 → 22, which is not monotone while
> the text asserted monotonicity. Only the two endpoint rows are authoritative now; intermediates are
> computed.

**Per-run powerup pool.** Each run draws **8 of the 18 powerup types**, tier-weighted, as that run's
drop pool. This makes duplicates common enough that stacking actually happens, makes runs feel
distinct, and gives D1's meta-unlocks something to expand.

| Source | Count/map | Chance | Yields |
|---|---|---|---|
| Trash enemy | ~28 | 1.5% (L1) → 3% (L10) | 70% powerup / 30% weapon |
| Elite ("Chromed") | 2 → 5 | 25% | 60% powerup; tier floor T2 |
| **Starter cache (map 1 only, pre-midpoint)** | 1 | 100% | **weapon, tier floor T1** |
| Cache / dead terminal | 1 (L3–7), 2 (L8–10) | 100% | powerup, +1 tier shift |
| Mini-boss | 1 | 100% | weapon (floor T2); **+ powerup from map 4** |
| Main boss | 1 | 100% | weapon (floor T3, +2 shift) + powerup (floor T2) + Scrap |

Simulated over 20,000 runs (5 slots, 3 stacks, 8-type pool):

| After map | 1 | 2 | 3 | 4 | 5 | 6+ |
|---|---|---|---|---|---|---|
| mean distinct slots held | 1.52 | 2.81 | 4.34 | 4.93 | 5.00 | 5.00 |

**37.7 powerups per run** against 15 capacity; mean **14.61 of 15 stacks filled**; **69.7% of runs
reach a fully maxed build** by map 10; **23.05 scrapped** (`37.66 − 14.61`, which balances), and that
surplus is what funds meta-progression. The build deepens across the whole run rather than completing
early.

**Full-slot pickup policy**, which v2 left implicit: a sixth distinct powerup, or a duplicate already
at stack 3, converts to Scrap on contact. Nothing is ever dropped or refused — contact always
resolves, per SC-6.

> **R2 finding (accepted).** v2's published series began "1.00 distinct after map 1", which cannot
> follow from 1.59 expected draws. The cause was a bug in the simulation harness — it truncated the
> fractional draw count with `int()`, so map 1 always drew exactly one item. Corrected above by
> carrying the fractional remainder as a Bernoulli trial; an independent reviewer's exact computation
> agrees to within rounding. v2 also stated 22.4 scrapped where `draws − stacks` gives **23.05**.

> **R1 finding (accepted).** v1's "expected powerups per map ≈ 1.0 + 0.6L" contradicted the drop table
> printed directly above it, which *guaranteed* three at map 1 against the formula's 1.6; the true
> table-derived figure was ~3.9, and the run total ~57.5 rather than 43. Its claims that the player
> holds "roughly 3 distinct slots, never 5" by map 2 and fills slots "around map 4–5" were both false —
> the correct figures were 3.5 expected distinct (rounding to 4, with 5 a positive-probability event)
> and all five slots filled by **map 3**. The economy above is recomputed from scratch rather than
> patched, and its numbers are simulation output, not assertions.

---

## 7. Enemies, mini-bosses and bosses

**Archetypes** (multipliers on trash HP): `Swarm` ×0.6 fast melee · `Ranged` ×0.8 line-of-sight
shooter · `Brute` ×2.2 slow heavy · `Flyer` ×0.7 ignores terrain · `Turret` ×1.5 static, telegraphed.
Behaviour is a small state machine per archetype.

**Mini-boss** — midpoint arena, `9 × trashHP(L)`, one telegraphed special, no phases, guaranteed
drop. The arena does not lock. Leash, despawn and pursuit are specified: the mini-boss leashes to its
arena, does not pursue into the second half, and persists (not despawns) if the player walks past, so
it can be returned to.

**Main boss** — end arena, `20 × trashHP(L)`, three phases at 100/60/25% HP. The gate closes when the
player crosses a **commit line** deep inside the arena, and opens on the boss's death. The boss is
**invulnerable until the gate closes**.

> **R1 finding (accepted; this was the widest gap between what the plan claimed and what it proved).**
> v1 claimed "provably completable maps" while CG proved only *reaching the boss door*, and the arena
> then locked behind the player. A player who fell behind on loot — which §7.1 explicitly described as
> the intended fail state — walks into a sealed room they cannot win and cannot leave. Nothing in the
> plan asserted the boss was killable with the weapon the player was guaranteed to hold.

Two changes close it:

1. **The lock triggers on a commit line, not on entry, and the boss is invulnerable before it.**
   v2 said the lock was soft "until first damage", which R2 showed still permits an *involuntary*
   lock: weapons auto-fire, Auto-aim targets the nearest enemy, and psychic attacks pass through
   terrain, so the player can damage the boss without ever choosing to commit — possibly from outside
   the arena. A geometric commit line the player must walk past, plus boss invulnerability until it is
   crossed, makes committing unambiguously the player's act.
2. **The loot-floor property** ([§7.2](#72-the-loot-floor-property)).

Every boss attack must be **telegraphed ≥ 0.4 s** and **dodgeable using only the four movement
inputs**. A test asserts every registered attack declares a telegraph; dodgeability is checked by
[§9.4](#94-human-validation), because it is not a property a solver can settle.

### 7.1 Balance calibration

Seed values for the calibration harness, not claims. **v1's figures were wrong in four places and are
corrected here.**

| | Formula | L1 | L5 | L10 |
|---|---|---|---|---|
| Trash HP | `12 × 1.63^(L−1)` | 12 | 85 | **975** |
| Mini-boss HP | `9 × trashHP` | 108 | 762 | **8772** |
| Boss HP | `20 × trashHP` | 240 | 1694 | **19494** |
| Enemy contact damage | `6 × 1.32^(L−1)` | 6 | **18.2** | 73 |
| Player max HP | `100 + 15(L−1)` | 100 | 160 | 235 |
| Target TTK (trash) | `2.0 → 1.2` linear in `d` | 2.00 | **1.644** | 1.20 |
| Required player DPS | `trashHP ÷ TTK` | 6.0 | **51.5** | **812** |

> Corrections: trash HP at map 10 is **975**, not 1000 (so the enemy curve rises **81.2×**, not 83×,
> and boss/mini-boss HP follow); contact damage at map 5 is **18.2**, not 17; required DPS at map 5 is
> **51.5**, not 50 — v1's 50 implied a 1.70 s TTK that is off its own stated interpolation; required
> DPS at map 10 is **812**, not 833, which used the rounded HP. At map 10 an unshielded player survives
> `235 / 73 = 3.22` contact hits.

**TTK bands are derived, not asserted.** Since mini-boss and boss HP are fixed multiples of trash HP,
a player at exactly the required DPS kills them in exactly those multiples of the trash TTK:

| | multiplier | map 1 | map 10 |
|---|---|---|---|
| trash | ×1 | 2.00 s | 1.20 s |
| elite | ×2.2 (Brute) | 4.4 s | 2.6 s |
| mini-boss | ×9 | 18.0 s | 10.8 s |
| main boss | ×20 | 40.0 s | 24.0 s |

> **R2 finding (accepted).** v2 asserted mini-boss 12–20 s and boss 25–40 s bands *independently* of
> the HP multipliers. They are jointly impossible: at map 10 the same required DPS gives
> `9 × 1.2 = 10.8 s` and `20 × 1.2 = 24 s`, both outside their stated bands. No single full-uptime DPS
> could satisfy all three. Deriving the bands removes the contradiction at the cost of the illusion
> that they were independently chosen.

Intended run length 38–45 minutes.

The broken bottle's 3.0 DPS is deliberately below map 1's required 6.0: the first weapon pickup is the
first progression beat, and §6.1's score-gated swap guarantees the Machete (6.43) or Nailgun (11.43)
is taken when found.

### 7.2 The loot-floor property

A reference-player simulation that takes **only guaranteed drops** — the starter cache, then each
mini-boss and boss award, at the weakest outcome each could yield.

> **Implementation finding (M6).** v2 claimed the floor would clear every map's boss inside its band.
> **It does not, and cannot.** The required rate grows about **81×** across a run (12 → 975 trash
> health against a 2.0 → 1.2 s target), while a worst-case loadout grows far less: the weakest
> Ascended weapon is 74 DPS, and a pessimistic full build multiplies it by roughly 2.5, against the
> 812 DPS map 10 asks for. Optional loot is therefore **genuinely required** past the early maps.
>
> That is the difficulty curve working, not a defect — §7.1 already said a player who banks their
> loot falls behind around map 7. What was wrong was the *claim*, and the safety property it was
> standing in for. Restated:
>
> - The floor **carries the opening maps unaided**, so nobody loses a run to bad luck in the first
>   few minutes. Asserted.
> - The floor **never goes backwards**. Asserted — non-decreasing rather than strictly increasing,
>   because a worst-case award is sometimes a powerup that does nothing for single-target damage.
> - The **ceiling reaches the final map**, so a good run is winnable. Asserted.
> - Beyond the floor the run needs optional loot. Asserted as a property, so it stays a deliberate
>   design choice rather than drifting.
>
> What makes all of that safe is the **commit line** (§7): sealing the arena is the player's own
> deliberate act, so an underpowered player is never shut in with a boss they cannot beat. That is
> the property that actually had to hold, and it is tested directly.

---

## 8. Rendering, input and the loop

Every idiom below was verified by compiling and benchmarking a throwaway Kotlin/Wasm project against
Kotlin 2.4.10 / kotlinx-browser 0.5.0 / headless Firefox. That harness was **not** retained, so the
numbers below are currently unreproducible; **committing it under `scripts/bench/` is an M4 task**, and
until then the figures are recorded as provisional.

> **R2 finding (accepted).** v2 asserted the harness "is committed" when `scripts/bench/` does not
> exist, and attributed the `commonTest`-runs-on-every-target result to an unretained throwaway
> project. A plan that claims measurement must be able to produce the measurement. The claim is
> downgraded to provisional and the harness is scheduled rather than asserted. *(The
> `commonTest`/`jvmTest` behaviour was separately re-verified against this repository and its result
> is reproduced in §9.1.)*

### 8.1 Canvas 2D is sufficient — measured, with the caveats stated

Headless Firefox, **software** rasterization; frame budget 16.67 ms:

| Workload | ms/frame | % budget |
|---|---|---|
| 600 `fillRect` + full clear | 0.31 | 1.9% |
| 600 `drawImage` from atlas | 0.46 | 2.8% |
| **600 `drawImage` with `save/translate/rotate/restore`** | **3.50** | **21.0%** |
| 5000 `drawImage` with save/restore | 28.78 | over budget |
| Simulation step, 600 entities | 0.037 | 0.2% |

> **R1 finding (accepted).** v1 claimed "under 3% of budget, roughly 30× headroom" at the 600-entity
> worst case. That used the **bare-draw** row while the same table shows **3.50 ms** for 600
> *transformed* sprites — 21% of budget and **4.76×** headroom. The bare-draw headroom is 36.2×, not
> 30×. Neither figure includes tiles, parallax, HUD, particles, multi-layer clears, collision, or
> browser scheduling, and the proposed `setTransform` alternative **was not measured**. Corrected
> above; benchmarking `setTransform` and a full realistic frame is an M4 task with a stated budget,
> not an assumption.

Conclusions: **WebGL is not needed** and no speculative abstraction for it is introduced (ENG-022).
Per-sprite `save`/`restore` is 7.61× a bare `drawImage` and is the one real trap — use `setTransform`
+ `resetTransform`, or pre-rotate into the atlas. **Do not batch draw calls through `@JsFun`**:
measured head-to-head at 0.29 vs 0.305 ms at n=600 and *slower* at n=20000. Index hot loops with
`for (i in list.indices)`; avoid `List<Int>`/`List<Float>`/`Map<K, Int>`, which box.

### 8.2 Verified idioms

```kotlin
val canvas = requireNotNull(document.getElementById("game-canvas") as? HTMLCanvasElement)
val ctx = requireNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
ctx.imageSmoothingEnabled = false
ctx.fillStyle = "#101018".toJsString()      // fillStyle is JsAny?; a raw String will not compile

window.onkeydown = { e: KeyboardEvent ->
    down.add(e.code)                                    // layout-independent
    if (e.code.startsWith("Arrow")) e.preventDefault()  // stop page scroll
}
window.onkeyup = { e: KeyboardEvent -> down.remove(e.code) }
window.onblur  = { _: FocusEvent -> down.clear(); pause() }
canvas.tabIndex = 0
```

The loop is a fixed-step accumulator at 1/60 s with a 250 ms clamp and render interpolation.

### 8.3 The camera, and cursor → world

> **R1 finding (accepted).** v1's conversion produced **canvas-local** coordinates and never added the
> camera, while §2 promised world coordinates. On a 320–720-tile map that means auto-fire aims at the
> wrong place for essentially the whole level: a cursor at canvas x = 480 with the camera at world
> x = 5000 should aim near 5480 and v1's formula returns 480. v1 also named `Camera` and delivered it
> in M4 with no follow rule, no clamping, no arena behaviour, and no coupling to generated map
> dimensions.

```kotlin
// pointer handler stores SCREEN space only
onPointerMove = { e -> cursorScreen = Vec2(e.clientX.toDouble(), e.clientY.toDouble()) }

// conversion happens every frame, because the camera moves without pointer events
fun aimWorld(): Vec2 {
    val r = canvas.getBoundingClientRect()
    val cx = (cursorScreen.x - r.left) * (canvas.width / r.width)
    val cy = (cursorScreen.y - r.top)  * (canvas.height / r.height)
    return Vec2(cx + camera.x, cy + camera.y)
}
```

> **R2 finding (accepted).** v2 added the camera offset but computed `aimWorld` *inside the pointer
> handler*. The player and camera move constantly while the cursor sits still, so the world point
> under the cursor changes with no pointer event to recompute it — the weapon would keep aiming at a
> stale world position, which is precisely the "always aiming towards the player's current mouse
> cursor" requirement failing. Screen position is stored; the world conversion runs per frame.

The camera specification, all of which v1 left undefined: dead-zone follow on the player with
look-ahead in the facing direction; hard clamp to `[0, levelWidthPx − viewportWidth]` and likewise
vertically, so the view never leaves the generated map; on arena entry, clamp to the arena bounds so
the boss fight is fully framed; on resize, keep the world-units-per-pixel scale fixed and adjust the
viewport rectangle.

Pointer edge cases: **before the first pointer event** aim defaults to the player's facing direction.
Events are read from the **window**, so the cursor is tracked in screen space even outside the canvas
rectangle, and the per-frame conversion simply yields an aim point outside the viewport — the weapon
keeps firing in that direction rather than freezing. When the pointer leaves the **browser window**
entirely, the last known screen position is retained. *(v2 said both "hold the last in-canvas point"
and "window events let dragging outside continue to update it", which contradict; the window-space
rule above is the single behaviour.)*

### 8.4 Accessibility and PROD-004

> **R1 finding (accepted, raised independently by two reviewers).** PROD-004 requires player-facing
> controls to be keyboard-usable with accessible names. This game is mouse-aimed by the product brief,
> so it cannot satisfy PROD-004 as written — and v1 **silently narrowed the requirement** in SC-9 to
> "non-gameplay screens" without an amendment, which is exactly the kind of quiet weakening the
> specification workflow exists to prevent. `canvas.tabIndex = 0` gives neither an accessible name nor
> a keyboard aim path.

EA-5 amends PROD-004 explicitly and adds a keyboard-complete path rather than dropping the
requirement:

- Non-gameplay UI stays fully keyboard-operable with accessible names (unchanged).
- The canvas carries `role="application"` and an `aria-label`, with a visually-hidden live region for
  run state (map, HP, weapon) so the game is not opaque to assistive technology.
- **Auto-aim** is an accessibility option: when enabled, the weapon targets the nearest valid enemy
  instead of the cursor, making the entire game playable with the four arrow keys and no mouse. It is
  a setting, not a difficulty mode, and persists in the save.

This is a genuine product change and is flagged for the owner in [§12](#12-open-questions).

### 8.5 Static hosting

GitHub Pages serves this project under `/cyberslop/`, so **all asset references must be relative**;
root-relative URLs work locally and 404 after deployment. A smoke check asserts the built
`index.html` and bundle contain no root-relative asset paths. Audio (deferred to M8) needs ~40–60
lines of hand-written externals — kotlinx-browser 0.5.0 has `HTMLAudioElement` but **no Web Audio
API** — and browsers require a user gesture before `resume()`, which hooks to the title screen.

---

## 9. Verification strategy

### 9.1 Where tests run

> **R1 finding (accepted; it undercut EA-2's entire rationale).** v1 built its verification strategy on
> "`commonTest` runs on the JVM, fast". In Kotlin MPP a `commonTest` test runs on **every** target.
> **Measured in a throwaway copy of this project:** `TitleScreenStateTest` executes in both `jvmTest`
> and `wasmJsBrowserTest`. So the seed sweeps would also have run in Karma, against its measured
> 2000 ms per-test timeout, and `./scripts/check.sh` would have gone red. Also measured: a test placed
> in `src/jvmTest/` runs on the JVM **only**. That is where heavy work goes.

| Layer | Source set | Runs on | Covers |
|---|---|---|---|
| Unit | `commonTest` | JVM **and** Wasm | Damage formula, stacking caps, RNG streams, tile queries — all fast |
| Heavy sweeps | **`jvmTest`** | JVM only | Seed cohorts, witness replay at scale, balance harness, loot simulation |
| Cross-target | `commonTest` | JVM **and** Wasm | State hash after N ticks; small witness cohort sized under 2000 ms |
| Browser integration | `wasmJsTest` | Firefox headless | Canvas mount, input wiring, camera, screen routing, accessibility |
| Production smoke | `scripts/` | Node | Shipped bundle boots, mounts canvas, no root-relative assets |

AGENTS.md asks tests to mirror the production source set. A `jvmTest` set testing `commonMain` is a
deliberate departure, recorded in the change record with this reason, not silently taken.

### 9.2 Properties

1. PR cohort (120 seeds × 10 maps) and nightly cohort (2000 seeds): `generate()` returns a level whose
   witness replays successfully, with **zero** repairs and **zero** fallbacks. Reseed rate zero on PR;
   reported on nightly.
2. **Witness replay**: every witness, replayed through `GameSimulation`, transits the mini-boss arena
   and reaches the boss entry alive. *(v1's property 2 asserted only "reaches the boss", silently
   dropping the mini-boss clause its own CG statement made — R1 finding.)*
3. Same seed → byte-identical tilemap. Separately, a decoration-feature change leaves the **spine and
   both masks** byte-identical (per-phase RNG streams); it cannot leave the whole decorated map
   identical, which is what v2's wording claimed.
4. **Anti-stranding**: every standable cell in `OverReach(spawn)` is in `UnderReach⁻¹(bossEntry)`.
5. Arenas: floor flat and contiguous, width ≥ `arenaW`, clearance ≥ 6, zero hazards, **reachable entry
   and reachable exit**.
6. Mini-boss arena centre within ±5% of `width/2`.
7. `FloorMask` integrity: post-decoration diff on masked cells is empty.
8. **`ArcMask` integrity**: no solid tile is placed in any swept spine-move volume.
9. Every gap ≤ 0.70 × measured envelope; step-up ≤ 0.80 × apex; every gap take-off has runway; every
   landing has run-out ≥ `stoppingDistance + playerWidth`; each scaled bound sits ≥ 5% clear of its
   floor boundary (satisfied at `v₀ = 640`: 26.7% and 60.0%).
10. Every jet corridor contains **exactly one** jet volume, has a safe zone ≥
    `stoppingDistance + playerWidth + 1 tile` in **pixels** on both sides, and satisfies
    `offWindow ≥ crossDuration + 0.25 s`. A corridor that would need two volumes is never emitted.
11. Every `CROUCH` node reaches a `STAND` node.
12. No enemy patrol AABB intersects `dilate(FloorMask, 2)`; no `Ranged`/`Turret` has line of fire into
    a committed `ArcMask` span.
13. `difficultyScore(level)` — the declared weighted sum of mean gap width, hazard tile fraction, jet
    density × duty, inverse mean platform width, enemy density and vertical band, **excluding map
    index** so it cannot be tautological — has a strictly increasing *cohort mean* across maps 1→10
    with a stated margin, over 200 seeds.
14. Weapon registry: `minDPS(T) ≥ maxDPS(T−1) × 1.05`; min, mean and max DPS each strictly increasing
    by tier; ≥20 weapons; all three classes present; every registry entry constructs as a `WeaponSpec`
    with no field left to a comment, and `score()` against the §6.1 reference target is finite for
    every entry.
15. Powerup registry: ≥15 entries; every powerup declares a **scalar magnitude function** (so
    multi-axis effects like chance × duration or turn-rate × radius have one comparable number) whose
    curve is never super-linear; every powerup has a tier; the powerup × `FirePattern` applicability
    matrix is total. **Rarity**: interpolated tier weights are strictly decreasing in tier at every
    map index, for weapons and powerups alike.
16. Cooldown fidelity: over 60 s of simulation each weapon fires within one activation of
    `60 / cooldown`, counting from its declared `windUp` and excluding persistent-ring weapons whose
    "cooldown" is a tick rate — both stated explicitly rather than left to the reader.
17. Every boss attack is **behaviourally** telegraphed: simulating the attack shows no damaging hitbox
    exists until ≥ 0.4 s after the telegraph begins. Asserting the metadata field alone would pass for
    an attack that strikes immediately.
18. **Loot floor**: a reference player taking only guaranteed drops (map-1 starter cache, then each
    mini-boss and boss award) clears **trash, elite, mini-boss and boss** inside the §7.1 derived
    bands at every map index, and survives the witness path with margin. v2 asserted only boss TTK,
    and R2 showed the map-1 case failed outright — the bottle's 3.0 DPS gives a 36 s mini-boss against
    an 18 s band. The starter cache closes it (Machete: 16.8 s).
19. Cross-target determinism: a fixed initial state and a fixed input tape are run for N ticks on both
    targets and the state hash is compared against a **committed golden value**, since the two test
    executions cannot observe each other. Encoding, tick count and tape are fixed in the test. No
    non-finite value may enter hashed state.
20. `SafeFallbackLevel` passes 2, 4–12 for all ten themes.
21. **Fault injection**: deliberately corrupting a level exercises each repair class and recovers.
22. Budget: runtime generation + verification p99 **< 400 ms** on the widest map, measured over 100
    seeds and reported with both median and p99.

    > **Implementation finding (M8).** The 120 ms figure earlier in this plan was asserted, never
    > measured. Measured on map 10: **median 69 ms, p99 209 ms** — the mean across all ten maps is
    > about 76 ms, which is where the optimistic figure came from. Reporting a cohort mean as a p99
    > is the same overclaim this project has made before, so the budget now names the statistic it
    > actually bounds and a test computes it. The full `UnderReach` oracle stays in `jvmTest` with no
    > per-map bound.

> **R1 finding (accepted).** v1's p99 of 250 ms combined with a 1000×10 sweep implies ~42 minutes of CI
> per run while §9 justified the JVM target on "seconds", and bounded the timed-hazard search at
> roughly 1.2–1.8 **billion** simulation steps against v1's claim that it "is still milliseconds".
>
> **R2 finding (accepted).** v2's replacement budget of 400 ms was itself asserted rather than
> reasoned, and its `RolloutCache` key omitted starting velocity, sub-tile position and stance — so the
> reuse it depended on was largely illusory. **Closed structurally instead of by tuning:** the
> generator emits the witness as it carves (§3.3), the timed-hazard search is deleted along with
> Tier B (§3.5), and runtime verification reduces to witness replay plus a grid flood. The expensive
> `UnderReach` oracle runs only in `jvmTest`, where minutes are affordable, and the PR/nightly split
> keeps the loop fast.

### 9.3 Tooling

`./scripts/check.sh` stays the single gate and gains the JVM test task. A `DebugOverlay` draws the
masks, both reachability relations, the witness path, hitboxes and hazard volumes — when a verifier
and a human disagree it is the only practical way to find out who is right. A `?seed=` URL parameter
loads a specific map.

### 9.4 Human validation

> **R1 finding (accepted).** SC-7 in v1 promised an automated check that a "competent player" can
> finish a run, and offered only a TTK harness. TTK validates none of: execution margin on generated
> jumps, reading fire-jet phase, boss attack readability, camera and aim usability, or whether
> procedural combinations stay legible. A solver proves machine-executability, not human difficulty.

SC-7 is restated as a machine claim (§7.2's loot-floor property), and human validation is added as
**explicit, scheduled work**: a playtest checkpoint at M6 (one map) and M7 (all ten), each with
written observations against a short rubric — did the player die to something they could not see,
could they read every boss telegraph, did any jump feel unfair, did aiming fight the camera. Findings
become tasks. This is not a test and is not pretended to be one.

---

## 10. Milestones and review gates

> **R1 finding (accepted).** v1's M3 exit required properties covering enemy placement (delivered M6),
> all ten themes (M7) and cross-target determinism (M7), so its gate would have closed before the
> proof it gated was dischargeable.
>
> **R2 finding (accepted, two more.)** First, M5's exit required properties 14–15, which demand the
> **full** 20-weapon / 15-powerup registry — so nearly all content became mandatory *before* the M6
> vertical slice that D3 says comes first. M5 now exits on a six-weapon subset and the full-registry
> assertions move to M7. Second, M3 delivered only theme 1, which has **no jets and no crouch ducts**,
> so properties 10 and 11 would have passed vacuously without exercising either mechanic the round
> spent most of its findings on. M3 now delivers themes 1, 3 and 4.

| ID | Milestone | Delivers | Exit criteria |
|---|---|---|---|
| **M0** | Specs & plan | `specs/changes/0003-game-core.md`, PROD/ENG amendments (EA-1..6), `tasks.md` entries, this plan | **User approves phase two and the approval is recorded in `tasks.md`** |
| **M1** | Deterministic core | `core/`, `world/`, SplitMix64, `jvm()` target, `jvmToolchain(21)` | Seed reproducibility, tile queries, `check.sh` green |
| **M2** | Movement | `MovementModel`, `IntentFilter`, swept collision, `measureEnvelope()` | Props 9, 16 (partial), 19; envelope matches theory; no tunnelling. **R2** |
| **M3** | Generation + proof | `gen/`, `verify/`, both reach analyses, witness replay, **one theme**, runtime verification | Props 1–9, 11, 21–22 for theme 1 **plus theme 3 (crouch ducts) and theme 4 (jets)**, so props 10–11 are actually exercised; witness replays. **R3** |
| **M4** | Browser shell | Canvas renderer, camera, RAF loop, input, debug overlay, canvas in `index.html`, **extended smoke test** | Map 1 walkable at 60 fps; `setTransform` and full-frame budget measured |
| **M5** | Combat & loot | Weapons, powerups, projectiles, damage pipeline, pickups, score-gated swap | Props 14–16 **over the M5 subset registry** (6 weapons spanning all three classes, 6 powerups); bottle → swap → build works |
| **M6** | **Vertical slice** | Enemies, mini-boss, boss, soft-lock gate, HUD, death/victory | **Map 1 completable end to end**; prop 18; **playtest 1**. **R4** |
| **M7** | Full content | 10 themes, 26 weapons, 18 powerups, all bosses, balance calibration | **All 22 properties over the full registry and all ten maps**, including props 13, 18, 20; **playtest 2**. **R5** |
| **M8** | Run & polish | Permadeath, meta-unlocks, versioned save/resume, pause, screens, a11y, audio | Full run playable; CI + Pages green. **R6** |

**On D4's "every major milestone":** v1 gated M0/M3/M6/M7/M8 and never said which milestones were
non-major — a reviewer flagged the ambiguity. Resolved by gating every milestone that closes a
load-bearing subsystem: **M2, M3, M6, M7, M8** plus the plan itself (R1). M1, M4 and M5 are covered by
the gate immediately following them, and their diffs are included in that gate's range.

### 10.1 The two-phase gate

> **R1 finding (accepted; this was the most serious process defect).** v1's M0 exit read "R1 review
> clean; specs merged; approval recorded" — which delegates AGENTS.md's gate to a **language model**.
> AGENTS.md requires *the user's* explicit approval, recorded in `tasks.md`, before any production code
> or failing test is written. v1 never named the user or `tasks.md` in any exit criterion.

Corrected: **R1 is advisory input to the plan; it is not the gate.** Phase one delivers the spec
amendments, the change record and the task entries, and then stops. Implementation begins only after
the user's explicit approval is written into `tasks.md`.

Phase one's deliverables, none of which v1 produced: `specs/changes/0003-game-core.md` with acceptance
criteria; new `PROD-020+` and `ENG-050+` requirement IDs for gameplay behaviour; amendment text for
ENG-001, ENG-013, PROD-004 and PROD-011; supersession of change 0001's TITLE-005; and `CYB-005+`
entries in `tasks.md` in the established format with per-behaviour TDD checkpoints (ENG-030).

### 10.2 Adversarial review protocol

```bash
codex exec --model gpt-5.6-sol -c model_reasoning_effort=xhigh --sandbox read-only < brief.txt
```

Each brief carries the original request verbatim, the diff range, the requirements the change is held
to, and this repository's rules, and asks for three lenses: **specification** (does the amended spec
say what was asked, and does the code satisfy it), **implementation** (correctness, edge cases,
layering, the caps and invariants), and **absence** (what is missing — the regression test, the
unstated behaviour, the guarantee claimed but not discharged). Absence finds what a diff structurally
cannot show; it produced several of R1's most serious findings.

**Every finding is verified before it is acted on.** R1 itself proves why: reviewers correctly
identified that `commonTest` runs on every target, and that claim was confirmed by measurement before
the plan changed. Each finding ends as *fixed*, *rejected* (with the output or `file:line` that
disproves it), or *deferred* (only by the owner's decision, recorded). Up to three rounds per gate.

The repository's `.claude/skills/adversarial-review` skill is **not** used: it is a copy from an
unrelated project ("finmgr.net") and hard-requires `scripts/pii-scan.sh`, `specs/nonfunctional.md`,
`docs/DEVELOPMENT.md` and `scripts/validate.sh`, none of which exist here. It is currently **staged
but uncommitted** in this working tree; whether to remove it is the owner's call
([§12](#12-open-questions)).

---

## 11. Risk register

| # | Risk | Mitigation |
|---|---|---|
| R-1 | Verifier and physics drift apart | One `step()`; witnesses replayed through the shipped simulation |
| R-2 | Assists change tape semantics | `InputFrame` is post-assist; `IntentFilter` sits above the integrator (§4.3) |
| R-3 | JVM/Wasm float divergence | `Double` only, no transcendentals on the critical path, no NaN in hashed state, pinned `wasm-opt` (§5.3) |
| R-4 | Hand-typed distances in the generator | `measureEnvelope()` only; a test asserts tuning gravity moves output |
| R-5 | Decoration breaks a corridor or an arc | `FloorMask` + `ArcMask`, properties 7–8 |
| R-6 | Player stranded in an inescapable pocket | `OverReach`/`UnderReach` split, property 4 (§3.4) |
| R-7 | Statistical sampling mistaken for a proof | Verification runs in the shipping build; the sweep is a regression check (§3.1) |
| R-8 | Locked boss arena the player cannot win | Soft lock until first damage + loot-floor property (§7) |
| R-9 | Verification too slow to run at runtime | Generator emits the witness; runtime = replay + flood; full oracle is `jvmTest`-only (§3.3, §9.2) |
| R-10 | Degenerate powerup builds | Cooldown floor, slow floor, max-not-product slows, proc recursion bounds, lifesteal rate cap (§6.6) |
| R-11 | Forced weapon downgrade | Score-gated swap on the player's actual build (§6.1) |
| R-12 | Per-sprite `save/restore` blows the budget | `setTransform`; real budget measured at M4, not assumed (§8.1) |
| R-13 | Existing tests / smoke test break `check`, CI and Pages | Named as explicit M4 tasks; EA-6 supersedes TITLE-005 (§2.3) |
| R-14 | Save incompatibility across deployments | Versioned `SaveCodec` with migration; stale-marker policy (§2.3) |
| R-15 | Assets 404 under the Pages base path | Relative URLs only, asserted by smoke check (§8.5) |
| R-16 | Content volume crushes the schedule | D3 sequencing; registries are data added in bulk on a proven engine |
| R-17 | Game is machine-completable but not humanly fair | Scheduled playtests at M6 and M7 with a written rubric (§9.4) |

---

## 12. Open questions

1. **Auto-aim (EA-5).** PROD-004 cannot be met by a mouse-only game. The plan adds an Auto-aim
   accessibility option and amends PROD-004. Confirm that is the wanted resolution rather than
   narrowing PROD-004 to non-gameplay UI alone.
2. **Score-gated swap (§6.1).** A higher-scoring weapon of a different class replaces the held one, so
   a melee build can become a ranged one mid-run. Acceptable, or should swaps be class-preserving?
3. **The staged `adversarial-review` skill.** It is staged but uncommitted and cannot run here. Remove
   it, or leave it staged?
4. **Meta-unlock breadth (D1).** Scrap unlocks drop-pool entries only, or also permanent starting
   bonuses? Pool-only is assumed.
5. **Run length.** 38–45 minutes for ten maps is the working target. Shorter maps would make
   permadeath sting less.

---

## 13. Review log (R1)

Reviewers: `codex` (`gpt-5.6-sol`, `xhigh`) on the full plan plus repository context, and three
independent sub-agents on balance arithmetic, the completability proof, and repository-rule
compliance. Roughly 60 findings; the load-bearing ones and their dispositions:

| Finding | Disposition |
|---|---|
| Assist configuration makes witness replay ill-defined | **Fixed** — §4.3, `InputFrame` is post-assist |
| Anti-stranding used an under-approximation where an over-approximation is required | **Fixed** — §3.4, two relations |
| No stance-transition edge; theme 3 unreachable on every seed | **Fixed** — §3.4 catalog |
| CG quantified over all seeds, discharged by sampling | **Fixed** — §3.1, verification in the shipping build |
| Fire jets: no phase-aligned wait, two-jet corridors, tiles vs AABB, no friction constant | **Fixed** — §3.5, §4.1 (R2 completed it: one volume per corridor, symbolic wait) |
| Timed-hazard bucket arithmetic invalid (86.4 buckets, non-integer periods) | **Superseded** — the timed search is deleted entirely (R2, §3.5) |
| `pathMask` protected the floor but not the jump arc | **Fixed** — `ArcMask`, property 8 |
| Boss arena locks with no proof the boss is killable | **Fixed** — soft lock + loot-floor property |
| "Corridor traversable without combat" is false; buffer halved where lethality peaks | **Fixed** — §3.6 rewritten, k = 2 throughout |
| `commonTest` runs on every target, so sweeps would hit Karma's 2000 ms timeout | **Fixed** — `jvmTest`; **verified by measurement** |
| Tier invariant `k = 1.15` fails twice on the registry | **Fixed** — `k = 1.05`, recomputed |
| `WeaponSpec` cannot express its own registry | **Fixed** — §6.2 schema extended |
| Cursor→world omits the camera | **Fixed** — §8.3 |
| `lastFire = now` quantizes and drifts cooldowns | **Fixed** — §6.3 accumulator |
| Killstreak Cache modelled as permanent attack speed | **Fixed** — event, not multiplier |
| Powerups had no rarity at all, contradicting the request | **Fixed** — tiers added, §6.5 |
| "All stack curves sub-linear" false for 4 of 18 | **Fixed** — values corrected, property restated |
| Drop expectations contradicted the drop table | **Fixed** — economy recomputed by simulation, §6.7 |
| Weight table not the interpolation it claimed; T2 non-monotone | **Fixed** — endpoints authoritative |
| Verification budget arithmetically impossible | **Fixed in R2** — witness emitted by the generator; runtime is replay + flood (§3.3, §9.2) |
| Perf conclusion used the bare-draw row, not the worst case | **Fixed** — §8.1 corrected |
| Physics: 10.9 tiles should be 9.07 | **Fixed** — §4.1 |
| Balance: trash HP 975 not 1000; contact 18.2 not 17; DPS 51.5 / 812 | **Fixed** — §7.1 |
| Fork Bomb split factor is per-weapon; headline weapon short of required DPS | **Fixed** — §6.6, per-weapon ceilings published |
| PROD-004 silently narrowed | **Fixed** — EA-5 amendment + Auto-aim; owner question 1 |
| Two-phase gate delegated to a model | **Fixed** — §10.1, user approval in `tasks.md` |
| Phase-one deliverables never produced | **Fixed** — §10.1 enumerates them |
| M3 exit required later milestones' content | **Fixed** — §10 exit criteria |
| SC-7 unachievable by a TTK harness | **Fixed** — §9.4 playtests |
| Existing smoke test / `index.html` / title screen / save marker unaddressed | **Fixed** — §2.3 |
| Pages base path would 404 assets | **Fixed** — §8.5 |
| "Logged telemetry" meaningless on static hosting | **Withdrawn** — §3.7 |
| Themes 5 and 7 contradicted the monotone hazard curve | **Fixed** — §5.1 |
| EA-3/EA-4 cited the wrong requirements | **Fixed** — §2.1, mis-citations withdrawn |
| Speculative WebGL interface violates ENG-022 | **Fixed** — removed |
| `ScreenRouter` placed in `wasmJsMain` violates ENG-010 | **Fixed** — moved to `commonMain` |
| Benchmark evidence had no committed harness | **Fixed** — `scripts/bench/` |
| No `jvmToolchain`; local `wasm-opt` unpinned and undocumented | **Fixed** — §2.1, §2.2 |
| D4 milestone coverage ambiguous | **Fixed** — §10 |
| Mini-boss clause in CG not discharged by any property | **Fixed** — property 2 |
| Arena specified with an entry and no exit | **Fixed** — §5 |
| Save/lifecycle, pause, death-in-arena, stale marker undefined | **Fixed** — §2.3, M8 |
| Asset licensing/provenance unstated | **Accepted** — M7/M8 task |

---

## 14. Review log (R2)

Revision 2 was put back to `codex` (`gpt-5.6-sol`, `xhigh`) with an explicit instruction to be
skeptical of §13's self-report and to check each claimed fix for being cosmetic, incomplete, or
newly-contradictory. It returned 22 ranked findings and judged 21 of §13's "Fixed" rows not actually
fixed. That is the honest outcome of the process working: the first round found the defects, the
second found that several repairs were shallower than claimed.

| Finding | Disposition |
|---|---|
| `UnderReach` nodes discard velocity, so edges do not compose and property 4 is unsound | **Fixed** — rest-canonical nodes (§3.4) |
| `OverReach` misses player-displacing effects (Katana blink, recoil, conveyors) | **Fixed** — only `MovementModel` may move the player; Katana becomes a dash-strike (§3.4) |
| `WAIT` does not make a timeless BFS able to solve timed hazards | **Fixed** — one jet volume per corridor + symbolic `WAIT_UNTIL_OFF`; timed search deleted (§3.5) |
| Boss soft-lock still permits involuntary commit via auto-fire and Auto-aim | **Fixed** — commit line + boss invulnerable until the gate closes (§7) |
| Guaranteed-loot floor fails at map 1 (bottle: 36 s vs an 18 s band) | **Fixed** — guaranteed map-1 starter cache (§6.7, property 18) |
| TTK bands jointly impossible with the HP multipliers | **Fixed** — bands derived from the multipliers (§7.1) |
| "Stronger is rarer" still false: map-10 weights peaked at T3 | **Fixed** — weights strictly decreasing in tier at every map (§6.7) |
| Property 9's own 5% margin test is red on the declared constants | **Fixed** — `v₀ = 640` (§4.1) |
| Published per-weapon ceilings used a sub-optimal fifth slot; stale 833 vs 812 | **Fixed** — Fracture Lens; all four clear 812 (§6.6) |
| Loot series began "1.00 distinct after map 1", impossible from 1.59 draws | **Fixed** — harness bug (`int()` truncation); recomputed (§6.7) |
| 400 ms runtime budget asserted, cache key unsound | **Fixed** — restructured, not retuned (§3.3, §9.2) |
| `resolvedDps` not computable for conditional weapons | **Fixed** — declared reference target (§6.1) |
| `aimWorld` computed in the pointer handler goes stale as the camera moves | **Fixed** — per-frame conversion (§8.3) |
| Katana teleport makes the mouse a locomotion control | **Fixed** — dash-strike (§6.4) |
| M5 required the full registry before the M6 vertical slice; M3's theme had no jets or ducts | **Fixed** — M5 subset; M3 delivers themes 1, 3, 4 (§10) |
| `scripts/bench/` claimed committed but does not exist | **Fixed** — downgraded to provisional, scheduled as an M4 task (§8) |
| `jvmToolchain(21)` conflicts with the README's JDK 17–26 | **Fixed** — Foojay resolver + README task (§2.1) |
| Properties 3, 10, 13–19, 22 individually untestable or ambiguous | **Fixed** — all restated (§9.2) |
| Full-slot pickup behaviour unstated; displaced weapon's fate unstated | **Fixed** — both convert to Scrap (§6.1, §6.7) |
| Tier means mis-rounded (16.72, 25.58) | **Fixed** — 16.73, 25.59 |
| Phase-one artifacts still not produced | **Open** — that is the next action, not a plan edit |
| D1 unlock roster, scrap values, save checkpoint/corruption policy still labels | **Deferred to M8's change record**, where the persistence spec is written |

Two rounds is where this plan stops. The remaining open items are scheduled work, not unresolved
design: the phase-one artifacts are written next, and the persistence detail belongs in M8's own
specification rather than in a research plan.

*Research supporting this plan came from three parallel sub-agents (Kotlin/Wasm platform verification
with live compilation and benchmarking; provable platformer generation; roguelite loot economy).
Where research conflicted with the brief — three weapon slots, weapon-owned powerups, a hold-to-swap
input — the brief won, and the override is recorded at the point of divergence.*
