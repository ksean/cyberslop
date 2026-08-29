package io.github.ksean.cyberslop.progression

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileCodecTest {
    @Test
    fun `a current profile has a canonical byte for byte round trip`() {
        val profile = PlayerProfile(
            spendableScrap = 1_250,
            lifetimeScrap = 3_600,
            upgrades = UpgradeRanks(2, 4, 1),
            discoveredWeapons = setOf(
                WeaponId.RiotbreakerShotgun,
                WeaponId.BrokenBottle,
                WeaponId.SableCorpRailgun,
            ),
            discoveredPowerups = setOf(PowerupId.RedMarketSiphon, PowerupId.OverclockCoil),
        )
        val encoded = ProfileCodec.encode(profile)
        val restored = ProfileCodec.decode(encoded).getOrThrow()

        assertEquals(profile, restored)
        assertEquals(encoded, ProfileCodec.encode(restored))
    }

    @Test
    fun `a legacy scrap integer becomes both counters with safe defaults`() {
        val profile = ProfileCodec.decodeLegacyScrap("1200").getOrThrow()

        assertEquals(1_200, profile.spendableScrap)
        assertEquals(1_200, profile.lifetimeScrap)
        assertEquals(UpgradeRanks(), profile.upgrades)
        assertEquals(setOf(WeaponId.BrokenBottle), profile.discoveredWeapons)
        assertTrue(profile.discoveredPowerups.isEmpty())
    }

    @Test
    fun `malformed or unknown profiles are rejected as a whole`() {
        listOf(
            null,
            "",
            "99|0|0|0,0,0|BrokenBottle|",
            "1|-1|0|0,0,0|BrokenBottle|",
            "1|20|10|0,0,0|BrokenBottle|",
            "1|0|0|6,0,0|BrokenBottle|",
            "1|0|0|0,0,0||",
            "1|0|0|0,0,0|NoSuchWeapon|",
            "1|0|0|0,0,0|BrokenBottle|NoSuchPowerup",
            "1|0|0|0,0,0|BrokenBottle,BrokenBottle|",
        ).forEach { encoded ->
            assertTrue(ProfileCodec.decode(encoded).isFailure, "accepted '$encoded'")
        }
    }
}
