package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.progression.DiscoveryCatalog
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.sim.TestLevels
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes the real card frame so centring, contrast and icon scale can be visually reviewed. */
class DiscoveryCardFrameTest {
    @Test
    fun `riotbreaker discovery frame is written for inspection`() {
        val sim = TestLevels.simulation()
        val camera = Camera(0.0, 180.0, VIEW_WIDTH, VIEW_HEIGHT)
        val frame = Scene.compose(
            sim,
            camera,
            Backdrops.of(SEED, sim.level),
            HudModel.of(sim),
            0.0,
            SceneBuilder(),
            discovery = DiscoveryCatalog.of(DiscoveryId.Weapon(WeaponId.RiotbreakerShotgun)),
        )
        val sink = SvgPaintSink(VIEW_WIDTH * Scene.ZOOM, VIEW_HEIGHT * Scene.ZOOM, "#05060a")
        FramePainter.paint(frame, sink)
        val out = File("build/icon-sheets").also { it.mkdirs() }.resolve("discovery-card.svg")
        out.writeText(sink.toSvg())

        assertTrue(out.length() > 0)
    }

    private companion object {
        const val SEED = 0xD15C0uL
        const val VIEW_WIDTH = 240.0
        const val VIEW_HEIGHT = 130.0
    }
}
