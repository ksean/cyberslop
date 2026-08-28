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
    }
}

/** Captures what an icon paints, so a test can measure it. */
private class RecordingIconSink : IconSink {
    val strokes = mutableListOf<PlacedStroke>()
    val dots = mutableListOf<PlacedDot>()

    override fun stroke(x1: Double, y1: Double, x2: Double, y2: Double, weight: StrokeWeight) {
        strokes += PlacedStroke(x1, y1, x2, y2, weight)
    }

    override fun dot(x: Double, y: Double, radius: Double) {
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
