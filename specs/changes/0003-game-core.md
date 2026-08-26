# Change 0003: Game core

- **Status:** In implementation
- **Implementation approval:** Approved by the user on 2026-08-25
- **Created:** 2026-08-26

## Intent

Turn Cyberslop from a title screen into the game described in [plan.md](../../plan.md): a ten-map
cyberpunk-dystopian side-scrolling roguelite with procedurally generated, provably completable maps,
automatic cursor-aimed weapons, and a weapon/powerup economy.

`plan.md` is the research record and is **not normative**. This change record and the requirements it
amends are.

## Requirements added

[PROD-020..032](../product.md) define gameplay, completability, content volume, rarity, pickup
behaviour and run structure. [ENG-050..056](../engineering.md) define simulation purity, the movement
model's authority, determinism, and where verification runs.

## Requirements amended, and why

- **PROD-004** required all player-facing controls to be keyboard-operable. The product brief mandates
  cursor-directed aiming, so the original wording is unsatisfiable. It is narrowed to non-gameplay
  controls, and **PROD-022** adds an Auto-aim setting so a complete run remains playable without a
  pointing device. Narrowing without that addition would have removed a real accessibility guarantee.
- **PROD-011** deferred start/continue behaviour to a later specification. This is that specification.
- **ENG-001** required the `wasmJs` target. A `jvm()` target is added **for verification only** —
  common-source tests otherwise run exclusively in the browser runner, whose per-test timeout is
  2000 ms, which the map-generation seed sweeps cannot fit inside. No artifact ships from it.
- **ENG-013** deferred rendering technology. Measurement (recorded in plan.md §8.1) shows the
  browser's own Canvas 2D context draws 600 sprites in 0.46 ms against a 16.67 ms budget on software
  rasterization. No framework or engine is added.
- **ENG-031** is clarified: a test in the common source set executes on *every* declared target, so
  target-specific test source sets are the mechanism for work that cannot fit the browser runner.

## Requirements superseded

- **TITLE-005** (change 0001) made title-button activation a deliberate placeholder, with a test
  asserting the screen does not change. `New game` now starts a run and `Continue game` resumes one,
  so that requirement and its test are replaced rather than deleted.
- **TITLE-006** assigned save-format ownership to a future persistence specification. That ownership
  transfers here: the save carries an explicit format version and a migration path, and the title
  screen continues to consume availability through the existing boundary.

## The completability guarantee

**PROD-024** is the load-bearing requirement and is discharged mechanically, not by inspection:

1. The generator lays a spine of standable rest positions joined by moves whose distances come from
   the movement model's measured envelope. Spine geometry and the swept volume of every move are
   written to immutable masks that decoration may not overwrite.
2. Because every move is chosen from that envelope, the generator emits the witness as it carves.
3. The witness is replayed through the shipping simulation. A map whose witness fails replay is never
   presented (**ENG-056**).

The guarantee covers traversal of static geometry and timed hazards to the boss arena. It does not
cover enemy behaviour or killing the boss; those are covered by separate invariants and by a
loot-floor property asserting that a player taking only guaranteed drops clears every encounter
inside its time band.

## Acceptance examples

1. Given any seed and map index, the generated map is returned with a witness whose replay through
   the game's own movement model transits the mini-boss arena and reaches the boss arena at full
   health.
2. Given a generated map, no cell the player can reach is one the player cannot leave.
3. Given a new run, the player holds a broken bottle which swings every two seconds, aimed at the
   cursor, with no attack input bound.
4. Given the player walks over a weapon that scores higher than the held one against the reference
   target, it is equipped and the previous weapon becomes Scrap; a lower-scoring weapon becomes Scrap
   on contact without changing the loadout.
5. Given five distinct powerups are held, walking over a sixth converts it to Scrap; walking over a
   held powerup already at stack three converts it to Scrap; otherwise it is applied.
6. Given the main boss is alive, the map exit is closed; on its death the exit opens.
7. Given the player crosses the boss arena's commit line, the gate closes and the boss becomes
   vulnerable. Before that line the boss cannot be damaged, including by automatic fire.
8. Given the player dies, the run ends, the in-progress save is cleared, and `Continue game` is not
   offered.
9. Given Auto-aim is enabled, a complete run is playable using only the four arrow keys.
10. Given any map index, drop weight is strictly decreasing in rarity tier.

## Out of scope

Final art and animation, music, narrative, leaderboards, and any networked feature. Audio is limited
to the sound effects scheduled in the final milestone.

## Implementation

Sequenced as milestones M1–M8 in [plan.md §10](../../plan.md), each with its own `tasks.md` entries.
Adversarial review gates follow M2, M3, M6, M7 and M8.
