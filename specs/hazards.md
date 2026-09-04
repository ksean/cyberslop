# Map Hazards

Hazards are part of the world, not of the enemy population, and are the only things besides
enemies and bosses that can hurt the player (PROD-026, PROD-064). Every enemy or boss body drains
contact the same way a damaging hazard does, at the rate its enemy class declares
(enemies.md, PROD-069). Two classes exist: **lethal**
hazards, which the witness route never touches, and **damaging** hazards, which are survivable and
are placed off the witness route so the proven path stays hazard-free.

## Kinds

| Hazard | Class | Where it is written | Effect on contact |
|---|---|---|---|
| Acid (toxic pool) | lethal | the floor of a gap, when the theme allows acid and the curve's hazard frequency rolls | death |
| Void | lethal | below the bottom row of the map | death |
| Fire jet | lethal while on | one per jet corridor, spanning the six rows above the floor, timed `period / duty / phase` | death |
| Spike strip | damaging | a `Spikes` tile on walkable floor, 1–3 tiles long | `1.0 × contactDamage` per second of overlap |
| Broken glass | damaging | a `BrokenGlass` tile on walkable floor, in patches 1–2 tiles long | `0.5 × contactDamage` per second of overlap |
| Burning barrel | damaging | a `Barrel` object standing on a floor tile, with a flame one tile above it | `1.5 × contactDamage` per second of overlap with the barrel or its flame |

Acid, void, spikes and broken glass are tile kinds (`Acid`, `Void`, `Spikes`, `BrokenGlass`; none
blocks movement). A fire jet and a barrel are objects on the `Level`, not tiles. A jet's state is
`isOnAt(t) = ((t + phase) mod period) < on` on the level clock, so a witness replay and the live
game agree on every jet.

Acid's liquid identity is presentational: an exposed surface has a bright edge and several
differently phased bubbles rising through the pool (PROD-085, presentation.md P-58). Bubble phase,
height and size never enter the level or contact model; an acid tile is equally lethal at every
animation phase.

A fire jet's flame and source are likewise presentational: while the jet is on, several pointed
flame tongues wave above a ruptured pipe outlet in the supporting solid tile; while it is off, the
pipe remains visible and the flame does not (PROD-096, presentation.md P-70). The pipe is not a new
tile or object, and flame phase never enters `isOnAt`, the lethal volume or the level digest.

A burning barrel's fire is likewise presentational: several independently phased flame tongues
wave above the drum instead of forming a static triangular spike (PROD-099, presentation.md P-73).
Their geometry stays within the existing flame cell above the barrel. Flame phase never enters the
barrel footprint, damaging-contact calculation or level digest; the barrel and its entire flame
cell remain equally damaging at every animation phase.

Broken glass is static presentation over its exact tile footprint. Each tile shows a low scatter
of rusty, jagged shard segments as specified by presentation.md P-85. The drawn shard tips never
extend beyond the bottom 30 % of the tile, so a patch reads as small ground debris rather than a
spike wall. Its colour and shape do not change its non-blocking collision or contact rate.

Spike strips and barrel bodies resolve all of their colours from the current map palette
(PROD-114, presentation.md P-93 and P-94). The building-window colour is the canonical map-theme
colour and is reused exactly for every filled spike blade and barrel drum; spike supports and
barrel bands use the palette's contrasting tile-edge colour so their structure stays readable.
Spike blades are filled triangles, not stroked outlines. The barrel's fire is the exception to its
themed body: it retains the fixed warm outer-flame and yellow-hot core shared by the game's flame
presentation.

## Contact rules

- Survivable hazards use `Balance.hazardDamage(L) = 6 × [1 + 4(L - 1) / 9]` as their damage unit,
  retaining the existing linear 100 % to 500 % map curve independently of PROD-113's enemy-damage
  increase. Each kind's multiplier in the table above applies to this unit.
- The movement model samples lethal overlap **per sub-step** (half a tile), so a terminal-velocity
  tick cannot step over a one-tile hazard layer; a jet burn is tested against the player's AABB at
  the current level clock. Either sets health to zero at the end of the tick, after all other
  damage.
- Damaging overlap is tested against the player's AABB each tick and drains `rate × hazardDamage
  × dt`; one maximal spike strip or broken-glass patch counts once however many of its tiles the
  box overlaps, and overlapping distinct hazards drains each. It can kill a player who stands in
  it, and it never displaces the player (ENG-051). A terminal broken-glass contact has the semantic
  `Glass` damage source and the bleed death effect.
- Enemy traversal does not change those effects. An engaged ground enemy treats an acid/void span,
  a spike strip, a broken-glass patch and a barrel footprint as something its verified leap must
  clear; a Flyer crosses in flight. These hazards do not acquire a second enemy-damage model. An
  active fire jet is a closed corridor to a ground pursuer until its normal off-window; enemy
  waiting or jumping never changes the jet's phase. Safe launch, swept clearance and landing are
  specified and verified by enemies.md P-61.

## Generation constraints

- Acid appears only under gaps the player jumps, never on walkable ground; its frequency follows
  the difficulty curve and the theme's `allowsAcid`.
- A jet corridor is `2 × jetSafe + 1` tiles wide with the jet in the middle, where `jetSafe` is
  derived from the measured stopping distance. The generator computes the crossing time at 75 % of
  run speed and refuses the corridor unless `period − on ≥ crossing + 0.25 s`, then waits for the
  off-window, walks the crossing, and removes the jet if the crossing did not fit. Themes allow
  jets from map 4.
- **Damaging hazards are placed last**: carve → decorate → populate → replay → static pickups →
  damaging hazards → confirming replay. A candidate is a standable cell (solid below, free above)
  such that every cell of the hazard's footprint — each cell of a spike strip or glass patch; a
  barrel's cell and the flame cell above it — is at Chebyshev distance ≥ 2 from every recorded
  foothold, is not in the `ArcMask`, is outside both arenas and the entry ramp before each, is
  strictly left of `gateColumn`, and is at Chebyshev distance ≥ 2 from every static pickup cell.
  Candidates are drawn from the `hazard` stream at `damagingHazardsPerHundredTiles × widthTiles /
  100` (generation.md), rounded down. The density target is `7/3 + (14/3)d` per 100 tiles for
  `d = (mapIndex - 1) / 9`, so its map-10 endpoint is exactly three times its non-zero map-1
  baseline. Kind weights are spike:glass:barrel = 2:2:1, with spike strips
  1–3 cells long and glass patches 1–2 cells long. Separate glass candidates may not be
  edge-adjacent, so each maximal horizontal glass run remains one declared 1–2-cell patch. This is
  one shared hazard budget rather than an increase to the density curve. The confirming replay
  counts damaging contact; any whole strip, patch or barrel the tape still overlaps is removed,
  deterministically, so the shipped level's route is hazard-free by measurement and not only by
  construction.
- The **exit corridor** is every in-map column strictly greater than `gateColumn`, where the first
  player-centre crossing completes the map after the gate opens. Spine construction carves that
  corridor as plain safe floor; no acid gap or fire-jet corridor is proposed on the gate or beyond,
  and damaging-hazard candidate selection rejects every spike, glass or barrel footprint touching
  `gateColumn` or a later column. Therefore no hazard can sit on top of the gate wall and no acid,
  jet, spike, glass or barrel occupies the exit corridor. Its blue sparkling surface is the
  presentation-only completion marker specified in presentation.md, not a new hazard kind.

## Verified properties

- **P-10** Every jet corridor contains exactly one jet volume with pixel-measured safe zones and an
  off-window that fits the crossing plus reaction time (completability.md).
- **P-36** Damaging hazards: overlapping a spike strip, broken-glass patch, barrel body or barrel
  flame drains health at the hazard's rate times `hazardDamage(mapIndex)` per second and a single
  tick of contact does not kill; the damage unit is 100 % of its map-1 value on map 1, 500 % on map
  10 and linear between them; every footprint
  cell is at Chebyshev distance ≥ 2 from every witness foothold and every static pickup, outside
  the `ArcMask` and both arenas, and strictly left of `gateColumn`; the confirming replay reports
  no damaging contact on every map of a seed cohort; a spike, glass patch and barrel fault-injected
  onto the replayed route are removed by the confirming pass, deterministically, and nothing else
  is; the per-map count rises with map index in cohort mean, including from map 1's non-zero
  baseline.
- **P-81** Gate hazard exclusion: over the generation cohort, no acid, fire jet, spike, glass or
  barrel footprint touches `gateColumn` or any later column. Boundary fixtures that attempt to
  carve an acid gap or fire-jet corridor there are rejected; fault-injected spike, glass and barrel
  footprints on the gate itself and beyond it are removed by final confirmation without changing
  earlier hazards.
- **P-84** Broken glass: a player's AABB overlapping one or several cells of one maximal glass
  patch drains exactly `0.5 × contactDamage × dt` per tick, two distinct overlapping patches
  stack, and a one-pixel-clear player takes nothing. Glass is non-blocking, refreshes the ordinary
  hurt flash, can kill with semantic `Glass`/bleed cause, participates in `Hazards.count` and
  `ThreatScore`, and is placed/confirmed within the shared damaging-hazard budget at the declared
  weights and 1–2-cell length. Map 1 participates in the shared baseline budget and cohort pressure
  remains increasing.
- **P-61** Enemy hazard traversal is verified in enemies.md; the same spike, glass, barrel, acid,
  void and fire-jet geometry remains unchanged for the player and for witness replay.
- Hazard contact: safe ground reports no lethal contact; falling into acid does; a single fast tick
  through a hazard is still caught; acid does not block movement; jet-bearing themes generate jets
  on every map that allows them.
