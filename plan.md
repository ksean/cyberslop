# Research & Development Plan

A living document: one goal, the steps that reach it, and who does each. Requirements live in
[`specs/`](specs/README.md) and never here; anything learned that stays true is moved into a
specification, and a step is deleted once it is done. Work items and their status are in
[`tasks.md`](tasks.md).

## Goal

**Make Cyberslop's difficulty come from its enemies and its maps, and make every threat and every
attack legible.** Today the maps are hard and the population is harmless: enemies pace a three-tile
patrol and never act on the player, bosses stand inert until the player walks past a line, two
hazards exist and both are instant death, and the player's melee weapons out-reach nothing. The
goal is reached when a rank-and-file enemy that has noticed the player hunts or harasses them, a
boss fights from the moment it notices the player, maps carry visible non-lethal hazards, melee is
the high-risk high-reward class, each of those actions is animated — and the machine checks in
`specs/` are green with a bot playthrough showing pressure rising across the ten maps.

## Design decisions

Settled before implementation; the specification amendments say the same thing normatively.

1. **Awareness radius, not screen space.** The simulation cannot see the screen, so "visible" is
   a Euclidean awareness radius of 22 tiles (the auto-aim range) with hysteresis at 28 tiles
   (tuned down from 33 in step 9 so an outrun pack drops off before the boss). An
   engaged enemy is free of its patrol span; an unengaged one patrols as today. *(enemies.md)*
2. **Engaged enemies act by role.** Melee archetypes pursue; a Shooter approaches to firing range,
   holds, and retreats inside five tiles; a Turret is fixed. Walkers gain gravity and a ledge rule
   for voluntary steps — they never step off an edge or onto a lethal tile, and never jump, because
   an enemy in acid is a free kill and an enemy that jumps is a second movement model. A Flyer
   pursues in both axes but never enters a committed column. Nothing moves faster than the player
   runs, asserted.
3. **Melee enemies swing, and their bodies hurt to touch.** The telegraphed swing with its own
   cooldown, reach and 90° arc is the attack; a per-second contact drain (decision 9) sits under
   it; Shooters wind up, fire faster shots more often. **Fairness on committed spans** becomes a runtime rule: no enemy damage lands while
   the player's box overlaps a committed column, nor within a quarter-second landing grace after
   one, so free movement cannot invalidate the route-safety argument that spawn placement used to
   carry alone.
4. **Bosses activate on awareness and pursue, and dodges become mechanics.** An engaged boss or
   mini-boss moves, attacks and is vulnerable wherever the player stands, and may leave its arena
   under the ledge rule. The commit line goes: the exit gate is a solid column carved with the map
   that only the boss's death clears — which is what the code has always done — so nothing can seal
   a player in. Each attack gets a hit condition its listed dodge defeats (Slam and Rush miss an
   airborne player, Sweep misses a crouched one, Volley misses a player who moved off the aimed
   x), tested both ways. The camera never frames an arena; Volley's reach is capped at eight tiles.
5. **Damaging hazards are placed last and confirmed by replay.** Spike strips, broken glass and
   burning barrels hurt per second and are survivable; their whole footprint keeps two tiles from
   every witness foothold and every static pickup, stays out of the `ArcMask` and arenas, and lies
   strictly left of the boss gate; a confirming replay removes any hazard the tape still touches.
   Density follows the shared curve from map 2.
6. **Melee out-reaches and out-damages ranged.** Every melee weapon reaches ≥ 2 m, beyond any
   enemy swing; per tier, melee mean DPS (bottle excluded) exceeds ranged mean DPS. The later
   class-wide 1.5× melee increase permits adjacent tier bands to overlap while tier minimum, mean
   and maximum DPS remain increasing.
7. **A crouch is a pose, not a scale.** Limb lengths preserved, joints bent, within the crouch box.
8. **Difficulty is measured.** `ThreatScore` over the generated population and hazards; a
   route-pressure harness over all ten maps (witness tape with the population acting, gross
   incoming damage per hundred tiles, asserted rising by thirds); and a boss-pressure harness on
   the floor-covered maps that must win. A whole-simulation digest (P-40) over every
   future-affecting field joins the determinism check, built after hazards so its golden is cut
   once.
9. **Touch hurts, a weapon is a build, a shot shows where it went.** (a) A living enemy's body
   drains `1.0 × contactDamage` per second of overlap, like a hazard, under the fairness rule; not
   bosses. (b) A different weapon pickup equips: the old weapon and every powerup slot convert to
   Scrap; another copy of the held weapon converts to its tier's Scrap value and preserves the
   build. A paired boss award applies weapon then powerup. The loot floor is derived against that
   policy — its guaranteed weapon sequence always changes ids, so the arriving loadout is the last
   guaranteed weapon plus the powerups awarded after it — and the covered-map count is measured,
   not assumed. (c) Projectiles draw a body and tracer;
   instant patterns leave a hit indicator (beam, chain, ring) whose geometry is the hit test's.
   *(product.md PROD-069..071; enemies.md Contact, the loot floor; combat.md Weapon pickup;
   presentation.md Weapon effects; P-41..P-43.)*

## Steps

Each step is one `tasks.md` entry with its red tests named. Every step but 6a touches
`GameSimulation`, `Entities`, `Scene` or `Actor` and runs **sequentially** under one owner; 6a was
the only step disjoint enough to run in parallel, and its scope was `Weapons.kt`, the registry test
and the loot-floor expectations only.

1. **Specify and review.** *(done — three rounds)* Amend the specs with decisions 1–8;
   adversarial review of the plan; disposition findings; repeat until a round returns nothing
   load-bearing.
2. **Awareness and movement.** *(done)* `LiveEnemy.engaged`; pursuit, approach/hold/retreat;
   walker gravity and the ledge rule; committed columns on `Level`; Flyer boundary; speed cap.
3. **Attacks and their animation.** *(done)* Telegraphed swings with an arc replacing the aura; shot
   wind-up and cadence; committed-span fairness with the landing grace; per-enemy wind-up/swing/
   shot windows; `Action.WindUp`; enemy swoosh and muzzle flash in `Scene`; the swoosh and flash
   geometry the player will share.
4. **Bosses.** *(done)* Awareness-driven activation; commit line removed; pursuit under the ledge rule;
   attack hit conditions and dodges; Volley reach cap; boss gait; attack poses and effects;
   `Camera.framing` deleted.
5. **Hazards.** *(done)* `Spikes` tile kind, `Barrel` level object, `hazardDamage()`, placement, the
   confirming replay with fault injection, drawing, curve row, P-30 still green.
6. **Player weapons and crouch.**
   - 6a *(sub-agent — done)*: melee reach and damage rebalance in `Weapons.kt`; P-37.
   - 6b *(done)*: crouch pose in `Actor.kt`; the player's swoosh and firing cue (flash, or the
     Kessler pulse) through step 3's shared geometry, test-first in `SceneTest`.
7. **Small contract gaps the review found.** *(done)* Boss powerup award floor (T2) in `DropTable`; the
   P-15 weapon × powerup × stack resolution test.
8. **Determinism digest.** *(done)* `GameSimulation.digest()` over every future-affecting field, a golden
   in `commonTest`, and a mutation test per state family.
9. **Measure and tune.** *(done; gate 2 ran its three rounds — see tasks.md)* `ThreatScore`, route pressure, boss pressure, P-39; tune awareness radius,
   speeds, swing damage and hazard density until pressure rises by thirds and the floor-covered
   maps are won on every cohort seed; `./scripts/check.sh`; implementation review; disposition
   findings.
10. **Contact, pickup reset, shot indicators.** Decision 9, one owner, three sub-steps in
    `tasks.md` (CPS-1..3) each red then green, then `./scripts/check.sh`, then gate 3: adversarial
    review of the whole change, findings dispositioned, up to three rounds.
11. **Range-aware bosses, life steal, bounce, burst fire, hurt flash, health bars.** PROD-072..077,
    P-44..P-47; one owner, five sub-steps in `tasks.md` (RBH-1..5) each red then green, then
    `./scripts/check.sh`, then gate 4: adversarial review, findings dispositioned, up to three
    rounds. *(done; gate 4 ran its three rounds — see tasks.md; two rejected design findings
    are the user's call)*

12. **Aged materials, kind rings, hovering drops, shot looks.** PROD-050 restated, PROD-078..080,
    P-50..P-53; one owner, four sub-steps in `tasks.md` (LOOK-1..4) each red then green, the icon
    sheet regenerated and looked at after LOOK-1, then `./scripts/check.sh`, then gate 5:
    adversarial review, findings dispositioned, up to three rounds. *Done; gate 5 ran its three
    rounds (9, 7, 3 findings, all confirmed and fixed) — see `tasks.md`.*
13. **Detailed cyberpunk backdrops and basic audio feedback.** *(done)*
    Extend PROD-040 and add PROD-102, P-76 and P-77. Preserve the three existing
    parallax rates while giving every theme a deterministic structural motif profile and denser
    procedural detail. Report semantic player-melee, player-ranged and ground-item pickup cues from
    the common tick, then synthesize short Web Audio patches in a browser adapter. Complete BGS-1
    through BGS-4 in `tasks.md` test-first, inspect the ten-theme world sheet and run
    `./scripts/check.sh`.
14. **Tier-coded weapon-drop rings.** *(done)*
    Restate PROD-050, PROD-051, P-29 and P-51 so weapon drops use white, green, gold, purple and red
    rings from T1 through T5 while powerups remain blue. Give only T4 and T5 rings fixed,
    progressively wider blooms, preserve the non-colour casing and pip cues, keep rings off held,
    HUD and discovery icons, and retain the constant item-batch bound. Complete RING-1 and RING-2
    in `tasks.md` test-first, inspect the all-tier icon sheet, then run `./scripts/check.sh`.
15. **Map-scaled boss melee charges.** *(done)*
    Add PROD-104 and P-79: each mini-boss and main-boss melee activation independently charges at
    a probability scaling linearly from 50 % on map 1 to 90 % on map 10. A charged attack advances
    in its locked direction and sweeps its normal attack geometry over the path actually travelled,
    without extra damage opportunities or loss of the normal dodge and fairness rules. Complete
    BMC-1 in `tasks.md` test-first, update deterministic state coverage and run
    `./scripts/check.sh`.
16. **Close-range rank-and-file melee cadence.** *(done)*
    Add PROD-105 and P-80: while the player is within swing reach, a Swarm, Flyer or Brute
    progresses its wind-up and post-strike cooldown at twice the ordinary rate without changing
    damage. Keep ranged-enemy and boss cadence unchanged, account for both effective in-reach
    timings in `ThreatScore`, then complete MSH-1 in `tasks.md` test-first and run
    `./scripts/check.sh`.
17. **Keep the boss gate and exit free of hazards.** *(done)*
    Extend PROD-094 and add P-81 so every hazard kind is rejected from the gate column as well as
    the exit corridor. Complete GATE-1 in `tasks.md` test-first.
18. **Keep map starts clear of normal enemies.** *(done)*
    Add PROD-106 and P-82: preserve each requested population while keeping every complete initial
    patrol span strictly outside the player's inclusive 22-tile start exclusion. Complete SPAWN-1
    in `tasks.md` test-first.
19. **Lead moving targets with grenade launchers.** *(done)*
    Add PROD-107 and P-83: record actual last-tick target movement and solve Ashfall's deterministic
    whole-tick arc against its constant-velocity intercept, with a stationary fallback. Complete
    LEAD-1 in `tasks.md` test-first and extend the determinism digest.
20. **Add broken-glass ground hazards.** *(done)*
    Add PROD-108, P-84 and P-85: place 1–2-cell glass patches inside the existing hazard budget,
    drain at `0.5 × contactDamage` per second and draw small static rusty jagged debris. Complete
    GLASS-1 in `tasks.md` test-first and inspect the representative world sheet.
21. **Increase all player-melee damage by 50 %.** *(done)*
    Add PROD-109 and P-86, update the nine registry rows exactly, preserve every non-damage field
    and recalibrate affected assertions and pressure fixtures. Complete MELEE-1 in `tasks.md`
    test-first.
22. **Add grounded ramen healing drops.** *(done)*
    Add PROD-110, P-87 and P-88: give each rank-and-file death an independent one-in-eight ramen
    roll, place a successful bowl on deterministic safe reachable ground, heal 5 % of current
    maximum health on walk-over contact and briefly flash the player green. Draw the small rusty
    bowl, wavy noodles and two right-side angled chopsticks without weapon/powerup hover or rarity
    treatment. Complete RAMEN-1 in `tasks.md` test-first, then run `./scripts/check.sh`.
23. **Double the ramen pickup's visual scale.** *(done)*
    Extend PROD-110 and add P-89 so the bowl, noodles, chopsticks and their stroke widths use a 2×
    linear presentation scale while the ground anchor and every simulation rule remain unchanged.
    Complete RAMEN-SCALE-1 in `tasks.md` test-first, inspect the regenerated ramen sheet, then run
    `./scripts/check.sh`.
24. **Make ground-item payloads valid by construction.** *(done)*
    Add ENG-057 and replace the nullable weapon/powerup plus ramen boolean combination with closed
    equipment-loot and ramen variants. Keep collection order, rendering, placement, audio and the
    deterministic digest unchanged. Complete PICKUP-MODEL-1 in `tasks.md` under characterization
    tests, then run `./scripts/check.sh`.
25. **Centralize actor body geometry.** *(done)*
    Add ENG-024 so `PlayerState` and `LiveEnemy` own their centre/body derivations, replacing the
    repeated formulas and raw default-physics offsets in simulation, presentation and tests.
    Complete BODY-GEOMETRY-1 in `tasks.md`, then run `./scripts/check.sh`.
26. **Decompose scene painting and cohere its tests.** *(done)*
    Add ENG-014, ENG-025 and ENG-035. Leave `Scene.compose` as the ordered composition root while
    extracting focused painters, split the 54-test catch-all `SceneTest` by current responsibility,
    table-drive equivalent cases and express ramen's final signature without historical geometry.
    Complete SCENE-COHESION-1 in `tasks.md` with exact draw-list parity and regenerated sheets.
27. **Reuse isolated heavy generation fixtures.** *(done)*
    Add ENG-036 and replace repeated `LevelGenerator.generate(seed, mapIndex)` setup in read-only
    JVM cohort tests with a memoized test corpus that returns isolated mutable state. Preserve every
    seed, map, assertion and direct generator test. Complete TEST-CORPUS-1 in `tasks.md`, compare the
    before/after generation count and check duration, then run `./scripts/check.sh`.
28. **Remove current inspection debt.** *(done)*
    Remove the audited unused declarations and parameters, unresolved KDoc, redundant qualifiers,
    unnecessary non-null assertions and trivial duplicate branches without changing behaviour or
    adding a lint dependency. Complete CLEANUP-1 in `tasks.md`, rerun IntelliJ inspections and
    `./scripts/check.sh`.

## Agents

| Role | Who | Scope |
|---|---|---|
| Main agent | Claude | every step but 6a; integrates 6a |
| Sub-agent A | Claude (general-purpose, own worktree) | step 6a — `combat/Weapons.kt`, `WeaponRegistryTest`, `LootFloorTest` comments only; reported one out-of-scope fixture failure rather than editing it |
| Adversarial reviewer | `codex exec --model gpt-5.6-sol -c model_reasoning_effort=high --sandbox read-only` | gate 1 after step 1; gate 2 after step 9; gate 3 after step 10; gate 4 after step 11; gate 5 after step 12; three lenses per ENG-071; up to three rounds each |

The sub-agent receives the relevant spec sections verbatim and the files it owns, runs the focused
tests then `./gradlew jvmTest`, and does not run `check.sh` or touch files outside its scope. The
main agent merges, runs the full gate, and answers review findings.

## Open questions

- Enemy base speed: at 41 % of run speed the player can always walk away from a Swarm; the bot
  playthrough decides whether `ENEMY_SPEED` rises. Constraint: never above run speed.
- Whether a Shooter should lead its target. The first pass aims at the position when the wind-up
  starts; the playthrough says whether that is toothless.
