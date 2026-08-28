# Change 0005: Visual identity, animation and loot density

- **Status:** Implemented on 2026-08-26
- **Implementation approval:** Ratified by the owner on 2026-08-27, after review, having seen the plan and the work. It was **first** given in advance, in the request that asked for the plan ("Once the plan is completed, proceed to implement the plan"), which is not the post-review approval `AGENTS.md` asks for — eight review rounds said so, and the record kept saying so rather than papering over it until the owner settled it.
- **Created:** 2026-08-26

## Intent

The game is playable and every subsystem the brief asks for is wired, but everything it draws is a
flat rectangle. [Change 0004](0004-keyboard-only-controls.md) deliberately put art out of scope and
said so: *"Art, animation and audio. The visual indications here are placeholder shapes."* This change
discharges that.

It does four things:

1. Gives the game a cyberpunk-dystopian **visual identity** — ten palettes, a parallax backdrop, lit
   tile surfaces, and hazards that read as hazards.
2. **Animates** the player: standing, moving sideways, jumping up, falling, crouching, crouch-walking,
   firing, and swinging.
3. Makes the five enemy archetypes **visually distinct**, and makes a stronger enemy **look** stronger
   by a monotone rule rather than by impression.
4. Raises **loot density** to one drop per five kills and adds statically placed pickups averaging two
   per map.

The research and the design behind it are in [`plan.md` §15](../../plan.md#15-visual-identity-animation-and-loot-density).

## Requirements added

| ID | Requirement |
|---|---|
| PROD-040 | A cyberpunk-dystopian 2D identity; each sub-theme has its own palette and backdrop |
| PROD-041 | The player is animated across eight distinguishable states; weapon animation composes over movement |
| PROD-042 | Enemy archetypes differ by silhouette; bulk, plating and protrusions are monotone in health across the grid, and drawn luminance is monotone within a map |
| PROD-043 | A boss is drawn distinctly from trash, and a mini-boss from a main boss |
| PROD-044 | A ground pickup shows its kind and its rarity tier |
| PROD-045 | The HUD shows health, weapon, powerup stacks, map index and sub-theme |
| PROD-046 | One in five slain rank-and-file enemies drops something, three in ten of those a weapon; boss awards stay guaranteed |
| PROD-047 | Static pickups average two per map, on ground the map's own witness stood on |
| PROD-048 | Title and run-ended screens share the in-game identity |
| ENG-060 | Platform-independent presentation lives in `commonMain` and is testable without a browser |
| ENG-061 | Drawing-state changes per frame are bounded by style batches, not by entity count |
| ENG-062 | Animation is a pure function of simulation state and elapsed simulation time |
| ENG-063 | No runtime asset dependency; everything drawn is produced by code |

## Requirements superseded

- **`plan.md` §6.7's trash drop row** (1.5% → 3%) and the value that was actually implemented
  (3% → 6%) are both replaced by PROD-046's flat 20%. The two disagreed with each other, which is its
  own reason to state the number in `specs/` rather than in a research document.
- **`plan.md` §6.7's "cache / dead terminal" row** (1 per map on maps 3–7, 2 on maps 8–10) is replaced
  by PROD-047. It was never implemented; the only pre-placed item in the game is map 1's starter cache.

Nothing in change 0003 or 0004 is withdrawn. PROD-033 (a visible swing) and PROD-034 (a drawn boss
with its health) remain in force and are strengthened, not replaced, by PROD-041 and PROD-043.

## Why a procedural rig and not a sprite atlas

No art exists. An atlas without art is an empty pipeline, and it would add a network fetch, a decode
before first paint, and a 404 mode under the Pages base path — three failure modes for a build whose
current asset count is zero. A rig is arithmetic, so under ENG-031 it is testable in `commonTest`:
*"the crouch pose is shorter than the standing pose"* is an assertion, where a frame index into an
atlas is not. And the owner's *"more stylized in later implementations"* falls out of a rig for free —
pass two is a richer draw function over the same joints, not a new pipeline.

ENG-063 records the decision so that adding assets later is a specification change rather than a
quiet drift.

## Why the frame is batched

`plan.md` §8.1 measured per-sprite `save`/`translate`/`rotate`/`restore` at **7.61×** a bare draw —
21% of the frame budget at 600 entities. A rig multiplies per-entity draw count by roughly six. The
frame is therefore assembled as a small set of batches, each a flat `DoubleArray` of coordinates, and
limbs are stroked segments rather than rotated rectangles — so no per-sprite transform exists to be
slow. ENG-061 states the bound, and it is testable without a browser.

A batch is keyed by **layer, style, shape and stroke width together**, and each of those four is a
bounded set. All four parts are load-bearing, and two of them were found by review rather than by
design. Without the layer, two things far apart in depth that share a colour collapse into one batch
and paint in the wrong order. Without the width, the renderer has to break its stroke path inside a
batch: measured at 45, 279 and **1,579** `beginPath`/`stroke` pairs for 10, 100 and 600 entities,
while the batch count sat at 34 the whole time — a proxy the code did not deliver. With width in the
key it is 52 batches at every entity count, each costing a fixed amount — one property for a
fill, three for a stroke — rather than the "exactly one" this record claimed until round ten.

The honest limit is unchanged: this bounds *state changes*, which is the part §8.1 measured. It does
not bound rasterization, and §8.1's full-frame measurement remains an open task.

## What the loot change does and does not affect

- **`LootFloor` is a bound on the player again, and it took five review rounds and four policies.**
  It computes from guaranteed awards only, and the obvious corollary — that more loot can only help —
  is false: `PowerupSlots` scrapped a sixth distinct powerup, so drops filling all five slots made a
  later guaranteed one scrap on contact, below the loadout this file bounds a player to. What was
  tried, and why each failed:
  1. **Rank slots by `Powerup.magnitude`** — generic strength, not contribution to damage; made
     `damagePerSecondAt` *fall* between maps four and five.
  2. **Rank by `expectedDps`** — counts one target only; displaced a splash powerup that was worth
     more, dropping the weapon score 50.6 to 40.8.
  3. **Rank by `WeaponScore`** — what the game judges builds by, but the floor is written in damage;
     a legal route ended at 30.3 against a map-four floor of 32.0.
  4. **Require both** — keeps a build monotone in each measure, which still does not make one route's
     end dominate another's: **10 of 8,160** three-powerup routes ended below the floor, worst by
     21.1 damage.

  What closes it is none of the rankings: **a guaranteed award is never refused**, and displaces
  whichever slot costs least damage to lose. Measured over the same 8,160 routes on all ten maps,
  **0** end below the floor. PROD-028 requires it, `LootFloor` models the policy the game runs, and
  the test walks optional routes *then* the guaranteed awards — the shape three earlier versions of
  it missed.
- **Completability (PROD-024) is unaffected.** A witness is a movement tape; loot does not appear in
  it.
- **Static drops must not weaken the corridor invariant.** They are placed outside both arenas and
  outside every committed span, on `Populator`'s own reasoning — a pickup that lures the player onto a
  span they cross committed is the hazard that rule exists to prevent.
- **Reachability is proved, not approximated.** A pickup stands on a cell the map's own verified
  witness stood on. The first version used the arc mask, which a review round showed to be unsound:
  `SpineWalker.rollback` deliberately does not rewind it, so it retains cells from abandoned move
  proposals no witness ever walks.
- **`plan.md` §6.7's published powerup economy becomes stale**, since it was simulated at the old
  rate. Recomputing it is recorded as a follow-up, not a blocker.

## Acceptance examples

1. Given a running game, the player character is drawn as an articulated figure whose legs alternate
   while running, whose body is lower while crouched, whose limbs tuck while rising and reach while
   falling, and whose weapon arm recoils on a shot and sweeps on a melee swing — while the legs keep
   moving.
2. Given the five enemy archetypes on one map, each is identifiable by silhouette alone with colour
   removed.
3. Given any two enemies anywhere in the run, the one with more health is drawn with bulk, plating
   and protrusions each no lower than the other's, and the extremes differ strictly. Given two
   enemies on the same map, the one with more health is drawn no dimmer.
3b. Given any palette, its three glow tones are strictly increasing in luminance.
4. Given two different sub-themes, their palettes and backdrops differ.
5. Given a scene containing 600 entities and the same scene containing 10, the number of canvas
   state changes the renderer issues is the same — and no batch mixes stroke widths, so a batch is
   one path stroked once. A batch costs a fixed amount rather than exactly one change: one property
   for a fill, three for a stroke, three for a label.
6. Given 10,000 simulated rank-and-file kills at any map index, the proportion that drop an item is
   0.20 within sampling error, and three in ten of those are weapons.
6b. Given a mini-boss or a main boss killed at any map index, it awards loot every time — the rate in
   PROD-046 does not apply to it, because the guaranteed-loot floor is computed from those awards.
7. Given a cohort of seeds, the mean number of static pickups per map is 2.0 ± 0.15, every count is in
   `{1, 2, 3}`, and every pickup stands on a cell the map's own witness stood on, outside both arenas
   and outside every committed span. Map one additionally holds change 0003's starter cache, which
   this count deliberately excludes.
7b. Given a level, adding its static pickups cannot change what its witness replay produces — which
   is what lets one replay both prove the route and supply the ground the pickups stand on.
8. Given a ground pickup, its kind and its rarity tier are visible without picking it up.
9. Given a running game, the HUD shows health, the equipped weapon's name, each held powerup with its
   stack count, and the map index and sub-theme.
10. Given the title screen and the run-ended screen, both use the game's palette and typography.

## Out of scope

- **Pass-two styling:** grime, scanlines, chromatic edges, screen shake, hit flashes, particles and
  destruction. Pass one is silhouette, motion, palette and light direction.
- **Audio**, which remains change 0003's deferred item.
- **Recomputing `plan.md` §6.7's powerup economy simulation** at the new drop rate.
- **Recalibrating `WeaponScore` against `expectedDps`.** They order weapons differently — 381 of
  3,243 accepted swaps lower damage, and a "Debt Collector" Minigun at 65.9 DPS is given up for a
  Rustline Machete at 7.3 — so an optional *weapon* can still put a player below the floor. That is
  pre-existing, predates this change, and is raised as `plan.md` §12 question 7.
- **`plan.md` §8.1's full-frame budget measurement**, which is still owed and is unchanged by this
  work.
