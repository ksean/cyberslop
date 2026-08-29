package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.progression.DiscoveryRecorder
import io.github.ksean.cyberslop.progression.PlayerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Browser-host ordering: save, clear, announce; then clear again only when the queue closes. */
class DiscoverySessionTest {
    @Test
    fun `session persists before opening announces each card and clears input at both boundaries`() {
        var profile = PlayerProfile()
        val events = mutableListOf<String>()
        val session = DiscoverySession(
            record = { collected ->
                events += "saved"
                DiscoveryRecorder.record(profile, collected).also { profile = it.profile }
            },
            clearInput = { events += "cleared" },
            announce = { events += "announced:$it" },
        )
        val weapon = DiscoveryId.Weapon(WeaponId.RiotbreakerShotgun)
        val powerup = DiscoveryId.Powerup(PowerupId.RedMarketSiphon)

        val update = session.collect(listOf(weapon, powerup, weapon))

        assertEquals(listOf(weapon, powerup), update.entries.map { it.id })
        assertTrue(session.paused)
        assertEquals(
            listOf(
                "saved",
                "cleared",
                "announced:Riotbreaker Shotgun. Fires five projectiles at once in a 30° spread.",
            ),
            events,
        )

        session.advance(30.0, pageActive = false)
        assertEquals(weapon, session.active?.id)
        // This frame's delta happened before the pickup tick opened the card, so none of it is
        // visible-card time. Even a catch-up frame must not shorten the three-second interval.
        session.advance(0.25, pageActive = true)
        session.advance(2.99, pageActive = true)
        assertEquals(weapon, session.active?.id)
        session.advance(0.01, pageActive = true)
        assertEquals(powerup, session.active?.id)
        assertTrue(events.last().startsWith("announced:Red Market Siphon."))
        session.advance(3.0, pageActive = true)

        assertNull(session.active)
        assertFalse(session.paused)
        assertEquals(2, events.count { it == "cleared" })
        assertEquals(emptyList(), session.collect(listOf(powerup, weapon)).entries)
        assertEquals("saved", events.last())
    }
}
