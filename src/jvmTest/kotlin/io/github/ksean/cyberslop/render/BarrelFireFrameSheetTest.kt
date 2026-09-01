package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.Barrel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes two real frames so P-73's non-spike silhouette and wave can be inspected. */
class BarrelFireFrameSheetTest {
    @Test
    fun `burning barrel frames at two phases are written for inspection`() {
        val barrels = BARREL_COLUMNS.map { Barrel(it, TestLevels.FLOOR_ROW) }
        val sim = TestLevels.simulation(TestLevels.flat(barrels = barrels))
        val camera = Camera(0.0, 180.0, VIEW_WIDTH, VIEW_HEIGHT)
        val backdrop = Backdrops.of(SEED, sim.level)
        val directory = File("build/icon-sheets/barrel-fire").also { it.mkdirs() }

        FRAMES.forEach { sample ->
            val frame = Scene.compose(sim, camera, backdrop, HudModel.of(sim), sample.time, SceneBuilder())
            val sink = SvgPaintSink(VIEW_WIDTH * Scene.ZOOM, VIEW_HEIGHT * Scene.ZOOM, "#05060a")
            FramePainter.paint(frame, sink)
            val out = directory.resolve("barrel-fire-${sample.name}.svg")
            out.writeText(sink.toSvg())

            assertTrue(out.length() > 0, "${sample.name} frame was empty")
            assertTrue(
                frame.batches.any {
                    it.layer == Layer.Hazard && it.primitive == Primitive.Segment &&
                        it.style in setOf(FIRE_OUTER, FIRE_CORE)
                },
                "${sample.name} frame has no wavy barrel fire",
            )
        }
    }

    private data class Sample(val name: String, val time: Double)

    private companion object {
        const val SEED = 0xBA22E1uL
        const val VIEW_WIDTH = 240.0
        const val VIEW_HEIGHT = 130.0
        const val FIRE_OUTER = "#ff5a1f"
        const val FIRE_CORE = "#ffd166"
        val BARREL_COLUMNS = listOf(5, 7, 9)
        val FRAMES = listOf(Sample("phase-a", 0.17), Sample("phase-b", 0.41))
    }
}
