package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.TILE_SIZE
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-73: a burning barrel is topped by wavy fire rather than a triangular spike. */
class BarrelFirePresentationTest {
    @Test
    fun `barrel fire is three unequal joined two tone tongues inside its flame cell`() {
        val frame = frame(simulation(BARREL), START_TIME)
        val outerBands = bands(flameStrokes(frame, FIRE_OUTER))
        val coreBands = bands(flameStrokes(frame, FIRE_CORE))

        assertEquals(SEGMENTS_PER_TONGUE, outerBands.size, "the outer fire is not a linked tapered flame")
        assertEquals(SEGMENTS_PER_TONGUE, coreBands.size, "the hot core is not a linked tapered flame")
        outerBands.forEach { assertEquals(TONGUES, it.size, "an outer band does not contain three tongues") }
        coreBands.forEach { assertEquals(TONGUES, it.size, "a core band does not contain three tongues") }

        outerBands.indices.forEach { band ->
            assertTrue(coreBands[band].first().width < outerBands[band].first().width)
            for (tongue in 0 until TONGUES) {
                assertStrokeLineEquals(outerBands[band][tongue], coreBands[band][tongue])
            }
        }

        val paths = (0 until TONGUES).map { tongue -> outerBands.map { it[tongue] } }
        paths.forEachIndexed { tongue, path ->
            path.zipWithNext().forEach { (lower, upper) ->
                assertEquals(lower.x2, upper.x1, TOLERANCE, "tongue $tongue is not joined at x")
                assertEquals(lower.y2, upper.y1, TOLERANCE, "tongue $tongue is not joined at y")
            }
            val horizontal = path.map { it.x2 - it.x1 }
            assertTrue(horizontal.any { it > TOLERANCE }, "tongue $tongue never turns right")
            assertTrue(horizontal.any { it < -TOLERANCE }, "tongue $tongue never turns left")
        }
        assertEquals(TONGUES, paths.map { it.first().x1 }.toSet().size, "the tongues share one lid anchor")
        assertEquals(TONGUES, paths.map { it.last().x2 }.toSet().size, "the tongues converge on one spike apex")
        assertEquals(
            TONGUES,
            paths.map { path -> path.minOf { minOf(it.y1, it.y2) } }.toSet().size,
            "the tongues are not unequal in height",
        )

        val cellLeft = screenX(BARREL.column)
        val cellRight = cellLeft + TILE_PX
        val cellTop = screenY(BARREL.flameRow)
        val cellBottom = screenY(BARREL.row)
        paths.flatten().forEach { stroke ->
            val radius = stroke.width / 2.0
            assertTrue(minOf(stroke.x1, stroke.x2) - radius >= cellLeft - TOLERANCE, "$stroke leaves the left edge")
            assertTrue(maxOf(stroke.x1, stroke.x2) + radius <= cellRight + TOLERANCE, "$stroke leaves the right edge")
            assertTrue(minOf(stroke.y1, stroke.y2) - radius >= cellTop - TOLERANCE, "$stroke leaves the top edge")
            assertTrue(maxOf(stroke.y1, stroke.y2) + radius <= cellBottom + TOLERANCE, "$stroke leaves the bottom edge")
        }
        paths.forEach { path ->
            assertEquals(cellBottom, path.first().y1 + path.first().width / 2.0, TOLERANCE, "a flame left the lid")
        }
    }

    @Test
    fun `barrel fire waves deterministically with interpolation and coordinate phase`() {
        val sim = simulation(BARREL)
        val start = allFlameStrokes(frame(sim, START_TIME))
        val moving = allFlameStrokes(frame(sim, MOVING_TIME))
        val repeated = allFlameStrokes(frame(sim, START_TIME + WAVE_PERIOD))

        assertTrue(start.indices.any { !start[it].sameGeometry(moving[it]) }, "the barrel fire did not wave")
        assertStrokesEqual(start, repeated)
        assertStrokesEqual(start, allFlameStrokes(frame(sim, START_TIME)))

        val tickTime = 0.4
        val interpolated = allFlameStrokes(frame(sim, tickTime, alpha = 0.5))
        val atPresentationTime = allFlameStrokes(frame(sim, tickTime - TICK_SECONDS / 2.0))
        assertStrokesEqual(atPresentationTime, interpolated)

        val other = BARREL.copy(column = BARREL.column + 3)
        val phased = allFlameStrokes(frame(simulation(BARREL, other), START_TIME))
        val firstPose = strokesInCell(phased, BARREL).map { it.relativeTo(screenX(BARREL.column)) }
        val otherPose = strokesInCell(phased, other).map { it.relativeTo(screenX(other.column)) }
        assertTrue(
            firstPose.indices.any { !firstPose[it].sameGeometry(otherPose[it]) },
            "barrels at different coordinates wave in unison",
        )
    }

    @Test
    fun `drawing barrel fire changes neither digest nor damaging contact`() {
        val contactBarrel = Barrel(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW)
        val sim = simulation(contactBarrel)
        val before = sim.digest()

        frame(sim, START_TIME)
        frame(sim, MOVING_TIME)

        assertEquals(before, sim.digest())
        assertEquals(contactBarrel, sim.level.barrels.single())
        val health = sim.run.health
        sim.tick(InputFrame())
        assertTrue(sim.run.health < health, "drawing the fire changed the barrel's damaging contact")
    }

    @Test
    fun `more barrels add flames without adding flame style batches`() {
        val one = frame(simulation(BARREL), START_TIME)
        val many = frame(simulation(BARREL, BARREL.copy(column = BARREL.column + 3)), START_TIME)

        assertEquals(flameBatchKeys(one), flameBatchKeys(many))
        assertTrue(allFlameStrokes(many).size > allFlameStrokes(one).size)
    }

    private fun simulation(vararg barrels: Barrel): GameSimulation =
        TestLevels.simulation(TestLevels.flat(barrels = barrels.toList()))

    private fun frame(sim: GameSimulation, time: Double, alpha: Double = 1.0): DrawList = Scene.compose(
        sim,
        CAMERA,
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim),
        time,
        SceneBuilder(),
        alpha,
    )

    private fun flameStrokes(frame: DrawList, style: String): List<Stroke> = frame.batches
        .filter { it.layer == Layer.Hazard && it.style == style && it.primitive == Primitive.Segment }
        .flatMap(::strokes)

    private fun allFlameStrokes(frame: DrawList): List<Stroke> =
        flameStrokes(frame, FIRE_OUTER) + flameStrokes(frame, FIRE_CORE)

    private fun strokes(batch: DrawBatch): List<Stroke> = (0 until batch.size).map { index ->
        val at = index * Primitive.Segment.stride
        Stroke(batch[at], batch[at + 1], batch[at + 2], batch[at + 3], batch.width)
    }

    private fun bands(strokes: List<Stroke>): List<List<Stroke>> =
        strokes.groupBy(Stroke::width).entries.sortedByDescending { it.key }.map { it.value }

    private fun strokesInCell(strokes: List<Stroke>, barrel: Barrel): List<Stroke> {
        val left = screenX(barrel.column)
        val right = left + TILE_PX
        return strokes.filter { minOf(it.x1, it.x2) >= left && maxOf(it.x1, it.x2) <= right }
    }

    private fun flameBatchKeys(frame: DrawList): Set<BatchKey> = frame.batches
        .filter { it.layer == Layer.Hazard && it.style in setOf(FIRE_OUTER, FIRE_CORE) }
        .map { BatchKey(it.style, it.primitive, it.width) }
        .toSet()

    private fun screenX(column: Int): Double = (column * TILE_SIZE - CAMERA.x) * Scene.ZOOM

    private fun screenY(row: Int): Double = (row * TILE_SIZE - CAMERA.y) * Scene.ZOOM

    private fun assertStrokeLineEquals(expected: Stroke, actual: Stroke) {
        assertEquals(expected.x1, actual.x1, TOLERANCE)
        assertEquals(expected.y1, actual.y1, TOLERANCE)
        assertEquals(expected.x2, actual.x2, TOLERANCE)
        assertEquals(expected.y2, actual.y2, TOLERANCE)
    }

    private fun assertStrokesEqual(expected: List<Stroke>, actual: List<Stroke>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertStrokeLineEquals(expected[index], actual[index])
            assertEquals(expected[index].width, actual[index].width, TOLERANCE)
        }
    }

    private data class Stroke(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val width: Double,
    ) {
        fun sameGeometry(other: Stroke): Boolean =
            abs(x1 - other.x1) < TOLERANCE &&
                abs(y1 - other.y1) < TOLERANCE &&
                abs(x2 - other.x2) < TOLERANCE &&
                abs(y2 - other.y2) < TOLERANCE

        fun relativeTo(left: Double): Stroke = copy(x1 = x1 - left, x2 = x2 - left)
    }

    private data class BatchKey(val style: String, val primitive: Primitive, val width: Double)

    private companion object {
        const val SEED = 0xBA22E1uL
        const val FIRE_OUTER = "#ff5a1f"
        const val FIRE_CORE = "#ffd166"
        const val START_TIME = 0.17
        const val MOVING_TIME = 0.41
        const val WAVE_PERIOD = 0.72
        const val TONGUES = 3
        const val SEGMENTS_PER_TONGUE = 4
        const val TOLERANCE = 1e-8
        const val TILE_PX = TILE_SIZE * Scene.ZOOM
        val BARREL = Barrel(6, TestLevels.FLOOR_ROW)
        val CAMERA = Camera(0.0, 180.0, 240.0, 130.0)
    }
}
