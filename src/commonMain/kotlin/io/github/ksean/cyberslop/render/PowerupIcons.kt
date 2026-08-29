package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.render.Material.Energy
import io.github.ksean.cyberslop.render.Material.Glass
import io.github.ksean.cyberslop.render.Material.Rust
import io.github.ksean.cyberslop.render.Material.Steel
import io.github.ksean.cyberslop.render.Material.Wood
import io.github.ksean.cyberslop.render.StrokeWeight.Hair
import io.github.ksean.cyberslop.render.StrokeWeight.Line
import io.github.ksean.cyberslop.render.StrokeWeight.Slab

/**
 * What each powerup looks like (PROD-049).
 *
 * Every one is built through [Icon.cased], so the casing that carries kind without colour (PROD-050)
 * cannot be forgotten on one entry out of eighteen — which matters because two of the ten palettes
 * hold an `accent` within an RGB distance of 13 of these outlines, and on those maps blue is not a
 * signal at all.
 *
 * The casing takes the two opposite corners, so contents stay inside roughly `[-0.78, 0.78]` by
 * `[-0.62, 0.62]`.
 */
object PowerupIcons {
    fun of(id: PowerupId): Icon = icons.getValue(id)

    private val icons: Map<PowerupId, Icon> = mapOf(
        PowerupId.FractureLens to Icon.cased(
            listOf(
                IconOp.Stroke(-0.52, -0.10, 0.0, -0.42, Line, Glass),
                IconOp.Stroke(0.0, -0.42, 0.52, -0.10, Line, Glass),
                IconOp.Stroke(-0.52, 0.10, 0.0, 0.42, Line, Glass),
                IconOp.Stroke(0.0, 0.42, 0.52, 0.10, Line, Glass),
                IconOp.Stroke(-0.52, -0.10, -0.52, 0.10, Hair, Steel),
                IconOp.Stroke(0.52, -0.10, 0.52, 0.10, Hair, Steel),
                IconOp.Stroke(-0.18, -0.28, 0.06, 0.02, Hair, Rust),
                IconOp.Stroke(0.06, 0.02, -0.10, 0.32, Hair, Rust),
            ),
        ),

        PowerupId.KineticDamper to Icon.cased(
            listOf(
                IconOp.Stroke(-0.68, -0.44, -0.68, 0.44, Line, Steel),
                IconOp.Stroke(0.68, -0.44, 0.68, 0.44, Line, Steel),
                IconOp.Stroke(-0.60, 0.0, -0.36, -0.36, Hair, Rust),
                IconOp.Stroke(-0.36, -0.36, -0.06, 0.36, Hair, Rust),
                IconOp.Stroke(-0.06, 0.36, 0.24, -0.36, Hair, Rust),
                IconOp.Stroke(0.24, -0.36, 0.48, 0.36, Hair, Rust),
                IconOp.Stroke(0.48, 0.36, 0.60, 0.0, Hair, Rust),
            ),
        ),

        PowerupId.RangerOptics to Icon.cased(
            listOf(
                IconOp.Stroke(-0.62, -0.32, 0.58, -0.32, Line, Steel),
                IconOp.Stroke(-0.62, 0.32, 0.58, 0.32, Line, Steel),
                IconOp.Stroke(-0.62, -0.32, -0.62, 0.32, Line, Steel),
                IconOp.Stroke(0.62, -0.46, 0.62, 0.46, Line, Glass),
                IconOp.Stroke(0.14, 0.0, 0.54, 0.0, Hair, Rust),
                IconOp.Stroke(0.34, -0.26, 0.34, 0.26, Hair, Rust),
            ),
        ),

        PowerupId.GuillotineCodec to Icon.cased(
            listOf(
                IconOp.Stroke(-0.52, -0.52, 0.36, -0.52, Line, Steel),
                IconOp.Stroke(-0.52, -0.16, 0.36, -0.46, Line, Steel),
                IconOp.Stroke(-0.52, -0.52, -0.52, -0.16, Hair, Rust),
                IconOp.Stroke(-0.64, -0.60, -0.64, 0.42, Hair, Rust),
                IconOp.Stroke(0.48, -0.60, 0.48, 0.42, Hair, Rust),
                IconOp.Stroke(-0.64, 0.42, 0.48, 0.42, Line, Steel),
                IconOp.Dot(-0.28, 0.14, 0.07, Energy),
                IconOp.Dot(-0.06, 0.14, 0.07, Energy),
                IconOp.Dot(0.16, 0.14, 0.07, Energy),
            ),
        ),

        PowerupId.HollowpointFirmware to Icon.cased(
            listOf(
                IconOp.Stroke(-0.62, -0.28, 0.16, -0.28, Line, Steel),
                IconOp.Stroke(-0.62, 0.28, 0.16, 0.28, Line, Steel),
                IconOp.Stroke(-0.62, -0.28, -0.62, 0.28, Line, Steel),
                IconOp.Stroke(0.16, -0.28, 0.56, -0.12, Line, Steel),
                IconOp.Stroke(0.16, 0.28, 0.56, 0.12, Line, Steel),
                IconOp.Stroke(0.56, -0.12, 0.42, 0.0, Hair, Energy),
                IconOp.Stroke(0.42, 0.0, 0.56, 0.12, Hair, Energy),
                IconOp.Stroke(-0.48, -0.28, -0.48, 0.28, Hair, Rust),
            ),
        ),

        PowerupId.SpikeDriver to Icon.cased(
            listOf(
                IconOp.Stroke(-0.10, -0.54, -0.10, 0.54, Slab, Steel),
                IconOp.Stroke(-0.70, 0.0, 0.46, 0.0, Line, Rust),
                IconOp.Stroke(0.46, 0.0, 0.78, 0.0, Hair, Energy),
                IconOp.Stroke(-0.70, -0.26, -0.70, 0.26, Line, Wood),
                IconOp.Stroke(0.06, -0.30, 0.32, -0.48, Hair, Rust),
                IconOp.Stroke(0.06, 0.30, 0.32, 0.48, Hair, Rust),
            ),
        ),

        PowerupId.RedMarketSiphon to Icon.cased(
            listOf(
                IconOp.Stroke(-0.40, -0.52, 0.02, -0.52, Line, Steel),
                IconOp.Stroke(0.02, -0.52, 0.02, -0.16, Line, Steel),
                IconOp.Dot(0.02, 0.04, 0.10, Energy),
                IconOp.Stroke(-0.28, 0.18, -0.18, 0.56, Line, Glass),
                IconOp.Stroke(0.32, 0.18, 0.22, 0.56, Line, Glass),
                IconOp.Stroke(-0.18, 0.56, 0.22, 0.56, Line, Glass),
                IconOp.Stroke(-0.28, 0.18, 0.32, 0.18, Hair, Rust),
                IconOp.Stroke(-0.22, 0.38, 0.26, 0.38, Hair, Energy),
            ),
        ),

        PowerupId.MassDriver to Icon.cased(
            listOf(
                IconOp.Stroke(-0.44, -0.26, 0.14, -0.26, Slab, Steel),
                IconOp.Stroke(-0.72, 0.16, 0.72, 0.16, Hair, Rust),
                IconOp.Stroke(-0.72, 0.38, 0.72, 0.38, Hair, Rust),
                IconOp.Stroke(0.26, -0.26, 0.66, -0.26, Line, Steel),
                IconOp.Stroke(0.66, -0.26, 0.46, -0.46, Hair, Energy),
                IconOp.Stroke(0.66, -0.26, 0.46, -0.06, Hair, Energy),
            ),
        ),

        // The leads are corroded through; the coil between them is live.
        PowerupId.OverclockCoil to Icon.cased(
            listOf(
                IconOp.Stroke(-0.76, 0.0, -0.52, 0.0, Line, Rust),
                IconOp.Stroke(-0.52, 0.0, -0.34, -0.42, Line, Energy),
                IconOp.Stroke(-0.34, -0.42, -0.08, 0.42, Line, Energy),
                IconOp.Stroke(-0.08, 0.42, 0.18, -0.42, Line, Energy),
                IconOp.Stroke(0.18, -0.42, 0.44, 0.42, Line, Energy),
                IconOp.Stroke(0.44, 0.42, 0.58, 0.0, Line, Energy),
                IconOp.Stroke(0.58, 0.0, 0.78, 0.0, Line, Rust),
            ),
        ),

        // A coolant manifold, frosted at the tips: the level spar is rusted through, the other
        // two are steel, the barbs are the cold.
        PowerupId.ChillProtocol to Icon.cased(
            listOf(
                IconOp.Stroke(-0.60, 0.0, 0.60, 0.0, Line, Rust),
                IconOp.Stroke(-0.30, -0.52, 0.30, 0.52, Line, Steel),
                IconOp.Stroke(-0.30, 0.52, 0.30, -0.52, Line, Steel),
                IconOp.Stroke(0.40, -0.16, 0.60, 0.0, Hair, Energy),
                IconOp.Stroke(0.40, 0.16, 0.60, 0.0, Hair, Energy),
                IconOp.Stroke(-0.40, -0.16, -0.60, 0.0, Hair, Energy),
                IconOp.Stroke(-0.40, 0.16, -0.60, 0.0, Hair, Energy),
            ),
        ),

        PowerupId.BurnRig to Icon.cased(
            listOf(
                IconOp.Stroke(-0.62, 0.48, 0.62, 0.48, Slab, Rust),
                IconOp.Stroke(-0.36, 0.30, -0.46, -0.10, Line, Energy),
                IconOp.Stroke(-0.46, -0.10, -0.24, -0.36, Line, Energy),
                IconOp.Stroke(0.0, 0.30, -0.08, -0.28, Line, Energy),
                IconOp.Stroke(-0.08, -0.28, 0.14, -0.58, Line, Energy),
                IconOp.Stroke(0.36, 0.30, 0.28, -0.10, Line, Energy),
                IconOp.Stroke(0.28, -0.10, 0.50, -0.36, Line, Energy),
            ),
        ),

        PowerupId.RicochetRom to Icon.cased(
            listOf(
                IconOp.Stroke(-0.58, -0.44, -0.58, 0.44, Line, Steel),
                IconOp.Stroke(0.58, -0.44, 0.58, 0.44, Line, Steel),
                IconOp.Stroke(-0.58, -0.44, 0.58, -0.44, Hair, Rust),
                IconOp.Stroke(-0.58, 0.44, 0.58, 0.44, Hair, Rust),
                IconOp.Stroke(-0.40, 0.24, 0.0, -0.24, Line, Energy),
                IconOp.Stroke(0.0, -0.24, 0.40, 0.24, Line, Energy),
                IconOp.Dot(0.40, 0.24, 0.10, Energy),
            ),
        ),

        PowerupId.SeekerDaemon to Icon.cased(
            listOf(
                IconOp.Stroke(0.24, -0.36, 0.60, 0.0, Line, Energy),
                IconOp.Stroke(0.60, 0.0, 0.24, 0.36, Line, Energy),
                IconOp.Stroke(0.24, 0.36, -0.12, 0.0, Line, Energy),
                IconOp.Stroke(-0.12, 0.0, 0.24, -0.36, Line, Energy),
                IconOp.Dot(0.24, 0.0, 0.09, Glass),
                IconOp.Stroke(-0.76, 0.46, -0.36, 0.06, Line, Rust),
                IconOp.Stroke(-0.36, 0.06, -0.14, 0.0, Hair, Rust),
            ),
        ),

        PowerupId.ArcCascade to Icon.cased(
            listOf(
                IconOp.Stroke(-0.66, -0.48, -0.20, 0.0, Line, Energy),
                IconOp.Stroke(-0.20, 0.0, 0.10, -0.24, Line, Energy),
                IconOp.Stroke(0.10, -0.24, 0.52, 0.48, Line, Energy),
                IconOp.Stroke(-0.20, 0.0, -0.48, 0.44, Hair, Rust),
                IconOp.Stroke(0.10, -0.24, 0.52, -0.46, Hair, Rust),
                IconOp.Stroke(0.30, 0.12, 0.68, 0.06, Hair, Rust),
            ),
        ),

        PowerupId.BrownoutCharge to Icon.cased(
            listOf(
                IconOp.Stroke(0.0, -0.54, 0.0, -0.04, Line, Rust),
                IconOp.Stroke(-0.44, -0.28, -0.58, 0.10, Line, Steel),
                IconOp.Stroke(-0.58, 0.10, -0.28, 0.50, Line, Steel),
                IconOp.Stroke(-0.28, 0.50, 0.28, 0.50, Line, Steel),
                IconOp.Stroke(0.28, 0.50, 0.58, 0.10, Line, Steel),
                IconOp.Stroke(0.58, 0.10, 0.44, -0.28, Line, Steel),
                IconOp.Dot(0.0, 0.18, 0.10, Energy),
            ),
        ),

        PowerupId.ForkBomb to Icon.cased(
            listOf(
                IconOp.Stroke(-0.74, 0.0, -0.18, 0.0, Line, Rust),
                IconOp.Stroke(-0.18, 0.0, 0.32, -0.44, Line, Rust),
                IconOp.Stroke(-0.18, 0.0, 0.40, 0.0, Line, Rust),
                IconOp.Stroke(-0.18, 0.0, 0.32, 0.44, Line, Rust),
                IconOp.Dot(0.42, -0.50, 0.10, Energy),
                IconOp.Dot(0.52, 0.0, 0.10, Energy),
                IconOp.Dot(0.42, 0.50, 0.10, Energy),
            ),
        ),

        PowerupId.ThermitePayload to Icon.cased(
            listOf(
                IconOp.Stroke(-0.32, 0.02, -0.32, 0.52, Line, Steel),
                IconOp.Stroke(0.24, 0.02, 0.24, 0.52, Line, Steel),
                IconOp.Stroke(-0.32, 0.52, 0.24, 0.52, Line, Steel),
                IconOp.Stroke(-0.32, 0.02, 0.24, 0.02, Slab, Steel),
                IconOp.Stroke(-0.04, -0.14, -0.12, -0.38, Hair, Rust),
                IconOp.Stroke(-0.12, -0.38, 0.16, -0.52, Hair, Rust),
                IconOp.Stroke(-0.28, 0.28, 0.20, 0.28, Hair, Rust),
                IconOp.Dot(0.26, -0.58, 0.10, Energy),
            ),
        ),

        PowerupId.KillstreakCache to Icon.cased(
            listOf(
                IconOp.Stroke(-0.58, -0.40, 0.58, -0.40, Line, Steel),
                IconOp.Stroke(-0.58, 0.44, 0.58, 0.44, Line, Steel),
                IconOp.Stroke(-0.58, -0.40, -0.58, 0.44, Line, Steel),
                IconOp.Stroke(0.58, -0.40, 0.58, 0.44, Line, Steel),
                IconOp.Stroke(-0.34, -0.18, -0.34, 0.24, Hair, Rust),
                IconOp.Stroke(-0.14, -0.18, -0.14, 0.24, Hair, Rust),
                IconOp.Stroke(0.06, -0.18, 0.06, 0.24, Hair, Rust),
                IconOp.Stroke(-0.44, 0.24, 0.28, -0.18, Hair, Wood),
            ),
        ),
    )
}
