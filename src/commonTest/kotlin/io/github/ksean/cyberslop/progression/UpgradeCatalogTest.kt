package io.github.ksean.cyberslop.progression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpgradeCatalogTest {
    @Test
    fun `all tracks have five shared rank prices and their specified effects`() {
        assertEquals(UpgradeId.entries.toSet(), UpgradeCatalog.all.map { it.id }.toSet())
        UpgradeCatalog.all.forEach { upgrade ->
            assertEquals(listOf(100, 250, 500, 1_000, 2_000), upgrade.prices)
            assertEquals(5, upgrade.maxRank)
        }

        assertEquals(1.5, UpgradeRanks(reinforcedChassis = 5).healthMultiplier)
        assertEquals(1.25, UpgradeRanks(blackMarketFirmware = 5).weaponDamageMultiplier)
        assertEquals(0.75, UpgradeRanks(reactiveDermalWeave = 5).incomingDamageMultiplier)
    }

    @Test
    fun `next price follows current rank and ends at max rank`() {
        val chassis = UpgradeCatalog.of(UpgradeId.ReinforcedChassis)

        assertEquals(100, chassis.nextPrice(currentRank = 0))
        assertEquals(2_000, chassis.nextPrice(currentRank = 4))
        assertNull(chassis.nextPrice(currentRank = 5))
    }
}
