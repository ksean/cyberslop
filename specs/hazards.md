# Map Hazards

Hazards are part of the world, not of the enemy population, and are the only things besides
enemies and bosses that can hurt the player (PROD-026, PROD-064). Two classes exist: **lethal**
hazards, which the witness route never touches, and **damaging** hazards, which are survivable and
are placed off the witness route so the proven path stays hazard-free.

## Kinds

| Hazard | Class | Where it is written | Effect on contact |
|---|---|---|---|
| Acid | lethal | the floor of a gap, when the theme allows acid and the curve's hazard frequency rolls | death |
| Void | lethal | below the bottom row of the map | death |
| Fire jet | lethal while on | one per jet corridor, spanning the six rows above the floor, timed `period / duty / phase` | death |
| Spike strip | damaging | a `Spikes` tile on walkable floor, 1–3 tiles long | `1.0 × contactDamage` per second of overlap |
| Burning barrel | damaging | a `Barrel` object standing on a floor tile, with a flame one tile above it | `1.5 × contactDamage` per second of overlap with the barrel or its flame |

Acid, void and spikes are tile kinds (`Acid`, `Void`, `Spikes`; none blocks movement). A fire jet
and a barrel are objects on the `Level`, not tiles. A jet's state is `isOnAt(t) = ((t + phase) mod
period) < on` on the level clock, so a witness replay and the live game agree on every jet.

## Contact rules

- The movement model samples lethal overlap **per sub-step** (half a tile), so a terminal-velocity
  tick cannot step over a one-tile hazard layer; a jet burn is tested against the player's AABB at
  the current level clock. Either sets health to zero at the end of the tick, after all other
  damage.
- Damaging overlap is tested against the player's AABB each tick and drains `rate × contactDamage
  × dt`; overlapping two damaging hazards drains both. It can kill a player who stands in it, and
  it never displaces the player (ENG-051).

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
  such that every cell of the hazard's footprint — each cell of a spike strip; a barrel's cell and
  the flame cell above it — is at Chebyshev distance ≥ 2 from every recorded foothold, is not in
  the `ArcMask`, is outside both arenas and the entry ramp before each, and is at Chebyshev
  distance ≥ 2 from every static pickup cell. Candidates are drawn from the `hazard` stream at
  `damagingHazardsPerHundredTiles × widthTiles / 100` (generation.md), rounded down, spikes twice
  as often as barrels, strips 1–3 cells long. The confirming replay counts damaging contact; any
  hazard the tape still overlaps is removed, deterministically, so the shipped level's route is
  hazard-free by measurement and not only by construction.

## Verified properties

- **P-10** Every jet corridor contains exactly one jet volume with pixel-measured safe zones and an
  off-window that fits the crossing plus reaction time (completability.md).
- **P-36** Damaging hazards: overlapping a spike strip, a barrel's body or a barrel's flame drains
  health at the hazard's rate per second and a single tick of contact does not kill; every footprint
  cell is at Chebyshev distance ≥ 2 from every witness foothold and every static pickup, outside
  the `ArcMask` and both arenas; the confirming replay reports no damaging contact on every map of
  a seed cohort; a spike and a barrel fault-injected onto the replayed route are removed by the
  confirming pass, deterministically, and nothing else is; the per-map count rises with map index
  in cohort mean and is zero on map 1.
- Hazard contact: safe ground reports no lethal contact; falling into acid does; a single fast tick
  through a hazard is still caught; acid does not block movement; jet-bearing themes generate jets
  on every map that allows them.
