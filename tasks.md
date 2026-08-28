# Tasks

Open implementation work, one entry per step of [`plan.md`](plan.md). An entry is deleted when its
step is done; a finding from adversarial review is recorded under the entry it concerns until it is
dispositioned. Anything worth keeping moves into `specs/`.

**Implementation approval for the difficulty plan:** given by the user in the request that asked
for the plan — "assume that the specification, requirements, and tasks do not need to be approved
separately … build an entirely new research and development plan (and then subsequently execute
that plan)" — with adversarial review directed for both the plan and the implementation.

## Open

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
(`7460885450017593737`, re-pinned after DIFF-9's tuning) and a mutation in each family — player/run,
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

## Deferred

Not scheduled by the current plan; kept so they are not forgotten.

- Human playtest of a full run with a written rubric (fairness, telegraph readability, camera).
- Sound effects: kotlinx-browser exposes no Web Audio API, so this needs hand-written externals.
- Recalibrate `WeaponScore` against `expectedDps` (see `specs/combat.md`, Known gaps).
- A committed, reproducible frame-time benchmark (the 7.6× transform figure is unretained).
- Draw projectiles as their weapon rather than as one dot.
- Pass-two styling: grime, scanlines, screen shake, hit flashes, particles.
