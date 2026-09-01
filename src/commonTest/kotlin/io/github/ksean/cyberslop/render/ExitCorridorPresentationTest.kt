package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExitCorridorPresentationTest {
    @Test
    fun `exposed floor beyond the gate has a blue edge and three sparks per tile`() {
        val sim = TestLevels.simulation()
        val frame = frame(sim, 0.0)
        val edges = rects(frame, Layer.Terrain, Scene.EXIT_SURFACE)
        val sparks = dots(frame, Layer.HazardSurface, Scene.EXIT_SPARK)
        val exitTiles = sim.level.widthTiles - sim.level.gateColumn - 1

        assertEquals(exitTiles, edges.size)
        assertEquals(exitTiles * 3, sparks.size)
        val gateX = TileMap.toWorld(1) * Scene.ZOOM
        assertTrue(edges.all { it[0] > gateX })
        assertTrue(sparks.all { it[0] > gateX })
    }

    @Test
    fun `exit sparks move and repeat without changing simulation state`() {
        val sim = TestLevels.simulation()
        val before = sim.digest()
        val start = dots(frame(sim, 0.0), Layer.HazardSurface, Scene.EXIT_SPARK)
        val moved = dots(frame(sim, 0.2), Layer.HazardSurface, Scene.EXIT_SPARK)
        val repeated = dots(frame(sim, Scene.EXIT_SPARK_PERIOD), Layer.HazardSurface, Scene.EXIT_SPARK)

        assertNotEquals(start, moved)
        assertEquals(start, repeated)
        assertEquals(before, sim.digest())
    }

    private fun frame(sim: GameSimulation, time: Double): DrawList = Scene.compose(
        sim,
        Camera(TileMap.toWorld(sim.level.gateColumn - 1), 180.0, 160.0, 130.0),
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim),
        time,
        SceneBuilder(),
    )

    private fun rects(frame: DrawList, layer: Layer, style: String): List<List<Double>> =
        primitives(frame, layer, style, Primitive.Rect)

    private fun dots(frame: DrawList, layer: Layer, style: String): List<List<Double>> =
        primitives(frame, layer, style, Primitive.Dot)

    private fun primitives(
        frame: DrawList,
        layer: Layer,
        style: String,
        primitive: Primitive,
    ): List<List<Double>> = frame.batches
        .filter { it.layer == layer && it.style == style && it.primitive == primitive }
        .flatMap { batch ->
            (0 until batch.size).map { index ->
                (0 until primitive.stride).map { offset -> batch[index * primitive.stride + offset] }
            }
        }

    private companion object {
        val SEED = 0xE71uL
    }
}
