package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.FireJet
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P-70: a fire jet remains identifiable by its ruptured pipe while its flame is off. */
class FireJetPresentationTest {
    @Test
    fun `an off jet leaves a broken pipe in its supporting tile but no flame`() {
        val plain = frame(TestLevels.simulation(TestLevels.flat()), OFF_TIME)
        val withJet = frame(TestLevels.simulation(TestLevels.flat(jets = listOf(JET))), OFF_TIME)

        assertFalse(JET.isOnAt(OFF_TIME), "fixture: the jet is active")
        assertTrue(
            terrainPipeMarks(withJet) > terrainPipeMarks(plain),
            "the off jet added no pipe mouth, rim or crack to its supporting tile",
        )
        assertEquals(
            0,
            withJet.batches.filter { it.layer == Layer.Hazard && it.primitive == Primitive.Segment }.sumOf { it.size },
            "the off jet retained flame strokes",
        )
    }

    @Test
    fun `an active jet is three joined tapered two tone flame tongues inside its lethal column`() {
        val frame = frame(TestLevels.simulation(TestLevels.flat(jets = listOf(JET))), ON_TIME)
        val outerBands = bands(flameStrokes(frame, FIRE_OUTER))
        val coreBands = bands(flameStrokes(frame, FIRE_CORE))

        assertTrue(JET.isOnAt(ON_TIME), "fixture: the jet is off")
        assertEquals(SEGMENTS_PER_TONGUE, outerBands.size, "the outer flame does not taper in five steps")
        assertEquals(SEGMENTS_PER_TONGUE, coreBands.size, "the hot core does not taper in five steps")
        outerBands.forEach { assertEquals(TONGUES, it.size, "an outer flame band does not contain three tongues") }
        coreBands.forEach { assertEquals(TONGUES, it.size, "a core flame band does not contain three tongues") }

        outerBands.indices.forEach { band ->
            assertTrue(coreBands[band].first().width < outerBands[band].first().width)
            for (tongue in 0 until TONGUES) {
                assertStrokeLineEquals(outerBands[band][tongue], coreBands[band][tongue])
            }
            if (band > 0) {
                assertTrue(outerBands[band].first().width < outerBands[band - 1].first().width)
                assertTrue(coreBands[band].first().width < coreBands[band - 1].first().width)
            }
        }

        for (tongue in 0 until TONGUES) {
            val path = outerBands.map { it[tongue] }
            path.zipWithNext().forEach { (lower, upper) ->
                assertEquals(lower.x2, upper.x1, TOLERANCE)
                assertEquals(lower.y2, upper.y1, TOLERANCE)
            }
            val horizontal = path.map { it.x2 - it.x1 }
            assertTrue(horizontal.any { it > TOLERANCE }, "tongue $tongue never turns right")
            assertTrue(horizontal.any { it < -TOLERANCE }, "tongue $tongue never turns left")
        }

        val tileLeft = (JET.column * TILE_SIZE - CAMERA.x) * Scene.ZOOM
        val tileRight = tileLeft + TILE_SIZE * Scene.ZOOM
        val top = (JET.topRow * TILE_SIZE - CAMERA.y) * Scene.ZOOM
        val bottom = ((JET.bottomRow + 1) * TILE_SIZE - CAMERA.y) * Scene.ZOOM
        outerBands.flatten().forEach { stroke ->
            val radius = stroke.width / 2.0
            assertTrue(minOf(stroke.x1, stroke.x2) - radius >= tileLeft - TOLERANCE, "$stroke leaves the left edge")
            assertTrue(maxOf(stroke.x1, stroke.x2) + radius <= tileRight + TOLERANCE, "$stroke leaves the right edge")
            assertTrue(minOf(stroke.y1, stroke.y2) - radius >= top - TOLERANCE, "$stroke leaves the top edge")
            assertTrue(maxOf(stroke.y1, stroke.y2) + radius <= bottom + TOLERANCE, "$stroke leaves the bottom edge")
        }
        assertEquals(
            top,
            outerBands.flatten().minOf { minOf(it.y1, it.y2) - it.width / 2.0 },
            TOLERANCE,
            "no pointed tongue reaches the top of the lethal volume",
        )
    }

    @Test
    fun `flame waves deterministically and repeats after its presentation cycle`() {
        val sim = TestLevels.simulation(TestLevels.flat(jets = listOf(JET)))
        val start = allFlameStrokes(frame(sim, ON_TIME))
        val moving = allFlameStrokes(frame(sim, MOVING_TIME))
        val repeated = allFlameStrokes(frame(sim, ON_TIME + WAVE_PERIOD))

        assertTrue(start.indices.any { index -> !start[index].sameGeometry(moving[index]) }, "the flame did not wave")
        assertStrokesEqual(start, repeated)
        assertStrokesEqual(start, allFlameStrokes(frame(sim, ON_TIME)))

        val tickTime = 0.4
        val interpolated = allFlameStrokes(frame(sim, tickTime, alpha = 0.5))
        val atPresentationTime = allFlameStrokes(frame(sim, tickTime - TICK_SECONDS / 2.0))
        assertStrokesEqual(atPresentationTime, interpolated)
    }

    @Test
    fun `drawing fire changes neither the jet tile digest nor lethal contact`() {
        val lethalJet = JET.copy(column = TestLevels.SPAWN_COLUMN)
        val sim = TestLevels.simulation(TestLevels.flat(jets = listOf(lethalJet)))
        val before = sim.digest()

        frame(sim, ON_TIME)
        frame(sim, MOVING_TIME)

        assertEquals(before, sim.digest())
        assertEquals(lethalJet, sim.level.jets.single())
        assertEquals(TileKind.Solid, sim.level.tiles[lethalJet.column, lethalJet.bottomRow + 1])
        assertTrue(sim.tick(InputFrame()).playerDied, "drawing the fire changed its lethal contact")
    }

    @Test
    fun `more jets add marks without adding pipe or flame style batches`() {
        val one = frame(TestLevels.simulation(TestLevels.flat(jets = listOf(JET))), ON_TIME)
        val many = frame(
            TestLevels.simulation(TestLevels.flat(jets = listOf(JET, JET.copy(column = 9)))),
            ON_TIME,
        )

        assertEquals(jetBatchKeys(one), jetBatchKeys(many))
        assertTrue(allFlameStrokes(many).size > allFlameStrokes(one).size)
    }

    private fun terrainPipeMarks(frame: DrawList): Int = frame.batches
        .filter { it.layer == Layer.Terrain && it.primitive in setOf(Primitive.Dot, Primitive.Segment) }
        .sumOf { it.size }

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

    private fun jetBatchKeys(frame: DrawList): Set<BatchKey> = frame.batches
        .filter {
            (it.layer == Layer.Terrain && it.primitive in setOf(Primitive.Rect, Primitive.Dot, Primitive.Segment)) ||
                (it.layer == Layer.Hazard && it.style in setOf(FIRE_OUTER, FIRE_CORE))
        }
        .map { BatchKey(it.layer, it.style, it.primitive, it.width) }
        .toSet()

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

    private fun frame(sim: GameSimulation, time: Double, alpha: Double = 1.0): DrawList = Scene.compose(
        sim,
        CAMERA,
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim),
        time,
        SceneBuilder(),
        alpha,
    )

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
    }

    private data class BatchKey(
        val layer: Layer,
        val style: String,
        val primitive: Primitive,
        val width: Double,
    )

    private companion object {
        const val SEED = 0xF1AEuL
        const val FIRE_OUTER = "#ff5a1f"
        const val FIRE_CORE = "#ffd166"
        const val ON_TIME = 0.18
        const val MOVING_TIME = 0.41
        const val OFF_TIME = 2.0
        const val WAVE_PERIOD = 0.72
        const val TONGUES = 3
        const val SEGMENTS_PER_TONGUE = 5
        const val TOLERANCE = 1e-8
        val CAMERA = Camera(0.0, 180.0, 240.0, 130.0)
        val JET = FireJet(
            column = 6,
            topRow = TestLevels.FLOOR_ROW - 5,
            bottomRow = TestLevels.FLOOR_ROW,
            periodSeconds = 4.0,
            onSeconds = 1.0,
            phaseSeconds = 0.0,
        )
    }
}
