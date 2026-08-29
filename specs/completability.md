# The Completability Guarantee

> **CG.** For every level the game shows a player, the generator holds a **witness**: a finite
> sequence of `InputFrame`s which, replayed through the game's own movement model from the spawn
> point, transits the mini-boss arena and reaches the boss arena entrance without contacting a
> lethal hazard. (PROD-024, ENG-056)

The witness is an input tape, not a path — a tape is executable. The replay uses the shipped
physics. The claim is about every level **shown**, not every seed: verification runs in the
shipping build, and the seed sweep in `jvmTest` is a regression check, not the guarantee.

## Scope

CG covers traversal of static geometry and timed hazards to the boss door. It does not cover
enemies (dynamic and killable — see *Enemies on the route* below and enemies.md), killing the boss
(the loot floor in enemies.md), damaging hazards (hazards.md, placed off the route), or human
execution (human validation).

## How it is discharged

1. **Correct by construction.** The generator lays a spine of standable *anchors* joined by *moves*
   whose (Δx, Δy) are clamped to the measured envelope with margin. Spine geometry is written to an
   immutable `FloorMask`; the swept volume of every move to an `ArcMask`. Decoration writes nowhere
   in `FloorMask` and places no solid tile in `ArcMask`.
2. **The generator emits the witness as it carves.** Every move is chosen from the envelope and
   lands on a rest node, so the generator already knows each move's input program and concatenates
   them. No search runs on the runtime path.
3. **Replay proves it.** `WitnessReplay` runs the tape through `MovementModel.step` against the
   populated level with the level clock driving the jets, and succeeds only if it reached the
   mini-boss arena, reached the boss arena and never touched a lethal tile or a burning jet. It
   also records every grounded cell (the *footholds*) for placement of static pickups and hazards.

`LevelGenerator.build` makes up to eight deterministic attempts (`seed' = mix(seed, attempt)`);
an attempt whose replay fails is discarded; if all eight fail, generation fails loudly rather than
presenting an unproven map. There is no repair pass and no fallback level.

## Two reachability analyses

Soundness of the witness needs an *under*-approximation of capability; anti-stranding needs an
*over*-approximation. Both live in `verify.Reachability` and run as a `jvmTest` oracle, not on the
runtime path.

**`UnderReach`** — nodes are `RestCell(column, row, stance)` **rest states**: every rollout begins
from a cell's canonical position and ends grounded, motionless and steered back to within 1.5 px of
the landing cell's canonical position (an edge that cannot settle there is discarded), so a chain of
edges composes to within that bounded residual. Edges come from replaying the catalog of control
programs through the real movement model:

| Program | Parameters |
|---|---|
| jump | run frames ∈ {0, 9, 18} × hold frames ∈ {4, 8, 40} × direction ∈ {−1, 0, +1} |
| walk, crouch-walk | 120 frames, direction ∈ {−1, +1} |
| crouch down, stand up | 30 frames in place |

The catalog holds no mid-air reversal, so `UnderReach` is a strict under-approximation of what a
player can do, which is the direction soundness needs. A witness waits for a jet as literal idle
frames computed while carving — the level clock starts at zero and is deterministic — so the stored
witness is exactly the input sequence PROD-024 names, not a program that produces one.

**`OverReach`** — a deliberately generous grid flood: from any cell the player may be anywhere in
the free-fall envelope plus the full jump envelope in both directions.

```
witness        : bossEntry ∈ UnderReach(spawn), transiting the mini-boss arena
anti-stranding : every standable cell in OverReach(spawn) is in UnderReach⁻¹(bossEntry)
```

Everywhere a player could conceivably get to, a player is provably able to leave. ENG-051 keeps
`OverReach` honest as weapons and enemies are added: only the movement model moves the player.

## Hazards on the witness path

- **Acid** is never standable; a rollout whose swept AABB touches acid is discarded.
- **Fire jets.** Each jet corridor contains **exactly one** jet volume, with a safe zone of at least
  `stoppingDistance + playerWidth + 1 tile`, measured in **pixels against the player's AABB**, on
  both sides. The off-window satisfies `offWindow ≥ crossDuration + REACTION (0.25 s)`. Replay starts
  at `t = 0` with declared jet phases and the witness waits symbolically, so the tape stays exact
  and the search stays time-free.
- **Damaging hazards** (hazards.md) are placed after the replay, off its footholds and off the
  `ArcMask`, and a second replay confirms the tape touches none.

## Enemies on the route

A **committed column** is one that holds a lethal tile in any row, or whose corridor (the first
`ArcMask` row in the column) has no solid tile anywhere beneath it — where the player is airborne on
a trajectory they cannot change. `Level.committedColumns` records them for the simulation. The
player **occupies** a committed column while any column their AABB overlaps is committed.

1. **Placement.** No enemy spawn or patrol sits on or within three columns of a committed column;
   no ranged or turret spawn has unobstructed line of fire into a committed span of `ArcMask`.
2. **Runtime.** An engaged Flyer may fly through a committed column and a ground enemy may cross it
   only on a leap whose fixed-step preview found a safe landing (enemies.md, PROD-088). Movement is
   not the safety boundary: no rank-and-file or boss swing, projectile, beam or contact drain deals
   damage while the player occupies a committed column, nor until the player has been grounded and
   clear of every committed column for `LANDING_GRACE = 0.25 s`. A player therefore keeps the same
   protected crossing and first grounded reaction window while pursuers remain able to follow.
3. **Survivability.** Enemy damage along the corridor is *survivable*, not *avoidable*: the bot
   playthrough walks the witness with guaranteed-only loot, with the population engaged, and
   asserts survival over the seed cohort.

## Verified properties

- **P-01** Seed cohort (40 seeds × 10 maps): every generated level's witness replays successfully
  on the first attempt — zero reseeds.
- **P-02** Witness replay transits the mini-boss arena and reaches the boss entry alive; replay is
  idempotent; a truncated witness does not report success.
- **P-04** Anti-stranding, as stated above, swept over the ten maps of one seed.
- **P-10** Every jet corridor has exactly one jet volume, pixel-measured safe zones on both sides,
  and an off-window that fits the crossing plus reaction time.
- **P-11** Every `CROUCH` node reaches a `STAND` node.
- **P-12** No enemy spawn or patrol touches a committed span; no ranged or turret spawn has line of
  fire into one; the runtime fairness rule holds, including across the boundary: a strike or a
  projectile/beam arriving on the first grounded tick after a crossing, or while any overlapped
  column is committed, deals nothing (P-34). Enemy traversal of those spans is verified separately
  by P-61 rather than forbidden.
- **P-22** Runtime generation + verification p99 < 400 ms on the widest map (generation.md).
