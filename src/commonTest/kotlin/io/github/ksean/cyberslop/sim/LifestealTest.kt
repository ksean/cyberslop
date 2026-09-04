package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.progression.UpgradeRanks
import io.github.ksean.cyberslop.run.RunState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P-45, life steal (PROD-073): Red Market Siphon heals on every hit — a swing, a projectile
 * landing, a blast, a chain jump, splash — capped per hit and per second, never above max health,
 * and never on a damage-over-time tick.
 */
class LifestealTest {
    @Test
    fun `a projectile landing heals the fraction of its damage`() = healsOnFirstHit(WeaponId.ScraplineZipPistol)

    @Test
    fun `a melee swing heals the fraction of its damage`() = healsOnFirstHit(WeaponId.BrokenBottle, column = 5)

    @Test
    fun `a blast heals the fraction of its damage`() = healsOnFirstHit(WeaponId.MigraineLoop)

    @Test
    fun `a chain jump heals the fraction of its damage`() = healsOnFirstHit(WeaponId.GhostwireTether)

    @Test
    fun `splash heals too`() {
        // The bottle reaches the first turret only; Thermite's splash reaches the second.
        val sim = simulation(WeaponId.BrokenBottle, PowerupId.RedMarketSiphon to 1, PowerupId.ThermitePayload to 1)
        val first = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 5)
        val second = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6)
        val (dealt, healed) = firstHit(sim, listOf(first, second))
        assertTrue(second.health < second.maxHealth, "fixture: no splash reached the second turret")
        assertClose(dealt * 0.02, healed)
    }

    @Test
    fun `a hit heals at most four points`() {
        val sim = simulation(WeaponId.KesslerOrbitalUplink, PowerupId.RedMarketSiphon to 3)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6)
        val (dealt, healed) = firstHit(sim, listOf(enemy))
        assertTrue(dealt * 0.045 > GameSimulation.LIFESTEAL_CAP, "fixture: the hit is too small to cap")
        assertClose(GameSimulation.LIFESTEAL_CAP, healed)
    }

    @Test
    fun `a second of hits heals at most twelve points`() {
        // One strike over six turrets would heal 24 by the per-hit cap alone; the budget allows 12.
        val sim = simulation(WeaponId.KesslerOrbitalUplink, PowerupId.RedMarketSiphon to 3)
        repeat(6) { TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 5 + it % 3, row = TestLevels.FLOOR_ROW - it / 3) }
        val start = sim.run.health
        repeat(60) { sim.tick(InputFrame()) }
        val healed = sim.run.health - start
        assertTrue(healed > 8.0, "fixture: only $healed healed, so the cap was never reached")
        assertTrue(healed <= GameSimulation.LIFESTEAL_PER_SECOND + 1e-9, "healed $healed in one second")
    }

    @Test
    fun `a burn tick heals nothing`() {
        val sim = simulation(WeaponId.ScraplineZipPistol, PowerupId.RedMarketSiphon to 1, PowerupId.BurnRig to 1)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6)
        firstHit(sim, listOf(enemy))
        val afterHit = sim.run.health
        val enemyAfterHit = enemy.health
        repeat(20) { sim.tick(InputFrame()) }
        assertTrue(enemy.health < enemyAfterHit, "fixture: the enemy did not burn")
        assertEquals(afterHit, sim.run.health, "burn ticks healed the player")
    }

    @Test
    fun `healing never exceeds max health`() {
        val sim = simulation(WeaponId.ScraplineZipPistol, PowerupId.RedMarketSiphon to 1, damaged = 0.0)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6)
        firstHit(sim, listOf(enemy))
        assertEquals(sim.run.maxHealth, sim.run.health)
    }

    @Test
    fun `without the siphon nothing heals`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6)
        val (_, healed) = firstHit(sim, listOf(enemy))
        assertEquals(0.0, healed)
    }

    /** Round-1 finding: life is stolen from damage actually dealt, and the budget pays only for health actually gained. */
    @Test
    fun `overkill steals only the life the enemy had`() {
        val sim = simulation(WeaponId.KesslerOrbitalUplink, PowerupId.RedMarketSiphon to 3)
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6, health = 1.0)
        val start = sim.run.health
        repeat(30) { sim.tick(InputFrame()) }
        assertClose(1.0 * 0.045, sim.run.health - start)
    }

    @Test
    fun `the budget pays only for health actually gained`() {
        val sim = simulation(WeaponId.KesslerOrbitalUplink, PowerupId.RedMarketSiphon to 3, damaged = 1.0)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6)
        firstHit(sim, listOf(enemy))
        assertEquals(sim.run.maxHealth, sim.run.health)
        assertClose(GameSimulation.LIFESTEAL_PER_SECOND - 1.0, sim.lifestealBudget)
    }

    /** The budget refills at 12 HP/s, so ten seconds of ceaseless hits bank at most one second's worth extra. */
    @Test
    fun `ten seconds of hits heal at most eleven seconds of budget`() {
        // A rank-five chassis leaves room for the whole eleven seconds of budget without relying on map scaling.
        val sim = simulation(
            WeaponId.KesslerOrbitalUplink,
            PowerupId.RedMarketSiphon to 3,
            damaged = 140.0,
            mapIndex = 10,
            upgrades = UpgradeRanks(reinforcedChassis = 5),
        )
        repeat(6) { TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 5 + it % 3, row = TestLevels.FLOOR_ROW - it / 3) }
        val start = sim.run.health
        repeat(600) { sim.tick(InputFrame()) }
        val healed = sim.run.health - start
        assertTrue(healed > 100.0, "fixture: only $healed healed")
        assertTrue(healed <= GameSimulation.LIFESTEAL_PER_SECOND * 11 + 1e-9, "healed $healed in ten seconds")
    }

    /** Round-2 finding: a player killed earlier in the tick is not brought back by a hit landing later in it. */
    @Test
    fun `a hit landing after the killing blow in the same tick heals nobody`() {
        val sim = simulation(WeaponId.ScraplineZipPistol, PowerupId.RedMarketSiphon to 3, damaged = 99.5, level = TestLevels.flat())
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 12)
        val centre = sim.player.centre(Physics.Default)
        // In list order: the enemy's shot lands on the player, then the player's lands on the turret.
        sim.projectiles.add(LiveProjectile(centre - Vec2(1.0, 0.0), Vec2(60.0, 0.0), 50.0, 0, 1.0, passesTerrain = false, fromPlayer = false))
        val target = enemy.centre
        sim.projectiles.add(LiveProjectile(target - Vec2(1.0, 0.0), Vec2(60.0, 0.0), 50.0, 0, 1.0, passesTerrain = false, fromPlayer = true, weapon = sim.autoFire.weapon))
        val report = sim.tick(InputFrame())
        assertTrue(enemy.health < enemy.maxHealth, "fixture: the player's shot did not land")
        assertTrue(report.playerDied, "the player survived the tick")
        assertEquals(0.0, sim.run.health, "life steal resurrected the player")
    }

    /** Round-3 finding: bosses take damage on their own path, so they get their own cases. */
    @Test
    fun `a hit on a boss heals the fraction of the damage it took`() {
        val sim = bossSimulation(damageBossTo = null)
        val before = sim.boss.fight.health
        val (dealt, healed) = firstBossHit(sim)
        assertClose(before - sim.boss.fight.health, dealt)
        assertClose(minOf(dealt * 0.045, GameSimulation.LIFESTEAL_CAP), healed)
    }

    @Test
    fun `overkill on a boss steals only the life it had`() {
        val sim = bossSimulation(damageBossTo = 1.0)
        val (dealt, healed) = firstBossHit(sim)
        assertClose(1.0, dealt)
        assertClose(1.0 * 0.045, healed)
    }

    private fun bossSimulation(damageBossTo: Double?): GameSimulation {
        val level = TestLevels.flat(bossArena = io.github.ksean.cyberslop.world.Arena(2, 12, TestLevels.FLOOR_ROW + 1), committedColumns = 1..5)
        val sim = simulation(WeaponId.KesslerOrbitalUplink, PowerupId.RedMarketSiphon to 3, level = level)
        sim.boss.fight.engage()
        damageBossTo?.let { sim.boss.fight.damage(sim.boss.fight.health - it) }
        return sim
    }

    private fun firstBossHit(sim: GameSimulation): Pair<Double, Double> {
        repeat(300) {
            val bossBefore = sim.boss.fight.health
            val playerBefore = sim.run.health
            sim.tick(InputFrame())
            val dealt = bossBefore - sim.boss.fight.health
            if (dealt > 0.0) return dealt to sim.run.health - playerBefore
        }
        error("fixture: the boss was never hit")
    }

    private fun healsOnFirstHit(weapon: WeaponId, column: Int = 6) {
        val sim = simulation(weapon, PowerupId.RedMarketSiphon to 1)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = column)
        val (dealt, healed) = firstHit(sim, listOf(enemy))
        assertTrue(dealt > 0.0, "fixture: nothing was dealt")
        assertClose(dealt * 0.02, healed)
    }

    /** Ticks until any of [enemies] loses health; returns (damage dealt that tick, health gained that tick). */
    private fun firstHit(sim: GameSimulation, enemies: List<LiveEnemy>): Pair<Double, Double> {
        repeat(300) {
            val enemyBefore = enemies.sumOf { it.health }
            val playerBefore = sim.run.health
            sim.tick(InputFrame())
            val dealt = enemyBefore - enemies.sumOf { it.health.coerceAtLeast(0.0) }
            if (dealt > 0.0) return dealt to sim.run.health - playerBefore
        }
        error("fixture: no hit in 300 ticks")
    }

    /** The player stands over committed columns, so no enemy can touch their health (P-34). */
    private fun simulation(
        weapon: WeaponId,
        vararg build: Pair<PowerupId, Int>,
        damaged: Double = 50.0,
        mapIndex: Int = 1,
        upgrades: UpgradeRanks = UpgradeRanks(),
        level: io.github.ksean.cyberslop.world.Level = TestLevels.flat(committedColumns = 1..5, mapIndex = mapIndex),
    ): GameSimulation {
        var slots = PowerupSlots.empty()
        build.forEach { (id, stacks) -> repeat(stacks) { slots = slots.collect(id).first } }
        val initial = RunState.begin(TestLevels.SEED, upgrades).copy(mapIndex = mapIndex)
        val run = initial.copy(
            health = initial.maxHealth,
            loadout = Loadout(Weapons.of(weapon), slots),
        ).damaged(damaged)
        return GameSimulation(level, run, TestLevels.SEED)
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-6, "expected $expected, was $actual")
}
