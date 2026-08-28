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

### DIFF-5 — Hazards

- [ ] Red: `HazardDamageTest` — overlapping spikes, a barrel's body or its flame drains at the
      rate; one tick does not kill; two hazards drain both; no displacement.
- [ ] Red: `HazardPlacementTest` (jvmTest) — every footprint cell ≥ 2 from footholds and pickups,
      outside `ArcMask` and arenas; confirming replay reports zero contact on every cohort map; a
      fault-injected spike and barrel on the route are removed and nothing else is; count zero on
      map 1 and rising in cohort mean; `WitnessReplayTest` — hazards cannot change where the tape goes.
- [ ] Red: `SceneTest` — spikes and barrels draw on `Layer.Hazard`; `PickupIconTest` P-30 green.
- [ ] Implement `TileKind.Spikes`, `Barrel`, `hazardDamage()`, `HazardPlacer`, drawing, the
      `damagingHazardsPerHundredTiles` curve row.

### DIFF-6 — Player weapons and crouch

- [x] 6a (sub-agent): `WeaponRegistryTest` P-37 red ("Broken Bottle reaches only 25.6 px";
      "Street melee mean 6.43 not above ranged mean 10.09") then green; registry rebalanced; P-14
      green; one fixture in `MechanicsTest` re-anchored by the main agent.
- [ ] 6b: red `ActorTest` — crouch limb lengths equal standing, knees forward of the hip–ankle
      line, highest point within the crouch height; red `SceneTest` — a player swing's swoosh outer
      radius equals the resolved reach (Ranger Optics included); a player shot's cue sits at the
      held weapon; the Kessler draws an activation pulse; implement.

### DIFF-7 — Contract gaps

- [ ] Red: a main-boss powerup award is never below T2 over many seeds; implement a tier floor in
      `DropTable.rollPowerup`.
- [ ] Red: every weapon × powerup × stack count resolves finite and positive (P-15).

### DIFF-8 — Determinism digest

- [ ] Red: `SimulationDeterminismTest` (commonTest, under the browser timeout) — the digest after
      N ticks matches a committed golden; a mutation in each state family changes it.
- [ ] Implement `GameSimulation.digest()` per `specs/enemies.md` P-40.

### DIFF-9 — Measure, tune, review

- [ ] `ThreatScore` + cohort test; `RoutePressureTest` (thirds rising, all maps);
      `BossPressureTest` (floor-covered maps won on every cohort seed).
- [ ] Tune; `./scripts/check.sh` green; review gate 2; disposition every finding.

## Deferred

Not scheduled by the current plan; kept so they are not forgotten.

- Human playtest of a full run with a written rubric (fairness, telegraph readability, camera).
- Sound effects: kotlinx-browser exposes no Web Audio API, so this needs hand-written externals.
- Recalibrate `WeaponScore` against `expectedDps` (see `specs/combat.md`, Known gaps).
- A committed, reproducible frame-time benchmark (the 7.6× transform figure is unretained).
- Draw projectiles as their weapon rather than as one dot.
- Pass-two styling: grime, scanlines, screen shake, hit flashes, particles.
