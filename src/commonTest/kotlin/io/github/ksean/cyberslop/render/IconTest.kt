package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The icon vocabulary (ENG-064).
 *
 * An icon is line art in a `[-1, 1]` box, and the whole reason it is line art is that a segment is
 * closed under rotation where an axis-aligned rectangle is not — which is what lets one piece of
 * geometry serve a drop on the ground, the same weapon in the player's hand and its entry in the
 * HUD (`specs/presentation.md`, Item icons). These tests hold that claim up.
 */
class IconTest {
    @Test
    fun `an icon cannot be built outside its own box`() {
        assertFailsWith<IllegalArgumentException>("a stroke leaving the box was accepted") {
            Icon(listOf(IconOp.Stroke(0.0, 0.0, 1.4, 0.0)))
        }
        assertFailsWith<IllegalArgumentException>("a dot whose radius leaves the box was accepted") {
            Icon(listOf(IconOp.Dot(0.9, 0.0, 0.3)))
        }
    }

    @Test
    fun `an icon resolves to the same geometry every time`() {
        assertEquals(
            WeaponIcons.of(io.github.ksean.cyberslop.combat.WeaponId.BrokenBottle).ops,
            WeaponIcons.of(io.github.ksean.cyberslop.combat.WeaponId.BrokenBottle).ops,
            "resolving the same weapon twice gave two different icons",
        )
    }

    @Test
    fun `aiming right places the icon unrotated`() {
        val icon = Icon(listOf(IconOp.Stroke(-1.0, -0.5, 1.0, 0.5), IconOp.Dot(0.25, 0.0, 0.1)))
        val placed = RecordingIconSink().also { icon.paint(100.0, 50.0, 10.0, Vec2.Right, it) }

        assertEquals(listOf(90.0, 45.0, 110.0, 55.0), placed.strokes[0].coordinates())
        assertEquals(Triple(102.5, 50.0, 1.0), placed.dots[0].let { Triple(it.x, it.y, it.radius) })
    }

    @Test
    fun `orienting an icon is a rigid motion`() {
        val icon = Icon(
            listOf(
                IconOp.Stroke(-0.8, 0.0, 0.8, 0.0),
                IconOp.Stroke(0.0, -0.6, 0.0, 0.6),
                IconOp.Stroke(-0.5, -0.5, 0.5, 0.5, StrokeWeight.Slab),
            ),
        )
        val rest = RecordingIconSink().also { icon.paint(0.0, 0.0, 7.0, Vec2.Right, it) }

        aims().forEach { aim ->
            val turned = RecordingIconSink().also { icon.paint(31.0, -12.0, 7.0, aim, it) }

            rest.strokes.indices.forEach { index ->
                assertTrue(
                    abs(turned.strokes[index].length() - rest.strokes[index].length()) < TOLERANCE,
                    "aim $aim changed stroke $index's length",
                )
            }
            // Lengths alone do not separate a rotation from a reflection: a sign error in
            // `(u*ax - v*ay, u*ay + v*ax)` mirrors the icon while leaving every stroke as long as
            // it was. The angle between two strokes is what catches it.
            assertTrue(
                abs(turned.crossOfFirstTwo() - rest.crossOfFirstTwo()) < TOLERANCE,
                "aim $aim reflected the icon rather than rotating it",
            )
        }
    }

    @Test
    fun `orienting agrees with the rotation the rest of the project uses`() {
        val icon = Icon(listOf(IconOp.Stroke(0.0, 0.0, 1.0, 0.0), IconOp.Stroke(0.0, 0.0, 0.0, -0.7)))

        listOf(0.0, 30.0, 90.0, 150.0, 210.0, 315.0).forEach { degrees ->
            val aim = TrigTable.rotate(Vec2.Right, degrees)
            val placed = RecordingIconSink().also { icon.paint(0.0, 0.0, 1.0, aim, it) }

            icon.strokes.indices.forEach { index ->
                val source = icon.strokes[index]
                val expected = TrigTable.rotate(Vec2(source.x2, source.y2), degrees)
                assertTrue(
                    abs(placed.strokes[index].x2 - expected.x) < TOLERANCE &&
                        abs(placed.strokes[index].y2 - expected.y) < TOLERANCE,
                    "at $degrees degrees stroke $index landed at " +
                        "(${placed.strokes[index].x2}, ${placed.strokes[index].y2}), " +
                        "not (${expected.x}, ${expected.y})",
                )
            }
        }
    }

    @Test
    fun `a powerup icon is cased and a weapon icon is not`() {
        assertTrue(
            PowerupIcons.of(io.github.ksean.cyberslop.loot.PowerupId.OverclockCoil).cased,
            "a powerup was drawn without the module casing that carries its kind without colour",
        )
        assertTrue(
            !WeaponIcons.of(io.github.ksean.cyberslop.combat.WeaponId.RiotbreakerShotgun).cased,
            "a weapon was drawn inside a powerup's casing",
        )
    }

    /**
     * P-50: age is a rule of the painter (PROD-078). A steel stroke is followed by a rust streak
     * along its rear 40 %, one weight lighter; a material that does not weather draws nothing more.
     */
    @Test
    fun `a steel stroke is drawn with a rust streak along its rear and glass is not`() {
        val steel = Icon(listOf(IconOp.Stroke(-1.0, 0.0, 1.0, 0.0, StrokeWeight.Slab, Material.Steel)))
        val glass = Icon(listOf(IconOp.Stroke(-1.0, 0.0, 1.0, 0.0, StrokeWeight.Slab, Material.Glass)))

        val rusted = colourPass(steel)
        val clean = colourPass(glass)

        val body = rusted.single { it.style == Material.Steel.colour }
        assertEquals(listOf(0.0, 50.0, 40.0, 50.0), body.segment(0), "the steel body is not the stroke")
        assertEquals(IconStyles.widthOf(StrokeWeight.Slab, SCALE), body.width)
        val streak = rusted.single { it.style == Material.Steel.weathering }
        assertEquals(listOf(22.0, 50.0, 38.0, 50.0), streak.segment(0), "the streak is not the rear 40 %")
        assertEquals(Layer.ItemWear, streak.layer, "the streak is not on the wear layer, over its material")
        assertEquals(Layer.Items, body.layer)
        assertEquals(IconStyles.widthOf(StrokeWeight.Line, SCALE), streak.width, "the streak is not one weight lighter")

        assertEquals(1, clean.size, "glass weathered: ${clean.map { it.style }}")
        assertEquals(Material.Glass.colour, clean.single().style)
    }

    /** Review round 2: a streak must be strictly narrower than its line, at the hand's scales too. */
    @Test
    fun `a streak is only drawn where it is narrower than its stroke`() {
        val line = Icon(listOf(IconOp.Stroke(-1.0, 0.0, 1.0, 0.0, StrokeWeight.Line, Material.Steel)))
        // Chrome Fang in the hand is about 11.5 px of scale: Line and Hair both snap to the floor.
        val held = colourPass(line, scale = HELD_SCALE)
        assertEquals(listOf(Material.Steel.colour), held.map { it.style }, "a streak as wide as its line was drawn")
        assertEquals(null, IconStyles.streakWidthOf(StrokeWeight.Line, HELD_SCALE))

        val ground = colourPass(line, scale = SCALE)
        val body = ground.single { it.style == Material.Steel.colour }.width
        val streak = ground.single { it.style == Material.Steel.weathering }.width
        assertTrue(streak < body, "at ground scale the streak ($streak) is not narrower than its line ($body)")
        StrokeWeight.entries.forEach { weight ->
            listOf(HELD_SCALE, SCALE, Scene.PICKUP_PX, Scene.PICKUP_PX * 1.9).forEach { scale ->
                val width = IconStyles.streakWidthOf(weight, scale) ?: return@forEach
                assertTrue(width < IconStyles.widthOf(weight, scale), "$weight at $scale: streak $width is not narrower")
            }
        }
    }

    /** Review round 1: the ladder floor makes a "half Hair" as wide as the Hair, so a Hair has no streak. */
    @Test
    fun `a hair stroke has no streak and a dot never streaks`() {
        val hair = Icon(listOf(IconOp.Stroke(-1.0, 0.0, 1.0, 0.0, StrokeWeight.Hair, Material.Wood)))
        val marks = colourPass(hair)
        assertEquals(listOf(Material.Wood.colour), marks.map { it.style }, "a hair stroke streaked")

        val dot = Icon(listOf(IconOp.Dot(0.0, 0.0, 0.5, Material.Steel)))
        val dotMarks = colourPass(dot)
        assertEquals(1, dotMarks.size, "a steel dot drew ${dotMarks.map { it.style }}")
        assertEquals(Material.Steel.colour, dotMarks.single().style)
    }

    @Test
    fun `the halo pass is one colour whatever the material`() {
        val icon = Icon(
            listOf(
                IconOp.Stroke(-1.0, 0.0, 0.0, 0.0, StrokeWeight.Line, Material.Wood),
                IconOp.Stroke(0.0, 0.0, 1.0, 0.0, StrokeWeight.Line, Material.Energy),
                IconOp.Dot(0.0, 0.5, 0.2, Material.Glass),
            ),
        )
        val builder = SceneBuilder().also { it.begin() }
        icon.paint(20.0, 50.0, SCALE, Vec2.Right, IconBatchSink(builder, Layer.ItemHalo, SCALE, halo = true))
        val styles = builder.build().batches.map { it.style }.toSet()
        assertEquals(setOf(IconStyles.HALO), styles)
    }

    /** What the colour pass of [icon] puts in a frame, at [SCALE] with the origin at (20, 50). */
    private fun colourPass(icon: Icon, scale: Double = SCALE): List<DrawBatch> {
        val builder = SceneBuilder().also { it.begin() }
        icon.paint(20.0, 50.0, scale, Vec2.Right, IconBatchSink(builder, Layer.Items, scale, halo = false, wearLayer = Layer.ItemWear))
        return builder.build().batches
    }

    private fun DrawBatch.segment(index: Int): List<Double> {
        val at = index * Primitive.Segment.stride
        return listOf(this[at], this[at + 1], this[at + 2], this[at + 3])
    }

    private fun aims(): List<Vec2> = listOf(
        Vec2.Right,
        Vec2(0.0, 1.0),
        Vec2(0.0, -1.0),
        Vec2(-1.0, 0.0),
        Vec2(1.0, 1.0).normalisedOr(Vec2.Right),
        Vec2(-3.0, 4.0).normalisedOr(Vec2.Right),
    )

    private companion object {
        const val TOLERANCE = 1e-9
        const val SCALE = 20.0
        const val HELD_SCALE = 11.47
    }
}

/** Captures what an icon paints, so a test can measure it. */
private class RecordingIconSink : IconSink {
    val strokes = mutableListOf<PlacedStroke>()
    val dots = mutableListOf<PlacedDot>()

    override fun stroke(x1: Double, y1: Double, x2: Double, y2: Double, weight: StrokeWeight, material: Material) {
        strokes += PlacedStroke(x1, y1, x2, y2, weight)
    }

    override fun dot(x: Double, y: Double, radius: Double, material: Material) {
        dots += PlacedDot(x, y, radius)
    }

    /** The z-component of the cross product of the first two strokes: signed, so it sees mirroring. */
    fun crossOfFirstTwo(): Double {
        val a = strokes[0]
        val b = strokes[1]
        return (a.x2 - a.x1) * (b.y2 - b.y1) - (a.y2 - a.y1) * (b.x2 - b.x1)
    }
}

private data class PlacedStroke(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val weight: StrokeWeight,
) {
    fun length(): Double = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    fun coordinates(): List<Double> = listOf(x1, y1, x2, y2)
}

private data class PlacedDot(val x: Double, val y: Double, val radius: Double)
