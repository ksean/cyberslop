package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2

/**
 * How heavy a stroke is drawn. Three weights, never a continuum.
 *
 * A katana whose blade, guard and grip are one width is a stick, so one weight is not enough. A
 * continuous weight is too many: a stroke's width is part of its batch's identity (ENG-061), so
 * every distinct width is a batch, and an icon vocabulary with free widths would put the frame's
 * drawing-state cost back under the control of what happens to be on screen.
 *
 * A weight is a fraction of the icon's half-extent, so an icon is the same design at every tier
 * rather than a differently-proportioned one. [IconStyles.widthOf] resolves it, snapping to `Scene`'s
 * stroke ladder — which is what keeps the batch count constant: five tier scales collapse onto three
 * distinct widths per weight.
 */
enum class StrokeWeight(val fraction: Double) {
    Hair(0.07),
    Line(0.13),
    Slab(0.28),
}

/**
 * One mark in an icon.
 *
 * Two kinds, and deliberately no rectangle. An axis-aligned rectangle is **not closed under
 * rotation** — turn it and it is a quadrilateral, which [Primitive.Rect] cannot express — so an icon
 * containing one could not be drawn in the player's hand without either a canvas transform (`specs/presentation.md`
 * measured that at 7.61x a bare draw) or a second, unrelated piece of geometry for the held
 * case. A segment rotates to a segment and a dot is rotation-invariant, which is what lets one icon
 * serve the ground, the hand and the HUD (PROD-049).
 *
 * Coordinates are in the icon's own `[-1, 1]` box, `y` down as everywhere else in this project.
 * A round-capped [Stroke] at [StrokeWeight.Slab] *is* a filled bar, which is how an icon gets solid
 * mass without a rectangle.
 */
sealed interface IconOp {
    data class Stroke(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val weight: StrokeWeight = StrokeWeight.Line,
    ) : IconOp

    /** [radius] is in box units like every other coordinate here, not in pixels. */
    data class Dot(val x: Double, val y: Double, val radius: Double) : IconOp
}

/**
 * Where a placed icon's marks go.
 *
 * A sink rather than a returned list because an icon is placed every frame for every pickup on
 * screen, and the caller already owns batches to push into. Nothing here allocates.
 */
interface IconSink {
    fun stroke(x1: Double, y1: Double, x2: Double, y2: Double, weight: StrokeWeight)

    fun dot(x: Double, y: Double, radius: Double)
}

/**
 * The shape of one weapon or one powerup (PROD-049).
 *
 * Containment is checked here rather than in a test, so an icon that would bleed outside the box its
 * scale maps cannot be constructed at all — the same reason [Palette] refuses a glow ramp that is not
 * strictly increasing. What is *not* checked here is whether an icon carries enough ink to be
 * recognisable: that is a claim about the authored registries rather than about the type, and
 * `IconRegistryTest` asserts it over all forty-four.
 */
class Icon(val ops: List<IconOp>) {
    init {
        require(ops.isNotEmpty()) { "an icon with no marks draws nothing" }
        ops.forEach { op ->
            when (op) {
                is IconOp.Stroke -> {
                    requireInBox(op.x1, op.y1, op)
                    requireInBox(op.x2, op.y2, op)
                }

                is IconOp.Dot -> {
                    require(op.radius > 0.0) { "a dot with no radius draws nothing: $op" }
                    // The radius counts: a dot that bleeds out of the box overlaps whatever the
                    // HUD lays out beside it.
                    requireInBox(op.x - op.radius, op.y - op.radius, op)
                    requireInBox(op.x + op.radius, op.y + op.radius, op)
                }
            }
        }
    }

    val strokes: List<IconOp.Stroke> = ops.filterIsInstance<IconOp.Stroke>()

    val dots: List<IconOp.Dot> = ops.filterIsInstance<IconOp.Dot>()

    /**
     * Whether this icon wears the module casing that marks a powerup (PROD-050).
     *
     * Read off the geometry rather than carried as a flag. Two of the ten palettes hold an `accent`
     * within an RGB distance of 13 of these outline colours, so kind cannot rest on red-versus-blue
     * alone — and a boolean beside the ops could say "cased" while the drawn icon had no casing at
     * all, which is precisely the gap this is meant to close.
     */
    val cased: Boolean = ops.containsAll(CASING)

    /** The longer side of the icon's bounding box, in box units. At most 2.0. */
    val span: Double = span(ops)

    fun paint(
        originX: Double,
        originY: Double,
        scale: Double,
        aim: Vec2,
        sink: IconSink,
    ) {
        // The icon's local +x axis is laid along `aim`, and its +y along the perpendicular. No
        // angle is ever formed, so `TrigTable` is not consulted and ENG-054 has nothing to say:
        // the direction arrives as a unit vector and stays one. Four multiplies and two adds.
        val ax = aim.x
        val ay = aim.y
        ops.forEach { op ->
            when (op) {
                is IconOp.Stroke -> sink.stroke(
                    originX + (op.x1 * ax - op.y1 * ay) * scale,
                    originY + (op.x1 * ay + op.y1 * ax) * scale,
                    originX + (op.x2 * ax - op.y2 * ay) * scale,
                    originY + (op.x2 * ay + op.y2 * ax) * scale,
                    op.weight,
                )

                is IconOp.Dot -> sink.dot(
                    originX + (op.x * ax - op.y * ay) * scale,
                    originY + (op.x * ay + op.y * ax) * scale,
                    op.radius * scale,
                )
            }
        }
    }

    private fun requireInBox(x: Double, y: Double, op: IconOp) {
        require(x >= -1.0 && x <= 1.0 && y >= -1.0 && y <= 1.0) {
            "($x, $y) leaves the icon's [-1, 1] box: $op"
        }
    }

    companion object {
        /**
         * The module casing, as two corner brackets.
         *
         * Brackets rather than a full border: a closed rectangle around a 35 px icon spends most of
         * the box on the frame and makes eighteen powerups read as eighteen boxes. Two corners say
         * "cased" and leave the middle for what the thing actually is.
         */
        val CASING: List<IconOp.Stroke> = listOf(
            IconOp.Stroke(-0.96, -0.52, -0.96, -0.96, StrokeWeight.Hair),
            IconOp.Stroke(-0.96, -0.96, -0.52, -0.96, StrokeWeight.Hair),
            IconOp.Stroke(0.96, 0.52, 0.96, 0.96, StrokeWeight.Hair),
            IconOp.Stroke(0.96, 0.96, 0.52, 0.96, StrokeWeight.Hair),
        )

        /** An icon inside the casing every powerup wears. */
        fun cased(ops: List<IconOp>): Icon = Icon(CASING + ops)

        private fun span(ops: List<IconOp>): Double {
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            fun include(x: Double, y: Double) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            ops.forEach { op ->
                when (op) {
                    is IconOp.Stroke -> {
                        include(op.x1, op.y1)
                        include(op.x2, op.y2)
                    }

                    is IconOp.Dot -> {
                        include(op.x - op.radius, op.y - op.radius)
                        include(op.x + op.radius, op.y + op.radius)
                    }
                }
            }
            val width = maxX - minX
            val height = maxY - minY
            return if (width > height) width else height
        }
    }
}
