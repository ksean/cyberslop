package io.github.ksean.cyberslop.entity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BalanceTest {
    @Test
    fun `the published curve values hold`() {
        assertClose(12.0, Balance.trashHealth(1))
        assertClose(33.33, Balance.trashHealth(5))
        assertClose(60.0, Balance.trashHealth(10))

        assertClose(6.0, Balance.contactDamage(1))
        assertClose(22.0, Balance.contactDamage(5))
        assertClose(42.0, Balance.contactDamage(10))

        assertClose(6.0, Balance.hazardDamage(1))
        assertClose(16.67, Balance.hazardDamage(5))
        assertClose(30.0, Balance.hazardDamage(10))

        assertClose(100.0, Balance.playerMaxHealth())
    }

    @Test
    fun `enemy health and damage have the specified linear endpoint ratios`() {
        listOf(
            Balance::trashHealth,
            Balance::minibossHealth,
            Balance::bossHealth,
        ).forEach { healthAt ->
            assertClose(5.0 * healthAt(1), healthAt(10))
            assertConstantIncrement(healthAt)
        }

        assertClose(7.0 * Balance.contactDamage(1), Balance.contactDamage(10))
        assertConstantIncrement(Balance::contactDamage)

        assertClose(5.0 * Balance.hazardDamage(1), Balance.hazardDamage(10))
        assertConstantIncrement(Balance::hazardDamage)
    }

    @Test
    fun `enemy curves rise while player maximum health has no map input`() {
        assertClose(100.0, Balance.playerMaxHealth())
        (1..9).forEach { map ->
            assertTrue(Balance.trashHealth(map + 1) > Balance.trashHealth(map))
            assertTrue(Balance.contactDamage(map + 1) > Balance.contactDamage(map))
            assertTrue(Balance.hazardDamage(map + 1) > Balance.hazardDamage(map))
            assertTrue(Balance.requiredDps(map + 1) > Balance.requiredDps(map))
        }
    }

    @Test
    fun `time to kill bands are derived from the health multipliers, not asserted separately`() {
        // Boss health is a fixed multiple of trash health, so a player at exactly the required rate
        // kills a boss in exactly that multiple of the trash time. Choosing the two independently
        // makes them contradict, which is what an earlier version of the plan did.
        (1..10).forEach { map ->
            val dps = Balance.requiredDps(map)
            assertClose(Balance.targetTrashSeconds(map), Balance.trashHealth(map) / dps)
            assertClose(Balance.targetMinibossSeconds(map), Balance.minibossHealth(map) / dps)
            assertClose(Balance.targetBossSeconds(map), Balance.bossHealth(map) / dps)
        }
    }

    @Test
    fun `the strongest achievable build can meet the final map's requirement`() {
        assertTrue(
            Balance.peakAchievableDps() > Balance.requiredDps(10),
            "peak ${Balance.peakAchievableDps()} cannot meet ${Balance.requiredDps(10)}",
        )
    }

    @Test
    fun `the buffed starting weapon meets the first map's requirement`() {
        assertClose(
            Balance.requiredDps(1),
            io.github.ksean.cyberslop.combat.Weapons.startingWeapon.baseDps,
        )
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(
            abs(actual - expected) <= abs(expected) * 0.005 + 1e-9,
            "expected about $expected, was $actual",
        )
    }

    private fun assertConstantIncrement(valueAt: (Int) -> Double) {
        val increment = valueAt(2) - valueAt(1)
        (2..10).forEach { map ->
            assertClose(increment, valueAt(map) - valueAt(map - 1))
        }
    }
}
