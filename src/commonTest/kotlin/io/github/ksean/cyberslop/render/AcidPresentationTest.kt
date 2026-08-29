package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P-58: acid looks liquid without putting animation state into the simulation. */
class AcidPresentationTest {
    @Test
    fun `each exposed acid tile has three phased two tone bubble rings`() {
        val sim = acidSimulation(ACID_FIRST..ACID_LAST)
        val frame = frame(sim, 0.0)
        val outer = dots(frame, Layer.Hazard, Palettes.of(sim.level.theme).hazardGlow)
        val inner = dots(frame, Layer.HazardSurface, Palettes.of(sim.level.theme).hazard)

        assertEquals(ACID_TILES * BUBBLES_PER_TILE, outer.size)
        assertEquals(outer.size, inner.size)
        assertTrue(Layer.Hazard.ordinal < Layer.HazardSurface.ordinal)
        outer.indices.forEach { index ->
            assertEquals(outer[index].x, inner[index].x, TOLERANCE)
            assertEquals(outer[index].y, inner[index].y, TOLERANCE)
            assertTrue(inner[index].radius < outer[index].radius)
        }
        assertEquals(
            BUBBLES_PER_TILE,
            inner.take(BUBBLES_PER_TILE).map { it.y to it.radius }.toSet().size,
            "the bubbles on one tile share a phase",
        )
    }

    @Test
    fun `bubble motion is deterministic and exactly periodic`() {
        val sim = acidSimulation(ACID_FIRST..ACID_LAST)
        val start = surfaceDots(sim, 0.17)
        val moving = surfaceDots(sim, 0.43)
        val repeated = surfaceDots(sim, 0.17 + CYCLE_SECONDS)

        assertNotEquals(start, moving, "nothing moved or grew inside the cycle")
        assertDotsEqual(start, repeated)
        assertEquals(start, surfaceDots(sim, 0.17), "equal inputs produced a different surface")
    }

    @Test
    fun `pool width changes mark count but not bubble batch count`() {
        val narrow = frame(acidSimulation(ACID_FIRST..ACID_FIRST), 0.2)
        val wide = frame(acidSimulation(ACID_FIRST..ACID_LAST + 4), 0.2)

        fun bubbleBatches(frame: DrawList) = frame.batches.count {
            it.primitive == Primitive.Dot && it.layer in setOf(Layer.Hazard, Layer.HazardSurface)
        }

        assertEquals(bubbleBatches(narrow), bubbleBatches(wide))
        assertTrue(surfaceDots(acidSimulation(ACID_FIRST..ACID_LAST + 4), 0.2).size > surfaceDots(acidSimulation(ACID_FIRST..ACID_FIRST), 0.2).size)
    }

    @Test
    fun `drawing bubbles changes neither simulation digest nor acid lethality`() {
        val sim = acidSimulation(ACID_FIRST..ACID_LAST)
        val before = sim.digest()

        frame(sim, 0.0)
        frame(sim, 0.61)

        assertEquals(before, sim.digest())
        assertEquals(TileKind.Acid, sim.level.tiles[ACID_FIRST, TestLevels.FLOOR_ROW + 1])
        assertTrue(sim.level.tiles.isLethal(ACID_FIRST, TestLevels.FLOOR_ROW + 1))
    }

    private fun acidSimulation(columns: IntRange): GameSimulation =
        TestLevels.simulation(TestLevels.flat(acidColumns = columns))

    private fun surfaceDots(sim: GameSimulation, time: Double): List<Dot> =
        dots(frame(sim, time), Layer.HazardSurface, Palettes.of(sim.level.theme).hazard)

    private fun frame(sim: GameSimulation, time: Double): DrawList = Scene.compose(
        sim,
        Camera(0.0, 180.0, 240.0, 130.0),
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim),
        time,
        SceneBuilder(),
    )

    private fun dots(frame: DrawList, layer: Layer, style: String): List<Dot> =
        frame.batches
            .filter { it.layer == layer && it.style == style && it.primitive == Primitive.Dot }
            .flatMap { batch ->
                (0 until batch.size).map { index ->
                    val at = index * Primitive.Dot.stride
                    Dot(batch[at], batch[at + 1], batch[at + 2])
                }
            }

    private fun assertDotsEqual(expected: List<Dot>, actual: List<Dot>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index].x, actual[index].x, TOLERANCE)
            assertEquals(expected[index].y, actual[index].y, TOLERANCE)
            assertEquals(expected[index].radius, actual[index].radius, TOLERANCE)
        }
    }

    private data class Dot(val x: Double, val y: Double, val radius: Double)

    private companion object {
        const val SEED = 0xAC1DuL
        const val ACID_FIRST = 6
        const val ACID_LAST = 7
        const val ACID_TILES = 2
        const val BUBBLES_PER_TILE = 3
        const val CYCLE_SECONDS = 1.2
        const val TOLERANCE = 1e-9
    }
}
