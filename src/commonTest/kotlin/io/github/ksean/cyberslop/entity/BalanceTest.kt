package io.github.ksean.cyberslop.entity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BalanceTest {
    @Test
    fun `the published curve values hold`() {
        assertClose(12.0, Balance.trashHealth(1))
        assertClose(22.67, Balance.trashHealth(5))
        assertClose(36.0, Balance.trashHealth(10))

        assertClose(6.0, Balance.contactDamage(1))
        assertClose(16.67, Balance.contactDamage(5))
        assertClose(30.0, Balance.contactDamage(10))

        assertClose(100.0, Balance.playerMaxHealth(1))
        assertClose(235.0, Balance.playerMaxHealth(10))
    }

    @Test
    fun `enemy health and damage have the specified linear endpoint ratios`() {
        listOf(
            Balance::trashHealth,
            Balance::minibossHealth,
            Balance::bossHealth,
        ).forEach { healthAt ->
            assertClose(3.0 * healthAt(1), healthAt(10))
            assertConstantIncrement(healthAt)
        }

        assertClose(5.0 * Balance.contactDamage(1), Balance.contactDamage(10))
        assertConstantIncrement(Balance::contactDamage)
    }

    @Test
    fun `every curve rises with the map index`() {
        (1..9).forEach { map ->
            assertTrue(Balance.trashHealth(map + 1) > Balance.trashHealth(map))
            assertTrue(Balance.contactDamage(map + 1) > Balance.contactDamage(map))
            assertTrue(Balance.playerMaxHealth(map + 1) > Balance.playerMaxHealth(map))
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
    fun `a player at the required rate survives several contact hits`() {
        (1..10).forEach { map ->
            val hits = Balance.playerMaxHealth(map) / Balance.contactDamage(map)
            assertTrue(hits >= 3.0, "map $map: only $hits contact hits survivable")
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
