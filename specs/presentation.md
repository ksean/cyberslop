# Presentation

Everything the game draws is produced by code from the browser's 2D context (ENG-013, ENG-063).
Presentation state lives in `commonMain/render` and is testable without a browser (ENG-060);
`wasmJsMain/render/CanvasRenderer` walks the draw list and issues primitives, holding no rule.

## The draw list

Per-sprite `save`/`translate`/`rotate`/`restore` measured about 7.6× a bare draw on a throwaway
software-rasterization harness that was not retained; the figure is indicative and the
requirement is structural — the frame is never drawn entity by entity and no per-sprite transform
is used (ENG-061). `Scene` fills **style batches** — keyed by layer, style, primitive and stroke width — each a
flat `DoubleArray` of coordinates; the renderer sets style once per batch and loops. Three
primitives cover everything:

| Primitive | Fields | Used by |
|---|---|---|
| `Rect` | x, y, w, h | tiles, torsos, plates, HUD, backdrop |
| `Segment` | x1, y1, x2, y2, width | limbs, weapon barrels, swing arcs, jet columns, icons |
| `Dot` | x, y, r | eyes, projectiles, glow, muzzle flash, icon details |

A limb is a stroked segment rather than a rotated rectangle, so no transform exists to be slow. The
batch key includes the layer (painter's order) and the stroke width (one path stroked once). A
batch costs a fixed amount of drawing state — one property for a fill, three for a stroke, three
for a label. Buffers are reused per frame; allocation grows with actors on screen, not with the
primitives they draw. The batch count bounds state changes, which is what was measured; it does not
bound rasterization.

Layers, back to front: `Sky`, `BackdropFar/Mid/Near`, `Haze`, `Terrain`, `Hazard`, `HazardSurface`,
`ItemHalo`, `Items`, `ItemWear`, actors (with `ActorWear` over `ActorTrim`), `ActorStatus`,
`ShotGlow`, `ShotBody`, `ShotCore`, `Effects`, `Debug`, `Hud`, `HudOverlay`, `HudWear`. The halo, the
material and the weathering of an icon are on three layers, and a shot's three marks on three,
because on one layer their order depends on which batch opened first — and a wider batch a later
drop or a fresher impact opens paints over an earlier one's overlay. A bubble's glow dot is on
`Hazard` and its smaller body dot on `HazardSurface`, so every bubble remains a ring however the
acid body batches opened.

## The view

The camera view is measured in world units; `ZOOM = 3.5` makes a tile 56 screen pixels. Dead-zone
follow (25 % of the view) with look-ahead (12 %) in the facing direction; hard clamp to the level
bounds; on resize keep world-units-per-pixel fixed. The camera never frames an arena: a boss that
fights is engaged and pursuing, so it is within the awareness radius of the player the camera
follows. Boss projectiles and beams continue to terrain or the level boundary; the camera clips
their draw geometry at its own viewport rather than the simulation shortening them to fit. Player
ranged attacks are the deliberate exception: the most recently composed camera rectangle becomes
the next fixed tick's gameplay viewport under PROD-101. A travelling ranged shot spent at an edge
leaves its normal impact/tracer endpoint there; the renderer displays that simulation result and
does not independently decide whether the shot or an off-screen target can interact.

## Palettes and the world

Ten palettes, one per sub-theme, each with `sky`, `skyLow`, `backdropFar/Mid/Near`, `window`,
`tileBody`, `tileEdge`, `tileDeep`, `hazard`, `hazardGlow`, `accent`, `haze` and a three-step `glow`
ramp that is strictly increasing in luminance (enforced at construction). Colour temperature
drifts across the run, from cold slate and sodium orange to sterile white-gold.

Tiles have a lit top edge and a darker body (two batches, not two draws per tile). Acid has a
bright surface line and a dimmer body. Every exposed acid surface tile also carries three
two-tone bubble rings: a `hazardGlow` outer dot under a smaller `hazard` dot, at three horizontal
offsets and coordinate-derived phases. During a 1.2 s cycle each bubble rises through the upper
70 % of the liquid and grows from 1.5 to 4 screen px before resetting at the bottom; neighbouring
tiles and bubbles are out of phase, so the whole surface never pulses as one. The cycle reads the
same interpolated presentation time as hovering drops and has no simulation state. A fire jet has
a warm red-orange outer flame and a narrower yellow-white hot core. It rises as three pointed
tongues: one reaches the top of the jet volume and two asymmetric tongues split from it lower down.
Each tongue is a linked chain of diagonal strokes with alternating lateral turns rather than one
vertical stroke. Its joins move sideways through a deterministic 0.72 s loop while the base stays
centred on the outlet; different tongues use different phases, so the whole jet does not sway as
one rigid shape. The flame remains within the jet's one-column lethal footprint and
`topRow..bottomRow` vertical span. It is absent whenever `FireJet.isOnAt` is false, and changing
its shape never changes that on/off decision.

A broken-glass tile draws a static scatter only in the bottom 30 % of its cell: five disconnected,
unequal shard segments and three small crumbs, derived from tile coordinates without RNG. Fixed
rusty-brown `GLASS_RUST = #7a3f2b` shards and corroded-edge `GLASS_EDGE = #b66a45` crumbs form
irregular acute joins and broken slashes but no filled or closed triangle, common baseline or mark
taller than the 30 % band, so the patch reads as small jagged
ground debris rather than spikes. All shard segments use `GLASS_RUST` at one fixed stroke width and
all crumbs use `GLASS_EDGE` at one fixed radius, so one tile and any number of tiles open the same
two `HazardSurface` style batches. The
geometry is static presentation derived from `TileKind.BrokenGlass` and changes no collision,
damage, RNG state or digest.

Every jet also marks its supporting solid tile at `(column, bottomRow + 1)` with a permanently
visible broken pipe. A dull metal neck protrudes from the tile, a dark open mouth sits under the
flame, and an asymmetric split rim plus a descending crack makes the break readable without
colour or motion. The pipe remains visible while the jet is off; ordinary solid tiles do not gain
pipe marks. Pipe and flame geometry is derived from the existing `FireJet`, consumes no RNG, adds
no level or simulation state and changes neither the supporting `Solid` tile nor its collision.
The wave loop reads interpolated simulation time and freezes with pause.

### Cyberpunk-dystopian backdrops (PROD-040)

The backdrop remains three procedural parallax layers at exactly 0.12×, 0.30× and 0.55× the
camera rate, generated once per level from the `backdrop` stream and posed per frame by the existing
damped horizontal and vertical offset. The far layer carries the largest masses and fewest marks;
the middle layer adds recognisable infrastructure; the near layer has the densest roof damage,
supports, pipes, cables, vents, light strips and antennae. Detail must enrich a silhouette without
filling the playfield or competing with actors and hazards.

Every `ThemeId` has an authored backdrop profile. The profile selects building proportions, roof
profiles, window arrangements and at least three of the following primitive motif families:
stepped or broken rooflines, stacks, tanks, exposed pipes, gantries, suspended cables, antennae,
sign frames, buttresses, vents and bridge fragments. Seeded generation varies dimensions, spacing,
damage, lit cells and motif placement within that profile; it never replaces the profile with an
unrelated motif.

| Sub-theme | Required silhouette language and details |
|---|---|
| Ruined City Sprawl | sheared high-rises, missing roof corners, skeletal antennae and broken skywalk stubs |
| Rust Flats | low scrap sheds, tank farms, crooked derricks and corroded gantries |
| Flooded Undercity | squat pump houses, drainage mouths, raised pipe bridges and half-submerged tower remnants |
| Chem Foundry | distillation stacks, clustered chemical tanks, dense pipe runs and vent crowns |
| Neon Slums | crowded rooftop shacks, improvised sign frames, dish antennae and sagging utility cables |
| Sable Refinery | heavy cracking towers, flare stacks, refinery vessels and thick overhead pipes |
| Server Stacks | repeated cooling monoliths, ventilation grids, cable trunks and narrow status-light strips |
| Skybridge Ruin | isolated pylons, snapped elevated spans, skeletal towers and hanging cable silhouettes |
| Reactor Core | containment masses, cooling stacks, armoured conduits and sparse warning beacons |
| Arcology Vault | fortified mega-towers, sealed arches, surveillance spires and rigid transit ribs |

All motifs are abstract geometry with no readable text, logos or trademarks. They use the existing
draw-list primitives on `BackdropFar`, `BackdropMid` and `BackdropNear`; colour and primitive
vocabularies are fixed by depth, so the number of style batches does not grow with building or
detail count. No bitmap, font or other runtime asset is introduced. A backdrop reads no tile,
writes no tile, is excluded from the simulation digest and is never collidable.

Every burning barrel is topped by a smaller fire in the same warm outer and hot-core colours. A
broad central tongue and two shorter asymmetric tongues rise from distinct points on the drum lid;
each is a linked chain of diagonal strokes whose joints move laterally through a deterministic
0.72 s loop. Their different phases and heights keep the silhouette irregular, and the tongues do
not join into a closed triangle or converge on one shared apex, so a still frame does not read as a
spike. The bases stay attached to the lid and every stroke envelope stays within the existing flame
cell above the barrel. Coordinate-derived phase offsets keep multiple barrels from waving in
unison. The loop reads interpolated simulation time and freezes with pause; it consumes no RNG,
adds no level or simulation state and changes neither barrel contact nor the level digest.

The **exit corridor** is every column strictly greater than `Level.gateColumn`, the same boundary
whose first crossing completes a map after the boss opens the gate. Its floor remains ordinary
solid collision, but each exposed floor tile replaces the normal top edge with a fixed blue
`EXIT_SURFACE = #38a8ff` edge and draws three pale-blue spark dots on `HazardSurface`.
Coordinate-derived phases stagger a `0.90 s` loop in which each spark rises 8 screen px from that
edge and shrinks to zero before restarting. The edge's colour and the upward spark shape identify
the zone in a still frame; animation is additional feedback. It reads interpolated simulation
time, consumes no RNG, adds no level state and changes neither collision, completion nor the
digest. A pause freezes it with the rest of simulation-time presentation.

## The player rig

A humanoid is nine parts — head, torso, two upper arms, two forearms, two thighs, two shins — posed
by `Actor` into a `Pose` of resolved joint offsets in actor-local pixels (trig via `TrigTable`). A
pose carries a locomotion clip and an action overlay, each a total function of motion:

| Clip | Selected when | Reads as |
|---|---|---|
| `JumpRise` | airborne, `vy < 0` | legs tucked, torso forward |
| `JumpFall` | airborne, `vy ≥ 0` | legs reaching, torso back |
| `CrouchWalk` | crouched and moving | low box, short stride, head forward |
| `Crouch` | crouched | low box, knees bent, weapon across the body |
| `Run` | grounded and moving | full gait, lean proportional to `vx` |
| `Idle` | otherwise | weapon at rest, feet apart |

| Action | Selected while | Overrides |
|---|---|---|
| `WindUp` | an attack is telegraphing | lead arm drawn back and raised, held there |
| `Swing` | the simulation is still drawing a swing | lead arm sweeps the arc the hit covered |
| `Fire` | the simulation is still drawing a muzzle flash | weapon arm recoils and settles |
| `None` | otherwise | nothing |

The window of a wind-up, swing or flash is the simulation's, read off what it is still drawing, so
the arm cannot drift out of step with the hit. Only the arm chain changes under an action; the
legs keep what the clip gave them. Gait phase is distance travelled (`stridePx / 34 px`),
accumulated in the simulation — not wall time — so feet plant by construction. `stridePx` lives on
`GameSimulation` and `LiveEnemy`, deliberately outside the hashed `PlayerState`.

The same rig, clips and actions pose enemies and bosses: an enemy's `Motion` carries its own
wind-up, swing and shot windows, so a Brute winding up reads as a Brute winding up (PROD-063), and
a boss walking toward the player runs its gait rather than sliding. A boss attack's telegraph is
its `WindUp`; each active melee event is a `Swing` with its own swoosh, each projectile emission a
`Fire` with its own muzzle flash, and a Laser emission a charged lens plus its beam. Flurry and
Burst therefore visibly resolve three times rather than representing three hits with one pose. The
telegraph colour change stays through every profile.

**Crouch is a pose (PROD-067).** `Crouch` and `CrouchWalk` keep the standing figure's limb lengths
and bend the joints: hips dropped to about half the standing hip height, knees bent forward of the
hip–ankle line, torso and head leaning forward, weapon held low across the body. The figure's
highest point stays within the physics' crouch height; nothing is scaled.

### Player death (PROD-103)

A terminal player pose supersedes the normal locomotion clip and action overlay, so an attack arm
cannot keep swinging or firing after death. At death age zero the rig equals the pose drawn on the
lethal tick. Over `COLLAPSE_SECONDS = 2.0 s`, its joint angles and body anchors interpolate toward
a side-prone pose in the last facing direction: hips and torso lower, the torso turns horizontal,
knees fold, and the head finishes beside the torso. The rig resolves every intermediate pose from
its normal fixed limb lengths, and the effect is a pose only —
it never rewrites the player's physics box or position. From two seconds until
`DEATH_SEQUENCE_SECONDS = 4.0 s`, the final pose is held. The camera retains its lethal-frame
follow point throughout.

Exactly one cause effect is anchored to the interpolated player body for the complete four
seconds. Poison uses several independently phased, two-tone rising bubble rings around the torso
and head; flame uses upward forked tongues and embers; bleed uses pointed droplets falling from
the body. Their ring, fork and droplet silhouettes distinguish them without relying on colour or
motion. They follow the collapsing body, draw above the player on `ActorStatus`, and neither
create a hazard nor hide the held weapon. A cause mapped to `none` in simulation.md draws no cause
effect. Phase is derived from terminal age with no RNG or mutable renderer state, and browser
suspension freezes the collapse and effect together.

## Weapon effects (PROD-033, PROD-066)

- A player's `ArcSwing` is a **swoosh** drawn as one closed, swept fan: the outer boundary is the
  resolved reach, the radial boundaries are the angular interval swept so far, and two nested arcs
  plus sparse radial ribs make the enclosed region read at a glance. Its origin, locked direction,
  arc, reach and progress come from the gameplay-owned active swing (combat.md), never parallel
  renderer constants. Boundary strokes sit inside the footprint they describe. The fan may thin
  or dim during its window but never moves or draws outside the active region, and it disappears
  with that region rather than leaving a harmless afterimage. The player's swing pose uses the same
  locked direction, arc and progress, so the arm and held weapon do not sweep a generic angle.
- Every damageable enemy and boss is drawn with its damaging body silhouette inside the canonical
  combat body used by player-melee collision. Glow, health bars, held hardware and attack effects
  may extend outside it. A frame is composed only after the active swing has tested the combat
  bodies at the positions that frame draws, so a visible swoosh/body overlap has a direct hit from
  that swing (PROD-033, P-63).
- A ranged shot draws a **muzzle flash** at the barrel: a bright core dot, a longer bloom segment
  along the projectile's actual initial velocity and two short spikes at ±35°, fading over the
  flash window. This equals the aim for a straight shot; a lobber's flash visibly points into its
  upward launch.
- **Every shot shows where it went (PROD-071), and what fired it (PROD-080).** A travelling
  projectile is drawn in four marks in one **shot look**: a **glow** dot at `1.8 ×` its hit radius
  behind everything, a **body** dot at its hit radius, a **core** dot at `0.45 ×` its radius on
  top, and a **two-tone tracer** trailing it along its velocity for `TRACER_SECONDS = 0.05 s` of
  travel (17 px at the enemy shot speed) — a wide bloom segment in the glow colour under a thin
  one in the core colour — so a shot reads as a lit line of flight rather than a floating dot.
  The look is chosen by what fired the shot, from `ShotLooks`:

  | Fired by | Glow | Body | Core |
  |---|---|---|---|
  | a ranged weapon of the player's | `#7a3a10` | `#ff9a3c` | `#fff1c4` |
  | a psychic weapon of the player's | `#3a1a6e` | `#b06cff` | `#efe2ff` |
  | an enemy or a boss | `palette.hazardGlow` | `palette.hazard` | `#ffffff` |

  The player's colours are fixed across themes so that PROD-051's disjointness with the item
  colours is a property of two constant sets rather than of ten palettes. A player's shot with no
  build recorded (there is none in play; the branch exists) takes the ranged look. The bloom is
  `TRACER_BLOOM_WIDTH = 5` px under a `TRACER_WIDTH = 2` px core. Every impact kept for the flash
  window takes the same four-mark look as the live shot in the shooter's colours — an impact
  carries whether a psychic build fired it (`HitShape.Impact.psychic`) and is drawn at
  `IMPACT_PX = 5` px, both radii and widths thinning with the window, since the live shot's radius
  is not kept. Boss Bolt, Burst and Scatter rounds are ordinary live enemy-look projectiles; a
  Laser uses the same glow/core pairing on its locked segment. The marks are on three layers under
  `Effects` — `ShotGlow`
  (bloom and glow dot), `ShotBody`, `ShotCore` (core line and dot) — so glow is under body under
  core whatever opened first; a frame holding live shots of all three looks opens at most
  **fifteen** batches for them, a constant no number of shots moves (the fading impacts add only
  ladder widths). An attack that resolves instantly leaves a **hit indicator** on the
  simulation for the flash window, presentation-only and outside the digest like the swoosh, whose
  geometry is the geometry the hit test used:

  | Pattern | Indicator |
  |---|---|
  | `Strike` (Kessler) | a **beam**: a vertical segment from the top of the view down onto the strike point, with a core and a wider bloom, and a ring at the strike's scaled radius |
  | `Chain` | a **chain**: one segment from the weapon to the first target struck and one between each consecutive pair, in strike order, with a spark dot at every vertex |
  | `Blast`, `Pull` | a **ring** at the resolved radius, centred where the blast resolved |
  | `Orbit` | a ring at the orbit radius around the pattern's anchor — the aimed target for a cursor-anchored weapon |

  Each fades over the flash window: every indicator stroke thins with the window's remaining
  fraction. A chain that struck nothing draws nothing beyond the pulse; a beam is drawn whether or
  not the strike hit, because the strike point is what the player aimed. A projectile spent inside
  the tick it was fired — a point-blank hit, or terrain at the muzzle — is never in the live list
  when a frame is drawn, so its last line of flight is kept as an **impact** for the flash window
  and drawn as the same tracer.
- A weapon with no barrel — every psychic weapon (orbs, blasts and chains) and every
  cursor-anchored one (the Kessler dish) — draws an **activation pulse** instead: a ring around
  the held weapon that grows as it fades over the flash window.
- Enemies use the same swoosh and flash, in the enemy's own colours, drawn from their posed hand
  or barrel.

## Enemy looks

Five silhouettes that differ in shape:

| Archetype | Silhouette | Presentational motion |
|---|---|---|
| Swarm | small, hunched, spindly, oversized head | fast twitchy gait |
| Shooter | upright, one long weapon arm | measured walk; arm tracks the player inside firing range |
| Flyer | legless pod, twin thruster plumes | hovers, bobs, thrust trails its travel |
| Turret | wide cannon pod on short folded articulated legs | waits folded; unfolds to crawl, tracks with its barrel and tucks its legs during a leap |
| Brute | broadest, heavy shoulder plates, short thick limbs | slow heavy gait |

`EnemyLook.of(archetype, mapIndex)` returns `bulk`, `plates`, `spikes` and a glow tone that move
with the enemy's health. Plates and spikes are monotone across the whole grid; drawn size
(`height × bulk`) and luminance are monotone within a map (PROD-042) — a whole-grid size ordering
would force every archetype to the same height. An airborne walker or boss selects `JumpRise` or
`JumpFall` from its actual vertical velocity and does not advance a grounded gait while airborne.

**Boss loadouts (PROD-089).** Bosses reuse the rig at `MINIBOSS_SCALE = 2.6` and
`BOSS_SCALE = 3.7` with a health bar and the existing two-plate/four-plate crown distinction
(PROD-043), but no longer reuse one generic armed silhouette. `BossLook.of(profile, mapIndex,
isMain)` composes the profile's modules onto the body. Every enabled module, including a main
boss's not-yet-unlocked signature, contributes its marker from the first frame:

| Module | Shape marker, readable without colour | Active motion/effect |
|---|---|---|
| Slam | one oversized weighted forearm | overhead arm and ground swoosh |
| Sweep | one long lateral blade | level arm and level swoosh |
| Flurry | paired short blades | alternating arms and three separate swooshes |
| Rush | forward ram plate and piston shins | forward lean, tucked arms and trailing swoosh |
| Bolt | one narrow barrel | one recoil and flash |
| Burst | narrow barrel plus long magazine | three recoil/flash pulses |
| Scatter | short, wide five-port muzzle | five simultaneous flashes and diverging tracers |
| Laser | large circular lens between two emitter rails | charge glow, then bloom/core beam |

Markers are silhouette geometry, not labels or palette swaps. The primary melee implement occupies
the lead arm and the primary ranged hardware the opposite shoulder/arm. A main boss's locked
signature is visibly folded high on its back and unfolds into its active mount at 60 % health; it
is never mistaken for a primary copy. Thus even two main profiles that contain the same three
modules but assign different primaries have different colour-stripped geometry. Menace plating and
the map palette still scale as before, and neither may cover the implement tip, muzzle ports or
laser lens that communicates the attack.

**Enemy damage-over-time indicators (PROD-093).** Every living `LiveEnemy` whose
`burn.secondsLeft > 0` draws three two-tone flame chevrons and round ember dots rising over the
actual drawn body on `ActorStatus`; every one whose `bleed.secondsLeft > 0` draws three pointed
crimson droplets falling from that body. The upward forked flame and downward pointed drop remain
distinct in a still, monochrome frame. Their coordinate-staggered cycles are respectively 0.75 s
and 0.65 s, read from interpolated simulation time, and both sets draw when both statuses are
active. Hurt flash colours do not cover them. They disappear on the first frame their status is no
longer active or the enemy is dead. The indicators add no mutable state, randomness, damage or
digest fields; pausing freezes their phase.

**Hurt flash (PROD-076).** A hit — a swing, a projectile landing, a blast, a chain jump, splash;
not a burn or bleed tick — sets `hurtSecondsLeft = HURT_FLASH_SECONDS = 0.12 s` on the enemy or
boss, a presentation-only field decayed by the simulation like an enemy's `lastSwing` visual and
outside the digest. The player's active `ArcSwing` is rule-bearing and is not in that category
(P-40, P-63).
While it is positive the figure's body, limbs, head, plating and (for a boss) crown are drawn in `Palettes.HURT`
(`#ff3b30`) instead of their own styles — every form (biped, hover, crawler) — and the eye glow is
unchanged. A boss's telegraph colour wins over the flash: a telegraphing boss stays in the
telegraph colour however hard it is hit, because the tell is a fairness signal. The flash is a
style swap, so a frame holding both hurt and unhurt figures opens at most one extra batch per
figure batch kind (fifteen at the worst), a constant that no number of enemies moves (P-23).

Every positive damage event that lowers the player's current health sets the same 0.12 s window on
a presentation-only `playerHurtSecondsLeft`; damage prevented by a fairness rule and healing set
nothing. Repeated per-tick contact or hazard damage refreshes the window while damage continues.
During it the player's body, limbs, head, trim and arms use `Palettes.HURT`; the eye and held weapon
keep their identifying styles. It decays only on simulation ticks, is neither saved nor digested,
and changes no damage or invulnerability rule (PROD-095).

**Health bars (PROD-077).** A living rank-and-file enemy whose health is below its archetype's
maximum for the map draws the boss's bar above its figure: a dark back rect and a fill rect of
`healthFraction` of the width, width `ENEMY_SIZE × ZOOM`, on `Layer.Effects` in the same two
styles as a boss's, so every bar on screen shares the boss's two batches. An enemy at full health
draws none.

## Item icons

An icon is a list of `Stroke(x1, y1, x2, y2, weight, material)` and `Dot(x, y, r, material)` ops in
a local `[-1, 1]²` box, keyed by `WeaponId` or `PowerupId` in one registry (ENG-064). Only segments
and dots, because a segment is closed under rotation or reflection and a rectangle is not.
Orientation takes a unit aim `(ax, ay)` and a handedness `s`: `s = +1` for a right-facing
presentation and `s = −1` for a left-facing one, giving
`(u·ax − s·v·ay, u·ay + s·v·ax)` — no transform and no trig. Ground, HUD and discovery-card icons
use `s = +1`. A held icon uses the sign of the aim's x component, falling back to the actor's
facing when it is exactly vertical. Thus horizontal left maps `(u, v)` to `(−u, v)`, and an angled
left aim is the pointwise horizontal mirror of the corresponding angled right aim: the muzzle
follows the target without turning the stock, blade teeth, magazine or sight upside down
(PROD-084).

- **Three weights** — `Hair` 0.07, `Line` 0.13, `Slab` 0.28 of the half-extent — with round caps;
  a `Slab` is a filled bar, and every gap is measured against cap extension.
- **Materials (PROD-078)** — every op names what it is made of, and the material is its colour,
  fixed across themes. Five, no more, because every material is a batch style:

  | Material | Colour | Reads as | Weathering |
  |---|---|---|---|
  | `Wood` | `#8a5a2e` | a grip, a haft, a stock | a darker **grain** streak, `#4a2e14` |
  | `Steel` | `#9aa3ad` | a blade, a barrel, a receiver, a casing | a **rust** streak, `#b4542a` |
  | `Rust` | `#b4542a` | a part that is nothing but corrosion — chain links, a pipe, a rebar club | none: it is the streak |
  | `Glass` | `#7fa39a` | a bottle, a vial, a lens, a screen | none |
  | `Energy` | `#e8c46a` | a muzzle point, a coil, a field, a psychic wound | none |

  `Steel` is the default, so an unmarked op is metal. **Weathering** is systematic rather than
  authored: the painter draws every eligible `Steel` and `Wood` stroke twice, the material at its
  weight and then a streak one weight lighter (`Slab` → `Line`, `Line` → `Hair`) along the
  stroke's **rear 40 %** — from 55 % to 95 % of its length — on the wear layer above the
  material's, so no icon carries rust as extra geometry and every blade, barrel and haft is
  pitted the same way wherever a streak fits. A streak is drawn only where the ladder gives it a width **strictly under**
  its stroke's: a `Hair` never gets one (the ladder's floor is 1.5 px), and a `Line` on a small
  held weapon (Chrome Fang's hand scale is about 11.5 px) does not either, because a streak as
  wide as its line replaces rather than weathers it. A `Steel` or `Wood` dot — a rivet, a cap —
  gets no streak. **Every item bears at least one wear cue** (PROD-078): a `Rust` op, or a
  `Steel`/`Wood` stroke that carries a streak at the HUD's 8 px scale — the smallest an icon is
  drawn at, where a `Line`'s streak no longer fits and only a `Slab`'s does; a field of pure
  `Energy` and `Glass`, or an icon of nothing but `Line` metal, is given a corroded part. The icon's geometry, and so its
  identity (P-27, P-28), is unchanged by weathering; the sheet is the judge of how aged it looks.
  A streak lies on its own material, so it is judged against that material on the sheet and not
  against the backgrounds (P-30 ranges over the five material colours and six rings).
- **Kind and weapon tier (PROD-050)** — a drop is ringed: a stroked circle of sixteen chords at
  `KIND_RING = 1.35 ×` the icon's scale, one `Hair`-of-scale wide over its `Hair` halo. A powerup's
  ring remains fixed blue `#3d8bff`. A weapon ring is fixed by tier across every theme: T1 white
  `#f4f4f4`, T2 green `#39d353`, T3 gold `#ffd45a`, T4 purple `#b45cff`, T5 red `#ff2f2f`.
  The tier pips under a weapon ring use the same tier colour; powerup pips remain blue.
  T1–T3 and powerup rings have no coloured bloom. Before the near-black ring halo is drawn, T4
  draws the same sixteen-chord purple circle on `ItemHalo` at
  `Scene.strokeWidth(0.25 × scale)`; T5 draws its red circle there at
  `Scene.strokeWidth(0.34 × scale)`. The ordinary near-black halo covers the bloom's centre, so
  only a restrained coloured edge remains, with the T5 edge wider than T4. The ring and bloom are
  drawn by the pickup, not by the icon, which keeps them off the hand and out of the HUD:
  `IconPainter` knows nothing of either. Kind is also said by casing, and rarity by the existing
  tier pips, so neither meaning relies on colour.
- **Halo** — `#05060a`, under every line and the ring, fixed across themes. Every icon is drawn
  twice, halo under material, because a coloured line alone is within 2.0 luma of a tile colour on
  one palette and the halo alone within 0.1 of a sky on another; the pair separates by ≥ 40 for
  every material and all six ring colours on all ten palettes (PROD-051). The `Wood` colour is the
  darkest allowed: at luma 100 against the halo's 6 the pair still clears the rule on every
  background between them.
- **Hover (PROD-079)** — a drop is drawn at `y − HOVER_PX × sin(2π · t / HOVER_PERIOD + φ)` with
  `HOVER_PX = 4` screen px, `HOVER_PERIOD = 1.8 s`, `t` the frame's **presentation time** — the
  tick's elapsed simulation time less the fraction of a tick the frame has not reached,
  `t_tick − (1 − alpha) × TICK_SECONDS`, clamped at zero, the same `alpha` the player and camera
  are interpolated by (ENG-062) — and `φ = x_world / 40` so neighbouring drops, and the two halves
  of a paired award, are out of step. The ring and the
  pips hover with the icon. A death drop's resting origin is its jump-required simulation site
  (PROD-090), not the corpse or the floor. The simulation's item position, and so the pickup
  overlap, never moves.
- **Rarity** — `PickupLook.scale` 1.0 → 1.9 across the five tiers over `PICKUP_PX = 14` screen px
  (28 px at Street to 53 px at Ascended), plus a row of `tier + 1` pips in the kind's colour under
  the ring.
- **Composition** — icons are built from shared parts (`grip`, `stock`, `muzzle`, `ring`,
  `Icon.cased`); a melee weapon is a mass on a handle, a ranged weapon a barrel over a grip, a
  psychic weapon has neither. A `grip` and a `stock` are `Wood`, a `muzzle` is `Energy`, a `ring`
  is whatever the caller says, the casing is `Steel`.

Icons vary in geometry, not style, so the item layers open a bounded number of batches with all
forty-four on screen: five materials plus two streak colours plus six ring colours, each over the
distinct ladder widths the five tier scales snap a weight onto — at most **four** per weight (a
`Slab` body is 3.5, 4.5, 6, 6, 8 across the tiers) — is the vocabulary, and `PickupIconTest`
derives the bound from the ladder, including the two fixed tier-bloom widths on `ItemHalo`, and
counts against it rather than believing arithmetic in prose (P-31 states it). Whether an icon is *recognisable*, or looks
aged, is a human judgement made against the icon sheet, not a test.

## Ramen pickup and heal flash (PROD-110)

Ramen is a grounded food pickup, not a weapon or powerup icon. Its fixed drawing fits within
a 40 × 36 screen-pixel box: a dark-backed, shallow rust-red bowl (`#8f4a32`) has a thick rim,
two inward-sloping sides and a short base, with a worn highlight (`#c36b45`). At least two connected
pale-gold noodle strokes (`#d6b85f`) alternate slope as they rise just above the rim. Two distinct,
parallel brown chopsticks (`#7b4a2d`) emerge from the bowl's right half and angle upward to the
right. The silhouette must remain identifiable with colour removed. The bowl's bottom is aligned
to its selected support surface. It has no kind ring, tier pips, coloured bloom, rarity scaling or
hover; presentation time never changes its geometry or position. Its complete geometry and every
stroke width use the single design-space signature below at `RAMEN_VISUAL_SCALE = 2.0`. Coordinates
are screen-pixel offsets from the ground-aligned anchor before that scale is applied; each arrow
joins consecutive endpoints in one polyline.

| Part | Design stroke | Design-space endpoints |
|---|---:|---|
| Bowl outline | 2.0 | `(-8,-7)→(8,-7)`; `(-7.5,-6.5)→(-4,-1)`; `(7.5,-6.5)→(4,-1)`; `(-4,-1)→(4,-1)` |
| Bowl body | 1.5 | the same four segments as the outline |
| Worn mark | 1.5 | `(3.5,-1.8)→(6.4,-5.8)` |
| Left noodles | 1.5 | `(-5,-7)→(-6.5,-9)→(-4.5,-11)→(-6,-13)` |
| Middle noodles | 1.5 | `(-1,-7)→(0.5,-9)→(-1.5,-11)→(0,-13)` |
| Chopsticks | 1.5 | `(2,-6.5)→(7,-15.5)`; `(4,-6.5)→(9,-15.5)` |

The final stroked envelope is 37.5 × 32.5 screen pixels and its bottom is at anchor y. This table,
the scale and the styles above form the current ramen drawing definition; no prior-size geometry is
part of the contract.

Collecting a bowl starts `playerHealSecondsLeft = HEAL_FLASH_SECONDS = 0.12 s`, including when the
health cap prevents an increase. While positive, the player's body, limbs, head, trim and arms use
`Palettes.HEAL` (`#39d353`); the eye and held weapon keep their identifying styles. An overlapping
red hurt flash retains priority and the green timer does not decay while red is visible, so neither
feedback event erases the other. The heal timer advances only on simulation ticks, freezes with
pause, is not saved or digested, and changes no health, damage, invulnerability or input rule.

## Scrap-gain feedback (PROD-086)

All four positive in-run Scrap paths — the 2-Scrap enemy kill, the main boss's 40 Scrap, weapon
resolution (a replacement plus its cleared powerups or a same-weapon pickup), and a scrapped or
displaced powerup — go through one `gainScrap(amount)` boundary. Positive calls in one simulation
tick are summed into one
`ScrapGain(amount, origin, secondsLeft)` after pickup and reward resolution. Its origin is the
horizontal centre of the player's box, 6 world px above the visible head at the end of that tick.
The origin is then fixed in world space rather than following the player; a later gain creates a
separate label. Zero/negative changes, run-end banking, migration and shop spending create none.

For `SCRAP_GAIN_SECONDS = 0.90 s`, `Scene` draws exactly `+$amount` centred at that world anchor in
bold 18 px type and fixed gold `#ffd45a`. With `p = age / SCRAP_GAIN_SECONDS`, its screen y is
`originY − 20 px × p` and its opacity is `1 − p`: it rises linearly by 20 screen px and is fully
absent at expiry. Position and opacity use the same interpolated presentation time as the player,
so a frame between ticks does not stair-step. Opacity is a numeric `TextItem` property decided in
`commonMain`; the browser renderer only applies it and restores full opacity. The label is
presentational, is not saved, does not change `run.scrap`, and is excluded from the simulation
digest. A discovery-card pause freezes it because no simulation tick consumes its lifetime.

## Basic audio feedback (PROD-102)

Audio is driven by semantic cues returned from the fixed simulation tick, not by polling a visual
timer or recounting projectiles in the browser. `MeleeSwing` is emitted once when the player's
melee activation begins. `RangedFire` is emitted once for a simultaneous ranged volley and once for
each later round that actually leaves a time-separated burst. `PsychicFire` follows the same event
boundary for a psychic weapon: one cue for the trigger's simultaneous volley and one for each later
round that actually leaves a time-separated burst. Projectile count, hits and pierce do not
multiply either firing cue. `PickupPulse` is emitted once per contacted `GroundItem` removed by
pickup resolution, whether it held a weapon, a powerup, a paired award or ramen and whether its
loot was equipped, applied, displaced, scrapped, healed or capped by full health. Ticks with none
of those transitions emit no cue. Enemy
and boss attacks emit none in this basic set.

The browser adapter owns one Web Audio context, lazily created and resumed by the player gesture
that starts or continues a game, and synthesizes all four cues with short gain-enveloped oscillator
patches: a falling airy swing no longer than 140 ms, a sharper falling fire burst no longer than
100 ms, a soft rising pickup pulse no longer than 120 ms, and a subtle psychic warp no longer than
130 ms. The warp uses a smooth sine tone that starts near 320 Hz, bends down near 190 Hz, then rises
near 540 Hz; its peak gain is at most 0.05 so it reads beneath the ranged-fire patch rather than as
an alarm. Every patch has peak gain at most 0.12 and stops and disconnects its nodes at the end of
its envelope. There are no fetched or embedded sound files and no new dependency. The adapter
performs no audio operation before that gesture; if the browser still suspends or rejects audio,
the cue is skipped without throwing, changing game state or being queued for replay. A pause
produces no simulation cues, and resuming never replays old ones.

Sound is supplementary: every cue duplicates the visible swing, firing effect or disappearing
pickup, and no rule, warning or choice is communicated by audio alone. Cue values are
presentation-only, are not saved or digested, and consuming them cannot change deterministic
simulation state.

## HUD and screens

The HUD shows health, the equipped weapon's icon and name, each held powerup's icon and stack
count, and the map index and sub-theme (PROD-045). The title and shop are DOM screens with real
buttons and accessible names. A first-pickup discovery card is drawn centred over a dimmed canvas
with the registered icon, item name and description; its text is repeated through the visually
hidden live region (PROD-083, progression.md). The canvas carries `role="application"` and an
`aria-label` which names the arrow and A/D/S/W movement bindings, Space jump, automatic fire and
Escape pause.
Window focus loss and a hidden page clear held keys and pause; canvas focus loss clears held keys
(`specs/simulation.md`, key ledger).

An in-map manual pause leaves the last composed canvas visible and dimmed and opens a centred DOM
dialog titled `Paused`, with real buttons in `Resume`, `Return to title` order. The dialog has an
accessible name, is announced through the live region and focuses `Resume` on opening. `Resume` or
`Escape` closes it and returns focus to the canvas; `Return to title` uses progression.md's
voluntary run-ending transition. The dialog remains open across window blur/focus and visibility
changes, and no discovery card or other simulation-time presentation advances behind it.

On player death the canvas and HUD remain visible without a DOM dialog during the complete
four-second terminal sequence. Gameplay keys and `Escape` cannot dismiss it. At completion the
canvas is hidden and the existing DOM run-ended screen titled `You died` is shown and announced;
its `Return to title` button receives focus as before.

## Verified properties

- **P-23** Batch bound: the same scene at 10 and at 600 entities issues the same number of
  drawing-state changes, counted at the paint sink; no batch mixes stroke widths; the production
  bundle's smoke test bounds strokes per frame at 48 — the start frame measured 14 before drops
  were drawn in materials and 34 after, and the bound is a constant over drops, not segments.
- **P-24** Menace monotonicity: over the archetype × map grid ordered by health, plates and spikes
  are non-decreasing and strictly increasing between the extremes; within each map, drawn size and
  glow luminance are non-decreasing in health; every palette's glow ramp is strictly increasing.
- **P-26** Animation totality: every reachable motion selects a clip and an action; every clip and
  action is selected by some motion; crouch clips are shorter than standing clips by the physics'
  crouch height; a run cycle's lead foot alternates by a visible fraction of the figure's height;
  an action leaves the legs untouched.
- **P-38** Poses for attacks and crouch: an enemy motion in wind-up selects `WindUp`, in a swing
  `Swing`, after a shot `Fire`, each leaving the legs untouched; a shot draws its flash at the
  posed barrel; the crouch pose's limb segment lengths equal the standing pose's, its knees sit
  forward of the hip–ankle line, and its highest point is within the crouch height; the swoosh's
  outer arc radius equals the swing's reach. A player's swing pose takes its direction, arc and
  progress from the active `ArcSwing`, while enemy and boss poses keep their attack-owned motion.
  Each scheduled Flurry swing and Burst round selects a distinct action pulse on its own event tick
  rather than one visual for the whole active window.
- **P-43** Shots show where they went: a live projectile draws a dot at its position **at its hit
  radius** and a segment from it back along its velocity of `speed × TRACER_SECONDS`, player and
  enemy shots in their own styles; successive Ashfall frames therefore show a tracer tangent that
  rises, levels at the apex and falls with the simulation-owned velocity, and its muzzle flash uses
  the initial upward tangent. A projectile spent on the tick it was fired still leaves that tracer;
  boss Bolt, Burst and Scatter events produce respectively one, three straight and five
  diverging live projectiles with matching flash counts in the enemy-shot style, while an active
  Laser draws its locked 10 px bloom/core segment; a Kessler strike leaves a beam whose foot is the strike centre and a
  ring whose radius is the scaled strike radius; a chain leaves a segment per jump whose endpoints
  are the struck targets in strike order and none when nothing was struck; a blast, a pull and an
  orbit each leave a ring of the radius they resolved at — the pattern's own declared radius, scaled
  — and an orbit hits inside that radius and not beyond it; an indicator's stroke width falls
  across its window; every indicator is gone after the flash window; the digest (P-40) is unchanged
  by any indicator.
- **P-47** Hurt flash and health bars: an enemy hit this tick is drawn in `Palettes.HURT` on
  every figure batch (body, limbs, head) and its eye glow is not; a burn tick does not flash it;
  it returns to its own styles after `HURT_FLASH_SECONDS`; a hit boss flashes, crown included,
  unless it is telegraphing, in which case the telegraph colour is drawn; the hover and crawler forms flash
  too; a full-health enemy draws no bar, an enemy at 40 % draws a back rect of `ENEMY_SIZE ×
  ZOOM` and a fill of 40 % of it above its figure; a frame of 600 half-hurt, damaged enemies
  opens the same number of batches as one of 10 (the flash opens at most one red batch per
  figure batch kind — a constant — and the bars share the boss's two); the digest is unchanged
  by the flash.
- **P-27** Icon totality and distinctness: every id resolves to an icon, purely; no two icons are
  equal as geometry; every op is inside the box; every icon has at least three strokes spanning at
  least 60 % of the box's longer axis.
- **P-28** One icon, four presentations: ground, hand, HUD and discovery card draw the same op list
  and materials, differing only in scale and orientation; orientation preserves every distance and
  either preserves or horizontally reflects handedness.
- **P-29** Kind and tier survive colour removal: casing on every powerup, on no weapon; item id →
  kind and weapon tier → ring colour are total; the powerup blue differs from every weapon ring
  colour and the five weapon ring colours are pairwise distinct. Tier size and pips remain the
  non-colour rarity cue.
- **P-30** Legible on every map: for each palette and each of its nine background colours, and
  for each material colour and each ring colour, the halo or the line differs in luminance by
  ≥ 40; no items-layer style appears on the hazard or effects layer in the same frame.
- **P-31** Icon batch bound: a frame with the forty-four icons once and a frame with them four
  times issue the same number of drawing-state changes, and the item layers open ≤ `ITEM_BATCHES`,
  derived in the test from the vocabulary above and the distinct widths the ladder actually
  yields per weight over the five tiers.
- **P-50** Materials and weathering: every op in every registered icon names a material; each of
  the five materials is used by some icon; every `Steel` and `Wood` `Slab` or `Line` stroke drawn
  is followed by a streak in that material's weathering colour, at the lighter weight, whose
  endpoints lie at 55 % and 95 % along the stroke, on the wear layer above the material's; a
  `Hair` stroke, a dot, and a `Rust`, `Glass` or `Energy` stroke draw no streak, nor does any
  stroke whose streak would not be strictly narrower than it; every registered icon has a wear
  cue at the HUD scale and at the ground scale — a `Rust` op or a `Steel`/`Wood` stroke whose
  streak fits at that scale; a frame mixing tiers has every
  streak on the wear layer, one per weathered stroke of each icon, and none on the material
  layer; the ground, hand and HUD presentations emit the same material per op (P-28 extended to
  colour).
- **P-51** Kind and tier ring: each weapon drop holds one sixteen-chord stroked circle of radius
  `KIND_RING × scale` about the icon's drawn origin in its exact tier colour — T1 `#f4f4f4`, T2
  `#39d353`, T3 `#ffd45a`, T4 `#b45cff`, T5 `#ff2f2f` — and a powerup drop's is `#3d8bff`.
  Every ring sits over a sixteen-chord near-black halo of the same radius. T1–T3 and powerup frames
  contain no coloured bloom; T4 contains one purple sixteen-chord bloom at the same radius with
  width `Scene.strokeWidth(0.25 × scale)`, and T5 one red bloom with width
  `Scene.strokeWidth(0.34 × scale)`, both below the near-black halo, with the T5 width strictly
  greater at their registered scales. Pips use the owning ring colour. The player's held weapon,
  HUD icon and discovery-card icon draw no segment in any ring or bloom colour; an icon's own ops
  never contain one.
- **P-52** Hover: the drawn origin of a drop at time `t` is its world position less
  `HOVER_PX × sin(2π t / HOVER_PERIOD + φ)`; at `t + HOVER_PERIOD` it is the same; over a period
  its extremes differ by `2 × HOVER_PX`; the ring and pips move with it; both halves of a paired
  award hover and peak at different times; a frame at `alpha = 0.5` is drawn at the time a frame
  half a tick earlier at `alpha = 1` is; the simulation's item positions, the pickup overlap and
  the digest (P-40) are unchanged by the frame time; a death drop oscillates about, and is tested
  for contact at, the same raised resting position selected by P-64.
- **P-53** Shot looks: a live player's projectile fired by a ranged build draws glow, body and
  core dots at `1.8 ×`, `1 ×` and `0.45 ×` its hit radius in the ranged look's three colours and a
  two-tone tracer (bloom then core) of `speed × TRACER_SECONDS`; a psychic build's shot in the
  psychic look; an enemy's in the palette hazard look with a white core; an impact, every boss
  projectile and a boss Laser use the same look as the live shot would, and a same-tick psychic hit records `psychic`
  and is drawn violet; glow marks are on `ShotGlow`, body dots on `ShotBody` and core marks on
  `ShotCore` even when two impacts of different ages open different tracer widths; a frame with
  fifty shots of every look opens the same number of shot and effect batches for them as a frame
  with one of each; no shot colour is an item colour (PROD-051).
- **P-55** Held-weapon handedness: for every icon, a horizontal left aim maps every local point
  `(u, v)` to `(−u, v)` relative to the hand where a right aim maps it to `(u, v)`; left-up and
  left-down presentations are pointwise horizontal mirrors of their right-side counterparts.
  Stroke weights, materials and inter-point distances are unchanged, and the icon's positive-x
  damaging end remains on the aim ray. Ground, HUD and discovery presentations remain right-facing.
- **P-58** Bubbly acid: every visible acid tile with no acid directly above retains one body and
  bright surface and draws three two-tone bubble rings with distinct phases, glow under body on
  separate ordered layers. At two presentation
  times inside the 1.2 s cycle at least one ring has changed height and radius; at times one full
  cycle apart the acid draw list is equal. Equal level, camera and time always give an equal frame;
  changing or drawing the bubbles changes neither tile contact, lethality nor the simulation
  digest, and the bubble styles add a constant number of batches independent of pool width.
- **P-59** Scrap feedback: each of the kill, boss-award, different-weapon reset, same-weapon
  conversion and powerup-scrap paths raises `run.scrap` by its exact amount and creates a label for
  that amount; two positive awards in one tick create one label for their sum, while awards on
  different ticks remain distinct. At birth
  the bold golden `+X` is centred 6 world px above the player's then-current head; halfway through
  its 0.90 s life it is 10 screen px higher at 0.5 opacity; at expiry it is absent. Moving the
  player after birth does not move its world anchor; interpolation produces an intermediate y and
  opacity; a paused simulation does not age it. Zero gain and profile/shop changes create none,
  and adding, ageing or drawing labels changes neither the canonical save nor the P-40 digest.
- **P-62** Boss profile appearance: every attack module maps to exactly the silhouette marker in
  the table above; every legal mini-boss and main-boss profile draws all and only its enabled
  markers, including a locked signature, in both facing directions and in hurt/telegraph states.
  Distinct primary pairs have distinct colour-stripped geometry; the mini/main scale and crown
  remain distinct for every pair. Slam, Sweep, each Flurry event, Rush, Bolt, each Burst event,
  Scatter and Laser each select and draw their declared active pose/effect, and an airborne boss
  selects the rise/fall pose from its vertical velocity.
- **P-67** Damage-over-time indicators: burn-only, bleed-only and combined fixtures draw,
  respectively, upward forked flame/ember marks, downward pointed droplets and both on
  `ActorStatus`, anchored to each biped, hover and crawler body. Two times within each cycle differ
  and times one full cycle apart match; a paused time does not move them. Clearing one status
  removes only its marks, killing the enemy removes both, and composing them changes neither
  health, status duration nor digest. Frames with many identically affected enemies open the same
  status batch count as one.
- **P-68** Exit corridor: every exposed floor tile strictly right of `gateColumn` has the fixed blue
  surface and three pale upward sparks, while the gate column and every earlier floor tile do not.
  Two times within 0.90 s change spark height/size and times one full cycle apart match; pause
  freezes them. The geometry is derived from the existing completion boundary and changes no tile,
  collision, map-clear tick, RNG consumption or digest; its styles are distinct from every item
  ring and shot look.
- **P-69** Player hurt flash: enemy projectile, an unsuppressed boss-projectile contact from P-90,
  boss melee hit, normal-enemy contact, boss contact and
  spike, broken-glass and barrel damaging-hazard fixtures each lower health and start
  `playerHurtSecondsLeft`; a
  fairness-suppressed hit and healing do not. The window refreshes under continued damage, decays
  to zero after damage stops and is frozen by pause. While active every player figure style is
  `Palettes.HURT`, while the eye and held weapon retain their normal styles. Mutating the timer
  changes neither the canonical save nor P-40 digest.
- **P-70** Fire-jet presentation: the solid cell at `(column, bottomRow + 1)` draws a dark open
  mouth, metal neck, asymmetric split rim and crack in both the on and off states, while an
  ordinary solid cell draws none of those pipe marks. An active jet draws three pointed two-tone
  tongues made from joined diagonal segments, with distinct phases and lateral turns; every flame
  endpoint and stroke envelope stays within the jet column and `topRow..bottomRow` span. Two times
  within the 0.72 s cycle differ, times one full cycle apart match, and pause freezes the shape.
  An off jet draws no flame but retains its pipe. Composing either phase changes neither the
  `FireJet`, supporting tile, lethal contact, on/off timing nor simulation digest, and one jet and
  many jets open the same set of pipe/flame style batches.
- **P-73** Burning-barrel fire: every barrel draws three unequal, two-tone flame tongues whose
  distinct lid anchors and joined diagonal strokes form no closed triangle and share no apex.
  Every flame stroke envelope stays within the flame cell above the drum. Two times within the
  0.72 s cycle change at least one joint's lateral position, times one full cycle apart match, and
  pause freezes the shape. Barrels at different coordinates do not all share the same pose.
  Composing any phase changes neither damaging contact, barrel geometry nor the simulation digest,
  and one barrel and many barrels open the same set of flame style batches.
- **P-85** Broken-glass presentation: every `BrokenGlass` tile draws exactly five unequal,
  disconnected `#7a3f2b` shard segments and three `#b66a45` crumbs within the bottom 30 % of its
  cell. The geometry contains no closed triangle or common baseline and differs
  at two representative coordinates while reproducing exactly at the same coordinate and every
  presentation time. A solid, spike or empty control draws none of these marks. One patch and many
  patches add exactly the same two style batches; composing them consumes no RNG and changes no
  tile, health, collision or digest. The development world sheet is inspected for small, rusty,
  jagged ground readability and clear distinction from spikes.
- **P-88** Ramen and heal presentation: a ramen fixture draws a bowl no larger than 40 × 36 screen
  px with a thick rim, two inward-sloping sides, a base and a worn highlight; at least two connected
  alternating-slope noodle strokes rise above the rim, and exactly two distinct parallel
  chopsticks emerge from the right half and angle upward right. A colour-stripped fixture retains
  that bowl/noodle/chopstick silhouette. Its bottom meets the selected support, its draw list is
  identical at two presentation times, and it draws no ring, pips, bloom or hover. On collection,
  the player's figure uses `Palettes.HEAL` for exactly 0.12 active seconds while its eye and held
  weapon retain their styles, then returns to normal. Pause freezes the timer. If a hurt flash
  overlaps, red renders for its complete window while the green timer remains unchanged, then the
  complete green window renders. Mutating only the heal timer changes neither save nor P-40 digest.
- **P-89** Ramen visual signature: one scene test applies `RAMEN_VISUAL_SCALE = 2.0` to every
  endpoint and stroke width in the canonical design-space table, producing an exact 37.5 × 32.5 px
  stroked envelope whose bottom meets the support coordinate. It also verifies the parallel
  right-side chopsticks and alternating noodle slopes. Pickup position, contact reach, healing,
  RNG, hover absence and P-40 digest are independent controls and remain unchanged.
- **P-76** Backdrop identity and detail: the backdrop-profile registry is total over the ten
  `ThemeId`s; every profile has a unique colour-independent structural signature containing at
  least the motifs required by its table row. For a representative level, all three depths contain
  profile-owned structure and the near depth contains more detail marks per building than the far
  depth. Equal seed, level and theme reproduce equal geometry; changing the seed varies geometry
  without changing the profile, and changing the theme changes its signature. The three layers
  remain ordered at exactly 0.12, 0.30 and 0.55 parallax, and camera movement offsets every body,
  window and detail by its owning layer's rate. One building and a skyline of hundreds open the
  same backdrop style batches. Generation and composition change no tile, collision, RNG stream
  outside `backdrop` or simulation digest. The development world sheet renders all ten themes for
  human review of dystopian readability, depth and playfield contrast.
- **P-77** Basic audio cues: a player melee activation reports exactly one `MeleeSwing`; a ranged
  single shot and a simultaneous spread each report exactly one `RangedFire`; a psychic single shot
  and simultaneous volley each report exactly one `PsychicFire`; every later round of either class's
  timed burst reports one matching cue on its actual emission tick. Enemy and boss attacks report
  none. Removing a contacted weapon-only, powerup-only, paired or ramen `GroundItem` reports exactly
  one `PickupPulse`, including same-weapon Scrap, refused/displaced powerup, guaranteed, healed and
  full-health-capped outcomes; no contact reports none. Cue presence and consumption change neither
  canonical save nor P-40 digest.
  Browser wiring forwards each report cue once and in order to an injectable sink, forwards none
  while paused, never replays one after resume, and treats a suspended or failing audio context as
  silence. The psychic patch is distinct from all other patches, uses the specified bend, lasts at
  most 130 ms and peaks at most 0.05; every patch references no runtime audio asset.
- **P-78** Player death sequence: isolated lethal fixtures for acid, fire jet, barrel fire, Laser,
  spikes, broken glass, ordinary and boss projectiles, and ordinary and boss melee attacks capture their declared
  cause effect; void and body contact capture none. When several sources overlap, the first
  terminal event remains the cause. The lethal frame starts at age zero in its normal resolved
  pose; after 120 death-only ticks the same limb lengths form the final prone pose and the end
  screen is still absent; after another 120 ticks the browser shows `You died` and focuses `Return
  to title`. Movement, attacks, combatants, pickups, map exit, RNG and statuses are byte-identical
  throughout those ticks, and gameplay or Escape input neither mutates nor shortens the sequence.
  Poison, flame and bleed frames draw respectively rising rings, upward forks/embers and falling
  pointed droplets on the interpolated body at ages before and after the collapse; two ages within
  each effect cycle differ. A death with no mapped effect opens none of those styles. Cause effects
  and the collapse change neither health, saved state nor P-40's rule-bearing fields, and frames
  containing the same effect open a constant status-batch count. The run is banked and its save is
  cleared exactly once at terminal entry, including if the page closes before the end screen; a
  first discovery on the lethal tick remains recorded without displaying its card.
