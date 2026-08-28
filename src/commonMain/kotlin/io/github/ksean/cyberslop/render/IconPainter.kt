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
        weapon: Boolean,
        originX: Double,
        originY: Double,
        scale: Double,
        haloLayer: Layer,
        outlineLayer: Layer,
        aim: Vec2 = Vec2.Right,
    ) {
        require(haloLayer.ordinal < outlineLayer.ordinal) {
            "$haloLayer is not under $outlineLayer, so the halo would paint over the icon"
        }
        icon.paint(
            originX,
            originY,
            scale,
            aim,
            IconBatchSink(builder, haloLayer, IconStyles.HALO, scale, halo = true),
        )
        icon.paint(
            originX,
            originY,
            scale,
            aim,
            IconBatchSink(builder, outlineLayer, IconStyles.outlineOf(weapon), scale, halo = false),
        )
    }
}

/**
 * Pushes a placed icon into the frame's batches.
 *
 * Widths come from [IconStyles] rather than from the icon, and they are snapped to `Scene`'s ladder,
 * so the batches an icon opens are drawn from a fixed vocabulary however many icons the frame holds
 * (ENG-064).
 */
class IconBatchSink(
    private val builder: SceneBuilder,
    private val layer: Layer,
    private val style: String,
    private val scale: Double,
    private val halo: Boolean,
) : IconSink {
    override fun stroke(x1: Double, y1: Double, x2: Double, y2: Double, weight: StrokeWeight) {
        val width =
            if (halo) IconStyles.haloWidthOf(weight, scale) else IconStyles.widthOf(weight, scale)
        builder.batch(layer, style, Primitive.Segment, width).segment(x1, y1, x2, y2)
    }

    override fun dot(x: Double, y: Double, radius: Double) {
        // The halo pass grows a dot by half the amount it widens a line, so a dot is edged like
        // everything else rather than swallowed by the line it sits on.
        // `radius` arrives in pixels: `Icon.paint` has already applied the scale.
        val grown = if (halo) radius + IconStyles.HALO_FRACTION * scale / 2.0 else radius
        builder.batch(layer, style, Primitive.Dot).dot(x, y, grown)
    }
}
