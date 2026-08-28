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

Layers, back to front: `Sky`, `BackdropFar/Mid/Near`, `Haze`, `Terrain`, `Hazard`, `ItemHalo`,
`Items`, actors, `Effects`, `Hud`, `HudOverlay`, `Debug`. The halo and outline of an icon are on two
layers because on one layer their order depends on which batch opened first.

## The view

The camera view is measured in world units; `ZOOM = 3.5` makes a tile 56 screen pixels. Dead-zone
follow (25 % of the view) with look-ahead (12 %) in the facing direction; hard clamp to the level
bounds; on resize keep world-units-per-pixel fixed. The camera never frames an arena: a boss that
fights is engaged and pursuing, so it is within the awareness radius of the player the camera
follows, and an attack that could reach the player from off-screen (Volley) is capped at eight
tiles.

## Palettes and the world

Ten palettes, one per sub-theme, each with `sky`, `skyLow`, `backdropFar/Mid/Near`, `window`,
`tileBody`, `tileEdge`, `tileDeep`, `hazard`, `hazardGlow`, `accent`, `haze` and a three-step `glow`
ramp that is strictly increasing in luminance (enforced at construction). Colour temperature
drifts across the run, from cold slate and sodium orange to sterile white-gold.

Tiles have a lit top edge and a darker body (two batches, not two draws per tile). Acid has a
bright surface line and a dimmer body; a fire jet has a hot core, a cooler outer column and a floor
pool of light. The backdrop is three parallax layers of procedural skyline at 0.12×, 0.30× and
0.55× the camera rate, generated once per level from the `backdrop` stream and posed per frame by a
damped horizontal and vertical offset. It is never collidable.

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
its `WindUp`; its active window is a `Swing` with a swoosh (Slam, Sweep, Rush) or a `Fire` with a
muzzle flash and a fan of projectile dots (Volley). The telegraph colour change stays.

**Crouch is a pose (PROD-067).** `Crouch` and `CrouchWalk` keep the standing figure's limb lengths
and bend the joints: hips dropped to about half the standing hip height, knees bent forward of the
hip–ankle line, torso and head leaning forward, weapon held low across the body. The figure's
highest point stays within the physics' crouch height; nothing is scaled.

## Weapon effects (PROD-033, PROD-066)

- A melee swing is a **swoosh**: three nested arcs along the swing at decreasing radius and width,
  drawn from the weapon's tip inward, with spark dots at the leading edge, fading over the swing
  window. The outer arc is the reach the hit test used.
- A ranged shot draws a **muzzle flash** at the barrel: a bright core dot, a longer bloom segment
  along the aim and two short spikes at ±35°, fading over the flash window. The projectile is a
  dot in the palette accent.
- Enemies use the same two effects, in the enemy's own colours, drawn from their posed hand or
  barrel.

## Enemy looks

Five silhouettes that differ in shape:

| Archetype | Silhouette | Presentational motion |
|---|---|---|
| Swarm | small, hunched, spindly, oversized head | fast twitchy gait |
| Shooter | upright, one long weapon arm | measured walk; arm tracks the player inside firing range |
| Flyer | legless pod, twin thruster plumes | hovers, bobs, thrust trails its travel |
| Turret | wide fixed base, no legs, sweeping head | base never moves; barrel tracks the player |
| Brute | broadest, heavy shoulder plates, short thick limbs | slow heavy gait |

`EnemyLook.of(archetype, mapIndex)` returns `bulk`, `plates`, `spikes` and a glow tone that move
with the enemy's health. Plates and spikes are monotone across the whole grid; drawn size
(`height × bulk`) and luminance are monotone within a map (PROD-042) — a whole-grid size ordering
would force every archetype to the same height. Bosses reuse the rig at `MINIBOSS_SCALE = 2.6` and
`BOSS_SCALE = 3.7` with a crown of plating and a health bar (PROD-043).

## Item icons

An icon is a list of `Stroke(x1, y1, x2, y2, weight)` and `Dot(x, y, r)` ops in a local `[-1, 1]²`
box, keyed by `WeaponId` or `PowerupId` in one registry (ENG-064). Only segments and dots, because
a segment is closed under rotation and a rectangle is not. Orientation by a unit vector is
`(u·ax − v·ay, u·ay + v·ax)` — no transform, no trig.

- **Three weights** — `Hair` 0.07, `Line` 0.13, `Slab` 0.28 of the half-extent — with round caps;
  a `Slab` is a filled bar, and every gap is measured against cap extension.
- **Colours** — weapon outline `#ff2f2f`, powerup outline `#3d8bff`, halo `#05060a`, fixed across
  themes (PROD-050). Every icon is drawn twice, halo under outline, because the outline alone is
  within 2.0 luma of a tile colour on one palette and the halo alone within 0.1 of a sky on another;
  the pair is never closer than 42.9 (PROD-051).
- **Kind twice** — a powerup's icon sits in a module casing, a weapon's never does, because two
  palettes carry an accent within RGB distance 13 of an outline colour.
- **Rarity** — `PickupLook.scale` 1.0 → 1.9 across the five tiers over `PICKUP_PX = 14` screen px
  (28 px at Street to 53 px at Ascended), plus a row of `tier + 1` pips in the kind's colour.
- **Composition** — icons are built from shared parts (`grip`, `stock`, `muzzle`, `ring`,
  `Icon.cased`); a melee weapon is a mass on a handle, a ranged weapon a barrel over a grip, a
  psychic weapon has neither.

Icons vary in geometry, not style, so the item layers open at most 24 batches with all forty-four
on screen. The whole worst-case frame (600 enemies, every icon on the ground, a full build in the
HUD) opens about 90 batches. Whether an icon is *recognisable* is a human judgement made against
the icon sheet, not a test.

## HUD and screens

The HUD shows health, the equipped weapon's icon and name, each held powerup's icon and stack
count, and the map index and sub-theme (PROD-045). The title screen is a DOM screen with real
buttons and accessible names; the canvas carries `role="application"`, an `aria-label` and a
visually hidden live region for run state. Focus loss clears input and pauses.

## Verified properties

- **P-23** Batch bound: the same scene at 10 and at 600 entities issues the same number of
  drawing-state changes, counted at the paint sink; no batch mixes stroke widths; the production
  bundle's smoke test bounds strokes per frame.
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
  outer arc radius equals the swing's reach.
- **P-27** Icon totality and distinctness: every id resolves to an icon, purely; no two icons are
  equal as geometry; every op is inside the box; every icon has at least three strokes spanning at
  least 60 % of the box's longer axis.
- **P-28** One icon, three presentations: ground, hand and HUD draw the same op list differing only
  in scale and orientation; orienting is a rigid motion.
- **P-29** Kind survives colour removal: casing on every powerup, on no weapon; id → kind → colour
  is total; the two outline colours differ in hue and luminance.
- **P-30** Legible on every map: for each palette and each of its nine background colours, the
  halo or the outline differs in luminance by ≥ 40; no items-layer style appears on the hazard or
  effects layer in the same frame.
- **P-31** Icon batch bound: a frame with the forty-four icons once and a frame with them four
  times issue the same number of drawing-state changes, and the item layers open ≤ 24 batches.
