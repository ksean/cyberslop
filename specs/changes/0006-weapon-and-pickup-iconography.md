# Change 0006: Weapon and pickup iconography

- **Status:** Implemented on 2026-08-27
- **Implementation approval:** Given by the owner on 2026-08-27 after reviewing phase one — the post-review approval `AGENTS.md` asks for.
- **Created:** 2026-08-27

## Intent

The owner asked that a drop look like the thing it is: *"A drop that is a bottle should look like a
bottle, a drop that is a shotgun should look like a shotgun, and so on. Weapons should be outlined
with red, and powerups should be outlined with blue. All drops should look like the weapon/drop that
they correspond to."*

[Change 0005](0005-visual-identity-and-loot-density.md) gave the world ten palettes, the player a
rig and the enemies five silhouettes — and left all forty-four collectable items drawn as one of two
rectangles. A weapon is a bar in the theme's `accent`; a powerup is a block in `hazardGlow`, **the
acid colour**, so every powerup in the game is currently drawn the same colour as the hazard that
kills the player. Size carries rarity; nothing carries identity.

That is a real defect rather than missing polish, because of how this game's loot works. PROD-046
puts a drop on the ground every five kills, PROD-047 puts two more on the map, and PROD-030 makes
contact **irrevocable** — walking over a pickup resolves it, and a full build will scrap something to
take it. The only decision the player is given is whether to walk over the thing, and they currently
make it blind.

The research and the design are in [`plan.md` §16](../../plan.md#16-weapon-and-pickup-iconography).

## Requirements added and amended

| ID | Requirement |
|---|---|
| PROD-044 | *(amended)* A pickup must show the specific item it is, not merely its kind and tier |
| PROD-049 | Every weapon and powerup has its own recognisable icon; one icon serves ground, hand and HUD |
| PROD-050 | Weapons outlined red, powerups blue, in fixed colours; kind also readable with colour removed |
| PROD-051 | An icon stays legible on every sub-theme and is never drawn in a hazard's or projectile's colour |
| ENG-064 | Icon geometry lives in one `commonMain` registry, orients without a transform or a trig call, and adds a constant number of style batches |

Nothing is withdrawn. PROD-044's rarity clause stands and is carried by size and by a row of tier
pips; PROD-040's per-theme palettes stand and are what forced the outline colours to be validated
against all ten.

## Why line art, and why it is not an aesthetic choice

An icon may use only the draw list's `Segment` and `Dot`. `Rect` is excluded because an axis-aligned
rectangle is **not closed under rotation** — rotated, it is a quadrilateral the primitive cannot
express, and the only ways out are a per-sprite canvas transform (measured at 7.61x a bare draw in
`plan.md` §8.1, which the whole batching design exists to avoid) or four segments per rectangle
anyway. A segment rotates to a segment; a dot is rotation-invariant.

That is what lets one piece of geometry serve all three places PROD-049 names. And it needs no
trigonometry, so ENG-054 is untouched: `Pose.weaponAim` is already a unit vector, and rotating a
local point by it is `(u*ax - v*ay, u*ay + v*ax)` — four multiplies and two adds, with no angle
anywhere in the path.

Three stroke weights, fixed. A katana whose blade, guard and grip are one width is a stick. A
continuous weight would give almost every icon its own batch, which is the trap `plan.md` §15.3
already caught once.

## Why kind is carried twice

The owner set red and blue, and they are fixed rather than themed — a red thing is a weapon on all
ten maps or the rule teaches nothing. Two measurements were taken before committing:

- **Luminance against the terrain.** A red outline at Rec. 709 luma 91.2 is **2.0** from
  `ArcologyVault.tileBody` (89.3) and **3.3** from `NeonSlums.tileEdge` (87.9); a blue at 130.8 is
  **5.8** from `SkybridgeRuin.tileEdge`. In greyscale, a red drop on the Vault's floor *is* the floor.
  Fixed by drawing every icon twice — a near-black halo under the coloured line art — so that at least
  one of the two lines separates from whatever is behind it. Measured over all ten palettes and all
  nine of their background colours: the outline alone is worth **2.0** at its worst and a near-black
  halo alone **0.1** (against Reactor Core's sky), while the pair is worth **45.8**. PROD-051's margin
  is therefore stated over the pair; neither line clears it by itself.
- **Hue against the theme's own accent.** RGB distance from the outlines to each palette's `accent`
  is **12.0** for Reactor Core's `#ff3b30` against the red and **12.9** for Server Stacks' `#3b82f6`
  against the blue — and `accent` is the colour of projectiles, fire-jet plumes, the exit marker and
  the player's own trim. On two of ten maps the outline colour is four other things on screen. No
  choice of red and blue avoids this; the ten accents span the wheel by design.

So a powerup's icon sits in a **module casing** and a weapon's does not. It is the same doctrine
PROD-042 applies to enemy silhouettes, reached the same way — by measuring, not by principle — and it
is also simply what the objects are: a Fracture Lens and an Overclock Coil are gadgets, so drawing
them as cased modules is not a concession to legibility.

## What this does not touch

- **No rule, registry field or balance number moves.** `LootFloor`, `WeaponScore`, `DropTable` and
  every drop rate are untouched, and `plan.md` §12's two open questions about them are neither closed
  nor worsened.
- **Projectiles.** A railgun slug and a nailgun nail stay the same dot. Making a weapon's *fire* look
  like its icon is a larger surface — every `FirePattern` rather than every `WeaponId` — with its own
  performance question, since projectiles are the things there are hundreds of. Deliberately out of
  scope and recorded as a follow-up.
- **Enemy weapons.** An armed enemy's barrel stays the segment change 0005 gave it. Enemy weapons do
  not come from the registry, so there is no icon to resolve.
- **The rest of `plan.md` §15.10's pass-two list** — grime, scanlines, screen shake, hit flashes,
  particles — stays unscheduled.
- **`plan.md` §8.1's full-frame budget measurement**, still owed and unchanged by this work.

## Acceptance examples

1. Given a Riotbreaker Shotgun lying on the ground and a Kill-Switch Katana lying beside it, the two
   are drawn as different objects, and each is drawn as the object its name describes.
2. Given a weapon and a powerup lying on the ground, the weapon's outline is red and the powerup's is
   blue, on every one of the ten sub-themes.
3. Given the same frame rendered with all colour removed, a weapon is still distinguishable from a
   powerup, because the powerup is in a casing and the weapon is not.
4. Given a Broken Bottle lying on the ground and the same Broken Bottle after it has been picked up,
   the shape drawn in the player's hand is the shape that was on the ground, rotated to the aim and
   scaled — not a different drawing of the same weapon.
5. Given any two of the forty-four items, their icons are not the same geometry, and every id in both
   registries resolves to one.
6. Given a Street-tier drop and an Ascended-tier drop of the same kind, the second is drawn larger and
   carries more tier pips.
7. Given any of the ten palettes, the drawn outline-and-halo pair separates in luminance from that
   palette's sky, backdrop and tile colours by at least the stated margin, and no style used on the
   items layer is used by that frame on the hazard or effects layers.
8. Given a frame drawing one icon and a frame drawing all forty-four, the number of drawing-state
   changes issued through `PaintSink` is the same.
9. Given an icon oriented along any unit vector, every stroke keeps its length and every pair of
   strokes its angle.

## What building it changed

Recorded against the tasks in `tasks.md` and in [`plan.md` §16](../../plan.md#16-weapon-and-pickup-iconography);
three are design corrections rather than tuning, and all three were found by rendering rather than by
a test.

- **`PICKUP_PX` is in screen pixels, not world pixels.** `Scene.pickups` multiplies the *position* by
  the zoom and then uses `PICKUP_PX * scale` directly, so a drop was 10 px wide — a fifth of a tile —
  and `plan.md` §16.4's "35 px box" was wrong by the whole zoom factor. Drops are now sized against
  the tile they lie on: 28 px at Street to 53 px at Ascended.
- **Stroke weights are a fraction of the icon, not a fixed pixel width.** Fixed widths were chosen to
  hold the batch count down, and rendered they made a Street drop and an Ascended drop two different
  designs rather than one object at two sizes. The ladder collapses five tier scales onto three
  widths per weight anyway, so the cost was eight ladder steps rather than the feared fifteen.
- **The halo and the outline must be on two layers.** On one, their order is whichever batch was
  opened first — and a frame holding drops of two rarities opens them in the wrong order, because the
  larger icon's halo snaps to a wider ladder step and opens a new batch while its outline widths were
  already opened by the smaller icon. Every thin stroke on the larger drop came out solid black, with
  every test green. This is the second time in this project that batching by style has destroyed
  paint order, and the second time the repair has been a layer rather than a convention.

And one the sheet caught that no assertion could: **two icons can differ in every coordinate and
still read as the same thing.** The Static Lash and the Ghostwire Tether both came out as "a blob
with a zigzag" with the distinctness test green. Each was given what actually identifies it — the
lash a taper from Slab to Hair, the tether an anchor plate and a hook large enough to be the subject.

## Human validation

*"A shotgun looks like a shotgun"* is a claim about a person and no `commonTest` assertion reaches
it. It is validated the way `plan.md` §9.4 validates the things tests cannot: an **icon sheet**,
rendering all forty-four at all five tier sizes, in colour and in greyscale, over each of the ten
palettes, reviewed by the owner against the list of names.

The sheet is built **before** the forty-four icons are authored, not after. `plan.md` §15.9 records
six defects found by rendering a frame or a pose sheet and none of them by a test; authoring
forty-four icons without being able to see them is how forty-four unreadable icons get authored.

It lives in `jvmTest` rather than behind a `Layer.Debug` scene in `commonMain`, which is the stronger
form of "no production path": `jvm()` is a verification-only target producing no deployable artifact
(ENG-001), so the sheet is absent from the bundle by construction. It draws through `SceneBuilder` and
`FramePainter` into an SVG sink — the same traversal the browser uses — so what it shows is the
frame's own batches in the frame's own order. `WorldFrameSheetTest` does the same for a whole composed
game frame, which is where the halo-order defect above was found.
