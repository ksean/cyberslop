package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.render.Material.Energy
import io.github.ksean.cyberslop.render.Material.Glass
import io.github.ksean.cyberslop.render.Material.Rust
import io.github.ksean.cyberslop.render.Material.Steel
import io.github.ksean.cyberslop.render.Material.Wood
import io.github.ksean.cyberslop.render.StrokeWeight.Hair
import io.github.ksean.cyberslop.render.StrokeWeight.Line
import io.github.ksean.cyberslop.render.StrokeWeight.Slab

/**
 * What each weapon looks like (PROD-049).
 *
 * Every icon points along `+x`, because that is the axis [Icon.paint] lays along the aim — so the
 * shape a player sees lying on the ground is the shape that appears in their hand, turned to face
 * whatever the game has locked onto.
 *
 * Three families, and they are meant to be told apart at a glance before any one of them is
 * recognised individually: a melee weapon is a mass on a handle, a ranged weapon is a barrel over a
 * grip, and a psychic weapon has neither — it is a field, a wave or a wound.
 *
 * Composed from the small parts below rather than written out forty-four times, so that "every
 * pistol has a grip" is a function and not a coincidence.
 */
object WeaponIcons {
    fun of(id: WeaponId): Icon = icons.getValue(id)

    // ---- shared parts -------------------------------------------------------------------------

    /** The angled grip every firearm hangs from its receiver. Wood, like every handle (PROD-078). */
    private fun grip(x: Double, y: Double, drop: Double = 0.46) =
        IconOp.Stroke(x, y, x - drop * 0.32, y + drop, Line, Wood)

    /** A shoulder stock, running back and down from the receiver. */
    private fun stock(x: Double, y: Double, toX: Double, toY: Double) =
        IconOp.Stroke(x, y, toX, toY, Line, Wood)

    /** The bright point a barrel ends at, which is what makes a barrel read as a barrel. */
    private fun muzzle(x: Double, y: Double) = IconOp.Dot(x, y, 0.08, Energy)

    /** A closed ring, as a hexagon. Six strokes is the fewest that does not read as a polygon. */
    private fun ring(
        radius: Double,
        weight: StrokeWeight,
        turn: Double = 0.0,
        material: Material = Steel,
    ): List<IconOp.Stroke> {
        val points = (0 until RING_SIDES).map { side ->
            val degrees = turn + 360.0 * side / RING_SIDES
            io.github.ksean.cyberslop.core.TrigTable.rotate(
                io.github.ksean.cyberslop.core.Vec2(radius, 0.0),
                degrees,
            )
        }
        return points.indices.map { index ->
            val from = points[index]
            val to = points[(index + 1) % RING_SIDES]
            IconOp.Stroke(from.x, from.y, to.x, to.y, weight, material)
        }
    }

    private const val RING_SIDES = 6

    // ---- the registry -------------------------------------------------------------------------

    private val icons: Map<WeaponId, Icon> = mapOf(
        // Melee: a mass on a handle.

        // A steel cap ring, thin neck, a rusted wire wrap, a heavy body and the jagged crown where it broke. The step
        // from Hair through Line to Slab along one axis is the whole silhouette; drawn as an
        // outline instead it reads as a smudge at 28 px.
        WeaponId.BrokenBottle to Icon(
            listOf(
                IconOp.Dot(-0.86, 0.0, 0.11, Steel),
                IconOp.Stroke(-0.84, 0.0, -0.40, 0.0, Hair, Glass),
                // A rusted wire wrap where the neck meets the body — the grip, and the wear cue.
                IconOp.Stroke(-0.42, 0.0, -0.26, 0.0, Line, Rust),
                IconOp.Stroke(-0.22, 0.0, 0.46, 0.0, Slab, Glass),
                IconOp.Stroke(0.56, -0.12, 0.72, -0.30, Hair, Glass),
                IconOp.Stroke(0.60, 0.0, 0.86, 0.0, Hair, Glass),
                IconOp.Stroke(0.56, 0.12, 0.70, 0.30, Hair, Glass),
            ),
        ),

        WeaponId.RustlineMachete to Icon(
            listOf(
                IconOp.Stroke(-0.90, 0.10, -0.58, 0.06, Line, Wood),
                IconOp.Stroke(-0.52, -0.18, -0.52, 0.22, Hair, Steel),
                IconOp.Stroke(-0.42, -0.18, 0.84, -0.32, Hair, Rust),
                IconOp.Stroke(-0.38, 0.02, 0.70, 0.02, Slab, Steel),
                IconOp.Stroke(0.70, 0.10, 0.94, -0.26, Hair, Rust),
            ),
        ),

        WeaponId.CorpoRiotBaton to Icon(
            listOf(
                IconOp.Stroke(-0.84, 0.0, -0.52, 0.0, Slab, Wood),
                IconOp.Stroke(-0.48, 0.0, 0.90, 0.0, Line, Steel),
                IconOp.Stroke(-0.64, 0.16, -0.64, 0.66, Line, Steel),
                IconOp.Stroke(-0.80, -0.24, -0.80, 0.24, Hair, Rust),
                IconOp.Stroke(-0.68, -0.24, -0.68, 0.24, Hair, Rust),
            ),
        ),

        WeaponId.ChromeFang to Icon(
            listOf(
                IconOp.Stroke(-0.70, -0.34, -0.70, 0.34, Slab, Wood),
                IconOp.Stroke(-0.52, -0.28, 0.20, -0.46, Line, Steel),
                IconOp.Stroke(0.20, -0.46, 0.88, -0.20, Hair, Steel),
                IconOp.Stroke(-0.52, 0.28, 0.20, 0.46, Line, Steel),
                IconOp.Stroke(0.20, 0.46, 0.88, 0.20, Hair, Steel),
                IconOp.Dot(-0.70, 0.0, 0.10, Energy),
            ),
        ),

        // A whip is a *taper*, not a zigzag: Slab handle, Line body, Hair tip. Drawn at one weight
        // it was indistinguishable from the Ghostwire Tether on the sheet, which is the failure the
        // distinctness test cannot see — two icons can differ in every coordinate and still read as
        // the same thing.
        WeaponId.StaticLash to Icon(
            listOf(
                IconOp.Dot(-0.90, 0.10, 0.09, Steel),
                IconOp.Stroke(-0.86, 0.08, -0.60, 0.0, Slab, Wood),
                IconOp.Stroke(-0.56, 0.0, -0.16, -0.38, Line, Rust),
                IconOp.Stroke(-0.16, -0.38, 0.26, 0.30, Line, Rust),
                IconOp.Stroke(0.26, 0.30, 0.60, -0.26, Hair, Energy),
                IconOp.Stroke(0.60, -0.26, 0.84, 0.02, Hair, Energy),
                IconOp.Dot(0.90, 0.06, 0.07, Energy),
            ),
        ),

        WeaponId.GutterjackCleaver to Icon(
            listOf(
                IconOp.Stroke(-0.92, 0.18, -0.46, 0.12, Line, Wood),
                IconOp.Stroke(-0.32, -0.06, 0.76, -0.06, Slab, Steel),
                IconOp.Stroke(-0.34, -0.36, 0.80, -0.36, Hair, Rust),
                IconOp.Stroke(0.24, -0.36, 0.34, -0.18, Hair, Rust),
                IconOp.Stroke(-0.28, 0.22, 0.86, 0.16, Hair, Steel),
                IconOp.Dot(-0.64, 0.16, 0.10, Steel),
            ),
        ),

        WeaponId.KillSwitchKatana to Icon(
            listOf(
                IconOp.Stroke(-0.92, 0.0, -0.54, 0.0, Line, Wood),
                IconOp.Stroke(-0.84, -0.16, -0.78, 0.16, Hair, Rust),
                IconOp.Stroke(-0.70, -0.16, -0.64, 0.16, Hair, Rust),
                IconOp.Stroke(-0.48, -0.24, -0.48, 0.24, Line, Steel),
                IconOp.Stroke(-0.38, -0.04, 0.76, -0.14, Line, Steel),
                IconOp.Stroke(0.76, -0.14, 0.94, -0.28, Hair, Energy),
            ),
        ),

        WeaponId.ChromewreckMaul to Icon(
            listOf(
                IconOp.Stroke(-0.92, 0.22, 0.28, -0.06, Line, Wood),
                IconOp.Stroke(0.36, -0.10, 0.76, -0.18, Slab, Steel),
                IconOp.Stroke(0.30, -0.38, 0.80, -0.48, Hair, Rust),
                IconOp.Stroke(0.34, 0.16, 0.84, 0.06, Hair, Rust),
                IconOp.Stroke(0.44, -0.46, 0.50, 0.14, Hair, Steel),
                IconOp.Stroke(0.66, -0.50, 0.72, 0.10, Hair, Steel),
            ),
        ),

        WeaponId.MeatgrinderHalo to Icon(
            ring(0.56, Line) + listOf(
                IconOp.Stroke(0.56, 0.0, 0.94, 0.0, Hair, Rust),
                IconOp.Stroke(-0.56, 0.0, -0.94, 0.0, Hair, Rust),
                IconOp.Stroke(0.28, -0.48, 0.47, -0.81, Hair, Steel),
                IconOp.Stroke(-0.28, 0.48, -0.47, 0.81, Hair, Steel),
            ),
        ),

        // Ranged: a barrel over a grip.

        WeaponId.ScraplineZipPistol to Icon(
            listOf(
                IconOp.Stroke(-0.36, -0.30, 0.06, -0.30, Slab, Steel),
                IconOp.Stroke(0.10, -0.30, 0.72, -0.30, Line, Rust),
                grip(-0.30, -0.14),
                IconOp.Stroke(-0.24, 0.08, 0.04, 0.02, Hair, Steel),
                muzzle(0.74, -0.30),
            ),
        ),

        // The nail strip sits on top and reads as a strip, with the nails in it. Angled back and
        // down like a magazine it read as a broken wing.
        WeaponId.TenementNailgun to Icon(
            listOf(
                IconOp.Stroke(-0.36, 0.08, 0.26, 0.08, Slab, Steel),
                IconOp.Stroke(0.30, 0.08, 0.82, 0.08, Line, Rust),
                IconOp.Stroke(-0.46, -0.38, 0.30, -0.38, Line, Steel),
                IconOp.Stroke(-0.30, -0.32, -0.30, -0.10, Hair, Rust),
                IconOp.Stroke(-0.10, -0.32, -0.10, -0.10, Hair, Rust),
                IconOp.Stroke(0.10, -0.32, 0.10, -0.10, Hair, Rust),
                grip(-0.28, 0.24, drop = 0.40),
                muzzle(0.84, 0.08),
            ),
        ),

        WeaponId.GanglordSmg to Icon(
            listOf(
                IconOp.Stroke(-0.40, -0.22, 0.20, -0.22, Slab, Steel),
                IconOp.Stroke(0.24, -0.22, 0.74, -0.22, Line, Steel),
                IconOp.Stroke(-0.10, -0.06, -0.06, 0.50, Line, Rust),
                IconOp.Stroke(-0.06, 0.50, 0.08, 0.80, Hair, Rust),
                grip(-0.34, -0.06, drop = 0.44),
                stock(-0.46, -0.26, -0.88, -0.18),
                muzzle(0.76, -0.22),
            ),
        ),

        // Barrel over receiver over stock, with a heavy fore-end where the hand pumps it. Round caps
        // extend a stroke by half its width at each end, so every gap here is measured against that
        // rather than against the endpoints: the first version had a Slab receiver and a Line barrel
        // 0.12 apart and they fused into one mass.
        WeaponId.RiotbreakerShotgun to Icon(
            listOf(
                IconOp.Stroke(-0.30, -0.44, 0.90, -0.44, Line, Steel),
                IconOp.Stroke(-0.34, -0.14, -0.06, -0.14, Slab, Steel),
                IconOp.Stroke(0.26, -0.14, 0.52, -0.14, Slab, Wood),
                stock(-0.38, -0.10, -0.86, 0.18),
                IconOp.Stroke(-0.86, 0.10, -0.84, 0.42, Line, Wood),
                grip(-0.20, -0.06),
                muzzle(0.90, -0.44),
            ),
        ),

        WeaponId.VultureRailCarbine to Icon(
            listOf(
                IconOp.Stroke(-0.14, -0.16, 0.92, -0.16, Hair, Energy),
                IconOp.Stroke(-0.46, -0.16, -0.18, -0.16, Slab, Steel),
                IconOp.Stroke(-0.34, -0.50, 0.16, -0.50, Line, Glass),
                IconOp.Stroke(-0.20, -0.44, -0.20, -0.28, Hair, Steel),
                stock(-0.50, -0.12, -0.92, 0.08),
                grip(-0.34, -0.06, drop = 0.42),
                IconOp.Stroke(0.24, -0.34, 0.78, -0.34, Hair, Rust),
            ),
        ),

        WeaponId.AshfallGrenadeLobber to Icon(
            listOf(
                IconOp.Stroke(0.12, -0.26, 0.70, -0.26, Slab, Rust),
                IconOp.Stroke(0.80, -0.50, 0.80, -0.02, Line, Steel),
                IconOp.Stroke(-0.30, -0.26, 0.06, -0.26, Line, Steel),
                IconOp.Dot(-0.16, 0.12, 0.24, Steel),
                grip(-0.36, -0.12, drop = 0.44),
                stock(-0.38, -0.30, -0.88, -0.18),
            ),
        ),

        WeaponId.SableCorpRailgun to Icon(
            listOf(
                IconOp.Stroke(-0.16, -0.10, 0.94, -0.10, Line, Steel),
                IconOp.Stroke(0.06, -0.36, 0.88, -0.36, Hair, Energy),
                IconOp.Stroke(0.06, 0.16, 0.88, 0.16, Hair, Energy),
                IconOp.Stroke(-0.48, -0.10, -0.22, -0.10, Slab, Steel),
                stock(-0.52, -0.06, -0.92, 0.12),
                grip(-0.36, 0.02, drop = 0.44),
            ),
        ),

        WeaponId.DebtCollectorMinigun to Icon(
            listOf(
                IconOp.Stroke(0.06, -0.38, 0.94, -0.38, Hair, Steel),
                IconOp.Stroke(0.06, -0.12, 0.94, -0.12, Hair, Rust),
                IconOp.Stroke(0.06, 0.14, 0.94, 0.14, Hair, Steel),
                IconOp.Stroke(-0.42, -0.12, 0.02, -0.12, Slab, Steel),
                IconOp.Stroke(-0.38, 0.06, -0.72, 0.50, Line, Wood),
                IconOp.Stroke(-0.50, -0.18, -0.88, -0.32, Line, Wood),
            ),
        ),

        // No barrel and no grip: an emplacement that calls something down from somewhere else.
        WeaponId.KesslerOrbitalUplink to Icon(
            listOf(
                IconOp.Stroke(-0.54, 0.18, -0.20, -0.44, Line, Steel),
                IconOp.Stroke(-0.20, -0.44, 0.14, 0.18, Line, Steel),
                IconOp.Stroke(-0.20, 0.12, -0.20, 0.56, Line, Rust),
                IconOp.Stroke(-0.20, 0.56, -0.54, 0.86, Hair, Rust),
                IconOp.Stroke(-0.20, 0.56, 0.14, 0.86, Hair, Rust),
                IconOp.Stroke(0.10, -0.30, 0.70, -0.70, Hair, Energy),
                IconOp.Dot(0.80, -0.78, 0.12, Energy),
            ),
        ),

        // Psychic: no barrel, no blade. A field, a wave or a wound.

        WeaponId.NeuralSpike to Icon(
            listOf(
                IconOp.Stroke(-0.86, -0.30, -0.86, 0.30, Slab, Steel),
                IconOp.Stroke(-0.70, 0.0, 0.52, 0.0, Line, Rust),
                IconOp.Stroke(0.52, 0.0, 0.94, 0.0, Hair, Energy),
                IconOp.Stroke(0.30, -0.04, 0.08, -0.30, Hair, Energy),
                IconOp.Stroke(0.30, 0.04, 0.08, 0.30, Hair, Energy),
                IconOp.Dot(-0.58, 0.0, 0.09, Glass),
            ),
        ),

        // A glass emitter in a corroded housing, throwing waves: the first pair of arcs is the
        // rusted emitter ring, the rest is the field. A field of pure energy has nothing to age.
        WeaponId.MigraineLoop to Icon(
            listOf(
                IconOp.Dot(-0.72, 0.0, 0.18, Glass),
                IconOp.Stroke(-0.36, -0.26, -0.22, 0.0, Line, Rust),
                IconOp.Stroke(-0.22, 0.0, -0.36, 0.26, Line, Rust),
                IconOp.Stroke(-0.02, -0.46, 0.16, 0.0, Line, Energy),
                IconOp.Stroke(0.16, 0.0, -0.02, 0.46, Line, Energy),
                IconOp.Stroke(0.34, -0.66, 0.56, 0.0, Hair, Energy),
                IconOp.Stroke(0.56, 0.0, 0.34, 0.66, Hair, Energy),
            ),
        ),

        WeaponId.WetwareScreamer to Icon(
            listOf(
                IconOp.Stroke(-0.80, -0.14, -0.12, -0.46, Line, Steel),
                IconOp.Stroke(-0.80, 0.14, -0.12, 0.46, Line, Steel),
                IconOp.Stroke(-0.12, -0.46, -0.12, 0.46, Line, Steel),
                IconOp.Stroke(-0.80, -0.14, -0.80, 0.14, Hair, Rust),
                IconOp.Stroke(0.18, -0.30, 0.32, 0.0, Hair, Energy),
                IconOp.Stroke(0.32, 0.0, 0.18, 0.30, Hair, Energy),
                IconOp.Stroke(0.56, -0.52, 0.74, 0.0, Hair, Energy),
                IconOp.Stroke(0.74, 0.0, 0.56, 0.52, Hair, Energy),
            ),
        ),

        // An anchor plate, a slack wire and a hook big enough to be the subject. The first version
        // was a dot on a zigzag, which is what the Static Lash also is.
        WeaponId.GhostwireTether to Icon(
            listOf(
                IconOp.Stroke(-0.86, -0.30, -0.86, 0.30, Line, Steel),
                IconOp.Stroke(-0.78, 0.02, -0.36, -0.18, Hair, Rust),
                IconOp.Stroke(-0.36, -0.18, 0.12, -0.20, Hair, Rust),
                IconOp.Stroke(0.12, -0.20, 0.50, 0.02, Hair, Rust),
                IconOp.Stroke(0.50, 0.02, 0.80, 0.32, Line, Steel),
                IconOp.Stroke(0.80, 0.32, 0.92, -0.06, Line, Steel),
                IconOp.Stroke(0.92, -0.06, 0.64, -0.22, Line, Steel),
                IconOp.Stroke(0.64, -0.22, 0.72, -0.46, Hair, Energy),
            ),
        ),

        WeaponId.BlackboxChorus to Icon(
            listOf(
                IconOp.Stroke(-0.56, -0.20, 0.44, -0.20, Line, Steel),
                IconOp.Stroke(-0.56, 0.52, 0.44, 0.52, Line, Steel),
                IconOp.Stroke(-0.56, -0.20, -0.56, 0.52, Line, Steel),
                IconOp.Stroke(0.44, -0.20, 0.44, 0.52, Line, Steel),
                IconOp.Stroke(-0.32, 0.24, 0.20, 0.24, Slab, Glass),
                IconOp.Stroke(-0.36, -0.22, -0.52, -0.80, Hair, Rust),
                IconOp.Stroke(-0.06, -0.22, -0.06, -0.88, Hair, Rust),
                IconOp.Stroke(0.24, -0.22, 0.42, -0.80, Hair, Rust),
            ),
        ),

        WeaponId.SynapseHemorrhage to Icon(
            listOf(
                IconOp.Dot(-0.58, 0.0, 0.30, Glass),
                IconOp.Stroke(-0.24, 0.0, 0.06, 0.0, Line, Rust),
                IconOp.Stroke(0.06, 0.0, 0.48, -0.44, Line, Rust),
                IconOp.Stroke(0.48, -0.44, 0.86, -0.58, Hair, Energy),
                IconOp.Stroke(0.06, 0.0, 0.56, 0.06, Line, Rust),
                IconOp.Stroke(0.56, 0.06, 0.94, 0.0, Hair, Energy),
                IconOp.Stroke(0.06, 0.0, 0.44, 0.46, Line, Rust),
                IconOp.Stroke(0.44, 0.46, 0.80, 0.64, Hair, Energy),
            ),
        ),

        // A ring the same way the Halo is a ring, but turned, tighter, and with its teeth pointing
        // inward at a void — the two must not be mistakable for each other.
        WeaponId.NullEgoSingularity to Icon(
            ring(0.62, Line, turn = 30.0) + listOf(
                IconOp.Stroke(0.0, -0.94, 0.0, -0.62, Hair, Rust),
                IconOp.Stroke(0.82, 0.46, 0.54, 0.32, Hair, Rust),
                IconOp.Stroke(-0.82, 0.46, -0.54, 0.32, Hair, Rust),
                IconOp.Dot(0.0, 0.0, 0.10, Energy),
            ),
        ),

        // A mask with a rusted jaw, speaking.
        WeaponId.VoiceOfTheDeadNet to Icon(
            listOf(
                IconOp.Stroke(-0.64, -0.44, -0.46, 0.36, Line, Steel),
                IconOp.Stroke(0.04, -0.44, -0.14, 0.36, Line, Steel),
                IconOp.Stroke(-0.64, -0.44, 0.04, -0.44, Line, Steel),
                IconOp.Stroke(-0.46, 0.36, -0.14, 0.36, Line, Rust),
                IconOp.Dot(-0.46, -0.16, 0.09, Energy),
                IconOp.Dot(-0.14, -0.16, 0.09, Energy),
                IconOp.Stroke(0.26, -0.36, 0.78, -0.64, Hair, Energy),
                IconOp.Stroke(0.26, -0.04, 0.88, -0.04, Hair, Energy),
                IconOp.Stroke(0.26, 0.28, 0.78, 0.56, Hair, Energy),
            ),
        ),
    )
}
