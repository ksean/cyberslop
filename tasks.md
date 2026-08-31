# Tasks

Open implementation work, one entry per step of [`plan.md`](plan.md). An entry is deleted when its
step is done; a finding from adversarial review is recorded under the entry it concerns until it is
dispositioned. Anything worth keeping moves into `specs/`.

**Implementation approval for the difficulty plan:** given by the user in the request that asked
for the plan — "assume that the specification, requirements, and tasks do not need to be approved
separately … build an entirely new research and development plan (and then subsequently execute
that plan)" — with adversarial review directed for both the plan and the implementation.

## Open

### VAR — Scrap feedback, seeded boss profiles and hazard-crossing pursuit

**Request (verbatim):** "When the player receives scrap, you should see a golden \"+X\" above the
character's head moving slightly upwards and fading away, i.e. if you received 10 scrap from a
pickup (or something else) the character would have a golden \"+10\" above their head floating
upwards and fading away. Bosses and minibosses should have more variety and be randomly assigned.
Each mini boss and final boss should have at least 1 range attack, and 1 melee attack. They should
be able to have a variety of different attacks, not all of them should have the same attacks. For
example, some may should single projectiles, or spread shots like a shotgun, or laser attacks, or
single melee swings slowly, or multiple melee swings in rapid succession. Bosses and minibosses on
earlier maps should have weaker attacks (not only in the type of attack, but also the damage
inflicted) and should ramp up in difficulty towards the later maps. The visual design of the bosses
and mini bosses should also change, so that the type of attacks they can use are reflected in their
appearance. All enemies should be able to jump over obstacles like pits and spikes, so they can
chase the player across hazards. Ask the user any clarifying questions if needed."

**Phase one:** complete. PROD-086..089 were added in `product.md`; `enemies.md` replaces the fixed
Turret/never-jump rules with verified pursuit leaps, specifies the seeded boss roster, the three
attack bands, eight attack modules, damage/cadence escalation and P-60..P-61; `generation.md`,
`hazards.md` and `completability.md` carry the resulting generation/fairness boundaries;
`presentation.md` specifies the floating Scrap label, modular boss silhouettes and P-59/P-62;
`progression.md` identifies which Scrap counter changes produce feedback; `simulation.md` extends
the determinism boundary.

**Implementation approval:** given by the user after reviewing phase one — "approve phase two".

Defaults taken in the specification, each reversible during review:

1. Same-tick Scrap gains aggregate into one label. It lasts 0.90 s, rises 20 screen px, fades
   linearly, and stays at the world position above the player's head where the gain occurred rather
   than following the player.
2. "All enemies" means every ground-bound enemy, including mini/main bosses and the formerly fixed
   Turret, uses the safe leap; the Flyer achieves the same pursuit by flight. The Turret remains
   stationary until engagement, then unfolds into a slow crawler. Ground enemies wait out an active
   fire jet because its six-row column cannot be jumped over.
3. Random assignment is deterministic from the run seed so saves and JVM/Wasm replays agree. The
   mini-boss and main boss on one map cannot receive the same primary pair, nor can adjacent
   encounters; map-themed names remain unchanged.
4. Attack types are banded: maps 1–3 use Slam/Sweep plus Bolt/Burst, maps 4–6 use Slam/Flurry plus
   Burst/Scatter, and maps 7–10 use Flurry/Rush plus Scatter/Laser. Every encounter has one melee
   and one ranged module from full health; a main boss adds one visible-from-start signature at
   60 % health and attacks faster below 25 %.
5. Bolt, Burst and Scatter are real terrain-blocked projectiles, not only hit-condition artwork;
   Laser is a finite locked beam. All are capped at eight tiles and keep committed-span/landing
   grace while being allowed to hurt on boss ground.

After approval, one owner completes these in order. Every item begins with the smallest named test,
records its expected red failure here, makes the smallest production change, records the focused
green run, and leaves refactoring until green:

- [x] **VAR-1 — Scrap award boundary and label (PROD-086, P-59).** Add `ScrapGainTest` for the four
      positive award paths, same-tick aggregation, distinct later gains, zero/profile exclusions,
      expiry and digest/save exclusion; add focused `SceneTest` cases for exact `+X`, gold/bold
      style, birth anchor, fixed world origin, interpolated 20 px rise and numeric opacity. Route
      every `GameSimulation` increment through `gainScrap`, carry presentation-only live labels,
      add `TextItem.opacity`, and make `CanvasRenderer` apply and restore it. Run the focused common
      tests on JVM and Wasm.
      - Red: `jvmTest --tests io.github.ksean.cyberslop.sim.ScrapGainTest` failed as expected because
        `GameSimulation.scrapGains` did not exist.
      - Red: the focused simulation/scene run then failed as expected because `TextItem.opacity`
        did not exist, so the specified fade was not representable by the draw list.
      - Green: focused `ScrapGainTest` and `ScrapGainSceneTest` runs pass on JVM and Wasm. Every
        positive in-run award now crosses one aggregation boundary; the draw list and browser canvas
        preserve the exact gold text, anchored rise, fade and renderer opacity restoration.
- [x] **VAR-2 — Rank-and-file leap and traversal audit (PROD-088, P-33, P-61).** Start in
      `EnemyMovementTest` with an engaged Swarm crossing one pit, then cover widest flat acid/void,
      a three-tile spike strip, barrel, tallest step, no-safe-landing refusal, direction lock,
      attack suppression in air, fire-jet waiting, Flyer crossing and folded/unfolded Turret
      behaviour for every rank archetype. Implement a small immutable `EnemyLeap` plan plus the
      minimum `LiveEnemy` state using the shared gravity/collision vocabulary. Add the JVM cohort
      `EnemyPursuitEnvelopeTest` and generation rejection only after its fault-injected over-bound
      case is red.
      - Red: the first pit-crossing fixture stopped at the old ledge rule; the over-bound terrain
        fixture was accepted before a pursuit audit existed; boss jet timing and duplicate
        rank/body hazard reports then exposed missing runtime-time and immutable-removal boundaries.
      - Green: `EnemyMovementTest`, `BossBehaviourTest`, `EnemyPursuitEnvelopeTest`,
        `HazardPlacementTest`, `SeedCohortTest` and `GenerationBudgetTest` pass. Walkers and both boss
        ranks cross verified gaps, acid/void, spikes, barrels and steps, wait for jets, and retain
        direction/attack suppression through each leap; Flyers cross the same hazards in flight.
- [x] **VAR-3 — Boss module registry and seeded roster (PROD-087, P-60).** Add `BossProfileTest`
      first: registry totality, legal modules by band, melee+ranged from full health, signature
      phase, adjacent-pair exclusion, fixed-seed JVM/Wasm sequence, stream isolation, strictly
      increasing same-module damage and main-over-mini damage. Introduce typed attack/module/profile
      ids and `BossRoster`; pass the run seed into `Bosses` without coupling roster draws to live
      attack choice or loot. Update save reconstruction and digest mutation coverage.
      - Red: `BossProfileTest` first failed to compile on the absent typed module/profile roster;
        the initial damage-band cases then exposed the old shared fixed attack table.
      - Green: `BossProfileTest` and `BossDifficultyTest` pass on JVM and Wasm, including all-module
        coverage across 128 seeds, roster-stream isolation, adjacency exclusions, full-health
        melee+ranged availability, signature phase and increasing band/main-boss pressure.
- [x] **VAR-4 — Boss attack execution and boss leaps (PROD-063, PROD-072, PROD-087..088, P-17,
      P-35, P-44, P-60..P-61).** Extend `BossBehaviourTest` and `BossAttackChoiceTest` red-first for
      each event schedule and geometry: one Slam/Sweep/Rush hit, three Flurry swings at the declared
      offsets, one Bolt, three straight Burst rounds, five Scatter angles, one-hit Laser, aim/facing
      lock, terrain/range limits, boss-ground ownership, committed-span fairness, real-input dodge
      and stand-still hit. Refactor `LiveBoss.tick` to emit typed attack events for
      `GameSimulation`, add boss-owned projectiles/beam state, phase rest, and make both boss ranks
      cross the P-61 fixtures with their 44 × 56 box before any attack begins.
      - Red: the focused event and ranged tests failed on the absent typed schedules, projectile
        ownership and beam state; the leap fixture showed bosses still stopping at hazards.
      - Green: `BossAttackEventTest`, `BossRangedAttackTest`, `BossAttackChoiceTest` and
        `BossBehaviourTest` pass. Slam, Sweep, Flurry, Rush, Bolt, Burst, Scatter and Laser now use
        their declared timing/geometry, terrain and range rules, fairness windows and real dodges.
- [x] **VAR-5 — Loadout-reflecting silhouettes and motions (PROD-089, P-38, P-43, P-53, P-62).**
      Add `EnemyLookTest`, `SceneTest` and `HurtFlashSceneTest` cases for every module marker,
      colour-stripped profile distinctness, mini/main crowns, left/right profiles, signature
      hardware, jump clips and per-event Flurry/Burst poses. Add a composed `BossLook` and the
      smallest rig/effect extensions; keep telegraph colour above hurt flash and batch count
      independent of actor/projectile count. Extend `WorldFrameSheetTest`, generate early/mid/late
      mini/main frames with their attacks active, and inspect the sheet before recording green.
      - Red: render tests first failed on absent profile markers and the crawler form; motion tests
        then showed no jump clip or per-event boss pose.
      - Green: `EnemyLookTest`, `BossLookTest`, the focused scene/hurt-flash suites and
        `WorldFrameSheetTest` pass. The generated early/middle/late mini/main sheet was rasterized
        and inspected: all eight modules have distinct mounted hardware, signatures deploy by phase,
        and leap/event silhouettes remain readable in both facings.
- [x] **VAR-6 — Determinism and balance gate (P-39, P-40, P-60..P-61).** Extend the digest with
      every future-affecting leap/profile/event/projectile field and re-pin the cross-target golden
      only after mutation coverage is complete. Add `BossDifficultyTest` for profile coverage and
      strictly rising early/middle/late mean no-dodge damage per second; rerun
      `BossPressureTest`, route pressure/survival, the generation cohort and full-map run, tuning
      only the specified numeric levers if needed. Finish with `./scripts/check.sh` green (JVM,
      Wasm browser tests, production distribution and smoke).
      - Red: mutation coverage invalidated the old cross-target golden as expected. The first full
        gate then exposed pursuit-removal regressions in emitted difficulty/hazard/threat means and
        a 2 s Firefox timeout that passed alone but reproduced while JVM cohorts ran concurrently.
      - Green: the re-pinned digest (`10020045215349456527uL`) passes on JVM and Wasm; boss/route
        pressure, route survival, full-map run, difficulty, threat, hazard, seed-cohort and pursuit
        budget suites pass. `./scripts/check.sh` passes all JVM and Wasm browser tests, optimized
        production distribution and title-screen smoke (30 tasks, 7m14s); browser tests now follow
        the JVM cohort so the runner is not starved. `git diff --check` is clean.

### QOL — Alternate controls, permanent shop, first-pickup cards and liquid presentation

**Request (verbatim):** "Aiming to the left should not make weapons appear upside down, just a
mirrored \"flip\" of the appearance when pointing to the right. Also make the game playable using
the \"wasd\" keys as well. The space bar should also make the character jump. On the title screen,
there should be a \"Shop\" option to spend accumulated scrap on permanent upgrades to the character.
When a player picks up a weapon or weapon powerup for the very first time, the game should pause for
a few seconds, and a popup in the middle of the screen should show the weapon/weapon powerup picture,
with a brief description of what it does, for example, \"Riotbreaker Shotgun picture + The
Riotbreaker Shotgun shoots 3 projectiles in spread pattern\" or \"Red Market Siphon picture + The
Red Market Siphone heals on every hit\". If the player picks up the weapon or weapon powerup in new
game, it should not display the popup message anymore. The toxic poison pools should look more
\"bubbly\" to visually show that it is a pool of liquid, rather than just a static visual. Ask the
user any clarifying questions if needed."

**Phase one:** complete. PROD-021 and PROD-031 amended; PROD-081..085 added in `product.md`;
`progression.md` added for the profile, Scrap economy, shop catalog, migration and discovery-pause
contract (P-56..P-57); `simulation.md` adds alias semantics and P-54; `presentation.md` defines the
handed icon transform, discovery-card presentation and bubbly acid (P-55, P-58); `hazards.md` ties
the poison-pool look to unchanged lethal contact; the architecture index and specification index
are updated.

**Implementation approval:** given by the user after reviewing phase one — "approve and proceed".

Defaults taken in the specification, each reversible during review:

1. The shop has three five-rank tracks: +10 % maximum health per rank, +5 % weapon damage per rank,
   and −5 % incoming non-lethal damage per rank. Their shared rank prices are 100, 250, 500, 1,000
   and 2,000 Scrap.
2. Spendable Scrap and lifetime Scrap are separate. Spending never relocks weapons; the existing
   one-weapon-per-400 lifetime-Scrap progression remains.
3. A discovery card lasts 3.0 seconds of visible, focused time, cannot be skipped, and multiple new
   items queue weapon first. The Broken Bottle starts discovered.
4. Existing integer metadata and version-2 in-progress saves migrate, preserving the larger Scrap
   value; new upgrade and discovery fields take safe defaults.
5. Discovery pictures reuse the code-native item icon without the ground ring or rarity pips. Copy
   reflects the actual registry, so the current Riotbreaker description says five projectiles in
   a 30° spread rather than the illustrative three.

After approval, one owner completes these in order. Every item begins with the smallest named test,
records its expected red failure here, makes the smallest production change, then records the
focused green run; no item starts while the preceding one is red.

- [x] **QOL-1 — Input aliases (PROD-004, PROD-021, P-54).** Extended `BrowserInputTest` first for
      A/D/S/W, Space, value fallbacks, default prevention, simultaneous aliases and focus-loss
      clearing, and `CanvasAccessibilityTest` for complete control instructions; then implement
      source-aware canonical bindings without changing `InputFrame` or movement.
      **Red:** `wasmJsBrowserTest --tests …BrowserInputTest` failed as expected: KeyA and fallback A
      produced empty input, KeyA did not prevent default, and releasing ArrowLeft while A remained
      down released Left. The pre-existing canvas-blur case exposed a headless-fixture weakness:
      `canvas.focus()` had not made the synthetic canvas active, so the case now dispatches the
      blur event it is intended to verify directly.
      `CanvasAccessibilityTest` then failed to compile on the deliberately missing
      `configureGameplayCanvas`, proving the control-copy boundary was absent.
      **Green:** `wasmJsBrowserTest --tests …CanvasAccessibilityTest --tests …BrowserInputTest`
      passes (14 cases). Browser input now canonicalizes physical-code and value fallbacks,
      reference-counts simultaneous aliases, prevents scrolling, clears every active source at
      the existing lifecycle boundaries, and announces Arrow keys, WASD and Space.
- [x] **QOL-2 — Mirrored held icons (PROD-084, P-28, P-55).** Added pointwise horizontal and
      angled mirror cases to `IconTest` and `HeldWeaponTest`; explicit `IconHandedness` now changes
      only held placement, while every default call remains right-facing.
      **Red:** the focused JVM compile rejected the deliberately absent `IconHandedness` and paint
      overload; the held-scene fixture also established that simulation aim/facing are intentionally
      write-protected, so it was corrected to acquire both through a live target and movement tick.
      **Green:** `jvmTest --tests …IconTest --tests …HeldWeaponTest` passes (16 cases), including
      horizontal, up-left, down-left and exactly-vertical fallback geometry. `IconSheetTest` passes
      and regenerated `build/icon-sheets/icon-sheet-orientation.svg` with the left-hand transform;
      the pointwise sheet geometry and asymmetric Riotbreaker integration were inspected in both
      directions.
- [x] **QOL-3 — Bubbly acid (PROD-085, P-58).** `AcidPresentationTest` covers three phased rings,
      motion, periodicity, determinism, batch constancy and unchanged digest/contact. Acid now draws
      three coordinate-phased glow/body rings per exposed tile from interpolated presentation time,
      with `HazardSurface` structurally above `Hazard`.
      **Red:** the focused JVM compile rejected the missing `Layer.HazardSurface`, establishing that
      no ordered liquid-surface path existed.
      **Green:** `jvmTest --tests …AcidPresentationTest` passes (4 cases), and the existing
      `SceneTest`/`PickupIconTest` suites remain green. `AcidFrameSheetTest` generated seven-tile
      pool frames at 0.0 s and 0.4 s; both raster inspections show distinct hollow rings moving and
      growing across the bright liquid surface without synchronized pulsing.
- [x] **QOL-4 — Profile, economy and migration (PROD-031, PROD-082, P-56).** Added
      `PlayerProfileTest`, `UpgradeCatalogTest`, `ProfileCodecTest`, current/legacy `SaveCodecTest`
      cases and `LocalStorageSaveStoreTest`. The canonical profile now owns separate spendable and
      lifetime Scrap, ranks and discovery sets; run saves are version 3 and contain no profile copy.
      **Red:** profile/catalog tests failed to compile on the absent types; codec tests failed on the
      absent canonical codec/version-3 run boundary; browser compilation then exposed the old
      store's coupled `(run, meta)` API. After implementation, the first browser assertion exposed
      reference equality in the old non-data `PowerupSlots` fixture and was corrected to compare
      canonical run bytes.
      **Green:** the four focused common suites pass (15 cases) and
      `wasmJsBrowserTest --tests …LocalStorageSaveStoreTest` passes (4 cases). Version-2 runs remain
      resumable, the greater nonnegative legacy Scrap source migrates to both counters, a valid
      current profile wins thereafter, profile writes preserve run bytes, and spending never lowers
      lifetime unlocks. A transient Kotlin/Wasm incremental-linker ICE cleared with a clean rebuild.
- [x] **QOL-5 — Title shop (PROD-004, PROD-081).** Extended `TitleScreenStateTest`,
      `ScreenRouterTest`, `BrowserTitleScreenTest` and browser persistence cases; added
      `ShopScreenStateTest`, `BrowserShopScreenTest` and `BrowserRunEndedScreenTest`. Shop is always
      the final title action; rows expose balance, rank, total effect and price in text; available
      purchases and Back are ordered real buttons; end screens lead with Return to title.
      **Red:** common tests failed to compile on missing Shop action/state/model, browser compilation
      then stopped at the deliberately unhandled `Shop` action, and the end-screen test failed to
      compile on its missing adapter boundary.
      **Green:** all three focused common suites and the four focused browser suites pass. Purchases
      persist synchronously with an expected-rank guard against stale double activation, refresh the
      row and balance, preserve current run bytes/Continue availability, and Back/Return route to a
      freshly rendered title without starting a replacement run.
- [x] **QOL-6 — Permanent effects (PROD-082, P-56).** `PermanentUpgradeEffectTest` covers upgraded
      new/advanced maximum health with current-health-preserving profile refresh, every registered
      weapon, fixed Bleed, real melee, projectiles, swings, contact, spikes/barrels, boss attacks,
      and unchanged acid/void/fire-jet lethality. An immutable rank snapshot now enters each run;
      chassis applies at health boundaries, firmware in weapon resolution, and weave only in the
      shared non-lethal damage sink.
      **Red:** the test first failed to compile on the absent run snapshot, damage multiplier and
      resolved-weapon provenance. After the focused cases went green, the existing cross-target
      golden caught a rank-zero digest regression.
      **Green:** the focused suite passes (6 cases); eight existing determinism, hazard, enemy,
      boss, lifesteal and loot-floor suites pass (72 cases). Default ranks preserve the committed
      digest while a tagged non-default rank family remains future-state-sensitive.
- [x] **QOL-7 — Discovery events and copy (PROD-083, P-57).** The typed catalog has one entry for
      every 26-weapon and 18-powerup registry id, canonical names/icons and authored mechanic copy
      bounded to 140 characters. A tick reports fully resolved contacts in weapon-then-powerup
      order; the pure profile recorder filters already-known ids and duplicates, while the browser
      store saves a changed profile before returning entries for presentation.
      **Red:** the two focused common suites first failed to compile on the absent catalog,
      recorder, discovery id and tick-report field. The browser persistence case then failed on the
      absent `recordDiscoveries` transaction.
      **Green:** `DiscoveryCatalogTest` and `WeaponPickupTest` pass (8 cases), including first and
      fresh-run repeat, paired order, and applied/displaced/scrapped powerups;
      `LocalStorageSaveStoreTest` passes (6 cases), including synchronous persistence, deduplication
      and byte-for-byte run preservation. The recurring Wasm incremental-linker ICE cleared with a
      clean compile.
- [x] **QOL-8 — Discovery pause and card (PROD-004, PROD-083, P-57).** A common coordinator gives
      each deduplicated card three seconds of visible, focused time with no excess carried into the
      next. The host persists first, clears raw and assisted input at both queue boundaries, announces
      each entry, pauses the fixed-step accumulator on the pickup tick, ignores the opening frame's
      pre-pickup delta and defers same-tick map/death transitions until the queue closes. The scene
      replaces its HUD with a centred bordered card, dimmed live frame, canonical large icon and
      wrapped name/copy.
      **Red:** common compilation rejected the missing coordinator and scene parameter; browser
      compilation rejected the missing session and explicit input clear. A catch-up integration
      test then rejected the absent testable accumulator boundary, and the first host-timing run
      proved that the opening frame would otherwise shorten the interval.
      **Green:** `DiscoveryPauseTest` (2), `DiscoveryCardSceneTest` (1), `DiscoverySessionTest` (1),
      `DiscoveryLoopTest` (1) and `BrowserInputTest` (12) pass. The wider focused JVM rendering,
      simulation and determinism set and the seven related browser suites pass. A real Riotbreaker
      frame was generated as `build/icon-sheets/discovery-card.svg`, rasterized and inspected: card,
      copy and unadorned canonical icon are centred and legible.
- [x] **QOL-gate.** Every focused common/JVM and Wasm browser suite is green. The complete
      `./scripts/check.sh` gate passed against the installed pinned Binaryen distribution (JVM and
      Wasm tests, optimized production distribution and title-screen smoke; 30 tasks, 5m17s), and
      `git diff --check` is clean. A Firefox 154 playthrough of the optimized bundle exercised
      `Shop` → Chassis purchase (500 → 400 Scrap) → `Back` → `New game`, confirmed all A/D/S/W and
      Space events are handled, showed the upgraded 110/110 health, reached a first Static Lash
      pickup, observed its already-persisted centred discovery card, reloaded and continued the
      identical run, and reached the pickup again with no second card. The start, card and repeat
      frames were captured and inspected. Exact left, up-left, down-left and vertical-fallback
      weapon geometry was additionally inspected in the generated orientation sheet; the two
      generated acid frames show three independently phased rings rising and growing on every
      exposed liquid tile.

### DIFF-1 — Specify the difficulty changes and review the plan

- [x] Amend the specs with plan decisions 1–8 (PROD-036, PROD-060..068, P-32..P-40).
- [x] Review gate 1, round 1: 17 findings. Dispositions:
  - MAJOR PROD-036/062 contradiction and no physical commit gate — **confirmed** (the gate has
    always been solid from generation; commit only flipped a flag). Fixed: commit line removed
    from the design; PROD-036 restated; `BossFight.committed` and `Camera.frameArena` scheduled
    for deletion.
  - MAJOR completability.md described repair/fallback/runtime OverReach and a 120/2000-seed
    cohort the code lacks — **confirmed**. Fixed: rewritten to the actual runtime path (replay
    through `MovementModel`, eight attempts, fail loudly); P-01 is 40 seeds; P-20/P-21 dropped.
  - MAJOR pursuit invalidates spawn-only placement invariants — **confirmed**. Fixed: runtime
    fairness rule (no enemy damage while the player is over a committed column; Flyers never enter
    one), `Level.committedColumns`, P-34.
  - MAJOR Shooter inert band 13.75–22 tiles — **confirmed**. Fixed: approach / hold / retreat zones.
  - MAJOR boss pursuit vs arena camera clamp — **confirmed** (and `frameArena` was never called).
    Fixed: no arena framing; Volley capped at 8 tiles.
  - MAJOR PROD-063 not scheduled for bosses — **confirmed**. Fixed: boss attack poses and effects
    in DIFF-4; attack table gains a "drawn as" column.
  - MAJOR P-19 covers player physics only — **confirmed**. Fixed: P-19 narrowed honestly; P-40
    whole-simulation digest added as DIFF-5.
  - MODERATE bot playthrough undefined — **confirmed**. Fixed: policy, termination, death handling
    and cohort statistics specified; assertion is by thirds of the run.
  - MODERATE parallel split unsafe — **confirmed**. Fixed: only 7a runs in parallel.
  - MODERATE red tests not named — **confirmed**. Fixed below.
  - MODERATE engagement predicate imprecise — **confirmed**. Fixed: Euclidean, inclusive, hysteresis,
    called an awareness radius.
  - MODERATE ledge rule contradicts falling — **confirmed**. Fixed: rule scoped to voluntary steps;
    gravity, knockback, stun and body size specified.
  - MODERATE hazard placement order/geometry undefined — **confirmed**. Fixed: pipeline order,
    Chebyshev ≥ 2, full footprint, `ArcMask` exclusion, confirming replay removes offenders.
  - MODERATE README "current state" semantics — **confirmed**. Fixed.
  - MODERATE psychic wind-up and applicability matrix claims false — **confirmed**. Fixed to what
    `DamagePipeline` does.
  - MINOR icon catalog lost — **confirmed**. Restored as `specs/iconography.md`.
  - MINOR 7.6× stated as fact — **confirmed**. Caveated.
- [x] Review gate 1, round 2: 14 findings. Dispositions:
  - MAJOR the bot harness cannot finish late maps and its scope contradicted P-39 — **confirmed**.
    Fixed: split into route pressure (all maps, gross incoming damage, death = full health) and
    boss pressure (floor-covered maps, must win); targeting and termination stated; PROD-068 aligned.
  - MAJOR P-40 digest far from whole-state — **confirmed**. Fixed: canonical encoding over every
    future-affecting field listed by family, mutation test per family, scheduled after hazards.
  - MAJOR boss dodges are metadata — **confirmed** (`LiveBoss.inRange` is radial by name). Fixed:
    each attack gets a hit condition its dodge defeats; tested both ways in DIFF-4.
  - MAJOR reachability catalog still fictional — **confirmed**. Fixed: catalog and literal jet
    waits rewritten to the code; P-04 stated as one seed.
  - MAJOR committed-span rule leaves no reaction window — **confirmed**. Fixed: occupancy by AABB,
    `LANDING_GRACE = 0.25 s`, column definition aligned with `Populator`, boundary tests named.
  - MODERATE boss slow contradiction — **confirmed**. Fixed: immune, in `combat.md`.
  - MODERATE swing has no arc — **confirmed**. Fixed: 90° arc, behind-the-attacker test.
  - MODERATE 7a ownership overlapped `BalanceTest`/`RouteSurvivalTest` — **confirmed**; 7a had
    already finished without touching either. Plan scope corrected.
  - MODERATE player effects lacked red tests; Kessler has no barrel — **confirmed**. Fixed:
    PROD-066 asks for a firing cue (flash or activation pulse); `SceneTest` cases in DIFF-6b.
  - MODERATE boss powerup floor T2 unimplemented — **confirmed**. Scheduled as DIFF-7.
  - MODERATE P-13 cohort/margin false — **confirmed**. Fixed to 24 seeds and the 1.5× assertion.
  - MODERATE P-15 "matrix" — **confirmed**. Restated; resolution test scheduled as DIFF-7.
  - MODERATE confirming removal could stay dead — **confirmed**. Fault-injection and barrel-body
    tests added to P-36.
  - MINOR awareness boundary differs from auto-aim at equality — **confirmed**. Both strict `<`.
- [ ] Review gate 1, round 3.

### DIFF-2 — Awareness and movement — done

`EnemyMovementTest` (12) red then green; `LiveEnemy.engaged`, `Level.committedColumns`, pursuit,
approach/hold/retreat, walker gravity, the ledge rule, Flyer boundary, speed cap. The old
"never leaves the patrol span" assertion is deleted. Entry kept until the gate-2 review reads it.

### DIFF-3 — Attacks and animation — done

`EnemyAttackTest` (7) red then green: no contact aura, wind-up deals nothing, damage once per
cooldown, stun cancels, shot cadence and speed, the 90° swing arc (behind-the-attacker miss),
occupancy by AABB and `LANDING_GRACE`. `ActorTest`/`SceneTest` red then green: `Action.WindUp`
(arm back and raised, legs untouched), enemies posed from their own windows via
`Scene.enemyMotion`, and the swoosh (three nested arcs, sparks, outer arc = reach) and muzzle flash
(core, bloom, ±35° spikes) as shared functions drawn from the posed hand or barrel
(`Scene.barrelTip`). Entry kept until the gate-2 review reads it.

### DIFF-4 — Bosses

- [x] Red then green: `BossTest` re-anchored (no commit line; `engage()`), `BossBehaviourTest` —
      an unnoticed boss neither moves, attacks nor takes damage; an engaged boss fights before the
      player enters the arena; follows the player out and stops at a ledge; Volley reach is 8 tiles;
      **each attack's listed dodge avoids it and standing still does not**. `MapExitTest`,
      `PresentationTest`, `ExitClearanceTest`, `FullMapRunTest` re-anchored on engagement.
      Two fixture faults found on the way: `TestLevels` arenas used the standing row as
      `floorRow` (the generator's is the solid row), and the "fights before the arena" case walked
      the player clean past the boss before asserting.
- [x] Red then green: `SceneTest` — a telegraphing boss poses `WindUp`; an active Slam/Sweep
      poses `Swing` with a swoosh; an active Volley poses `Fire` with a flash and a fan; an
      approaching boss selects `Run` and its gait advances. `Scene.bossMotion`, `LiveBoss.moving`,
      `BossAttack.visual` (`AttackVisual`) so the renderer never matches on an attack's name.
- [x] `BossFight.committed`, `playerMoved`, `COMMIT_OFFSET` and arena framing are gone; the
      `LootFloor` comment that leaned on the commit line rewritten.
- [x] `./scripts/check.sh` green on both targets (JVM, wasm browser tests, distribution).

### DIFF-5 — Hazards — done

`HazardDamageTest` (6) red then green: spikes, barrel body, barrel flame each drain at their rate;
one tick does not kill; two hazards drain both; no displacement. `HazardPlacementTest` (jvmTest,
20 seeds × 10 maps): every footprint cell ≥ 2 from footholds and pickups, outside `ArcMask`, arenas
and ramps; zero route contact everywhere; a fault-injected spike and barrel on the route are removed
by `HazardPlacer.confirm` and nothing else is, deterministically; count zero on map 1 and rising in
cohort mean. `WitnessReplayTest`: hazards cannot change where the tape goes. `SceneTest`: spikes and
barrels draw on `Layer.Hazard`; `PickupIconTest` (P-30) still green. `TileKind.Spikes`, `Barrel`,
`Hazards`, `Level.barrels`, `ReplayResult.damagingContacts/touchedHazards`, `HazardPlacer`, the
`damagingHazardsPerHundredTiles` curve row, generator wiring, `GameSimulation.drainHazards`.

### DIFF-6 — Player weapons and crouch — done

- [x] 6a (sub-agent): `WeaponRegistryTest` P-37; registry rebalanced; P-14 green.
- [x] 6b: `ActorTest` — crouch limb lengths equal standing (thigh, shin, upper arm, forearm,
      torso), knees forward of the hip–ankle line, highest point within the crouch height; the rig
      now sizes every limb from the standing height and solves knees and elbows as two-bone chains.
      `SceneTest` — the player's swoosh outer radius equals the resolved reach (Ranger Optics
      included); the shot cue sits at the held weapon's muzzle (`Scene.muzzleOf`); the Kessler (any
      cursor-anchored weapon) draws an activation pulse ring rather than a flash.

### DIFF-7 — Contract gaps — done

`DropTableTest`: a powerup floor keeps every roll at or above the tier; `BossAwardTest`: over 60
seeds the main-boss powerup is ≥ T2 and the weapon ≥ T3 (`DropTable.rollPowerup(floor)`).
`PowerupRegistryTest`: every weapon × powerup × stack count resolves finite and positive (P-15).

### DIFF-8 — Determinism digest — done

`SimulationDeterminismTest` (commonTest): a 720-tick tape on map 1 matches a committed golden
(`7529129653326272212`, re-pinned after RBH) and a mutation in each family — player/run,
auto-fire accumulator, loot RNG, enemies, engagement, projectiles, items, bosses, boss rest — changes
it. `GameSimulation.digest()` per P-40; `internal` hooks on `AutoFire.remaining`, `lootRng`,
`LiveBoss.restSecondsLeft/attackIndex`; `Rng.state` readable.

### DIFF-9 — Measure, tune, review

- [x] `ThreatScore` + `ThreatScoreTest` (24 seeds: cohort mean rises strictly 1→10);
      `PressureHarness`, `RoutePressureTest` (8 seeds: thirds 5.0 → 16.1 → 30.1 gross damage per
      100 tiles; the floor survives every floor-covered route), `BossPressureTest` (8 seeds: every
      floor-covered map, 1–6, won). `GameSimulation.grossDamageTaken` counts every damage event
      before lifesteal.
- [x] Found and fixed on the way (test-first): **chain weapons could never damage a boss**
      (`resolveChain` enumerated trash only — a run whose guaranteed weapon was Voice of the Dead Net
      stood beside the boss forever; `ChainWeaponTest`); **the Populator had no arena exclusion**,
      so Brutes on the approach and Shooters in the exit corridor joined every boss fight
      (`Populator.isClearOfArenas`, `ARENA_APPROACH_TILES = 6`, keep-out to the map edge past the
      boss arena; `PopulationInvariantTest`; `specs/enemies.md` Population).
- [x] Tuned, with the spec tables updated: `DISENGAGE_PX` 33 → 28 tiles; Brute swing 1.6 → 1.2 ×
      contact; shot 0.6 → 0.45 × contact. Route damage was measured as dominated by projectiles the
      tape cannot dodge and by the mini-boss Slam on the walk-through.
- [x] `./scripts/check.sh` green on both targets before gate 2.
- [x] Review gate 2, round 1: 7 findings. Dispositions:
  - MAJOR projectiles resolved before exposure was updated, and the AABB's right edge used
    `width − 1` — **confirmed**. Fixed: `advanceExposure()` runs right after the movement step;
    edge at `width − 0.001`; `EnemyAttackTest` entry-tick case.
  - MAJOR the pressure harness started with the map's own boss powerup and collected optional
    loot — **confirmed**. Fixed: `LootFloor.slotsArrivingAt`, optional items (caches and kill
    drops) stripped every tick; `LootFloorTest`, `RoutePressureTest`; spec text in enemies.md.
    Re-measured under the stricter loadout: maps 4–5 then lost to packs 13–26 tiles behind, which
    is what led to the boss's-ground rule below.
  - MODERATE ≤ 35 % ranged and ≥ 3 archetypes claimed per map, enforced nowhere — **confirmed**.
    Fixed: `Populator.MAX_RANGED_SHARE` rejection and `withArchetypeFloor`; per-map cohort test.
  - MODERATE Flyer boundary tested by centre only — **confirmed**. Fixed: both body edges;
    test reads both edges.
  - MODERATE dodges proven only with a synthetic target — **confirmed**. Fixed:
    `BossBehaviourTest` drives the real player with the four inputs against a phase-three boss
    (zero damage dodging; every attack lands standing still); `TestLevels.dodge/standStill`
    shared with the jvmTest harness.
  - MODERATE self-anchored psychic weapons drew a muzzle flash — **confirmed**. Fixed: psychic or
    cursor-anchored → activation pulse; presentation.md says so; Neural Spike case in `SceneTest`.
  - MINOR stale text (plan 33 tiles, "inclusive" comment, LootFloor commit line) — **confirmed**.
    Fixed.
- [x] **The boss's ground** (found by the re-measurement above, not by the reviewer): with a
      guaranteed-only loadout the pack engaged on the final stretch still arrived mid-fight, and
      no spawn keep-out can fix a runtime problem. New rule in enemies.md (Pursuit): no enemy
      pursues onto an arena or its twenty-tile approach (`Level.isArenaGround`); walkers stop as
      at a ledge, Flyers hold as at a committed column; `Populator.ARENA_APPROACH_TILES = 20`
      keeps spawns off the same ground. `EnemyMovementTest` (2). Boss pressure: every
      floor-covered map won on every seed; route pressure thirds 5.1 → 16.4 → 30.2.
- [x] `./scripts/check.sh` green; `LevelLayoutTest`'s spread buckets now cover the spawnable
      span (the boss's ground holds no spawn by rule, so a raw last third is short by rule).
- [x] Review gate 2, round 2: 4 findings. Dispositions:
  - MAJOR the boss's ground stopped movement but not projectiles — a Shooter held at the edge is
    in range of anyone inside, and the mini-boss arena is open from its right — **confirmed**.
    Fixed: the fairness rule gains a second clause — no enemy swing or projectile lands on a
    player whose box overlaps the boss's ground (`enemyDamageAllowed`); bosses are not bound by
    it. `EnemyAttackTest`; enemies.md.
  - MAJOR a kill drop created and collected inside one tick escaped the harness's strip —
    **confirmed**. Fixed: `GameSimulation(optionalLoot = false)` creates no cache and no kill
    drop; the harness uses it; `GuaranteedOnlyTest`.
  - MAJOR the floor's weapon table overstated late maps (bosses guarantee Chromed, never
    Blacksite/Ascended) and the starter cache could roll the bottle (seed 17) — **confirmed**.
    Fixed: `weaponAt` is Street then Chromed; `rollWeapon(excluding)` keeps the bottle out of the
    cache; `LootFloorTest` (120 seeds; tier table). The honest floor rises 4.9× across a run
    (was asserted > 5× against the overstated table; now > 4×). `furthestClearableMap` and the
    pressure coverage follow the corrected floor; every covered map is still won on every seed.
  - MODERATE Flyers and Turrets had no visible wind-up — **confirmed**. Fixed: a charge glow at
    the pod's front / the barrel's mouth growing through the telegraph; `SceneTest`.
- [x] The 120-seed starter-cache sweep blocked the browser runner past its ping timeout
      (ENG-031); it moved to `jvmTest` (`LootFloorCohortTest`) and `commonTest` keeps seed 17.
- [x] Review gate 2, round 3: 6 findings. Dispositions:
  - MAJOR the guaranteed-only harness still placed the starter cache `weaponAt(1)` models, and
    skipping drop draws shifted the shared RNG — **confirmed**. Fixed: the harness clears the
    cache; kill-drop rolls are always drawn and merely withheld (`GuaranteedOnlyTest`,
    `RoutePressureTest`).
  - MODERATE `furthestClearableMap` judged a boss with its own award; `FullMapRunTest` and
    `RouteSurvivalTest` still started with `slotsAt` — **confirmed**. Fixed:
    `damagePerSecondArrivingAt` decides coverage; both tests use `slotsArrivingAt` (route
    survival also guaranteed-only); enemies.md "The loot floor".
  - MODERATE the digest omitted the gate tiles `openGate` mutates — **confirmed**. Fixed: the exit
    run's tiles are folded in; mutation case added; golden re-pinned (`6827669262270049212`).
  - MODERATE ranged telegraphs tracked the current player while the shot resolved on the stored
    aim — **confirmed**. Fixed: a winding-up enemy is drawn along `attackDirection`; a boss holds
    its facing through an attack (`BossBehaviourTest`, `SceneTest`; enemies.md).
  - MODERATE Rush had no lunge — **confirmed**. Fixed: through its active window the boss carries
    forward at 300 px/s under the ledge rule, hit resolved first; swoosh trails; enemies.md.
  - MINOR disengagement at exact equality — **confirmed**. Fixed: `<=`; equality case tested.
- [x] `./scripts/check.sh` green after round 3 (JVM, wasm browser tests, distribution).
- [ ] **Gate 2 reached the plan's three-round cap with round 3 still returning findings** (all
      fixed above). ENG-072 says rounds continue until one returns nothing load-bearing; the plan
      capped each gate at three. A fourth round is the user's call.

### CPS — Contact drain, weapon-pickup reset, shot indicators (plan step 10)

**Approval:** given by the user after reviewing phase one — "approve and proceed". The request ("Walking into enemies should cause the player to take
damage. Picking up a weapon should always change the player's current weapon, but reset their
weapon powerups. Range weapons should always have a visual indicator that shows where the weapon
was aimed/would hit an enemy … Implement using adversarial review.") directs the implementation and
the review; phase one stops here for the user to approve the specification below before any test
is written. Clarifications answered by the user: contact is a per-second drain (not bosses); every
weapon is taken and every slot converts to Scrap, boss awards included; indicators cover ranged
and psychic patterns for player, enemies and bosses; the loot floor is re-derived, not waived.

Specs amended: `product.md` PROD-030, PROD-061 restated, PROD-069, PROD-070, PROD-071;
`enemies.md` Contact, fairness rule, the loot floor, P-34, P-41; `combat.md` Weapon pickup, P-42,
Known gaps; `presentation.md` Weapon effects, P-43; `hazards.md` cross-reference; `plan.md`
decision 9 and step 10.

#### CPS-1 — Contact drain (PROD-069, P-41)

- [x] Red: `EnemyAttackTest` (or a new `ContactDrainTest` beside it) — overlap drains exactly
      `contactDamage × TICK_SECONDS` per tick; one tick does not kill; two overlapping enemies drain
      twice; a dead enemy, an enemy one pixel clear, a stunned enemy (still drains), a boss body
      (nothing); no drain over a committed column, within `LANDING_GRACE`, or on the boss's ground;
      the player's position is unchanged (P-19 clause). The existing "overlap outside a strike
      deals nothing" case is inverted, not deleted.
- [x] Green: `GameSimulation.drainContact()` beside `drainHazards()`, AABB-vs-14×14 overlap using
      the `EDGE` convention, gated by `enemyDamageAllowed()`; `EnemyAttacks.CONTACT_DRAIN = 1.0`.
- [x] Red then green: `ContactDrainTest` (7), `EnemyAttackTest` overlap case inverted and the two
      swing cases re-anchored on strikes (damage above the drain ceiling) since a pursuing enemy
      now drains while it swings. `GameSimulation.drainContact()`, `EnemyAttacks.CONTACT_DRAIN`.
- [x] **Measured after CPS-1 alone:** `RoutePressureTest`/`BossPressureTest` — map 5 seed 4 dies on
      the route with the (old) guaranteed loadout. Not retuned; re-measured after CPS-2's floor
      re-derivation decides whether map 5 is still covered. Digest golden moves (re-pinned once
      after CPS-3).
- [ ] `RoutePressureTest` / `BossPressureTest` / `ThreatScoreTest`: re-measure. `ThreatScore`
      excludes contact by spec; route pressure will rise — re-pin the thirds and confirm they still
      rise strictly and the floor still survives. If a floor-covered map is lost on any seed, stop
      and report rather than retune silently.

#### CPS-2 — Weapon pickup resets the build (PROD-070, P-42)

- [x] Red: `LoadoutTest` — `collect(weapon)` always equips, returns the old weapon's Scrap plus the
      Scrap of every cleared slot (by each powerup's tier), leaves `slots` empty; a lower-tier,
      lower-score weapon is still taken; with empty slots only the weapon's Scrap is paid.
      `BossAwardTest` — after the award the weapon is equipped **and** the powerup is held, from
      either approach direction. `GameSimulationTest` — a kill-drop weapon wipes a three-slot build
      and the Scrap total matches.
- [x] Green: `Loadout.collect(found, mapIndex)` returns `WeaponPickup.Equipped(replaced, scrap,
      clearedSlots)` with `slots = PowerupSlots.empty()`; `WeaponPickup.Scrapped` is deleted with
      its branch in `collectItems`. A paired award becomes **one** `GroundItem` carrying both
      weapon and powerup (`award()` stops dropping a second item a tile away), resolved weapon
      then powerup in `collectItems`; the digest encodes the new field (`SimulationDeterminismTest`
      mutation case, golden re-pinned once).
- [x] `LootFloor`: `weaponAt(map)` for the **boss fight** is the mini-boss award's weakest (Scav)
      on every map; `weaponArrivingAt(map)` is the previous boss's weakest (Chromed from map 2,
      Street on map 1); `slotsAt(map)` is exactly {the mini-boss powerup} from map 4 and empty
      before; `slotsArrivingAt(map)` is exactly {the previous boss's powerup} from map 2.
      `LootFloorTest` re-anchored; `furthestClearableMap` judges with the loadout *held at the
      boss* (`damagePerSecondAt`), because the arriving weapon is gone by then.
      **Coverage before → after: maps 1–5 → maps 1–3.** At every boss the floor holds the
      mini-boss's weakest Scav weapon (Migraine Loop, 16.1 DPS; Kinetic Damper from map 4 adds
      nothing to it); map 4's boss takes 38.8 s against a 33.3 s band. Arriving loadout: Zip
      Pistol (9.2 DPS) on map 1, Grenade Lobber + Spike Driver (24.8 DPS) from map 2. **For the
      user to weigh** (not tuned here): raising the mini-boss weapon floor, or letting a
      guaranteed award keep the better weapon, would restore coverage. The CPS-1 map-5 death is
      now outside coverage.
- [x] `WeaponScore` remains for powerup displacement only; its Known-gaps paragraph about weapon
      swaps is gone (done in the spec); delete any code path that compared weapons.
- [x] `HudModelTest`/HUD: nothing to change unless the HUD cached slots by weapon.

#### CPS-3 — Shots show where they went (PROD-071, P-43)

- [x] Red: `SceneTest` — a player projectile draws a dot at its position and a segment of length
      `speed × TRACER_SECONDS` back along its velocity in the player style; an enemy projectile the
      same in the hazard style; a Volley dot likewise; after a Kessler activation a beam segment
      whose lower end is the strike centre and a ring at `radius × hitboxScale`; after a Ghostwire
      Tether chain, one segment per jump with endpoints at the struck targets in order and none
      when no target was in range; after a Migraine Loop blast a ring of the resolved radius; every
      indicator gone after `FLASH_VISIBLE_SECONDS`. `SimulationDeterminismTest` — an indicator
      does not change the digest.
- [x] Green: `HitIndicator` sealed type in `Entities` (`Beam(foot, radius)`, `Chain(points)`,
      `Ring(centre, radius)`) with `secondsLeft/totalSeconds` like `MuzzleFlash`; set in
      `resolveBlast`, `resolveChain` and the `Strike`/`Orbit`/`Pull` branches of `emit`; the boss
      Volley and enemy `fire()` need nothing new because their shots are projectiles.
      `Scene.projectiles` draws body + tracer in two batches per side (dot, segment);
      `Scene.hitIndicator` draws beam/chain/ring on `Layer.Effects`. Batch count stays constant
      (P-23, P-31 still green).
- [x] Note for the reviewer: the Kessler's `Strike.delaySeconds` (0.35 s in the table) is declared
      and not consumed — the strike resolves on the firing tick. The beam is drawn at resolution;
      the delay is not implemented by this step and the table's claim is a pre-existing gap to be
      recorded or fixed by the user's decision.

#### CPS-gate — `./scripts/check.sh`, then gate 3

- [x] Check green on both targets (JVM, wasm browser tests, distribution).
- [x] Gate 3, round 1 (codex gpt-5.6-sol, high, read-only; 13 findings). Dispositions:
  - MAJOR Volley drew a fan along the boss's facing in the accent colour, not the band at
    `aimedX ± VOLLEY_WIDTH` — **confirmed**. Fixed: floor band and barrel-to-band tracers in the
    enemy-shot style; `LiveBoss.VOLLEY_WIDTH` public; `SceneTest` active-Volley case; spec text.
  - MAJOR a projectile spawned, flown and spent in one tick was never drawn — **confirmed**. Fixed:
    `GameSimulation.impacts` (`HitShape.Impact`, flash window, outside the digest) drawn as the
    same tracer; `SceneTest` point-blank case; presentation.md.
  - MAJOR boss pressure fought with whatever the mini-boss happened to roll, not the floor —
    **confirmed**. Fixed: `PressureHarness.holdFloor` → `GameSimulation.holdLoadout` (internal);
    `BossPressureTest` asserts the held weapon and slots equal `weaponAt`/`slotsAt` before the fight.
  - MODERATE enemies.md loot-floor bullet still judged coverage by `slotsArrivingAt` — **confirmed**.
    Fixed: coverage by the loadout held at the boss; route and mini-boss by the arriving loadout.
  - MODERATE P-43 narrower than PROD-071 — **confirmed**. Fixed: P-43 names enemy and boss
    projectiles, Volley, pull and orbit, body radius, fading; a test per branch.
  - MODERATE Orbit resolved at `rangePx` (6 m) instead of `pattern.radius` (3 m) — **confirmed**
    (pre-existing balance bug the indicator exposed). Fixed: `pattern.radius`; `OrbitWeaponTest`
    hit boundary around the aimed target.
  - MODERATE body drawn at 0.7 × hit radius against a spec that says hit radius — **confirmed**.
    Fixed: `SHOT_SCALE` deleted; radius asserted.
  - MODERATE mini-boss judged with the award it earns — **confirmed**. Fixed:
    `damagePerSecondArrivingAt` for trash and mini-boss.
  - MODERATE "no verification gate recorded" — **rejected**: `./scripts/check.sh` ran green
    (JVM, wasm, distribution) before the round was launched; `tasks.md` was ticked after launch,
    so the reviewer's clone predated the tick. Recorded here.
  - MINOR boss's-ground sentence omitted contact — **confirmed**. Fixed.
  - MINOR `WeaponScore`/`Weapon`/`Weapons` comments said score decides pickups — **confirmed**. Fixed.
  - MINOR indicators did not fade — **confirmed**. Fixed: every stroke width × strength; fade test.
  - MINOR P-41 lacked landing-grace and mini-boss cases — **confirmed**. Fixed: both added.
- [x] Gate 3, round 2 (6 findings). Dispositions:
  - MAJOR the route let the mini-boss roll a random ≥ Scav award, so P-39 was not discharged with
    the floor's loadout; `MapExitTest` started the route with `weaponAt` — **confirmed**. Fixed:
    `PressureHarness.pinAwards` replaces a dropped guaranteed award with the floor's weakest
    (`weaponAt`/`slotsAt`) where it lies; `BossPressureTest` asserts the held loadout equals the
    floor whenever the mini-boss fell; `MapExitTest` uses the arriving APIs; enemies.md states the
    per-map timeline (arriving → mini-boss award → held at the boss).
  - MODERATE enemies.md named `weaponAt` where it meant the arriving weapon, and the harness
    paragraph contradicted the timeline — **confirmed**. Fixed.
  - MODERATE a paired award's powerup is drawn a tile from the weapon but contact tested only the
    weapon's centre — **confirmed**. Fixed: `GroundItem.powerupPosition`/`inReachOf` (either icon
    collects the pair, `PAIRED_OFFSET` shared with the renderer); `WeaponPickupTest` collects from
    the powerup's side.
  - MINOR the Orbit row said "around the weapon" for a cursor-anchored pattern — **confirmed**.
    Fixed: around the pattern's anchor.
  - MINOR `EnemyAttacks` comment still said nothing hurts by touch — **confirmed**. Fixed.
  - MINOR impact tracers did not fade — **confirmed**. Fixed: width × strength; test extended.
- [x] Gate 3, round 3 (5 findings, none MAJOR). Dispositions:
  - MODERATE the post-tick award pin missed an award dropped and collected inside one tick —
    **confirmed**. Fixed: `GameSimulation.awardOverride` (internal hook applied in `award()` after
    the rolls, so the loot stream is unchanged); `PressureHarness.pinAwards` installs it before the
    route; `PressureHarnessTest` kills the mini-boss with its centre in reach and proves the
    post-tick loadout is the floor's.
  - MODERATE a projectile's hit read `autoFire.weapon`, so a pickup in flight rewrote its effects
    (pre-existing; PROD-070 made it matter) — **confirmed**. Fixed: `LiveProjectile.weapon` carries
    the firing `ResolvedWeapon`; the digest encodes its payload (golden re-pinned once more,
    `7583559373744013130`, encoding change only); `ProjectileOwnershipTest`; combat.md sentence.
  - MINOR combat.md "tier governs drop rarity only" beside tier-valued Scrap — **confirmed**. Fixed.
  - MINOR `LootFloorTest` comments still spoke of a sealing arena and a commit line — **confirmed**.
    Fixed.
  - MINOR no test collected a copy of the held weapon over a stacked build — **confirmed**. Fixed:
    `LoadoutTest` same-weapon case.
- [ ] **Gate 3 reached the plan's three-round cap with round 3 still returning (non-major)
      findings**, all fixed above. As at gate 2, ENG-072 says rounds continue until one returns
      nothing load-bearing; the plan caps each gate at three. A fourth round is the user's call.

### RBH — Range-aware bosses, life steal, bounce, burst fire, hurt flash, health bars (plan step 11)

**Approval:** given by the user after reviewing phase one — "approve and proceed" — with no
change to either option (the Siphon is fixed rather than duplicated; Ricochet ROM bounces off any
terrain face). The request ("Update bosses to use ranged
attacks more often when the player is far away, and melee attacks more often when the player is
near. Add a weapon powerup that can steal life, a weapon powerup that causes projectiles to bounce
when coming into contact with the floor. Machine gun type weapons should shoot multiple projectiles
one after another in a straight line, rather than spreading by default. Enemies should briefly
flash red when they take damage. Even non-boss enemies should have health bars when below full
health. Implement using adversarial review") directs the implementation and the review once the
specification below is approved; the approval is recorded on this line before any test is written.

**Two findings the user should weigh before approving** (the spec takes the first option of each):

1. *Life steal already exists.* Red Market Siphon (T2, 2/3.5/4.5 %) is in the registry with an
   icon, but heals only through `applyHit` — swings, blasts, chains — while a **projectile
   landing bypasses it** (`advanceProjectiles` → `damageEnemy`), so every ranged and psychic
   projectile weapon steals nothing, and the 12 HP/s cap is not implemented. The spec makes the
   Siphon steal on every hit (PROD-073). **Alternative:** keep that fix and add a second, distinct
   powerup (e.g. life on kill) — say so and the registry grows to 19.
2. *Ricochet ROM is dead.* "Bounces at 85 %" resolves to `ResolvedWeapon.ricochets`, which nothing
   reads. The spec makes it the terrain-bounce powerup (PROD-074): reflect off a floor, ceiling
   or wall, 85 % damage per bounce, 1/2/3 bounces. **Alternative:** floor-only bounces (a wall
   still stops the shot) — say so; or a new entry and Ricochet ROM deleted.

Specs amended: `product.md` PROD-072..077; `enemies.md` "Choosing the next attack", P-40 digest
families, P-44; `combat.md` spread-vs-burst, SMG and Minigun rows, Siphon and Ricochet ROM rows,
life steal, bounce, caps, P-45, P-46, Known gaps; `presentation.md` hurt flash, health bars, P-47;
`plan.md` step 11.

#### RBH-1 — Boss attack choice by distance (PROD-072, P-44) — done

- [x] Red then green: `BossAttackChoiceTest` (5) — near 15–25 % ranged, far 75–85 %, melee cycle
      Slam/Sweep/Rush in order, phase one and the mini-boss never Volley, same seed same
      sequence. `LiveBoss(spec, arena, tiles, rng)` with `Rng.derive(seed, mapIndex, "boss")` /
      `"miniboss"`; `chooseAttack` + `rangedWeight`; `meleeIndex`/`rangedIndex` replace
      `attackIndex`; a one-kind phase draws nothing. `BossBehaviourTest` untouched and green.
      Digest: both indices and `rng.state`; `SimulationDeterminismTest` mutation cases.

#### RBH-2 — Life steal on every hit (PROD-073, P-45) — done

- [x] Red then green: `LifestealTest` (9): projectile, swing, blast, chain, Thermite splash heal
      the fraction; 4 HP per-hit cap (Kessler ×3 stacks); 12 HP per-second budget (one Kessler
      strike over six turrets); burn ticks heal nothing; never above max; nothing without the
      Siphon. `GameSimulation.stealLife` called from `damageEnemy` and `damageBoss` (the two
      places every hit passes; the old `applyHit` heal deleted so nothing heals twice);
      `lifestealBudget` refilled per tick, in the digest. Found on the way and recorded in
      combat.md Known gaps: a projectile landing applies neither crit, falloff nor Thermite's
      on-hit blast (pre-existing; not changed).

#### RBH-3 — Terrain bounce (PROD-074, P-45) — done

- [x] Red then green: `ProjectileBounceTest` (7): floor reverses `vy` keeping `vx`, 85 % damage,
      lifetime and pierce carried, spent on the contact after the last bounce with an impact;
      ×3 survives three; a wall reverses `vx`; a bounced shot hits a (stunned, airborne) Flyer on
      its way back; a Neural Spike orb and an enemy shot never bounce. `ResolvedWeapon.bounces`
      (was the dead `ricochets`), `LiveProjectile.bouncesLeft` and `damage` now `var`,
      `GameSimulation.bounce` (axis probes `(new.x, old.y)` / `(old.x, new.y)`; a corner reverses
      both), `BOUNCE_DAMAGE = 0.85`. Digest: `bouncesLeft`. Fixture lesson: a Turret placed in
      mid-air falls (only Flyers ignore terrain).

#### RBH-4 — Burst fire for machine guns (PROD-075, P-46) — done

- [x] Red then green: `WeaponRegistryTest` burst invariant (the SMG is the only burst weapon; it
      declares no spread and `0.05 × (3 + 2) < 0.35 × 0.75`; the Minigun no longer declares a
      bloom it never applied); `BurstFireTest` (6): one round on the trigger tick, the rest at the
      interval, parallel to the trigger aim while the player walks, each from the muzzle of its
      own tick, each with a fresh flash; the Shotgun still fans five; Fork Bomb makes four; a
      trigger with rounds pending drops them. `WeaponSpec.burstIntervalSeconds`, `PendingBurst`,
      `GameSimulation.advanceBurst`/`spawnRound`. Digest: the pending burst. The Minigun keeps its
      cooldown as its cadence (an interval on it could not fit its 0.042 s cooldown floor with
      Fork Bomb) — combat.md says so.

#### RBH-5 — Hurt flash and health bars (PROD-076, PROD-077, P-47) — done

- [x] Red then green: `HurtFlashTest` (4, sim): a hit starts the flash, it decays to zero inside
      the window, a burn tick does not flash, a hit boss flashes, the flash is outside the digest.
      `HurtFlashSceneTest` (4, render): every form's figure primitives move wholly to
      `Palettes.HURT` and the eye does not; a hurt boss flashes unless telegraphing; a 40 %
      enemy adds exactly a back rect of `ENEMY_SIZE × ZOOM` and a 40 % fill above its figure, a
      full one none; 600 half-hurt damaged enemies open the same batches as 10.
      `LiveEnemy.hurtSecondsLeft/maxHealth/healthFraction`, `LiveBoss.hurtSecondsLeft`,
      `Palettes.HURT`, `Scene.hurtOr`, `plating(style)`, `healthBar(fraction)`.
      **Measured:** an all-hurt crowd opens one batch *fewer* (the hover body and turret base
      rects merge), a mixed crowd up to fifteen more — a constant; `SceneTest`'s bound test now
      half-hurts its crowd and `MAX_BATCHES` is 120 (measured 105); presentation.md and P-47
      state the invariance as 10 vs 600 rather than "the same as untouched".

#### RBH-gate — `./scripts/check.sh`, then gate 4

- [x] `RoutePressureTest` / `BossPressureTest` / `ThreatScoreTest` re-measured under the new
      boss selection: every floor-covered map (1–3) still won on every seed; route pressure per
      map 6.1, 4.3, 8.1, 18.4, 25.5, 31.9, 32.4, 32.5, 32.6, 32.6 — thirds 6.19 → 25.26 → 32.54,
      rising; `ThreatScore` unchanged by construction (bosses excluded). No retune.
- [x] Check green on both targets (JVM, wasm browser tests, distribution) before gate 4; 498 JVM tests.
- [x] Gate 4, round 1 (codex gpt-5.6-sol, high, read-only; 8 findings). Dispositions:
  - MAJOR the digest hashed a burst payload as id + damage and a projectile payload without
    knockback, while spawning reads pierce, reach, homing, hitbox and bounces — **confirmed**.
    Fixed: `Digest.addPayload` encodes every resolved field for both; `SimulationDeterminismTest`
    compares a Damper payload against a bare one and a Ricochet burst against a bare one.
    Golden re-pinned (`7529129653326272212`).
  - MAJOR a Railgun (23 px/tick) can cross a 16 px wall between two endpoint samples, so it
    tunnelled before this change and would skip a bounce after it — **confirmed** (pre-existing
    for terrain stops; the bounce inherited it). Fixed: `GameSimulation.move` walks a step in
    pieces of at most half a tile (`MAX_PROJECTILE_STEP`); `ProjectileBounceTest` fires a 24
    px/tick shot 4 px short of a one-tile wall, stopped without a bounce and reflected with one;
    P-45 states the rule.
  - MODERATE "rolling per-second cap" vs a token bucket that lets 24 HP land inside a second —
    **confirmed** as a spec contradiction. Fixed in the spec: it is a 12 HP budget refilling at
    12 HP/s, by design; P-45 asserts ten seconds heal ≤ 132 (`LifestealTest`, map 10 so max
    health does not cap the fixture).
  - MODERATE the Minigun's Fork Bomb rounds leave together, against PROD-075's "one after
    another" — **confirmed** as a wording gap, code kept: an interval cannot fit inside the
    Minigun's 0.042 s cooldown floor (`0.35 × 0.12`) with three Fork stacks, and a round a tick
    later on the same line lands a tick later on the same line. PROD-075 restated: the rounds of
    one activation; a single-round weapon satisfies it by cadence; combat.md gives the reason.
  - MODERATE life stolen from attempted damage (overkill on a 1 HP enemy healed the cap) and
    the budget charged for healing the player could not receive — **confirmed**. Fixed:
    `damageEnemy`/`damageBoss` pass the damage actually taken; `stealLife` heals `min(…,
    missing)` and spends only that. `LifestealTest` overkill and 1-HP-short cases; the tests'
    "dealt" clamps enemy health at zero.
  - MODERATE the SMG's first follow-up round left a tick late (`0.05 − 3/60 = 7e-18 > 0`) —
    **confirmed**. Fixed: `BURST_EPSILON`; `BurstFireTest` asserts the exact per-tick counts
    `1 1 1 2 2 2 3 3`; P-46 names ticks 4 and 7.
  - MODERATE a boss's crown kept its glow through the flash — **confirmed**. Fixed:
    `crown(style)`; `HurtFlashSceneTest` counts glow-styled `ActorTrim` primitives falling to
    zero; presentation.md names the crown.
  - MODERATE P-44's "same sequence on both targets" compared two runs on one target —
    **confirmed**. Fixed: the first twelve choices of seed 9 are pinned as a literal in
    `BossAttackChoiceTest` (commonTest, so the browser runner is held to it too).
- [x] `./scripts/check.sh` green after round 1 (JVM, wasm, distribution).
- [x] Gate 4, round 2 (7 findings; the reviewer confirmed seven of round 1's eight fixes complete).
  Dispositions:
  - MAJOR a hit landing later in the tick than the blow that killed the player healed them back
    — **confirmed** (projectiles resolve in list order; `playerDied` is read after the tick).
    Fixed: `stealLife` returns on `run.dead`; `LifestealTest` lands an enemy shot then a player
    shot in one tick and asserts the death stands; combat.md says so.
  - MODERATE round 1's rate fix left PROD-073 and the caps paragraph saying "per second" beside a
    token bucket — **confirmed**. Fixed: PROD-073 and the caps paragraph name the budget; the
    field's comment no longer says "rolling".
  - MODERATE a trigger on the tick a pending round was due let that round leave first (reachable
    via Killstreak Cache) — **confirmed**. Fixed: the tick drops the pending burst when the
    trigger fires, before `advanceBurst`; `BurstFireTest` clears the cooldown so the trigger
    and the due round coincide and asserts two rounds, not three; P-46 restated.
  - MODERATE a spread weapon spanned `(n − 1)/n` of its declared angle (five pellets over 24°,
    two nails over 6°) — **confirmed**, pre-existing, made explicit by the amended spec. Fixed:
    the step is `spread / (n − 1)`; `BurstFireTest` measures the Shotgun's outer pellets at 30°;
    combat.md gives the five angles.
  - MODERATE the Railgun's and Minigun's wind-ups are declared, scored and never paid —
    **confirmed**, pre-existing; recorded in combat.md Known gaps, not changed (outside the
    request; the Minigun row's wind-up stays as the registry declares it).
  - MODERATE the 60-per-weapon cap is unimplemented and enemy `fire()` bypassed the 300 cap —
    **confirmed**, pre-existing. Fixed the bypass (`fire` withholds at the cap; `EnemyAttackTest`
    fills the scene and proves the turret's shot is withheld); the per-weapon cap is recorded as
    a Known gap, not implemented.
  - MINOR the pressure re-measurement item was still open — **confirmed**. Fixed: recorded above
    with the measured route thirds.
- [x] `./scripts/check.sh` green after round 2 (JVM, wasm, distribution).
- [x] Gate 4, round 3 (4 findings, none MAJOR; "no additional load-bearing implementation
  defect found"; every round-2 fix confirmed present). Dispositions:
  - MODERATE the Minigun's Fork Bomb rounds still leave together against PROD-075's "one after
    another" — **rejected**, with the evidence recorded at round 1: the Minigun's activation is
    one round and its cadence *is* the sequence; an interval on it cannot fit its 0.042 s
    cooldown floor with three Fork stacks (a 0.05 s burst would be discarded by the next
    trigger and lose the rounds); the request's "rather than spreading" is met — the extras
    leave along one line. PROD-075 states the single-round case explicitly. **The user may
    overrule**: the alternative is one Fork round per tick on the Minigun and a relaxed
    "spent before the next trigger" invariant.
  - MODERATE the hurt flash excludes burn and bleed ticks, narrowing "when they take damage" —
    **rejected**: the narrowing was in the phase-one spec the user approved, with its reason
    (a burning enemy would be red for three seconds and the flash would then mark nothing).
  - MINOR the registry row and `stealLife`'s comment still said "12 HP/s" / "rolling" —
    **confirmed**. Fixed: the row names the budget; the comment names the token bucket.
  - MINOR no life-steal case targeted the boss path — **confirmed**. Fixed: `LifestealTest`
    heals from a boss hit (capped) and from a 1 HP boss's overkill.
- [x] `./scripts/check.sh` green after round 3 (JVM, wasm, distribution).
- [ ] **Gate 4 closed at the plan's three-round cap.** Round 3 returned nothing load-bearing
      in the implementation; its two MODERATE findings are rejected design decisions the user
      may overrule (above). Per ENG-072 a fourth round is the user's call.

### LOOK — Aged materials, kind rings, hovering drops, shot looks (plan step 12)

**Request (verbatim):** "add a red circle around weapon drops, and a blue circle around weapon
powerup drops, but otherwise apply a dystonian color scheme to the rest of the weapon, i.e. woods
are brown, metals are silver/grey, all the weapons should look "aged" or "rusty" in keeping with
the cyberpunk dystopian theme. When either a weapon or weapon powerup drops, the drop should hover
up and down slightly in the air, rather than remaining static in place. When a weapon is picked up
and wielded, the red circle should not be included as the player holds the weapon. Give
projectiles more detail and color. Ask the user any clarifying questions if needed. Implement
using adversarial review."

**Phase one:** PROD-050 restated, PROD-078..080 and PROD-051 amended in `product.md`;
`presentation.md` Item icons (materials, weathering, kind ring, hover), Weapon effects (shot
looks), P-29..P-31 restated, P-50..P-53 added; `iconography.md` material rule.

**Implementation approval:** given by the user after reviewing phase one — "defaults are fine,
approved, implement it" — so the four assumptions below stand as specified.

Open questions for the user (assumptions taken in the spec, each reversible):
1. The HUD draws **no** kind ring (the equipped weapon and the powerup column are already told
   apart by position and casing). Alternative: a small ring in the HUD too.
2. Powerups take the **same** material treatment as weapons (steel casing with rust, glass
   vials, energy coils). Alternative: powerups keep a single flat colour.
3. Enemy and boss shots **do** get the four-mark look, in the palette hazard colours with a
   white core. Alternative: only the player's shots change.
4. Hover amplitude 4 px over 1.8 s, phased by x so drops are out of step. Numbers are a guess
   until seen; the sheet does not show motion, so this one is judged in the running game.

Sub-steps, one owner, each red then green (`./gradlew jvmTest --tests` on the named class), then
`./scripts/check.sh`, then gate 5 per ENG-070..073, up to three rounds:

- [x] LOOK-1 **Materials.** *(done: `IconTest` 3 red → green, `IconRegistryTest` materials red → green; sheet regenerated and looked at — grips brown, blades grey with rust streaks, energy gold, glass teal.)* `Material` enum and colours in `IconStyle.kt`; `IconOp.Stroke` /
  `IconOp.Dot` gain `material = Steel`; `IconSink` carries it; `IconBatchSink` draws the
  weathering streak (`IconStyles.streakOf`, `StrokeWeight.lighter`). Red: `IconTest` — a `Steel`
  stroke placed emits a rust streak at 55–95 % one weight lighter; a `Glass` stroke emits none;
  `IconRegistryTest` — every material used, every op's material a member. Then author the
  materials across `WeaponIcons` and `PowerupIcons` by the rule in `iconography.md`, regenerate
  the icon sheet (`IconSheetTest`) and look at it.
- [x] LOOK-2 **Kind ring, not in the hand.** *(done: `PickupIconTest` ring case red → green; `HeldWeaponTest`/`HudIconTest` no-ring cases green; world frame looked at.)* `Scene.pickup` draws the ring over its halo via a
  `ring` on `ItemHalo`/`Items` (the `Effects` ring helper gains a layer parameter);
  `IconStyles.ringOf(weapon)` replaces `outlineOf`. Red: `PickupIconTest` — a weapon drop's
  frame holds red segments on `Items` tracing a circle of `KIND_RING × scale`, a powerup's blue;
  `HeldWeaponTest` — no red segment anywhere in the actor layers; `HudIconTest` — no ring colour
  in `Hud`/`HudOverlay`. P-30 rerun over the material and ring colours.
- [x] LOOK-3 **Hover.** *(done: hover case red → green.)* `Scene.pickups` takes `timeSeconds`; `Scene.hoverOffset(t, x)`.
  Red: `PickupIconTest` — origin at `t = HOVER_PERIOD / 4` differs from `t = 0` by `HOVER_PX`,
  returns at `t = HOVER_PERIOD`, pips and ring move with it; `GameSimulation` untouched, so P-40
  and the pickup tests are the proof the overlap did not move.
- [x] LOOK-4 **Shot looks.** *(done: 5 `SceneTest` cases red → green; `HitShape.Impact.psychic` added so an impact keeps its look.)* `ShotLook(glow, body, core)` and `ShotLooks.of(shot, palette)` in
  `commonMain/render`; `Scene.projectiles` and the Volley/impact paths draw four marks through
  one `shotMarks` function. Red: `SceneTest` — a ranged build's shot draws the three dots at the
  three radii in the ranged colours and a bloom-under-core tracer; a psychic build's in violet;
  an enemy's with `palette.hazard` body and white core; fifty shots open no more `Effects`
  batches than three. `PickupIconTest`'s disjointness case gains the shot colours.
- [x] LOOK-5 `./scripts/check.sh` green (JVM, wasm, distribution, smoke). First run: only
  `titleScreenSmokeTest` failed — 34 strokes per frame over a cap of 30 set when a drop was one
  colour; cap raised to 48 with its reasoning (P-23 amended); second run green.
- [x] Gate 5, round 1 (9 findings, 1 MAJOR; "Load-bearing findings: 6"). Dispositions:
  - MAJOR weathering not reliably over its material: a Street `Line` opens a streak batch a
    rarer drop reuses while the rarer drop's wider material batch opens later and paints over
    its own rust — **confirmed** (batches paint in first-open order within a layer). Fixed
    structurally: `ItemWear`, `ActorWear`, `HudWear` layers; `IconPainter.paint` takes a
    `wearLayer` and requires it above the material layer; mixed-tier test in `PickupIconTest`.
  - MODERATE PROD-078 said every metal/wooden *part* streaks while dots were exempt —
    **confirmed**. Fixed: PROD-078 says "stroke wide enough to carry one".
  - MODERATE a `Hair` streak snaps to the ladder floor (1.5 px) and is as wide as its line —
    **confirmed**. Fixed: a `Hair` has no streak; `streakWidthOf` returns null; spec and
    `IconTest` say so.
  - MODERATE shot paint order breaks across impacts of different ages (a fresher impact's wider
    tracer batch opens after an older one's dots) — **confirmed**. Fixed structurally:
    `ShotGlow`, `ShotBody`, `ShotCore` layers under `Effects`; test with two impacts four ticks
    apart.
  - MODERATE the browser passes tick time, so the hover steps while the player interpolates —
    **confirmed** (`GameHost.draw` passes `elapsedTicks × TICK_SECONDS` and `alpha`). Fixed:
    `Scene.presentationTime(t, alpha)` feeds the hover; spec restated; `alpha = 0.5` test.
  - MINOR the pulse/beam/blast rings went from 12 to 16 chords — **confirmed**, unintended.
    Fixed: `PULSE_SEGMENTS = 12` restored; `KIND_RING_SEGMENTS = 16` for drops only.
  - MODERATE no test that a psychic impact records the flag and stays violet — **confirmed**.
    Fixed: `SceneTest` case.
  - MINOR the ring's halo was not asserted — **confirmed**. Fixed: sixteen halo chords at the
    ring radius on `ItemHalo`.
  - MINOR the powerup half of a paired drop was untested — **confirmed**. Fixed: paired-drop
    case (both hover, peak apart, neither item moves).
- [x] `./scripts/check.sh` green after round 1 (JVM, wasm, distribution, smoke).
- [x] Gate 5, round 2 (7 findings, no MAJOR; "Load-bearing findings: 3"; every round-1 fix
  confirmed present). Dispositions:
  - MODERATE the shot-looks paragraph still said every mark is on `Effects` — **confirmed**.
    Fixed: names the three shot layers.
  - MODERATE Migraine Loop and Chill Protocol were pure energy/glass with nothing to age,
    against the request's "all the weapons" — **confirmed**. Fixed: PROD-078 and P-50 require a
    wear cue on every item; the Loop's emitter ring is `Rust`, the Protocol's spars `Steel`, the
    Broken Bottle's neck wrap `Rust` (the only cue the registry test then found missing);
    `IconRegistryTest` asserts the cue on all forty-four.
  - MINOR plan.md step 12 still said "awaiting approval" — **confirmed**. Fixed.
  - MODERATE a `Line` streak on a small held weapon (Chrome Fang, ≈11.5 px) snaps to the same
    1.5 px as its line — **confirmed**. Fixed: a streak is drawn only where its snapped width is
    strictly under its stroke's (`streakWidthOf` returns null otherwise); `IconTest` covers the
    held scale and every weight × scale; spec restated.
  - MINOR P-31's "three widths per weight" is false (Slab: 3.5, 4.5, 6, 6, 8) — **confirmed**.
    Fixed: the bound is derived in the test from the ladder over the five tiers; prose says four.
  - MINOR the psychic-impact test did not prove same-tick — **confirmed**. Fixed: target on the
    muzzle (the player's centre), asserts the projectile list is empty after the firing tick.
  - MINOR the mixed-tier test dropped steel rust because it is also `Rust`'s colour —
    **confirmed**. Fixed: counts streaks on the wear layer per material against the icons'
    weathered strokes.
- [x] `./scripts/check.sh` green after round 2 (JVM, wasm, distribution, smoke).
- [x] Gate 5, round 3 (3 findings, no MAJOR; "Load-bearing findings: 1"; every round-2 fix
  confirmed present). Dispositions:
  - MINOR the weathering paragraph still said "every stroke … at every scale" beside the
    exemptions — **confirmed**. Fixed: "every eligible stroke … wherever a streak fits".
  - MODERATE Overclock Coil, Chill Protocol and Voice of the Dead Net had only `Line` metal and
    no `Rust`, so at the HUD's 8 px scale (where a `Line` streak no longer fits) they had no
    wear cue — **confirmed**. Fixed: each gets a corroded part (the Coil's leads, the Protocol's
    level spar, the mask's jaw); `Scene.HUD_ICON` is public and `IconRegistryTest` holds the
    wear-cue rule at the HUD scale and the ground scale; PROD-078/P-50 say so.
  - MINOR `Icon.kt` and the test's comment still said three widths per weight — **confirmed**.
    Fixed: "at most four".
- [x] `./scripts/check.sh` green after round 3 (JVM, wasm, distribution, smoke).
- [ ] **Gate 5 closed at the plan's three-round cap.** Round 3 returned one load-bearing finding,
  fixed; per ENG-072 a fourth round is the user's call. (`codex exec --model gpt-5.6-sol
  -c model_reasoning_effort=high --sandbox read-only`, brief on stdin, pid recorded, closed
  with `close-agents.sh`); findings dispositioned here; spec corrected where a finding is
  confirmed; `./scripts/check.sh` after every fix round.

### SWING — Hitbox-faithful player melee swooshes

**Request (verbatim):** "The visual animation when swinging a melee weapon should be an accurate
representation of it's hitbox. For example, a player should not see the \"swoosh\" animation
overlap an enemy, and not actually hit that enemy."

**Phase one:** complete. PROD-033 and PROD-066 now require a player's `ArcSwing` visual and direct
hit test to use one live swept region; `combat.md` defines its geometry, timing, target combat
bodies, multi-target semantics and digest ownership (P-63); `presentation.md` defines the closed
fan and binds the arm pose and damaging body silhouettes to that same geometry; `simulation.md`
and `enemies.md` include the newly rule-bearing active swing in P-40 while retaining enemy attack
effects as presentation-only state.

**Implementation approval:** not yet given. Stop after phase one and wait for the user to review
these defaults and explicitly approve implementation.

Defaults taken in the specification, each reversible during review:

1. Scope is the player's eight `ArcSwing` weapons. Meatgrinder Halo's ring and enemy/boss attacks
   retain their separately specified behavior.
2. The swoosh is a live, cumulative sector over the pattern's existing 0.10 s `lingerSeconds`, not
   a harmless 0.16 s afterimage. Its direction locks at activation and its origin follows the
   moving player, so the effect stays attached without drifting away from its hitbox.
3. Enemies and bosses expose circular combat bodies that contain their damaging drawn body, while
   excluding glow, bars, held equipment and attack effects. Boundary contact counts as overlap.
4. Every eligible body overlapped by the swoosh takes one direct hit per activation. Player
   `ArcSwing`s therefore have no invisible pierce/target cap; Spike Driver remains useful to
   projectile patterns but has no direct effect on these swings.
5. The existing nested-arc identity remains, enclosed by radial edges/ribs so the complete active
   fan is readable. The actor's arm uses the weapon's actual arc and progress instead of the
   current generic 150° sweep.

After approval, complete these in order. Each behavior item starts with the smallest named test,
records its expected red failure here, makes the smallest production change, then records the
focused green run; no later item starts while an earlier one is red.

- [ ] **SWING-1 — Shared sector and combat-body geometry (PROD-033, P-63).** Add
      `MeleeSectorTest` first for inside, radial tangency, both angular tangencies and epsilon-outside
      cases, including a body whose centre is outside but radius overlaps. Introduce immutable
      commonMain sector/body geometry and canonical enemy/boss combat radii; add render-envelope
      cases proving every damaging body primitive is contained without making simulation depend on
      `render`.
- [ ] **SWING-2 — Gameplay-owned active swings (PROD-033, P-63).** Add `MeleeSwingTest` red-first
      for locked aim, moving origin, monotone progress over `lingerSeconds`, movement-before-hit
      ordering, scaled reach, later entry, early exit, once-per-target damage, three simultaneous
      direct hits and Spike Driver neutrality. Replace the instant presentation-only player swing
      with future-affecting active state; retain the triggering build for all hit consequences and
      keep Halo, enemy and boss paths unchanged.
- [ ] **SWING-3 — Faithful fan and pose (PROD-066, P-38, P-63).** Add
      `MeleeSwooshSceneTest` red-first at opening, midpoint and final progress for the exact origin,
      angular interval and reach, strokes contained by the sector, constant batches, no post-window
      effect, and the held arm/weapon at the shared leading angle. Draw the closed fan from active
      state and remove the renderer's independent 150° player-swing geometry. Generate a compact
      boundary/entry frame sheet and inspect it before recording green.
- [ ] **SWING-4 — Determinism and full gate (P-40, P-63).** Extend
      `SimulationDeterminismTest` mutation coverage to active geometry, progress and hit identities,
      then re-pin the cross-target golden only after all new future state is covered. Run the
      focused combat/render suites followed by `./scripts/check.sh` and `git diff --check`; record
      the exact red/green and full-gate evidence here.

### DROP — Jump-required enemy-death loot

**Request (verbatim):** "Update the height where the weapons and weapon/powerups drop after an
enemy dies so that a player must jump in order to collect them. For example, a player simply
running on the group would not accidentally collect a powerup after killing an enemy"

**Phase one:** complete. PROD-090 distinguishes death-created loot from existing walk-over map
pickups; `combat.md` fixes the resting height at two tiles above safe support, defines the unchanged
one-tile contact radius, deterministic safe-site fallback and real-jump invariant (P-64);
`presentation.md` keeps hover centred on that raised simulation position; `simulation.md` binds
reachability to the shipping integrator; and `enemies.md` requires loot-floor harnesses to collect
guaranteed awards through the same jump/contact path as a player.

**Implementation approval:** given by the user on 2026-08-30 after reviewing phase one. Phase two
may proceed through the red-green tasks below.

Defaults taken in the specification, each reversible during review:

1. Scope is every item created by a rank-and-file, mini-boss or main-boss death. Statically placed
   map pickups and map one's starter cache keep their current positions and walk-over collection.
2. A death drop rests exactly two tiles (32 px) above safe support. The existing strict one-tile
   radial pickup reach is unchanged; the default standing centre is therefore 19 px below the
   item's centre, while the measured 90.67 px normal jump reaches it comfortably.
3. The item preserves the slain actor's x when that produces a valid jump-only site. An airborne,
   over-hazard, low-ceiling or walk-collectable projection falls back to the nearest safe site in a
   fixed horizontal-distance, vertical-distance, column, row order. Placement consumes no RNG and
   guaranteed loot is never discarded.
4. Both icons of a paired weapon/powerup award rest at the raised y, one tile apart; jumping into
   either still resolves the pair weapon-first. Height is assigned immediately, with no new falling
   animation or item physics, and the existing visual hover remains collision-neutral.
5. Drop rate, split, rarity, contents, Scrap and discovery semantics remain unchanged. Reference
   harnesses may pin guaranteed contents but must perform the real collecting jump rather than
   injecting a loadout.

After approval, complete these in order. Each behavior item starts with the smallest named test,
records its expected red failure here, makes the smallest production change, then records the
focused green run; no later item starts while an earlier one is red.

- [x] **DROP-1 — Pure safe-site geometry (PROD-090, P-64).** Add `DeathDropPlacementTest`
      red-first for the exact two-tile rise, preserved death x, paired-icon clearance, grounded
      standing/crouching exclusion and a collecting jump stepped through `MovementModel`. Cover an
      adjacent raised surface, low ceiling, sealed nearer platform, lethal gap, airborne Flyer and
      equal-distance tie. Introduce one immutable commonMain death-drop site selector with a
      bounded, stable candidate order and no dependency on rendering or RNG.
      - Red: `./gradlew jvmTest --tests io.github.ksean.cyberslop.sim.DeathDropPlacementTest`
        failed at test compilation because `DeathDropPlacement` did not exist, as expected.
      - Green: the same focused command passes all five placement fixtures after adding the
        immutable selector and its bounded, stable fallback search.
- [x] **DROP-2 — Wire every death source and contact path (PROD-030, PROD-090, P-42, P-64).** Add
      `DeathDropPickupTest` red-first for rank-and-file weapon and powerup outcomes: running under
      each rest point does not collect, while a normal jump does. Extend `BossAwardTest` for mini-
      and main-boss paired awards, including a boss killed during a leap. Route all three death
      paths through the selector while leaving generic `GroundItem` contact and weapon-first pair
      resolution unchanged.
      - Red: `./gradlew jvmTest --tests io.github.ksean.cyberslop.sim.DeathDropPickupTest`
        failed both weapon and powerup cases because the grounded approach collected the item.
        `./gradlew jvmTest --tests io.github.ksean.cyberslop.sim.BossAwardTest` likewise failed
        the raised paired-award and airborne-pit cases while awards still used boss centres.
      - Green: one focused JVM run of `DeathDropPickupTest` and `BossAwardTest` passes all five
        cases after routing rank-and-file, mini-boss and main-boss deaths through the selector.
- [x] **DROP-3 — Non-death regressions, harness and full gate (P-25, P-40, P-52, P-64).** Add
      grounded-collection regressions for generated static drops and the starter cache; update
      `PressureHarnessTest` so its reference player jumps into a pinned award before continuing;
      cover the raised mean origin in `PickupIconTest`; and re-pin the determinism golden only after
      seeded occurrence/content/RNG comparisons show position is the sole loot-stream change. Run
      the focused simulation, physics, generation and render suites, then `./scripts/check.sh` and
      `git diff --check`, recording exact red/green and full-gate evidence here.
      - Red: `./gradlew jvmTest --tests io.github.ksean.cyberslop.sim.PressureHarnessTest`
        failed because its old fixture still expected collection on the mini-boss death tick.
        `./gradlew jvmTest --tests io.github.ksean.cyberslop.sim.SimulationDeterminismTest`
        then failed only the committed golden: expected `10020045215349456527`, actual
        `17077257187548672098`. The first full gate additionally exposed a pressure-harness route
        resuming on the first airborne contact tick; the strengthened harness fixture failed until
        the collecting jump finished and physically rejoined the witness route.
      - Green: the focused death-drop, boss-award, pressure-cohort, determinism, pickup, physics,
        generation and render JVM matrix passes. `./scripts/check.sh` then passes the complete JVM
        and Wasm browser suites, production distribution and smoke test in 7m15s; `git diff --check`
        passes.

## Deferred

Not scheduled by the current plan; kept so they are not forgotten.

- Human playtest of a full run with a written rubric (fairness, telegraph readability, camera).
- Sound effects: kotlinx-browser exposes no Web Audio API, so this needs hand-written externals.
- Recalibrate `WeaponScore` against `expectedDps` (see `specs/combat.md`, Known gaps).
- A committed, reproducible frame-time benchmark (the 7.6× transform figure is unretained).
- Draw projectiles as their weapon's own shape (a slug, a nail, a grenade) — the tracer in CPS-3 gives them a line of flight and LOOK-4 a lit body, not a silhouette.
- Pass-two styling: grime, scanlines, screen shake, hit flashes, particles.
