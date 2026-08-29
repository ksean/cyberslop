package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-59: the Scrap award is readable world-space feedback, not a second HUD counter. */
class ScrapGainSceneTest {
    @Test
    fun `Scrap feedback is exact gold bold centred and fades while rising`() {
        val sim = TestLevels.simulation()
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        sim.tick(InputFrame())
        val camera = Camera(0.0, 0.0, 320.0, 180.0)
        val backdrop = Backdrops.of(sim.run.seed, sim.level)

        fun label(alpha: Double) = Scene.compose(
            sim, camera, backdrop, HudModel.of(sim), 0.0, SceneBuilder(), alpha,
        ).texts.single { it.text == "+${GameSimulation.BOSS_SCRAP}" }

        val born = label(alpha = 0.0)
        assertEquals("#ffd45a", born.style)
        assertEquals(18.0, born.sizePx)
        assertEquals(TextAlign.Centre, born.align)
        assertTrue(born.bold)
        assertEquals(1.0, born.opacity)

        repeat(27) { sim.tick(InputFrame()) }
        val halfway = label(alpha = 1.0)
        assertEquals(born.x, halfway.x)
        assertEquals(born.y - GameSimulation.SCRAP_GAIN_RISE_PX / 2.0, halfway.y, 0.01)
        assertEquals(0.5, halfway.opacity, 0.01)
    }
}
