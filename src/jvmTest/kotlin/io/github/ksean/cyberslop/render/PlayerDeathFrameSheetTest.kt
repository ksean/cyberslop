package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.DeathSequence
import io.github.ksean.cyberslop.sim.PlayerDamageSource
import io.github.ksean.cyberslop.sim.TestLevels
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes the same common draw lists the browser paints for cause-by-time visual inspection. */
class PlayerDeathFrameSheetTest {
    @Test
    fun `death causes at collapse milestones are written for inspection`() {
        val directory = File("build/icon-sheets/player-death").also { it.mkdirs() }
        val cards = CAUSES.flatMap { cause ->
            SAMPLES.map { sample ->
                val sim = TestLevels.simulation().also {
                    it.deathSequence = DeathSequence(cause, sample.elapsedTicks)
                }
                val frame = Scene.compose(
                    sim,
                    Camera(0.0, 180.0, VIEW_WIDTH, VIEW_HEIGHT),
                    Backdrops.of(SEED, sim.level),
                    HudModel.of(sim),
                    sim.presentationTimeSeconds,
                    SceneBuilder(),
                )
                val sink = SvgPaintSink(VIEW_WIDTH * Scene.ZOOM, VIEW_HEIGHT * Scene.ZOOM, "#05060a")
                FramePainter.paint(frame, sink)
                val file = directory.resolve("${cause.name.lowercase()}-${sample.name}.svg")
                file.writeText(sink.toSvg())
                assertTrue(file.length() > 0)
                "<figure><img src=\"${file.name}\"><figcaption>${cause.name}: ${sample.caption}</figcaption></figure>"
            }
        }
        directory.resolve("index.html").writeText(
            "<html><style>body{margin:0;background:#111;color:#eee;font:14px sans-serif;" +
                "display:grid;grid-template-columns:repeat(3,1fr)}figure{margin:6px}" +
                "img{width:100%;background:#05060a}figcaption{padding:4px}</style>" +
                cards.joinToString("") + "</html>",
        )
    }

    private data class Sample(val name: String, val caption: String, val elapsedTicks: Int)

    private companion object {
        const val SEED = 0xDEA7uL
        const val VIEW_WIDTH = 160.0
        const val VIEW_HEIGHT = 110.0
        val CAUSES = listOf(PlayerDamageSource.Acid, PlayerDamageSource.Fire, PlayerDamageSource.Spike)
        val SAMPLES = listOf(
            Sample("start", "lethal pose", 0),
            Sample("half", "one-second collapse", DeathSequence.COLLAPSE_TICKS / 2),
            Sample("prone", "two-second prone hold", DeathSequence.COLLAPSE_TICKS),
        )
    }
}
