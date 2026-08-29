package io.github.ksean.cyberslop.save

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.progression.ProfileCodec
import io.github.ksean.cyberslop.progression.UpgradeRanks
import io.github.ksean.cyberslop.progression.UpgradeId
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.run.SaveCodec
import kotlinx.browser.localStorage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LocalStorageSaveStoreTest {
    private val store = LocalStorageSaveStore()

    @BeforeTest
    @AfterTest
    fun clearKeys() {
        listOf(RUN_KEY, META_KEY, PROFILE_KEY).forEach(localStorage::removeItem)
    }

    @Test
    fun `profile writes leave an in progress run byte for byte unchanged`() {
        val run = RunState.begin(SEED).copy(mapIndex = 3, health = 72.0, scrap = 44)
        val profile = PlayerProfile(900, 1_700, UpgradeRanks(1, 2, 0))
        store.save(run)
        val encodedRun = localStorage.getItem(RUN_KEY)

        store.saveProfile(profile)

        assertEquals(encodedRun, localStorage.getItem(RUN_KEY))
        assertEquals(encodedRun, store.load()?.first?.let(SaveCodec::encodeRun))
        assertEquals(profile, store.load()?.second)
    }

    @Test
    fun `legacy integer and version two run migrate using the greater nonnegative scrap`() {
        localStorage.setItem(META_KEY, "1600")
        localStorage.setItem(
            RUN_KEY,
            "2|$SEED|4|VultureRailCarbine||80.0|35|1200",
        )

        val restored = assertNotNull(store.load())

        assertEquals(4, restored.first.mapIndex)
        assertEquals(1_600, restored.second.spendableScrap)
        assertEquals(1_600, restored.second.lifetimeScrap)
        assertEquals(setOf(WeaponId.BrokenBottle), restored.second.discoveredWeapons)
        assertEquals(restored.second, ProfileCodec.decode(localStorage.getItem(PROFILE_KEY)).getOrThrow())
    }

    @Test
    fun `version two run scrap wins when it is newer than the legacy integer`() {
        localStorage.setItem(META_KEY, "800")
        localStorage.setItem(RUN_KEY, "2|$SEED|2|BrokenBottle||90.0|12|2000")

        assertEquals(2_000, store.loadProfile().lifetimeScrap)
    }

    @Test
    fun `a valid current profile is canonical and malformed current data is not partially applied`() {
        val current = PlayerProfile(250, 2_400, UpgradeRanks(2, 1, 3))
        store.saveProfile(current)
        localStorage.setItem(META_KEY, "9999")
        assertEquals(current, store.loadProfile(), "legacy data overrode the current profile")

        localStorage.setItem(PROFILE_KEY, "1|999|10|5,5,5|BrokenBottle|")
        localStorage.removeItem(META_KEY)
        assertEquals(PlayerProfile(), store.loadProfile(), "part of a malformed profile survived")
    }

    @Test
    fun `purchase checks the saved expected rank and preserves the run`() {
        val run = RunState.begin(SEED)
        store.save(run)
        store.saveProfile(PlayerProfile(500, 1_000))
        val runBytes = localStorage.getItem(RUN_KEY)

        val bought = store.purchase(UpgradeId.ReinforcedChassis, expectedRank = 0)
        val staleDoubleActivation = store.purchase(UpgradeId.ReinforcedChassis, expectedRank = 0)

        assertEquals(400, bought.spendableScrap)
        assertEquals(1, bought.upgrades.reinforcedChassis)
        assertEquals(bought, staleDoubleActivation)
        assertEquals(runBytes, localStorage.getItem(RUN_KEY))
    }

    @Test
    fun `first discoveries persist before they are returned and repeated ids are suppressed`() {
        val run = RunState.begin(SEED)
        store.save(run)
        store.saveProfile(PlayerProfile())
        val runBytes = localStorage.getItem(RUN_KEY)
        val weapon = DiscoveryId.Weapon(WeaponId.RustlineMachete)
        val powerup = DiscoveryId.Powerup(PowerupId.RedMarketSiphon)

        val first = store.recordDiscoveries(listOf(weapon, powerup, weapon))

        assertEquals(listOf(weapon, powerup), first.entries.map { it.id })
        assertEquals(first.profile, ProfileCodec.decode(localStorage.getItem(PROFILE_KEY)).getOrThrow())
        assertEquals(runBytes, localStorage.getItem(RUN_KEY))
        assertEquals(emptyList(), store.recordDiscoveries(listOf(powerup, weapon)).entries)
    }

    private companion object {
        const val SEED = 0xC0FFEEuL
    }
}
