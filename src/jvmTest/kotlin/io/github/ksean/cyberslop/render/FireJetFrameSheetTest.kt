package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.FireJet
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes the off state and two active phases so P-70's fire and broken-pipe read can be inspected. */
class FireJetFrameSheetTest {
    @Test
    fun `off and waving fire jet frames are written for inspection`() {
        val jet = FireJet(
            column = JET_COLUMN,
            topRow = TestLevels.FLOOR_ROW - 5,
            bottomRow = TestLevels.FLOOR_ROW,
            periodSeconds = 4.0,
            onSeconds = 1.0,
            phaseSeconds = 0.0,
        )
        val sim = TestLevels.simulation(TestLevels.flat(jets = listOf(jet)))
        val camera = Camera(0.0, 145.0, VIEW_WIDTH, VIEW_HEIGHT)
        val backdrop = Backdrops.of(SEED, sim.level)
        val directory = File("build/icon-sheets/fire-jet").also { it.mkdirs() }

        FRAMES.forEach { sample ->
            val frame = Scene.compose(sim, camera, backdrop, HudModel.of(sim), sample.time, SceneBuilder())
            val sink = SvgPaintSink(VIEW_WIDTH * Scene.ZOOM, VIEW_HEIGHT * Scene.ZOOM, "#05060a")
            FramePainter.paint(frame, sink)
            val out = directory.resolve("fire-jet-${sample.name}.svg")
            out.writeText(sink.toSvg())

            assertTrue(out.length() > 0, "${sample.name} frame was empty")
            assertTrue(
                frame.batches.any { it.layer == Layer.Terrain && it.primitive == Primitive.Dot },
                "${sample.name} frame has no pipe mouth",
            )
            assertTrue(
                frame.batches.any { it.layer == Layer.Terrain && it.primitive == Primitive.Segment },
                "${sample.name} frame has no split rim or crack",
            )
            val flame = frame.batches.any {
                it.layer == Layer.Hazard && it.primitive == Primitive.Segment &&
                    it.style in setOf(FIRE_OUTER, FIRE_CORE)
            }
            assertTrue(flame == sample.active, "${sample.name} frame has the wrong flame state")
        }
    }

    private data class Sample(val name: String, val time: Double, val active: Boolean)

    private companion object {
        const val SEED = 0xF1AEuL
        const val JET_COLUMN = 6
        const val VIEW_WIDTH = 150.0
        const val VIEW_HEIGHT = 130.0
        const val FIRE_OUTER = "#ff5a1f"
        const val FIRE_CORE = "#ffd166"
        val FRAMES = listOf(
            Sample("off", 2.0, false),
            Sample("phase-a", 0.18, true),
            Sample("phase-b", 0.41, true),
        )
    }
}
