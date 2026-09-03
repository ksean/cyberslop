# Enemies, Mini-bosses and Bosses

## Archetypes

Health is `multiplier × Balance.trashHealth(mapIndex)`, where
`Balance.trashHealth(L) = 12 × [1 + 2(L - 1) / 9]`. Ground speeds are fractions of
`ENEMY_SPEED`, and no archetype's ordinary pursuit speed may reach the player's run speed of 240
px/s (PROD-061); a committed leap may carry horizontally at, but never above, that speed. An
enemy's body is a 14 × 14 px box; its centre is `position + (7, 7)`.

| Archetype | Health × | Speed × | Role | Terrain |
|---|---|---|---|---|
| Swarm | 0.6 | 1.4 | melee: pursues and swings | walker |
| Brute | 2.2 | 0.6 | melee: pursues and swings | walker |
| Flyer | 0.7 | 1.1 | melee: pursues in both axes and swings | ignores terrain |
| Shooter | 0.8 | 0.8 | ranged: approaches, holds, retreats, shoots | walker |
| Turret | 1.5 | 0.45 | ranged: waits folded; once engaged, unfolds, holds range and shoots | walker |

Population: `Populator` places `widthTiles / 100 × enemiesPerHundredTiles` enemies (clamped to
8–60) with archetype weights Swarm 0.38, Brute 0.22, Flyer 0.18, Shooter 0.15, Turret 0.07, each
standing on the lowest standable row of its column with a patrol span of 1–3 tiles either side,
subject to the placement invariants in completability.md, and with no part of the patrol inside
the inclusive horizontal start exclusion `spawnColumn ± START_CLEAR_TILES`, where
`START_CLEAR_TILES = 22`, either arena, the twenty-tile approach before it, or anywhere past the
boss arena's approach. This is an initial-population rule: after engagement, rank-and-file enemies
may pursue into the mini-boss approach and arena, but not the main-boss protected ground. The
generator resamples from the remaining legal
columns so this exclusion does not reduce the requested population. Twenty-two start tiles equal
the awareness radius, guaranteeing that no initial rank-and-file enemy is already aware of or
targetable by the player. Twenty approach tiles leave a Swarm engaged at the awareness radius and
outrun on the approach beyond `DISENGAGE_PX` of the boss's centre.

For a player's direct `ArcSwing`, every rank-and-file silhouette is contained by a 24 px combat
disc about the enemy centre. This disc is intentionally larger than the 14 px movement box: the
drawn bodies vary by archetype while movement remains cell-sized.

## Awareness (PROD-060)

The simulation has no screen, so "visible" is an **awareness radius**, not a viewport test: an
enemy whose centre is at Euclidean distance strictly less than `AWARE_PX = 22 tiles` from the
player's centre becomes *engaged* — the same strict predicate auto-aim uses, so the two boundaries
agree at equality; it stays engaged until it dies or that distance exceeds `DISENGAGE_PX =
28 tiles`, at which point it resumes patrol — so a pack the player has outrun by that much drops
off rather than arriving at the boss with them. An unengaged non-Turret patrols `homeX ± patrolPx`;
a Turret folds at `homeX` and waits. An engaged enemy is not confined to its patrol span. The
radius is the one auto-aim uses, so an enemy the player's weapon can target is one that is acting
on the player.

## Movement (PROD-088, PROD-112)

- **Walkers** (Swarm, Brute, Shooter and Turret) have gravity: an unsupported walker falls at the
  player's gravity to terminal velocity and lands on the first solid tile; a walker that lands in
  a lethal tile dies. Falling continues while stunned. Knockback moves a walker horizontally and
  may push it off a ledge, after which it falls. A Turret remains folded and stationary while
  unengaged; engagement unfolds its articulated legs and it thereafter uses the ranged pursuit
  rules at `0.45 × ENEMY_SPEED`.
- **Ground steps and leaps.** A grounded walker first attempts its voluntary horizontal step. If
  the next tile in its chase direction begins an unsupported span, acid or void, a spike strip, a
  broken-glass patch, a barrel footprint, or a solid obstruction at body height, it asks
  `EnemyLeap.plan` for a landing
  in that direction. The planner advances the shipped fixed-step gravity and collision rules with
  `LEAP_VX = 240 px/s`, `LEAP_VY = -680 px/s`, the walker's real box and half-tile collision
  substeps. It selects the nearest landing beyond the blocking/hazard span whose whole swept box is
  clear of solid and lethal cells and the damaging-hazard footprint, and whose two bottom corners
  finish on non-lethal solid ground.
  It launches only if that replay succeeds; otherwise the walker stops at the old ledge-rule
  boundary. The horizontal direction is committed at take-off, no attack may begin while airborne,
  and another leap may not begin until `LEAP_COOLDOWN = 0.25 s` after landing. The generated gap,
  step and damaging-hazard bounds must lie inside this measured leap envelope, so a physically
  possible pursuit crosses every generated pit, acid span, spike strip, broken-glass patch and low
  step rather than
  treating it as a permanent wall. An active fire jet is not jumpable cover: a walker waits for
  its off-window before crossing its corridor.
- **Pursuit (melee).** An engaged Swarm or Brute moves toward the player by ground steps and safe
  leaps. An engaged Flyer moves in both axes toward the player and passes through terrain and
  hazards, including committed columns; the damage fairness rule, not an invisible movement wall,
  protects a player who is committed to a crossing.
- **Mini-boss ground (PROD-112).** The mini-boss arena and its twenty-tile approach impose no
  runtime boundary on an engaged rank-and-file enemy. A walker may step or select a safe leap
  landing there, a Flyer may cross the boundary, and ranged movement uses its ordinary
  approach/hold/retreat policy. Swings, shots and contact drain remain effective there. This does
  not place an unengaged patrol in the zone: initial population retains the exclusion above.
- **Main-boss ground.** No rank-and-file enemy pursues onto the main-boss arena, its twenty-tile
  approach or the exit corridor: a walker neither steps nor selects a leap landing there and a
  Flyer holds at the boundary. A Shooter held there can still be within range of someone inside,
  so the ground is fair as well as unenterable: **no rank-and-file swing, projectile or contact
  drain deals damage to a player whose box overlaps the main-boss ground**. Bosses are not bound
  by it; their ground is where they fight. An enemy already on protected ground is not trapped by
  the boundary.
- **Approach, hold, retreat (ranged).** An engaged Shooter or unfolded Turret faces the player and: beyond
  `SHOOTER_RANGE = 220 px` (13.75 tiles) steps toward the player; between `RETREAT_PX = 5 tiles`
  and `SHOOTER_RANGE` holds; inside `RETREAT_PX` steps away; all through safe ground steps/leaps, shooting
  whenever it can. Its voluntary movement uses the same safe leap planner when ground steps cannot
  cross the intervening obstacle.

## Attacks (PROD-061, PROD-063, PROD-105)

`contactDamage(L) = 6 × [1 + 4(L - 1) / 9]` is the unit every enemy attack and contact drain scales
from. Consequently every fixed rank, archetype and attack multiplier retains its identity while
the same enemy damage source rises linearly from its map-1 amount to exactly five times that amount
on map 10.

- **Contact (PROD-069).** Every living enemy body hurts to touch. While the player's AABB overlaps
  a rank-and-file enemy's 14 × 14 px box, health drains at
  `CONTACT_DRAIN = 1.0 × contactDamage` per second (`× dt` per tick, the way a damaging hazard
  drains — hazards.md). While it overlaps a living mini-boss or main boss's 44 × 56 px body,
  health drains at `BOSS_CONTACT_MULTIPLIER × CONTACT_DRAIN × contactDamage` per second, where
  `BOSS_CONTACT_MULTIPLIER = 3.0`. Each overlapping body drains independently. Contact is not an
  attack: it needs no wind-up, remains active regardless of attack or stun state, and stacks with
  a swing that lands in the same tick. It never displaces the player (ENG-051) and is subject to
  the fairness rule below like every other enemy damage source: committed columns and landing
  grace suppress every contact drain; main-boss ground additionally suppresses rank-and-file contact
  but not mini-boss or main-boss contact. Contact drain is not part of `ThreatScore`, which
  measures attacks the population can make, but pressure harnesses count it as gross damage like
  any other.

- **Melee swing.** A melee enemy whose target's centre is within `SWING_REACH = 1.5 tiles` of its
  own starts a swing: a wind-up during which it deals nothing and does not move, then one instance
  of swing damage if the player's centre is still within reach **and inside the 90° arc** centred on
  the swing direction, then a cooldown before it can swing again. The swing direction is the
  direction to the player at wind-up start, so a player who gets behind the enemy during the
  wind-up is missed by a swoosh that visibly went the other way.

  | Archetype | Wind-up | Damage | Cooldown after the strike |
  |---|---|---|---|
  | Swarm | 0.30 s | 0.6 × contact | 0.6 s |
  | Flyer | 0.30 s | 0.8 × contact | 0.8 s |
  | Brute | 0.45 s | 1.2 × contact | 1.1 s |

  A living Swarm, Flyer or Brute whose swing is winding up or cooling down progresses that timer at
  `MELEE_ATTACK_RATE_IN_REACH = 2.0` seconds per simulated second while the player's current centre
  is at or inside the swing's `SWING_REACH`, and at the ordinary 1.0 second per simulated second
  while the player is outside it (PROD-105). Entering or leaving reach changes the rate on that
  fixed tick, so time accumulated across mixed in-range and out-of-range intervals is proportional
  rather than resetting. A player who remains in reach therefore makes the effective wind-up
  0.15 s for Swarm and Flyer and 0.225 s for Brute, and halves the table's post-strike wait. The
  table's damage, reach, locked direction, arc and one-hit rule are unchanged. The rate does not
  apply to Shooter or Turret shots, contact drain, or either boss rank.

- **Shot.** A Shooter or Turret whose cooldown has elapsed, whose target is within
  `SHOOTER_RANGE` and in line of sight, winds up for `SHOT_WINDUP = 0.25 s` (holding its aim), then
  fires one projectile at the player's centre as it was at the start of the wind-up: speed 340
  px/s, lifetime 2.5 s, radius 6 px, damage `0.45 × contactDamage`, cooldown 0.75 s after the shot.
- **Fairness on committed spans and on main-boss ground.** No rank-and-file or boss swing,
  projectile, beam or contact drain deals damage while the player occupies a committed column (any
  column their AABB overlaps), nor until they have been grounded and clear of committed columns for
  `LANDING_GRACE = 0.25 s`. An ordinary jump which does not overlap a committed column neither
  starts nor resets this protection. In addition, rank-and-file damage is suppressed while the
  player's box overlaps main-boss ground; mini-boss ground has no such suppression, and either
  boss rank may hurt on its fight ground. A projectile that would have hit while protected is
  spent and a beam event is skipped. This is the runtime form of completability.md's placement
  invariant and holds however enemies move.
- **Status.** Slows floor at 40 % and take the strongest; a stunned enemy neither moves nor attacks
  for 0.5 s and a wind-up in progress is cancelled; burn and bleed drain per tick.
- **Reward.** A kill yields 2 Scrap and rolls the drop table; any resulting item uses combat.md's
  jump-required death-drop site (PROD-090).

## Mini-boss and main boss (PROD-087)

Both reuse `BossFight` and `LiveBoss`: body 44 × 56 px, feet-anchored, ordinary radial-hit radius
28 px and ground speed 55 px/s. A player's direct `ArcSwing` instead uses the silhouette-containing
combat disc from P-63: 36 px for the mini-boss and 56 px for the main boss, about the same combat
centre. They use gravity and the same replayed safe-leap decision as a walker, with their actual
larger box. They may leap out of their own arena to follow an engaged player, but never move or
jump before engagement and never start an attack airborne. A rank-and-file arena boundary does
not bind the boss whose fight it is.

### Seeded combat profiles

`BossRoster.forRun(seed)` owns profile assignment. It walks the twenty encounter slots in route
order — mini-boss then main boss on maps 1 through 10 — using only
`Rng.derive(seed, 0, "boss-roster")`. For the slot's map band it draws one melee module and one
ranged module uniformly from the four possible pairs, excluding the immediately preceding pair;
therefore a map's mini-boss and main boss never carry the same primary pair, and no adjacent
encounters repeat exactly. A main boss also draws one **signature** uniformly from the two modules
in its band that its primary pair did not take. The roster is reconstructed from the run seed on
continue and never reads or shifts loot, crit, generation or attack-choice randomness.

| Maps | Melee pool | Ranged pool | Difficulty character |
|---|---|---|---|
| 1–3 | Slam, Sweep | Bolt, Burst | slow single strikes and narrow, low-damage fire |
| 4–6 | Slam, Flurry | Burst, Scatter | rapid sequences and area-denying spread |
| 7–10 | Flurry, Rush | Scatter, Laser | sustained pressure, lunges and a beam |

Every profile has its primary melee and primary ranged attack from full health. A mini-boss has
one phase containing that pair. A main boss uses the pair above 60 % health, adds its signature at
60 %, and below 25 % keeps those attacks but shortens its between-attack rest from 0.9 s to 0.65 s;
its opening rest remains 0.8 s. The signature is announced by visible hardware from the start, not
sprouted when the phase changes (presentation.md). The map-themed encounter names remain; the
seeded profile changes how each named encounter fights and looks.

| | Health | Damage unit `U` | Award |
|---|---|---|---|
| Mini-boss | 6 × trash | `0.80 × contactDamage(mapIndex)` | weapon ≥ T2; plus a powerup from map 4 |
| Main boss | 12 × trash | `1.00 × contactDamage(mapIndex)` | weapon ≥ T3 with two tier shifts, powerup ≥ T2, 40 Scrap |

The damage in the attack table is per listed hit or projectile. The resolution column gives the
uncharged melee event schedule; a charged melee keeps the same number of opportunities but makes
each opportunity live over the interval specified under **Melee charges** below.
`contactDamage(mapIndex)` is strictly increasing, so the same module always hurts more on a later
map; the attack bands also replace early narrow attacks with rapid, spread, rush and beam pressure.
Telegraph interpolation still uses `d = (mapIndex − 1) / 9`: the left value is map 1, the right
map 10, and the 0.4 s fairness floor never scales.

| Attack | Kind | Telegraph (map 1 → 10) | Resolution | Damage | Dodge | Loadout silhouette |
|---|---|---|---|---|---|---|
| Slam | melee | 0.70 → 0.55 s | one 0.25 s downward swing; hits within 80 px only while the player is grounded | `1.10 × U` | jump | oversized weighted forearm and ground swoosh |
| Sweep | melee | 0.65 → 0.50 s | one 0.30 s level swing; hits a standing player within 80 px | `0.85 × U` | crouch | one long lateral blade and level swoosh |
| Flurry | melee | 0.60 → 0.45 s | three level swings at 0.00, 0.14 and 0.28 s of a 0.38 s active window; each tests a standing player within 72 px | `0.38 × U` each | crouch through the sequence | paired short blades, each swing drawn separately |
| Rush | melee | 0.55 → 0.40 s | one forward-ram hit at the start of a 0.40 s active window; hits a grounded player within 128 px; attack-driven movement uses the shared melee-charge rule below | `1.45 × U` | jump | forward ram and piston legs with a trailing swoosh |
| Bolt | ranged | 0.65 → 0.50 s | a 0.10 s window emits one terrain-blocked projectile at 280 px/s along the aim recorded at telegraph start | `0.50 × U` | move away from the recorded aim | one narrow barrel and one muzzle flash |
| Burst | ranged | 0.65 → 0.50 s | a 0.36 s window emits three terrain-blocked projectiles at 300 px/s and 0.12 s intervals along one recorded line | `0.22 × U` each | move away through the sequence | one barrel with a long magazine and three flashes |
| Scatter | ranged | 0.60 → 0.45 s | a 0.10 s window emits five terrain-blocked projectiles at 320 px/s, simultaneously at −15°, −7.5°, 0°, +7.5° and +15° about the recorded aim | `0.24 × U` each | move clear of the recorded fan | a short five-port muzzle and five flashes/tracers |
| Laser | ranged | 0.70 → 0.55 s | a 10 px-wide beam along the aim recorded at telegraph start, clipped by the first terrain face or level boundary, for 0.30 s; it may damage a player at most once | `1.05 × U` | move clear of the locked segment | a large lens which charges, then a core-and-bloom beam |

Bolt, Burst and Scatter projectiles stop when they hit terrain or the player, or when their swept
body crosses the level boundary; they have no independent travel-distance expiry. Laser uses the
same recorded aim as the other ranged modules and extends to the first terrain face or level
boundary. Thus an unobstructed ranged attack continues through the complete camera view whatever
the browser's viewport dimensions, without making simulation rules depend on a screen. Boss
projectiles carry boss ownership: they may hurt a player on boss ground, where the fight belongs,
but are suppressed while the player occupies a committed column and during the same
`LANDING_GRACE` as every other boss hit.

For player contact (PROD-111), every movement piece sweeps the round's closed, visible body disc
against the player's closed current-stance movement AABB. Tangency is contact. The earliest
contact before terrain places and spends the round at that point; if fairness permits damage, it
applies the round's declared damage exactly once and starts the ordinary player hurt flash. The
test uses the complete swept piece rather than its endpoint, so every Scatter angle and every
other boss round shares the same collision rule. Being airborne away from a committed column is
not protection.

A boss turns only between attacks. Every melee direction, projectile line, spread centre and Laser
endpoint is recorded when the telegraph begins, so crossing the boss mid-telegraph never turns the
tell. No damage event may occur before at least 0.4 s of telegraph. Slam, Sweep, Rush, Bolt, Scatter
and Laser have one damage opportunity; Flurry and Burst have the explicit event sequences above,
all covered by the one preceding telegraph. Every event draws the implement, swing, flash,
projectile or beam that owns it. A player who begins the listed dodge when the telegraph appears
and holds it through the active/projectile window takes no damage; a player in the named geometry
who does nothing is hit. For the three projectile rows, "move away" means choosing the horizontal
direction from the locked target point away from the boss and holding it until the last
round expires; the real-input case, not a synthetic target flag, discharges that claim. Bosses
resist slows entirely.

### Melee charges (PROD-104)

After either boss rank selects a melee module and before its telegraph begins, its encounter-local
melee-charge stream rolls exactly once. Conditional on a melee selection, the probability is

```text
chargeChance(mapIndex) = 0.50 + 0.40 × (mapIndex − 1) / 9
```

so it is exactly 50 % on map 1, exactly 90 % on map 10 and linear for maps 2–9. Every melee
module, including Rush, uses this roll; Rush is not an always-charging exception. Ranged attacks
neither roll nor charge. Mini-boss and main-boss charge rolls use independent streams derived as
`Rng.derive(seed, mapIndex, "miniboss-melee-charges")` and
`Rng.derive(seed, mapIndex, "boss-melee-charges")`. These streams never shift attack choice,
roster, loot, critical-hit or generation randomness. The result is fixed when the telegraph starts
and is future-affecting replay state.

On a charged activation, the boss starts moving when the active window begins and advances
horizontally at `LUNGE_SPEED = 300 px/s` for the whole active window, in the direction from the boss
to the target point locked at telegraph start. It never retargets mid-attack. This attack-driven
movement uses fixed-tick swept steps and stops at the last standable position before solid terrain,
a level boundary, unsupported or lethal ground, a damaging-hazard footprint or an active fire jet;
it does not begin a leap. Only the path actually travelled is dangerous. An uncharged melee attack,
including an uncharged Rush, makes no attack-driven movement.

Each declared melee damage opportunity becomes live at its event offset and remains live until the
next melee event, or until the active window ends for the last event. During that interval its
ordinary reach geometry is translated along the boss's actual movement and swept continuously
between consecutive fixed-tick positions, including both endpoints; tangency with the target is a
hit. An opportunity is spent on its first hit and cannot damage the player twice. Thus Slam, Sweep
and Rush still offer at most one hit and Flurry still offers at most three, but a boss cannot pass
its attack across the player between event samples or fixed ticks without the corresponding hit.
The module's ordinary grounded/crouched dodge is evaluated when contact would occur, and
committed-span, landing-grace and boss-ground fairness remain unchanged. Charge movement does not
displace the player, and ordinary boss-body contact remains a separate drain which may stack with
a charged melee hit.

**Choosing the next attack (PROD-072).** When a rest ends the boss chooses between its phase's
melee and ranged modules by how far the player stands, measured boss feet to player centre. The
probability of ranged is `RANGED_WEIGHT_NEAR = 0.2` at or inside `MELEE_REACH = 80 px`,
`RANGED_WEIGHT_FAR = 0.8` at or beyond `RANGED_PREFERRED_PX = 128 px`, and linear between. Every
phase now holds both kinds. Within the chosen kind its modules cycle round-robin on separate melee
and ranged indices, so a signature cannot starve a primary. Each encounter's choice comes from its
own stream (`Rng.derive(seed, mapIndex, "miniboss-attacks")` or `"boss-attacks"`), whose state and
both indices are in the digest (P-40); it never touches roster assignment or any other stream.

**Awards as a floor.** The starter cache never holds the bottle it exists to replace. A main
boss's weapon award guarantees Chromed; its two extra draws raise the odds of better and nothing
more, which is why `LootFloor.weaponArrivingAt` is Street on map 1 and Chromed from map 2 on.

**Activation (PROD-062).** A boss is inert and invulnerable until *engaged* — the player within
`AWARE_PX` of it — and from then on it moves, attacks and can be damaged, wherever the player
stands, and pursues the player by safe ground steps and leaps without regard to its own arena. It never
disengages. **The exit gate (PROD-036).** The main boss's exit gate is a solid column carved with
the map; it stands while the boss lives and, on the boss's death, it and every obstructing tile
between the arena and the map's right edge are cleared (PROD-035). Nothing the player or their
weapon does can seal an arena; there is no commit line. The mini-boss gates nothing and persists
if walked past.

## Balance calibration

| | Formula | Map 1 | Map 5 | Map 10 |
|---|---|---|---|---|
| Trash health | `12 × [1 + 2(L−1)/9]` | 12 | 22.7 | 36 |
| Mini-boss health | `6 × trash` | 72 | 136 | 216 |
| Boss health | `12 × trash` | 144 | 272 | 432 |
| Contact damage (the unit enemy attacks scale from) | `6 × [1 + 4(L−1)/9]` | 6 | 16.7 | 30 |
| Player max health | `100 + 15 (L−1)` | 100 | 160 | 235 |
| Target trash kill time | `2.0 → 1.2` linear | 2.00 | 1.64 | 1.20 |
| Required player DPS | `trash ÷ time` | 6.0 | 13.8 | 30 |

Mini-boss and boss kill-time bands are the health multipliers times the trash band. The boss
multipliers are sized for roughly three-quarters uptime, because dodging a telegraph means moving
out of reach. The broken bottle's 6.0 DPS now meets map 1's required 6.0. The guaranteed starter
cache remains the first weapon-progression beat, but is no longer required solely to bring the
starter above the map-one rate.

## Threat and pressure (PROD-068)

`ThreatScore.of(level)` measures the generated population and hazards, excluding map index: for
each melee enemy,
`damage per attack ÷ ((wind-up + cooldown) ÷ MELEE_ATTACK_RATE_IN_REACH)`, the rate seen by a
target who remains in reach; for each ranged enemy,
`damage per attack ÷ (wind-up + cooldown)`; for each damaging hazard, its per-second rate. These
contributions are summed and divided by `widthTiles / 100`. Bosses are excluded (every map has one
of each).

Two play harnesses in `jvmTest` measure survivability. Both start a map with the guaranteed loadout a player
*arrives* with (`LootFloor.weaponArrivingAt`, `LootFloor.slotsArrivingAt`: the awards of the maps
before, none of the map's own) at full health, with the map's optional caches removed so nothing
unearned is taken; when the mini-boss award drops, the harness replaces it where it lies with the
floor's weakest outcome (`weaponAt`, `slotsAt`), so the rest of the route and the boss fight are
played with what the floor models rather than with whatever the roll gave. They use the game's own
auto-aim (nearest target, bosses included once engaged) and record **gross incoming damage** —
every damage event before lifesteal — separately from net health.

- **Route pressure**, all ten maps: replay the witness tape while the population acts; the tape
  ends at the boss arena entrance. A death ends the map and counts as the map's full max health.
  Reported per map over the seed cohort as mean gross damage per 100 tiles of width.
- **Boss pressure**, on the maps the loot floor covers: after the route, fight with the dodge
  policy against the isolated main encounter (the earlier mini-boss, residual rank-and-file
  population and any uncollected award are absent) — answer each telegraphed attack with its dodge
  for the attack's whole duration, retreating directly away during a selected charge's telegraph
  and active window; otherwise maintain a nominal `64 px` horizontal centre-to-centre standoff.
  Near an arena edge and between attacks, vault over the boss, advancing through its
  horizontal span only once the bodies are vertically clear. Any boss-body contact during the
  fight counts toward gross damage. Continue until the boss dies or
  `FIGHT_TICKS = 12 000` elapse; the map must be won.
- **Boss escalation calibration**, all ten maps: on one flat, hazard-free arena, engage the seeded
  main boss below 25 % health against a stationary non-attacking target with enough health to live
  for 60 s, and record gross damage per second. Over the roster seed cohort, the mean of maps 1–3,
  then 4–6, then 7–10 must be strictly increasing. This does not claim that every random pair on
  map N is stronger than every pair on map N − 1; it measures the band, damage and late-phase
  cadence together while P-60 separately requires same-module damage to rise map by map.

## The loot floor

`LootFloor` models a reference player who takes only guaranteed awards — the starter cache, then
each mini-boss and boss award at its weakest outcome — under the game's real pickup policy. That
policy resets a build only when the pickup's `WeaponId` differs (PROD-070). Every consecutive
weapon in the reference route does differ: its minimum-tier sequence is Street starter cache,
Scav mini-boss, Chromed main boss, then alternating Scav and Chromed boss awards, and a weapon id
belongs to only one tier. The loadout at any point on the reference route is therefore still **the
last guaranteed weapon before that point, at its weakest, with only the guaranteed powerups awarded
after it** — never an accumulation across the run. Arriving at map `N ≥ 2` the reference player
holds map `N − 1`'s boss weapon and that boss's powerup; at map `N`'s main boss they hold the
mini-boss's weapon (Scav at its weakest) and, from map 4, the mini-boss's powerup, because the
mini-boss award replaced the boss weapon and emptied the build. The timeline of a map is therefore:
the arriving loadout from the map's start through the mini-boss fight, the mini-boss award at its
weakest from the moment it is taken, and that held loadout at the main boss. The floor is honest
about that: a forced different-weapon pickup can be a downgrade, and the floor says so rather than
assuming the player kept the better weapon. Optional loot is genuinely required past the early
maps. The fully simulated floor-covered prefix remains maps 1–3: the new health curve lets the
guaranteed weapon satisfy the boss-only damage band later than that, but route survival is not
guaranteed from map 4. Damage-only clearability remains reported separately so it cannot silently
expand the full route-and-boss guarantee. The floor's claims are:

Any simulation harness claiming this floor must take a death award through PROD-090's normal
jump/contact path before using its loadout or resuming the route. It may replace the roll with the
floor's declared weakest contents before contact, but may neither teleport the item nor equip the
inventory directly.

- it carries maps 1–3 unaided: a map counts as *covered* only if its main boss falls inside the
  kill-time band to the loadout **held at that boss** (`weaponAt`, `slotsAt` — the mini-boss award,
  never the boss's own) and the full-simulation guarantee includes it; the route and the mini-boss
  are judged with what the player **arrives** holding (`weaponArrivingAt`, `slotsArrivingAt`), and
  the boss-pressure harness fights with exactly the held loadout the floor models;
- it never goes backwards (non-decreasing damage across maps);
- the ceiling (best weapon, greediest legal build) reaches the final map's required rate;
- the guaranteed loadout survives the witness route on every map the floor covers, with the
  population engaged and attacking, over the seed cohort; later maps require optional loot to
  retain that survival guarantee, which is the curve.

## Verified properties

- **P-12** Placement invariants (completability.md).
- **P-17** Every boss attack is behaviourally telegraphed: no damaging hitbox, projectile or beam
  exists until ≥ 0.4 s after the telegraph begins; each scheduled hit in a Flurry or Burst occurs
  at its declared offset after that tell; every dodge is expressible with the four inputs.
- **P-18** Loot floor, as stated above; the full-map run test crosses map 1 on the witness, kills
  the boss by answering its telegraphs, and walks out.
- **P-24** Menace monotonicity (presentation.md).
- **P-32** Awareness: an unengaged non-Turret just outside `AWARE_PX` patrols within its span while
  a Turret there stays folded at home; one just inside engages and leaves its span; an engaged enemy
  stays engaged at `AWARE_PX + 1 tile` and
  disengages beyond `DISENGAGE_PX`; an engaged Swarm closes on the player; an engaged Shooter
  approaches from beyond `SHOOTER_RANGE`, holds between, and backs off inside `RETREAT_PX`; a
  Turret stays folded before engagement and unfolds into the same ranged hold/retreat policy after
  it; every archetype's ordinary pursuit speed is below the player's run speed.
- **P-33** Ground safety: a walker never takes an unsupported, lethal or body-blocked ground step;
  an unsupported walker falls and lands and one knocked into acid dies; when no verified leap
  exists it stops at that boundary. A jump state never begins an attack and preserves its take-off
  direction until landing.
- **P-34** Attacks: a rank-and-file enemy overlapping the player outside a strike deals exactly its
  normal contact drain and nothing more; a swing deals
  nothing during wind-up and its damage exactly once per cooldown; a player in reach but behind the
  swing direction is missed; a shot leaves after its wind-up at the new speed and cadence; a stun
  cancels a wind-up; no enemy damage lands while the player occupies a committed column or within
  the landing grace after leaving one — for a swing and for a projectile.
- **P-35** Boss activation: an unengaged boss neither moves nor attacks nor takes damage; an
  engaged boss attacks and takes damage wherever the player stands; the exit gate stands while the
  boss lives and opens only on its death; an engaged boss follows the player out of its arena and
  takes a verified leap across a gap between it and the player; each attack's telegraph selects the
  wind-up pose and every active event its swing, lunge, flash, projectile or beam. **Dodges are
  mechanics:** for every attack, including every event of Flurry and Burst, a player performing the
  listed dodge through the active/projectile window takes nothing and a player who stands still in
  its named geometry takes damage.
- **P-39** Pressure: over the seed cohort, `ThreatScore`'s cohort mean rises strictly across maps
  1→10; route pressure's mean gross damage per 100 tiles averaged over maps 1–3, 4–6 and 7–10 is
  strictly increasing; the guaranteed loadout survives the route and wins the boss fight on every
  floor-covered map on every cohort seed.
- **P-41** Contact drain: overlapping a living rank-and-file enemy drains
  `CONTACT_DRAIN × contactDamage × dt` per tick, while overlapping either a living mini-boss or a
  living main boss drains exactly three times that amount. One tick never kills a full-health
  player; overlapping bodies drain independently. A defeated body, a body one pixel clear, or any
  contact while the player is over a committed column or within landing grace drains nothing;
  main-boss ground suppresses only rank-and-file contact, while mini-boss ground does not. Contact
  remains active through stun and boss attack states, receives the normal incoming non-lethal
  damage multiplier, refreshes the player hurt flash, and never displaces the player.
- **P-40** Simulation determinism (simulation.md): `GameSimulation.digest()` is a canonical
  encoding of every mutable, future-affecting field — the player state and run (health, loadout,
  scrap), the auto-fire accumulator, the loot RNG state, every enemy in list order (position,
  velocity, aiming velocity, health, facing, engagement, leap target/cooldown, cooldown, wind-up and its aim, slow,
  stun, burn, bleed), every projectile in list order (position, velocity, damage, pierce, life,
  ownership, boss activation and already-hit target identities), every ground
  item, the pending burst (rounds left, seconds to the next, aim, payload), each boss (position,
  velocity, profile and phase, health, engagement, attack, elapsed and scheduled events, rest,
  melee and ranged attack indices, its attack-choice and melee-charge RNG states, aiming velocity, current charge
  selection and consumed charged-melee opportunities, reward flag),
  the player's active `ArcSwing` (snapshotted build and geometry, progress and already-hit targets),
  the terminal death phase and its elapsed fixed ticks, the exit state and the elapsed tick — with
  doubles encoded by their IEEE bits and lists by length then elements. Presentation-only fields
  (stride distance, enemy swing and flash visuals, aim direction), the captured death cause and
  collapse/effect geometry, the player/enemy/boss hurt-flash timers, status-indicator geometry and
  Scrap-gain labels are excluded. After N ticks of a fixed tape on a fixed seed it
  matches a committed golden value on both targets, and a mutation test per state family changes
  it.
- Shooters and turrets are at most 35 % of any map's population; every map holds at least three
  archetypes; enemies stand on the route rather than pooling at the arena.
- **P-82** Start clearance: every initially populated rank-and-file patrol span lies strictly
  outside `spawnColumn ± 22`, so every enemy centre begins at or beyond the strict 22-tile
  awareness/auto-aim boundary. Boundary fixtures reject a patrol that touches either endpoint and
  accept the next column outside it. Across the generation cohort no enemy begins engaged or
  targetable, while the requested population count, maximum ranged share, minimum archetype count
  and all arena exclusions remain satisfied.
- **P-44** Boss attack choice: a phase-three main boss with the player inside `MELEE_REACH`
  opens with a ranged attack in about 20 % of attacks over a long fixed-seed run (within ±5
  points) and with the player at or beyond `RANGED_PREFERRED_PX` in about 80 %; within a kind the
  modules cycle in profile order; a pinned seed's first twelve choices match one committed sequence
  on both targets; every telegraph, hit condition and dodge case of P-17 and P-35 is unchanged.
- **P-79** Boss melee charges: the probability is 0.50 on map 1, 0.90 on map 10 and differs by
  exactly `0.40 / 9` between adjacent maps; over a fixed large seed cohort each map's observed
  charge share is within one percentage point of that formula and the shares rise strictly. Both
  boss ranks and every melee module, including Rush, use the rule; a ranged selection consumes no
  charge roll and never charges, and the independent charge stream leaves the pinned attack-choice
  sequence unchanged. In forced charged fixtures the boss moves at 300 px/s in the telegraphed
  direction without retargeting, stops before unsafe or blocked ground, and cannot tunnel through a
  player placed anywhere in its actually travelled attack path. The hit deals the module's normal
  damage no more than once per declared opportunity; a player performing the declared dodge, under
  committed-span protection or beyond a clipped path takes none. The same forced uncharged modules
  make no attack-driven movement. Charge selection, progress and consumed opportunities reproduce
  across targets and perturb the P-40 digest when changed.
- **P-80** Close-range melee cadence: while a Swarm, Flyer or Brute is winding up or cooling down,
  its applicable timer decreases by exactly `2 × dt` on a fixed tick when the player's centre is
  at or inside `SWING_REACH`, and by exactly `dt` when the player is outside; intervals on the two
  sides accumulate without a reset. Remaining in reach halves each archetype's wind-up and
  post-strike wait, but wind-up still deals no damage and the existing damage, reach, arc and
  one-hit rule remain unchanged. Shooter and Turret cadence and every mini-boss and main-boss
  attack are unchanged. `ThreatScore.pressureOf` uses the accelerated in-reach wind-up and cooldown
  for melee archetypes and the existing full timings for ranged archetypes, and its cohort mean
  still rises strictly across maps 1→10.
- **P-90** Boss projectile contact: table-driven Bolt, Burst and Scatter fixtures sweep a round
  through the standing and crouching player AABB, including tangency at a body edge and a complete
  crossing whose two fixed-tick endpoints are outside the body. The earliest contact before
  terrain places and spends the round there, lowers health by exactly that round's declared damage
  once and starts `playerHurtSecondsLeft`. The same contact during an ordinary jump over
  non-committed ground still lands; contact over a committed column or during its landing grace is
  spent without damage or hurt flash. A near miss outside the radius remains live.
- **P-91** Mini-boss-zone access: on flat fixtures, every engaged rank-and-file archetype can enter
  and later leave the mini-boss's twenty-tile approach and arena under its normal movement policy;
  representative swing, projectile and contact-drain fixtures lower player health there. The same
  walker and Flyer fixtures still cannot enter main-boss protected ground, rank-and-file damage is
  suppressed there, and initial population patrols remain outside both arena exclusions across
  the generation cohort.
- **P-66** Full-view boss range: on an unobstructed level, Bolt, every Burst round and every Scatter
  pellet remain live after crossing the former eight-tile limit and are removed only after a player
  hit or swept level-boundary crossing; a solid wall still stops each at its first contact. Laser's
  locked segment reaches the level boundary on open ground and the first terrain face when blocked.
  The real camera clips those projectiles and beams at its own edge, and the existing telegraph,
  committed-span suppression, one-hit beam rule and scripted dodges remain unchanged.
- **P-60** Boss profiles and escalation: for a fixed run seed, all twenty assignments and their
  signature choices are identical on JVM and Wasm and reconstruct identically on continue; adjacent
  slots never share a primary pair; every mini-boss and every phase of every main boss contains
  melee and ranged; only the declared modules occur in each map band; over the seed cohort every
  legal pair and both signature kinds occur. The same module's damage is strictly increasing with
  map index, a main version exceeds the mini version on the same map, and mean no-dodge boss damage
  per second is strictly increasing from maps 1–3 to 4–6 to 7–10. The dodge bot still wins every
  loot-floor-covered boss fight on every cohort seed.
- **P-61** Pursuit across hazards: fixtures for a one- to three-tile spike strip, a one- to
  two-tile broken-glass patch, the widest generated flat acid/void gap and the tallest generated
  step are each crossed by every engaged
  ground archetype and by both boss ranks, with no swept lethal contact and a non-lethal supported
  landing; a Flyer crosses the same committed span in flight. No unengaged enemy launches, a
  fault-injected span beyond the measured envelope produces no launch, a rank-and-file trajectory
  never enters main-boss protected ground, and no enemy attack damages the player during the
  committed span or landing grace. Across the generation cohort every generated chase-direction obstacle is
  within the corresponding real-box leap envelope.
