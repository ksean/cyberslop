package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.TICK_SECONDS
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

    /** P-46: a machine gun bursts in a straight line; a burst is always spent before the next trigger. */
    @Test
    fun `burst weapons declare no spread and finish inside the cooldown floor`() {
        val bursting = Weapons.all.filter { it.burstIntervalSeconds > 0.0 }
        assertEquals(listOf(WeaponId.GanglordSmg), bursting.map { it.id })
        bursting.forEach { spec ->
            assertEquals(0.0, spec.spreadDegrees, "${spec.name} both bursts and spreads")
            val longest = spec.burstIntervalSeconds * (spec.projectileCount + 3 - 1)
            assertTrue(longest < spec.cooldown * 0.35, "${spec.name}'s burst ($longest s) outlasts its cooldown floor")
        }
        assertEquals(0.0, Weapons.of(WeaponId.DebtCollectorMinigun).spreadDegrees, "the minigun still blooms")
        assertTrue(Weapons.of(WeaponId.RiotbreakerShotgun).spreadDegrees > 0.0)
        assertTrue(Weapons.of(WeaponId.TenementNailgun).spreadDegrees > 0.0)
    }

    @Test
    fun `Ashfall is the only current lobbed projectile`() {
        val lobbed = Weapons.all.filter {
            (it.pattern as? FirePattern.Projectile)?.gravity?.let { gravity -> gravity > 0.0 } == true
        }

        assertEquals(listOf(WeaponId.AshfallGrenadeLobber), lobbed.map { it.id })
        val ashfall = lobbed.single()
        val pattern = ashfall.pattern as FirePattern.Projectile
        assertEquals(600.0, pattern.gravity)
        ProjectileBallistics.solve(
            origin = Vec2.Zero,
            target = Vec2(0.0, Targeting.AUTO_RANGE_PX),
            nominalSpeed = ashfall.projectileSpeed,
            gravity = pattern.gravity,
            lifetimeSeconds = pattern.lifetimeSeconds,
            tickSeconds = TICK_SECONDS,
        )
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
    fun `every melee weapon reaches at least two metres, beyond any enemy swing`() {
        Weapons.all.filter { it.cls == WeaponClass.Melee }.forEach {
            assertTrue(it.rangePx >= 2.0 * METRE_PX, "${it.name} reaches only ${it.rangePx} px")
            assertTrue(it.rangePx > ENEMY_SWING_PX, "${it.name} is inside an enemy swing")
        }
    }

    @Test
    fun `within each tier melee out-damages ranged on average, the bottle excluded`() {
        Tier.entries.forEach { tier ->
            val melee = Weapons.ofTier(tier)
                .filter { it.cls == WeaponClass.Melee && it.id != WeaponId.BrokenBottle }
                .map { it.baseDps }
            val ranged = Weapons.ofTier(tier).filter { it.cls == WeaponClass.Ranged }.map { it.baseDps }
            if (melee.isEmpty() || ranged.isEmpty()) return@forEach
            assertTrue(
                melee.average() > ranged.average(),
                "$tier melee mean ${melee.average()} not above ranged mean ${ranged.average()}",
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
        const val METRE_PX = 16.0

        /** An enemy swing reaches 1.5 tiles. */
        const val ENEMY_SWING_PX = 24.0
    }
}
