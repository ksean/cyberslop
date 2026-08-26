package io.github.ksean.cyberslop.entity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class BalanceTest {
    @Test
    fun `the published curve values hold`() {
        assertClose(12.0, Balance.trashHealth(1))
        assertClose(84.71, Balance.trashHealth(5))
        assertClose(974.70, Balance.trashHealth(10))

        assertClose(6.0, Balance.contactDamage(1))
        assertClose(18.22, Balance.contactDamage(5))
        assertClose(73.0, Balance.contactDamage(10))

        assertClose(100.0, Balance.playerMaxHealth(1))
        assertClose(235.0, Balance.playerMaxHealth(10))
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
    fun `the starting weapon is deliberately short of the first map's requirement`() {
        // The first weapon pickup is the opening progression beat; the bottle is not meant to carry
        // the player through map one on its own.
        assertTrue(
            io.github.ksean.cyberslop.combat.Weapons.startingWeapon.baseDps < Balance.requiredDps(1),
        )
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(
            abs(actual - expected) <= abs(expected) * 0.005 + 1e-9,
            "expected about $expected, was $actual",
        )
    }
}
