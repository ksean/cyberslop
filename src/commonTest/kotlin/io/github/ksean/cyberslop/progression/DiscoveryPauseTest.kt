package io.github.ksean.cyberslop.progression

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P-57: each card consumes exactly three seconds of visible, focused time and no game time. */
class DiscoveryPauseTest {
    @Test
    fun `cards are sequential unskippable and background time does not count`() {
        val weapon = DiscoveryCatalog.of(DiscoveryId.Weapon(WeaponId.RiotbreakerShotgun))
        val powerup = DiscoveryCatalog.of(DiscoveryId.Powerup(PowerupId.RedMarketSiphon))
        val pause = DiscoveryPause()

        assertTrue(pause.enqueue(listOf(weapon, powerup, weapon)), "the first card did not open")
        assertEquals(weapon, pause.active)
        assertEquals(2, pause.queuedCount)

        pause.advance(2.99, pageActive = true)
        pause.advance(60.0, pageActive = false)
        assertEquals(weapon, pause.active, "background time expired the card")

        pause.advance(0.01, pageActive = true)
        assertEquals(powerup, pause.active)
        assertEquals(0.0, pause.activeElapsedSeconds)

        // Excess from one frame is not charged to a card that was not yet visible.
        pause.advance(30.0, pageActive = true)
        assertNull(pause.active)
        assertFalse(pause.paused)
    }

    @Test
    fun `advancing discovery time never advances or mutates the simulation`() {
        val sim = TestLevels.simulation()
        val beforeDigest = sim.digest()
        val beforeTicks = sim.elapsedTicks
        val pause = DiscoveryPause()
        pause.enqueue(listOf(DiscoveryCatalog.of(DiscoveryId.Weapon(WeaponId.RustlineMachete))))

        repeat(180) { pause.advance(1.0 / 60.0, pageActive = true) }

        assertEquals(beforeTicks, sim.elapsedTicks)
        assertEquals(beforeDigest, sim.digest())
        assertNull(pause.active)
    }
}
