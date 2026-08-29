package io.github.ksean.cyberslop.loop

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.game.DiscoverySession
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.progression.DiscoveryRecorder
import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.sim.GroundItem
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fixed-step accumulator must notice a discovery opened by the tick it just ran. */
class DiscoveryLoopTest {
    @Test
    fun `a pickup inside a catch up frame permits exactly one tick then freezes`() {
        var profile = PlayerProfile()
        val session = DiscoverySession(
            record = { DiscoveryRecorder.record(profile, it).also { update -> profile = update.profile } },
            clearInput = {},
            announce = {},
        )
        val sim = TestLevels.simulation()
        sim.items.clear()
        sim.items += GroundItem(
            io.github.ksean.cyberslop.core.Vec2(sim.player.x + 6.0, sim.player.y + 13.0),
            Weapons.of(WeaponId.RustlineMachete),
            null,
        )
        val frames = FixedStepFrames()
        val step = {
            val report = sim.tick(InputFrame())
            if (report.collectedDiscoveries.isNotEmpty()) session.collect(report.collectedDiscoveries)
        }

        frames.advance(250.0, isPaused = { session.paused }, step = step)
        assertEquals(1, sim.elapsedTicks, "catch-up ticks ran after the card opened")
        assertTrue(session.paused)
        val frozen = sim.digest()

        frames.advance(250.0, isPaused = { session.paused }, step = step)
        assertEquals(1, sim.elapsedTicks)
        assertEquals(frozen, sim.digest())
    }
}
