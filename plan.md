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
5. **Damaging hazards are placed last and confirmed by replay.** Spike strips and burning barrels
   hurt per second and are survivable; their whole footprint keeps two tiles from every witness
   foothold and every static pickup and stays out of the `ArcMask` and the arenas; a confirming
   replay removes any hazard the tape still touches. Density follows the curve from map 2.
6. **Melee out-reaches and out-damages ranged.** Every melee weapon reaches ≥ 2 m, beyond any
   enemy swing; per tier, melee mean DPS (bottle excluded) exceeds ranged mean DPS; tier bands stay
   non-overlapping (the table in combat.md was checked against P-14).
7. **A crouch is a pose, not a scale.** Limb lengths preserved, joints bent, within the crouch box.
8. **Difficulty is measured.** `ThreatScore` over the generated population and hazards; a
   route-pressure harness over all ten maps (witness tape with the population acting, gross
   incoming damage per hundred tiles, asserted rising by thirds); and a boss-pressure harness on
   the floor-covered maps that must win. A whole-simulation digest (P-40) over every
   future-affecting field joins the determinism check, built after hazards so its golden is cut
   once.
9. **Touch hurts, a weapon is a build, a shot shows where it went.** (a) A living enemy's body
   drains `1.0 × contactDamage` per second of overlap, like a hazard, under the fairness rule; not
   bosses. (b) Every weapon pickup equips: the old weapon and every powerup slot convert to Scrap;
   a paired boss award applies weapon then powerup. The loot floor is re-derived to that policy —
   the arriving loadout is the last guaranteed weapon plus the powerups awarded after it — and
   the covered-map count is re-measured, not assumed. (c) Projectiles draw a body and tracer;
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

## Agents

| Role | Who | Scope |
|---|---|---|
| Main agent | Claude | every step but 6a; integrates 6a |
| Sub-agent A | Claude (general-purpose, own worktree) | step 6a — `combat/Weapons.kt`, `WeaponRegistryTest`, `LootFloorTest` comments only; reported one out-of-scope fixture failure rather than editing it |
| Adversarial reviewer | `codex exec --model gpt-5.6-sol -c model_reasoning_effort=high --sandbox read-only` | gate 1 after step 1; gate 2 after step 9; gate 3 after step 10; three lenses per ENG-071; up to three rounds each |

The sub-agent receives the relevant spec sections verbatim and the files it owns, runs the focused
tests then `./gradlew jvmTest`, and does not run `check.sh` or touch files outside its scope. The
main agent merges, runs the full gate, and answers review findings.

## Open questions

- Enemy base speed: at 41 % of run speed the player can always walk away from a Swarm; the bot
  playthrough decides whether `ENEMY_SPEED` rises. Constraint: never above run speed.
- Whether a Shooter should lead its target. The first pass aims at the position when the wind-up
  starts; the playthrough says whether that is toothless.
