package io.github.ksean.cyberslop.title

import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.progression.UpgradeId
import io.github.ksean.cyberslop.progression.UpgradeRanks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopScreenStateTest {
    @Test
    fun `shop rows expose balance rank total effect next price and affordability`() {
        val state = createShopScreenState(
            PlayerProfile(
                spendableScrap = 400,
                lifetimeScrap = 2_000,
                upgrades = UpgradeRanks(reinforcedChassis = 2, blackMarketFirmware = 5),
            ),
        )

        assertEquals(400, state.spendableScrap)
        assertEquals(UpgradeId.entries.toList(), state.rows.map { it.id })
        state.rows[0].let { row ->
            assertEquals("Rank 2 of 5", row.rankText)
            assertEquals("Maximum health +20%", row.effectText)
            assertEquals(500, row.nextPrice)
            assertFalse(row.canPurchase)
        }
        state.rows[1].let { row ->
            assertEquals("Rank 5 of 5", row.rankText)
            assertEquals("Weapon damage +25%", row.effectText)
            assertNull(row.nextPrice)
            assertEquals("Max rank", row.priceText)
            assertFalse(row.canPurchase)
        }
        state.rows[2].let { row ->
            assertEquals("Incoming non-lethal damage -0%", row.effectText)
            assertEquals(100, row.nextPrice)
            assertTrue(row.canPurchase)
        }
    }
}
