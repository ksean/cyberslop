package io.github.ksean.cyberslop.progression

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.render.PowerupIcons
import io.github.ksean.cyberslop.render.WeaponIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-57: discovery copy and identity are total over the two item registries. */
class DiscoveryCatalogTest {
    @Test
    fun `every weapon and powerup has bounded authored copy and its canonical icon`() {
        assertEquals(WeaponId.entries.size + PowerupId.entries.size, DiscoveryCatalog.all.size)
        assertEquals(
            WeaponId.entries.toSet(),
            DiscoveryCatalog.all.mapNotNull { (it.id as? DiscoveryId.Weapon)?.id }.toSet(),
        )
        assertEquals(
            PowerupId.entries.toSet(),
            DiscoveryCatalog.all.mapNotNull { (it.id as? DiscoveryId.Powerup)?.id }.toSet(),
        )

        DiscoveryCatalog.all.forEach { entry ->
            assertTrue(entry.description.isNotBlank(), "${entry.id} has blank discovery copy")
            assertTrue(entry.description.length <= 140, "${entry.id} copy is ${entry.description.length} characters")
            assertTrue(entry.description.endsWith('.'), "${entry.id} copy is not a sentence")
            when (val id = entry.id) {
                is DiscoveryId.Weapon -> {
                    assertEquals(Weapons.of(id.id).name, entry.name)
                    assertTrue(entry.icon === WeaponIcons.of(id.id), "${id.id} does not reuse its weapon icon")
                }

                is DiscoveryId.Powerup -> {
                    assertEquals(Powerups.of(id.id).name, entry.name)
                    assertTrue(entry.icon === PowerupIcons.of(id.id), "${id.id} does not reuse its powerup icon")
                }
            }
        }
    }

    @Test
    fun `reviewed examples describe the mechanics the registries actually execute`() {
        assertEquals(
            "Fires five projectiles at once in a 30° spread.",
            DiscoveryCatalog.of(DiscoveryId.Weapon(WeaponId.RiotbreakerShotgun)).description,
        )
        assertEquals(
            "Heals you for a fraction of the damage dealt by every weapon hit.",
            DiscoveryCatalog.of(DiscoveryId.Powerup(PowerupId.RedMarketSiphon)).description,
        )
    }

    @Test
    fun `recording keeps first-seen order deduplicates and suppresses later runs`() {
        val weapon = DiscoveryId.Weapon(WeaponId.RustlineMachete)
        val powerup = DiscoveryId.Powerup(PowerupId.BurnRig)

        val first = DiscoveryRecorder.record(PlayerProfile(), listOf(weapon, powerup, weapon))
        assertEquals(listOf(weapon, powerup), first.entries.map { it.id })
        assertTrue(WeaponId.RustlineMachete in first.profile.discoveredWeapons)
        assertTrue(PowerupId.BurnRig in first.profile.discoveredPowerups)

        val laterRun = DiscoveryRecorder.record(first.profile, listOf(powerup, weapon))
        assertTrue(laterRun.entries.isEmpty())
        assertEquals(first.profile, laterRun.profile)
    }
}
