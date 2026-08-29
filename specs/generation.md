# Procedural Generation

`gen.LevelGenerator.generate(seed, mapIndex)` returns a `Level` with its `Witness` and a
verification report. It never returns a level whose witness failed replay (ENG-056).

## Pipeline

```
budget   = MovementEnvelope.measure(physics).scaled          // simulation.md
curve    = DifficultyCurve.at(mapIndex)
theme    = Themes.forMap(mapIndex)
spine    : for each half — spawn plateau → moves clamped to budget → ramp → arena
           every move writes FloorMask and ArcMask and appends its input program to the witness
decorate : derived stream "decor"; writes nothing in FloorMask, no solid tile in ArcMask
populate : derived stream "enemy"; enemies under completability.md's placement invariants
replay   : the witness through MovementModel; a failed attempt is discarded (8 attempts, then fail)
place    : static drops on the replay's footholds ("cache"), then damaging hazards ("hazard")
confirm  : replay again; remove any damaging hazard the tape touches
pursuit  : reject an attempt with a gap, step or ground-hazard span outside any required enemy-box
           leap envelope (enemies.md P-61)
```

Arenas are carved during the spine pass: flat contiguous floor of at least `theme.arenaWidthTiles`,
ceiling clearance ≥ 6, zero hazards, a left entry and a right exit at floor level, footprint in
`FloorMask`. The mini-boss arena is centred within ±5 % of `width / 2`; the boss arena ends the map.

A rhythm-shaped constructive spine is used rather than wave-function collapse or cellular automata
with repair, because it is the only approach where completability is a property of the construction
rather than a post-hoc search.

## The ten sub-themes

Hazard availability is monotone: once acid or jets enter the run they never leave it. Themes differ
by move vocabulary, rest-platform width and arena size.

| Map | Sub-theme | Acid | Jets | Ducts | Arena (tiles) | Rest platform (tiles) | Character |
|---|---|---|---|---|---|---|---|
| 1 | Ruined City Sprawl | – | – | – | 16 | 8 | Gaps only; wide platforms; teaching map |
| 2 | Rust Flats | ✓ | – | – | 16 | 8 | Long flat runs, small acid pools |
| 3 | Flooded Undercity | ✓ | – | ✓ | 16 | 7 | Acid-led; crouch ducts and low ceilings |
| 4 | Chem Foundry | ✓ | ✓ | ✓ | 16 | 7 | Jet-led; step-up chains, narrow catwalks |
| 5 | Neon Slums | ✓ | ✓ | ✓ | 18 | 6 | Vertical stacking, thin platforms |
| 6 | Sable Refinery | ✓ | ✓ | ✓ | 18 | 6 | Jets positioned over acid spans |
| 7 | Server Stacks | ✓ | ✓ | ✓ | 18 | 5 | Extreme verticality, long drops |
| 8 | Skybridge Ruin | ✓ | ✓ | ✓ | 20 | 5 | Widest gaps, acid beneath the span |
| 9 | Reactor Core | ✓ | ✓ | ✓ | 20 | 5 | Max jet density, shortest off-windows |
| 10 | Arcology Vault | ✓ | ✓ | ✓ | 22 | 5 | Every move kind, max density and length |

Each theme also has its own palette and backdrop (presentation.md).

## Difficulty curve

`DifficultyCurve.at(mapIndex)` uses `d = (mapIndex − 1) / 9` and the endpoints below, clamped to the
measured budget. Fields interpolate linearly except for the two hazard schedules described below;
every field still moves in one direction.

| Parameter | Map 1 | Map 10 |
|---|---|---|
| width (tiles) | 320 | 720 |
| gap frequency | 0.12 | 0.42 |
| hazard frequency | 0.00 | 1.00 |
| max level gap (tiles) | 2 | 3 |
| vertical band (tiles) | 8 | 26 |
| jet duty | 0.25 | 0.40 |
| jet period (s) | 2.4 | 1.4 |
| jet corridor frequency | 0.10 | 0.34 |
| enemies per 100 tiles | 4 | 9 |
| damaging hazards per 100 tiles | 0 | 7 |

The level gap cap of 3 leaves a full tile inside `gapMaxTiles(0) = 5`; wider gaps occur only where
the landing is lower and the envelope is larger. Jet duty is bounded at 0.40 because a jet whose
off-window cannot fit a crossing is refused by the generator, which would make the hardest maps end
up with the fewest jets. The fairness floor `REACTION = 0.25 s` never scales.

Acid is proposed only inside a gap. Its per-map frequency is
`[0.00, 0.05, 0.30, 0.34, 0.37, 0.40, 0.95, 0.97, 0.99, 1.00]`: the larger steps establish it by
the end of the opening band and sharply mark entry into the late-game band without changing route
topology. Damaging-hazard density follows the linear 0→5 curve through map 8, caps at 4 on map 9,
then rises to 7 on map 10 before player-route and enemy-pursuit confirmation. That small plateau in
the proposal curve prevents rejection noise from making the emitted final map milder than map 9.

The player envelope remains the authority for completability. Separately, `EnemyLeap` measures its
fixed arc against each required real box (14 × 14 rank-and-file and 44 × 56 boss) and the pursuit
audit checks generated pits, acid/void gaps, spike/barrel spans and step-ups against those results.
An attempt outside either requirement is not shown; the enemy audit does not rewrite the witness or
make enemy movement part of the player's completability proof.

## Difficulty score

`gen.DifficultyScore.of(level)` measures the **generated tiles**, not the parameters: a weighted sum
of gap fraction (60), hazard fraction (70), jet pressure (25), verticality (12) and openness (1).
Map index and enemy density are deliberately not inputs — the first would make the metric
tautological, the second carries rejection-sampling noise and is outside the traversal guarantee.
It is a generation metric, not a claim about human difficulty. The population's contribution is
measured separately by `ThreatScore` (enemies.md), so that a change to enemies is visible to a
test and a change to terrain is not masked by one.

## Verified properties

- **P-05** Arenas: floor flat and contiguous, width ≥ `arenaWidthTiles`, clearance ≥ 6, zero
  hazards, reachable entry and reachable exit.
- **P-06** Mini-boss arena centre within ±5 % of `width / 2`.
- **P-07** `FloorMask` integrity: decoration changes no masked cell.
- **P-08** `ArcMask` integrity: no solid tile is placed in any swept spine-move volume.
- **P-09** Every gap ≤ 0.70 × envelope, step-up ≤ 0.80 × envelope; every take-off has runway; every
  landing has run-out ≥ `stoppingDistance + playerWidth`.
- **P-13** Over a cohort of 24 seeds, the cohort mean of `DifficultyScore` is strictly increasing
  from each map to the next, and map 10's mean is at least 1.5 × map 1's.
- **P-22** Runtime generation plus verification on the widest map: median and p99 reported over
  100 seeds; p99 < 400 ms.
- **P-61** The generated pursuit-obstacle audit and real-box leap fixtures are specified in
  enemies.md; the seed cohort contains no accepted obstacle outside either required leap envelope.
