package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2

/**
 * Draws an icon into a frame, halo first and outline over it.
 *
 * One place, because the order is load-bearing and it would otherwise be repeated at three call
 * sites: a coloured line over a dark backing reads as an object edged in that colour, where the
 * reverse reads as a dark object with a coloured core. The halo is also the whole of PROD-051 —
 * neither line separates from all ten palettes alone (`specs/presentation.md`, Item icons).
 *
 * **The two passes go on two layers, and that is not tidiness.** Drawn on one layer the order is
 * whichever batch was opened first, and a frame holding drops of two rarities opens them in the
 * wrong order: the larger icon's halo snaps to a wider ladder step and opens a new batch, while its
 * outline widths were already opened by the smaller icon and sit earlier in the frame. Rendered,
 * every thin stroke on the larger drop came out solid black.
 *
 * Two small sinks are allocated per icon drawn. That is tens of allocations in a frame that already
 * builds a `Pose` per visible actor, and none of it is per-primitive, which is the property `specs/presentation.md`
 * cares about.
 */
object IconPainter {
    fun paint(
        builder: SceneBuilder,
        icon: Icon,
        originX: Double,
        originY: Double,
        scale: Double,
        haloLayer: Layer,
        outlineLayer: Layer,
        wearLayer: Layer,
        aim: Vec2 = Vec2.Right,
        handedness: IconHandedness = IconHandedness.Right,
    ) {
        require(haloLayer.ordinal < outlineLayer.ordinal) {
            "$haloLayer is not under $outlineLayer, so the halo would paint over the icon"
        }
        require(outlineLayer.ordinal < wearLayer.ordinal) {
            "$wearLayer is not over $outlineLayer, so a material could paint over its own weathering"
        }
        icon.paint(
            originX,
            originY,
            scale,
            aim,
            IconBatchSink(builder, haloLayer, scale, halo = true),
            handedness,
        )
        icon.paint(
            originX,
            originY,
            scale,
            aim,
            IconBatchSink(builder, outlineLayer, scale, halo = false, wearLayer = wearLayer),
            handedness,
        )
    }
}

/**
 * Pushes a placed icon into the frame's batches.
 *
 * Widths come from [IconStyles] rather than from the icon, and they are snapped to `Scene`'s ladder,
 * so the batches an icon opens are drawn from a fixed vocabulary however many icons the frame holds
 * (ENG-064). The colour pass draws each mark in its [Material]'s colour and, for a material that
 * weathers, a streak along the stroke's rear (PROD-078) — the age of every blade and haft is a rule
 * of the painter, not of forty-four authored icons.
 */
class IconBatchSink(
    private val builder: SceneBuilder,
    private val layer: Layer,
    private val scale: Double,
    private val halo: Boolean,
    /** Where the weathering streaks go: structurally over [layer], for the reason [Layer.ItemWear] gives. */
    private val wearLayer: Layer = layer,
) : IconSink {
    override fun stroke(x1: Double, y1: Double, x2: Double, y2: Double, weight: StrokeWeight, material: Material) {
        if (halo) {
            builder.batch(layer, IconStyles.HALO, Primitive.Segment, IconStyles.haloWidthOf(weight, scale))
                .segment(x1, y1, x2, y2)
            return
        }
        builder.batch(layer, material.colour, Primitive.Segment, IconStyles.widthOf(weight, scale))
            .segment(x1, y1, x2, y2)
        val streak = material.weathering ?: return
        val width = IconStyles.streakWidthOf(weight, scale) ?: return
        val dx = x2 - x1
        val dy = y2 - y1
        builder.batch(wearLayer, streak, Primitive.Segment, width).segment(
            x1 + dx * IconStyles.STREAK_FROM, y1 + dy * IconStyles.STREAK_FROM,
            x1 + dx * IconStyles.STREAK_TO, y1 + dy * IconStyles.STREAK_TO,
        )
    }

    override fun dot(x: Double, y: Double, radius: Double, material: Material) {
        // The halo pass grows a dot by half the amount it widens a line, so a dot is edged like
        // everything else rather than swallowed by the line it sits on.
        // `radius` arrives in pixels: `Icon.paint` has already applied the scale.
        val grown = if (halo) radius + IconStyles.HALO_FRACTION * scale / 2.0 else radius
        builder.batch(layer, if (halo) IconStyles.HALO else material.colour, Primitive.Dot).dot(x, y, grown)
    }
}
