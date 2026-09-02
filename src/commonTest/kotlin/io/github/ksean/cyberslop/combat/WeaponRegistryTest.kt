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

    @Test
    fun `every player melee weapon has its fifty-percent damage increase`() {
        val expected = mapOf(
            WeaponId.BrokenBottle to 12.0,
            WeaponId.RustlineMachete to 25.5,
            WeaponId.CorpoRiotBaton to 25.5,
            WeaponId.ChromeFang to 19.5,
            WeaponId.StaticLash to 36.0,
            WeaponId.GutterjackCleaver to 63.0,
            WeaponId.KillSwitchKatana to 60.0,
            WeaponId.ChromewreckMaul to 150.0,
            WeaponId.MeatgrinderHalo to 60.0,
        )

        assertEquals(
            expected,
            Weapons.all.filter { it.cls == WeaponClass.Melee }.associate { it.id to it.damage },
        )
    }

    @Test
    fun `the melee increase preserves every other registry field`() {
        @Suppress("LongParameterList")
        fun melee(
            id: WeaponId,
            name: String,
            tier: Tier,
            cooldown: Double,
            reachMetres: Double,
            arcDegrees: Double,
            projectiles: Int = 1,
            knockback: Double = 0.0,
            onHit: List<HitEffect> = emptyList(),
            onFire: List<FireEffect> = emptyList(),
        ) = WeaponSpec(
            id = id,
            name = name,
            cls = WeaponClass.Melee,
            tier = tier,
            damage = 0.0,
            cooldown = cooldown,
            rangePx = reachMetres * METRE_PX,
            projectileSpeed = 0.0,
            projectileCount = projectiles,
            knockback = knockback,
            onHit = onHit,
            onFire = onFire,
            pattern = FirePattern.ArcSwing(arcDegrees, lingerSeconds = 0.1),
        )

        val expectedMeleeControls = listOf(
            melee(WeaponId.BrokenBottle, "Broken Bottle", Tier.Street, 2.0, 2.2, 70.0),
            melee(
                WeaponId.RustlineMachete, "Rustline Machete", Tier.Street, 1.4, 2.3, 80.0,
                onHit = listOf(HitEffect.Bleed(perSecond = 2.0, seconds = 3.0)),
            ),
            melee(
                WeaponId.CorpoRiotBaton, "Corpo Riot Baton", Tier.Scav, 1.1, 2.2, 90.0,
                knockback = 320.0,
                onHit = listOf(HitEffect.Stun(chance = 1.0, seconds = 0.3)),
            ),
            melee(
                WeaponId.ChromeFang, "Chrome Fang", Tier.Scav, 1.2, 2.0, 35.0,
                projectiles = 2,
            ),
            melee(
                WeaponId.StaticLash, "Static Lash", Tier.Chromed, 0.9, 4.0, 60.0,
                onHit = listOf(HitEffect.Shock(extraTargets = 1)),
            ),
            melee(
                WeaponId.GutterjackCleaver, "Gutterjack Cleaver", Tier.Chromed, 1.3, 2.4, 75.0,
                onHit = listOf(HitEffect.Execute(healthFraction = 0.15)),
            ),
            melee(
                WeaponId.KillSwitchKatana, "Kill-Switch Katana", Tier.Blacksite, 0.65, 2.8, 50.0,
                onFire = listOf(
                    FireEffect.DashStrike(reachPx = 3.0 * METRE_PX, invulnerableSeconds = 0.2),
                ),
            ),
            melee(
                WeaponId.ChromewreckMaul, "Chromewreck Maul", Tier.Blacksite, 1.6, 3.6, 100.0,
                knockback = 640.0,
            ),
            WeaponSpec(
                id = WeaponId.MeatgrinderHalo,
                name = "Meatgrinder Halo",
                cls = WeaponClass.Melee,
                tier = Tier.Ascended,
                damage = 0.0,
                cooldown = 0.35,
                rangePx = 2.8 * METRE_PX,
                projectileSpeed = 0.0,
                pattern = FirePattern.Orbit(radius = 2.8 * METRE_PX, revolutionsPerMinute = 220.0),
            ),
        )
        val expectedNonMeleeDamage = mapOf(
            WeaponId.ScraplineZipPistol to 7.0,
            WeaponId.TenementNailgun to 4.0,
            WeaponId.GanglordSmg to 4.0,
            WeaponId.RiotbreakerShotgun to 6.0,
            WeaponId.VultureRailCarbine to 28.0,
            WeaponId.AshfallGrenadeLobber to 33.0,
            WeaponId.SableCorpRailgun to 95.0,
            WeaponId.DebtCollectorMinigun to 7.0,
            WeaponId.KesslerOrbitalUplink to 120.0,
            WeaponId.NeuralSpike to 10.0,
            WeaponId.MigraineLoop to 13.0,
            WeaponId.WetwareScreamer to 9.0,
            WeaponId.GhostwireTether to 18.0,
            WeaponId.BlackboxChorus to 36.0,
            WeaponId.SynapseHemorrhage to 44.0,
            WeaponId.NullEgoSingularity to 55.0,
            WeaponId.VoiceOfTheDeadNet to 95.0,
        )

        assertEquals(
            expectedMeleeControls,
            Weapons.all.filter { it.cls == WeaponClass.Melee }.map { it.copy(damage = 0.0) },
        )
        assertEquals(
            expectedNonMeleeDamage,
            Weapons.all.filter { it.cls != WeaponClass.Melee }.associate { it.id to it.damage },
        )
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
        const val METRE_PX = 16.0

        /** An enemy swing reaches 1.5 tiles. */
        const val ENEMY_SWING_PX = 24.0
    }
}
