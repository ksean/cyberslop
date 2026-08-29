package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.TestLevels
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes two real frames so P-58's liquid read can be reviewed, not inferred from assertions. */
class AcidFrameSheetTest {
    @Test
    fun `acid frames at two phases are written for inspection`() {
        val sim = TestLevels.simulation(TestLevels.flat(acidColumns = 5..11))
        val camera = Camera(0.0, 180.0, VIEW_WIDTH, VIEW_HEIGHT)
        val backdrop = Backdrops.of(SEED, sim.level)
        val directory = File("build/icon-sheets").also { it.mkdirs() }

        TIMES.forEach { time ->
            val frame = Scene.compose(sim, camera, backdrop, HudModel.of(sim), time, SceneBuilder())
            val sink = SvgPaintSink(VIEW_WIDTH * Scene.ZOOM, VIEW_HEIGHT * Scene.ZOOM, "#05060a")
            FramePainter.paint(frame, sink)
            val out = directory.resolve("acid-frame-${(time * 10).toInt()}.svg")
            out.writeText(sink.toSvg())

            assertTrue(out.length() > 0)
            assertTrue(
                frame.batches.any { it.layer == Layer.HazardSurface && it.primitive == Primitive.Dot },
                "the inspection frame contains no bubble bodies",
            )
        }
    }

    private companion object {
        const val SEED = 0xAC1DuL
        const val VIEW_WIDTH = 240.0
        const val VIEW_HEIGHT = 130.0
        val TIMES = listOf(0.0, 0.4)
    }
}
