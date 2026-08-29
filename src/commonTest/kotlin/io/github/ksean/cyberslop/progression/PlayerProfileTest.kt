package io.github.ksean.cyberslop.progression

import io.github.ksean.cyberslop.combat.WeaponId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerProfileTest {
    @Test
    fun `a new profile has no scrap or upgrades and starts with the bottle discovered`() {
        val profile = PlayerProfile()

        assertEquals(0, profile.spendableScrap)
        assertEquals(0, profile.lifetimeScrap)
        assertEquals(UpgradeRanks(), profile.upgrades)
        assertEquals(setOf(WeaponId.BrokenBottle), profile.discoveredWeapons)
        assertTrue(profile.discoveredPowerups.isEmpty())
        assertEquals(8, profile.unlockedWeapons)
    }

    @Test
    fun `banking raises both scrap counters and lifetime unlocks`() {
        val banked = PlayerProfile().banking(850)

        assertEquals(850, banked.spendableScrap)
        assertEquals(850, banked.lifetimeScrap)
        assertEquals(10, banked.unlockedWeapons)
    }

    @Test
    fun `buying five ranks charges each displayed price without lowering lifetime unlocks`() {
        val start = PlayerProfile(spendableScrap = 10_000, lifetimeScrap = 10_000)
        val bought = (0 until 5).fold(start) { profile, _ ->
            profile.purchasing(UpgradeId.BlackMarketFirmware)
        }

        assertEquals(6_150, bought.spendableScrap)
        assertEquals(10_000, bought.lifetimeScrap)
        assertEquals(5, bought.upgrades.rankOf(UpgradeId.BlackMarketFirmware))
        assertEquals(26, bought.unlockedWeapons)
        assertSame(bought, bought.purchasing(UpgradeId.BlackMarketFirmware), "rank six changed the profile")
    }

    @Test
    fun `unaffordable and unknown purchases return the same profile`() {
        val profile = PlayerProfile(spendableScrap = 99, lifetimeScrap = 1_200)

        assertSame(profile, profile.purchasing(UpgradeId.ReinforcedChassis))
        assertSame(profile, profile.purchasing("NotAnUpgrade"))
        assertEquals(11, profile.unlockedWeapons)
    }
}
