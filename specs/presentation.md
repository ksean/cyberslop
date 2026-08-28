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
  along the aim and two short spikes at ±35°, fading over the flash window.
- **Every shot shows where it went (PROD-071).** A travelling projectile is drawn as a **body and
  tracer**: a dot at its position at its hit radius, and a segment trailing it along its velocity
  for `TRACER_SECONDS = 0.05 s` of travel (17 px at the enemy shot speed), so a shot reads as a
  line of flight rather than a floating dot. The player's projectiles use the palette's brightest
  glow; enemy and boss projectiles the palette hazard colour; the Volley's fan of dots is the
  same tracer treatment. An attack that resolves instantly leaves a **hit indicator** on the
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
  and drawn as the same tracer. A boss **Volley** shows the band it lands on: a segment on the
  floor across `aimedX ± VOLLEY_WIDTH` in the enemy-shot colour, and a fan of tracers travelling
  from the barrel to that band through the active window — not a fan along the boss's facing.
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
| Turret | wide fixed base, no legs, sweeping head | base never moves; barrel tracks the player |
| Brute | broadest, heavy shoulder plates, short thick limbs | slow heavy gait |

`EnemyLook.of(archetype, mapIndex)` returns `bulk`, `plates`, `spikes` and a glow tone that move
with the enemy's health. Plates and spikes are monotone across the whole grid; drawn size
(`height × bulk`) and luminance are monotone within a map (PROD-042) — a whole-grid size ordering
would force every archetype to the same height. Bosses reuse the rig at `MINIBOSS_SCALE = 2.6` and
`BOSS_SCALE = 3.7` with a crown of plating and a health bar (PROD-043).

**Hurt flash (PROD-076).** A hit — a swing, a projectile landing, a blast, a chain jump, splash;
not a burn or bleed tick — sets `hurtSecondsLeft = HURT_FLASH_SECONDS = 0.12 s` on the enemy or
boss, a presentation-only field decayed by the simulation like `lastSwing` and outside the digest.
While it is positive the figure's body, limbs, head, plating and (for a boss) crown are drawn in `Palettes.HURT`
(`#ff3b30`) instead of their own styles — every form (biped, hover, fixed) — and the eye glow is
unchanged. A boss's telegraph colour wins over the flash: a telegraphing boss stays in the
telegraph colour however hard it is hit, because the tell is a fairness signal. The flash is a
style swap, so a frame holding both hurt and unhurt figures opens at most one extra batch per
figure batch kind (fifteen at the worst), a constant that no number of enemies moves (P-23).

**Health bars (PROD-077).** A living rank-and-file enemy whose health is below its archetype's
maximum for the map draws the boss's bar above its figure: a dark back rect and a fill rect of
`healthFraction` of the width, width `ENEMY_SIZE × ZOOM`, on `Layer.Effects` in the same two
styles as a boss's, so every bar on screen shares the boss's two batches. An enemy at full health
draws none.

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
on screen. The whole worst-case frame (600 enemies, half of them in the hurt flash, every icon on
the ground, a full build in the HUD) opens about 105 batches. Whether an icon is *recognisable* is a human judgement made against
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
- **P-43** Shots show where they went: a live projectile draws a dot at its position **at its hit
  radius** and a segment from it back along its velocity of `speed × TRACER_SECONDS`, player and
  enemy shots in their own styles; a projectile spent on the tick it was fired still leaves that
  tracer; an active boss Volley draws the floor band `aimedX ± VOLLEY_WIDTH` and tracers toward it
  in the enemy-shot style; a Kessler strike leaves a beam whose foot is the strike centre and a
  ring whose radius is the scaled strike radius; a chain leaves a segment per jump whose endpoints
  are the struck targets in strike order and none when nothing was struck; a blast, a pull and an
  orbit each leave a ring of the radius they resolved at — the pattern's own declared radius, scaled
  — and an orbit hits inside that radius and not beyond it; an indicator's stroke width falls
  across its window; every indicator is gone after the flash window; the digest (P-40) is unchanged
  by any indicator.
- **P-47** Hurt flash and health bars: an enemy hit this tick is drawn in `Palettes.HURT` on
  every figure batch (body, limbs, head) and its eye glow is not; a burn tick does not flash it;
  it returns to its own styles after `HURT_FLASH_SECONDS`; a hit boss flashes, crown included,
  unless it is telegraphing, in which case the telegraph colour is drawn; the hover and fixed forms flash
  too; a full-health enemy draws no bar, an enemy at 40 % draws a back rect of `ENEMY_SIZE ×
  ZOOM` and a fill of 40 % of it above its figure; a frame of 600 half-hurt, damaged enemies
  opens the same number of batches as one of 10 (the flash opens at most one red batch per
  figure batch kind — a constant — and the bars share the boss's two); the digest is unchanged
  by the flash.
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
