package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.loot.PowerupSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeaponRegistryTest {
    @Test
    fun `the registry meets the required breadth`() {
        assertTrue(Weapons.all.size >= 20, "only ${Weapons.all.size} weapons")
        WeaponClass.entries.forEach { cls ->
            assertTrue(Weapons.all.any { it.cls == cls }, "no $cls weapon")
        }
    }

    @Test
    fun `the starting weapon is a broken bottle that swings every two seconds`() {
        val bottle = Weapons.startingWeapon

        assertEquals("Broken Bottle", bottle.name)
        assertEquals(WeaponClass.Melee, bottle.cls)
        assertEquals(2.0, bottle.cooldown)
    }

    @Test
    fun `identifiers are unique`() {
        assertEquals(Weapons.all.size, Weapons.all.map { it.id }.toSet().size)
    }

    @Test
    fun `damage per second rises with tier at every statistic`() {
        val byTier = Tier.entries.associateWith { tier -> Weapons.all.filter { it.tier == tier } }

        Tier.entries.zipWithNext { lower, higher ->
            val a = byTier.getValue(lower).map { it.baseDps }
            val b = byTier.getValue(higher).map { it.baseDps }
            assertTrue(b.min() > a.min(), "$higher min ${b.min()} not above $lower ${a.min()}")
            assertTrue(b.max() > a.max(), "$higher max ${b.max()} not above $lower ${a.max()}")
            assertTrue(
                b.average() > a.average(),
                "$higher mean ${b.average()} not above $lower ${a.average()}",
            )
        }
    }

    @Test
    fun `tiers do not overlap in power`() {
        val byTier = Tier.entries.associateWith { tier -> Weapons.all.filter { it.tier == tier } }

        Tier.entries.zipWithNext { lower, higher ->
            val ceiling = byTier.getValue(lower).maxOf { it.baseDps }
            val floor = byTier.getValue(higher).minOf { it.baseDps }
            assertTrue(
                floor >= ceiling * TIER_GAP,
                "$higher floor $floor is below $lower ceiling $ceiling x $TIER_GAP",
            )
        }
    }

    @Test
    fun `every weapon publishes a crit chance rather than relying on an implied default`() {
        Weapons.all.forEach {
            assertTrue(it.critChance >= 0.0 && it.critChance <= 1.0, "${it.name}: ${it.critChance}")
        }
    }

    @Test
    fun `every weapon scores finitely against the reference target`() {
        Weapons.all.forEach { weapon ->
            val score = WeaponScore.of(weapon, PowerupSlots.empty(), mapIndex = 5)
            assertTrue(score.isFinite() && score > 0.0, "${it(weapon)} scored $score")
        }
    }

    private fun it(weapon: WeaponSpec) = weapon.name

    private companion object {
        const val TIER_GAP = 1.05
    }
}
