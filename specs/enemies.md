# Enemies, Mini-bosses and Bosses

## Archetypes

Health is `multiplier × Balance.trashHealth(mapIndex)`. Speeds are fractions of `ENEMY_SPEED`, and
no archetype's effective speed may reach the player's run speed of 240 px/s (PROD-061). An enemy's
body is a 14 × 14 px box; its centre is `position + (7, 7)`.

| Archetype | Health × | Speed × | Role | Terrain |
|---|---|---|---|---|
| Swarm | 0.6 | 1.4 | melee: pursues and swings | walker |
| Brute | 2.2 | 0.6 | melee: pursues and swings | walker |
| Flyer | 0.7 | 1.1 | melee: pursues in both axes and swings | ignores terrain |
| Shooter | 0.8 | 0.8 | ranged: approaches, holds, retreats, shoots | walker |
| Turret | 1.5 | 0 | ranged: shoots | fixed |

Population: `Populator` places `widthTiles / 100 × enemiesPerHundredTiles` enemies (clamped to
8–60) with archetype weights Swarm 0.38, Brute 0.22, Flyer 0.18, Shooter 0.15, Turret 0.07, each
standing on the lowest standable row of its column with a patrol span of 1–3 tiles either side,
subject to the placement invariants in completability.md, and with no part of the patrol inside
either arena, the twenty-tile approach before it, or anywhere past the boss arena's approach — what
stands there arrives at the boss with the player, or shoots into the fight from the exit corridor,
and the boss fight is tuned as a boss fight. Twenty tiles is what leaves a Swarm engaged at the
awareness radius and outrun on the approach beyond `DISENGAGE_PX` of the boss's centre.

## Awareness (PROD-060)

The simulation has no screen, so "visible" is an **awareness radius**, not a viewport test: an
enemy whose centre is at Euclidean distance strictly less than `AWARE_PX = 22 tiles` from the
player's centre becomes *engaged* — the same strict predicate auto-aim uses, so the two boundaries
agree at equality; it stays engaged until it dies or that distance exceeds `DISENGAGE_PX =
28 tiles`, at which point it resumes patrol — so a pack the player has outrun by that much drops
off rather than arriving at the boss with them. An unengaged enemy patrols `homeX ± patrolPx`. An
engaged enemy is not confined to its patrol span. The radius is the one auto-aim uses, so an enemy
the player's weapon can target is one that is acting on the player.

## Movement

- **Walkers** (Swarm, Brute, Shooter) have gravity: an unsupported walker falls at the player's
  gravity to terminal velocity and lands on the first solid tile; a walker that lands in a lethal
  tile dies. Falling continues while stunned. Knockback moves a walker horizontally and may push it
  off a ledge, after which it falls.
- **The ledge rule** governs *voluntary* horizontal steps only: a walker does not take a step whose
  destination footprint (both bottom corners of its box) is not on solid ground, or is over a
  lethal tile, or is blocked by a solid tile at body height. It stops at the edge, facing the
  player, and resumes when a legal step exists. Walkers never jump.
- **Pursuit (melee).** An engaged Swarm or Brute steps toward the player under the ledge rule. An
  engaged Flyer moves in both axes toward the player and passes through terrain, but never enters
  a **committed column** (completability.md) — it holds at the column boundary.
- **The boss's ground.** No enemy pursues onto an arena or the twenty-tile approach before it
  (`Level.isArenaGround`): a walker stops there as at a ledge and a Flyer holds as at a committed
  column, so a pack the player has outrun waits at the door instead of joining a fight that is
  tuned as a boss fight. A Shooter held there is still within its range of someone inside, so
  the ground is fair as well as unenterable: **no enemy swing or projectile deals damage to a
  player whose box overlaps the boss's ground** — the second clause of the fairness rule below.
  Bosses are not bound by it; their ground is where they fight. The rule applies to every arena;
  an enemy already on that ground is not trapped by it.
- **Approach, hold, retreat (ranged).** An engaged Shooter faces the player and: beyond
  `SHOOTER_RANGE = 220 px` (13.75 tiles) steps toward the player; between `RETREAT_PX = 5 tiles`
  and `SHOOTER_RANGE` holds; inside `RETREAT_PX` steps away; all under the ledge rule, shooting
  whenever it can. A Turret never moves.

## Attacks (PROD-061, PROD-063)

Nothing hurts by touch: there is no contact aura. `contactDamage(mapIndex)` is the unit every enemy
attack scales from.

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

- **Shot.** A Shooter or Turret whose cooldown has elapsed, whose target is within
  `SHOOTER_RANGE` and in line of sight, winds up for `SHOT_WINDUP = 0.25 s` (holding its aim), then
  fires one projectile at the player's centre as it was at the start of the wind-up: speed 340
  px/s, lifetime 2.5 s, radius 6 px, damage `0.45 × contactDamage`, cooldown 0.75 s after the shot.
- **Fairness on committed spans and on the boss's ground.** No enemy swing or enemy projectile
  deals damage while the player occupies a committed column (any column their AABB overlaps),
  nor until they have been grounded and clear of committed columns for `LANDING_GRACE = 0.25 s`,
  nor while their box overlaps the boss's ground; a projectile that would have hit is spent. This is the runtime form of completability.md's placement invariant and holds
  however enemies move.
- **Status.** Slows floor at 40 % and take the strongest; a stunned enemy neither moves nor attacks
  for 0.5 s and a wind-up in progress is cancelled; burn and bleed drain per tick.
- **Reward.** A kill yields 2 Scrap and rolls the drop table (combat.md).

## Mini-boss and main boss

Both reuse `BossFight` and `LiveBoss`: body 44 × 56 px, feet-anchored, hit radius 28 px, speed 55
px/s, a walker under the ledge rule.

| | Health | Phases | Attacks | Award |
|---|---|---|---|---|
| Mini-boss | 6 × trash | 1 | Slam | weapon ≥ T2; plus a powerup from map 4 |
| Main boss | 12 × trash | 100 / 60 / 25 % | Slam, Sweep → + Volley → + Rush | weapon ≥ T3 with two tier shifts, powerup ≥ T2, 40 Scrap |

| Attack | Telegraph (map 1 → 10) | Active | Damage | Hits a player who is… | Dodge | Drawn as |
|---|---|---|---|---|---|---|
| Slam | 0.70 → 0.55 s | 0.25 s | 1.4 × contact | within 80 px and **on the ground** | jump | wind-up, then a downward swing and a ground swoosh |
| Sweep | 0.65 → 0.50 s | 0.30 s | 1.1 × contact | within 80 px and **standing** (not crouched) | crouch | wind-up, then a level swing and swoosh |
| Volley | 0.60 → 0.45 s | 0.50 s | 0.8 × contact | within 8 tiles and within 24 px of the **x recorded when the telegraph began** | move aside | wind-up, then a muzzle flash and a fan of projectile dots |
| Rush | 0.55 → 0.40 s | 0.40 s | 1.6 × contact | within 128 px and **on the ground** | jump | wind-up, then a lunge with a trailing swoosh |

A boss turns only between attacks: an attack holds its facing and its aim from the moment its
telegraph begins, so a player crossing it mid-telegraph sees the swing go where the tell said. A
Rush is a lunge: through its active window the boss carries forward at 300 px/s under the ledge
rule, its hit resolving on the window's first tick before it moves; its swoosh trails behind.
No telegraph may be shorter than 0.4 s, enforced in the constructor. An attack deals its damage
once, on the first tick of its active window, if the player is inside its hit condition — so each
attack's listed dodge is the input that removes the player from that geometry, and a player who
does nothing is hit. Between attacks the boss
rests 0.9 s (0.8 s before its first) and walks toward the player with a gait; attacks cycle
round-robin through the current phase's list. Bosses resist slows entirely.

**Awards as a floor.** The starter cache never holds the bottle it exists to replace. A main
boss's weapon award guarantees Chromed; its two extra draws raise the odds of better and nothing
more, which is why `LootFloor.weaponAt` is Street on map 1 and Chromed from map 2 on.

**Activation (PROD-062).** A boss is inert and invulnerable until *engaged* — the player within
`AWARE_PX` of it — and from then on it moves, attacks and can be damaged, wherever the player
stands, and pursues the player under the ledge rule without regard to its arena. It never
disengages. **The exit gate (PROD-036).** The main boss's exit gate is a solid column carved with
the map; it stands while the boss lives and, on the boss's death, it and every obstructing tile
between the arena and the map's right edge are cleared (PROD-035). Nothing the player or their
weapon does can seal an arena; there is no commit line. The mini-boss gates nothing and persists
if walked past.

## Balance calibration

| | Formula | Map 1 | Map 5 | Map 10 |
|---|---|---|---|---|
| Trash health | `12 × 1.63^(L−1)` | 12 | 84.7 | 974.7 |
| Mini-boss health | `6 × trash` | 72 | 508 | 5848 |
| Boss health | `12 × trash` | 144 | 1017 | 11696 |
| Contact damage (the unit enemy attacks scale from) | `6 × 1.32^(L−1)` | 6 | 18.2 | 73 |
| Player max health | `100 + 15 (L−1)` | 100 | 160 | 235 |
| Target trash kill time | `2.0 → 1.2` linear | 2.00 | 1.64 | 1.20 |
| Required player DPS | `trash ÷ time` | 6.0 | 51.5 | 812 |

Mini-boss and boss kill-time bands are the health multipliers times the trash band. The boss
multipliers are sized for roughly three-quarters uptime, because dodging a telegraph means moving
out of reach. The broken bottle's 4.0 DPS sits deliberately below map 1's required 6.0: the first
weapon pickup is the first progression beat, and the starter cache guarantees it.

## Threat and pressure (PROD-068)

`ThreatScore.of(level)` measures the generated population and hazards, excluding map index: for
each enemy, `damage per attack ÷ (wind-up + cooldown)` using the archetype's swing or shot; for
each damaging hazard, its per-second rate; both summed and divided by `widthTiles / 100`. Bosses
are excluded (every map has one of each).

Two harnesses in `jvmTest` measure play. Both use the guaranteed loadout a player *arrives* with
(`LootFloor.weaponAt`, `LootFloor.slotsArrivingAt`: the awards of the maps before, none of the
map's own) at full health, with the map's optional caches removed so nothing unearned is taken, the game's own auto-aim (nearest target, bosses included once engaged), and record
**gross incoming damage** — every damage event before lifesteal — separately from net health.

- **Route pressure**, all ten maps: replay the witness tape while the population acts; the tape
  ends at the boss arena entrance. A death ends the map and counts as the map's full max health.
  Reported per map over the seed cohort as mean gross damage per 100 tiles of width.
- **Boss pressure**, on the maps the loot floor covers: after the route, fight with the dodge
  policy — answer each telegraphed attack with its dodge for the attack's whole duration, otherwise
  close on the boss — until the boss dies or `FIGHT_TICKS = 12 000` elapse; the map must be won.

## The loot floor

`LootFloor` models a reference player who takes only guaranteed awards — the starter cache, then
each mini-boss and boss award at its weakest outcome — under the game's real pickup policy.
Optional loot is genuinely required past the early maps; the floor's claims are:

- it carries the opening maps unaided, and a map counts as *covered* only if its boss falls inside
  the kill-time band to the loadout the player **arrives** with (`slotsArrivingAt`) — never to
  that boss's own award;
- it never goes backwards (non-decreasing damage across maps);
- the ceiling (best weapon, greediest legal build) reaches the final map's required rate;
- the guaranteed loadout survives the witness route on every map the floor covers, with the
  population engaged and attacking, over the seed cohort; on later maps the route is survivable
  only with optional loot, which is the curve.

## Verified properties

- **P-12** Placement invariants (completability.md).
- **P-17** Every boss attack is behaviourally telegraphed: no damaging hitbox exists until ≥ 0.4 s
  after the telegraph begins; every dodge is expressible with the four inputs.
- **P-18** Loot floor, as stated above; the full-map run test crosses map 1 on the witness, kills
  the boss by answering its telegraphs, and walks out.
- **P-24** Menace monotonicity (presentation.md).
- **P-32** Awareness: an enemy just outside `AWARE_PX` patrols within its span; one just inside
  engages and leaves its span; an engaged enemy stays engaged at `AWARE_PX + 1 tile` and
  disengages beyond `DISENGAGE_PX`; an engaged Swarm closes on the player; an engaged Shooter
  approaches from beyond `SHOOTER_RANGE`, holds between, and backs off inside `RETREAT_PX`; a
  Turret never moves; every archetype's effective speed is below the player's run speed.
- **P-33** Ledge rule: a walker at a ledge, beside a lethal tile or facing a wall does not step;
  an unsupported walker falls and lands; a walker knocked into acid dies; a Flyer stops at a
  committed column; through a bot playthrough of every map in the cohort no walker's voluntary
  step ever leaves its footprint unsupported.
- **P-34** Attacks: an enemy overlapping the player outside a strike deals nothing; a swing deals
  nothing during wind-up and its damage exactly once per cooldown; a player in reach but behind the
  swing direction is missed; a shot leaves after its wind-up at the new speed and cadence; a stun
  cancels a wind-up; no enemy damage lands while the player occupies a committed column or within
  the landing grace after leaving one — for a swing and for a projectile.
- **P-35** Boss activation: an unengaged boss neither moves nor attacks nor takes damage; an
  engaged boss attacks and takes damage wherever the player stands; the exit gate stands while the
  boss lives and opens only on its death; an engaged boss follows the player out of its arena and
  stops at a ledge; each attack's telegraph selects the wind-up pose and its active window the
  swing, lunge or flash. **Dodges are mechanics:** for every attack, a player performing the listed
  dodge through the active window takes nothing and a player who stands still takes the damage.
- **P-39** Pressure: over the seed cohort, `ThreatScore`'s cohort mean rises strictly across maps
  1→10; route pressure's mean gross damage per 100 tiles averaged over maps 1–3, 4–6 and 7–10 is
  strictly increasing; the guaranteed loadout survives the route and wins the boss fight on every
  floor-covered map on every cohort seed.
- **P-40** Simulation determinism (simulation.md): `GameSimulation.digest()` is a canonical
  encoding of every mutable, future-affecting field — the player state and run (health, loadout,
  scrap), the auto-fire accumulator, the loot RNG state, every enemy in list order (position,
  velocity, health, facing, engagement, cooldown, wind-up and its aim, slow, stun, burn, bleed),
  every projectile in list order (position, velocity, damage, pierce, life, ownership), every ground
  item, each boss (position, health, engagement, attack, elapsed, rest, attack index, reward flag),
  the exit state and the elapsed tick — with doubles encoded by their IEEE bits and lists by length
  then elements. Presentation-only fields (stride distance, swing and flash visuals, aim direction)
  are excluded. After N ticks of a fixed tape on a fixed seed it matches a committed golden value on
  both targets, and a mutation test per state family changes it.
- Shooters and turrets are at most 35 % of any map's population; every map holds at least three
  archetypes; enemies stand on the route rather than pooling at the arena.
