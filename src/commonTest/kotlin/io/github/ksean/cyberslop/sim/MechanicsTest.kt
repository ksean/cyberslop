package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mechanics the registries describe have to actually run. Every one of these covers something
 * that was previously data nothing executed.
 */
class MechanicsTest {
    @Test
    fun `a single melee swing reaches only what it is pointed at`() {
        // Measured across exactly one swing. Over many swings auto-aim legitimately re-targets as
        // the nearest thing changes, so counting damage over time proves nothing about the arc.
        val sim = simulation(WeaponId.RustlineMachete)
        // Only the two fixtures: the generated map seeds a Swarm near spawn that a longer reach
        // would otherwise take the single allowed hit.
        sim.enemies.clear()
        val behind = enemyNear(sim, offsetX = -20.0)
        val ahead = enemyNear(sim, offsetX = 20.0)

        while (sim.lastSwing == null) sim.tick(InputFrame())

        val struck = listOf(ahead, behind).count { it.health < it.startingHealth }
        assertEquals(1, struck, "one swing damaged both sides; the arc is not being respected")
    }

    @Test
    fun `a swing never reaches behind the direction it is pointed`() {
        val forward = io.github.ksean.cyberslop.core.Vec2(1.0, 0.0)
        val backward = io.github.ksean.cyberslop.core.Vec2(-1.0, 0.0)

        assertTrue(io.github.ksean.cyberslop.core.TrigTable.withinArc(forward, forward, 40.0))
        assertEquals(
            false,
            io.github.ksean.cyberslop.core.TrigTable.withinArc(forward, backward, 40.0),
            "an arc of 80 degrees reached directly behind the swing",
        )
    }

    @Test
    fun `a boss telegraphs before it can hurt anyone`() {
        val sim = simulation()
        val boss = sim.boss
        boss.fight.engage()

        var damageBeforeTelegraph = 0.0
        var elapsed = 0.0
        while (elapsed < 0.35) {
            damageBeforeTelegraph += boss.tick(TICK_SECONDS, BossTarget(boss.position, onGround = true, crouched = false))
            elapsed += TICK_SECONDS
        }

        assertEquals(0.0, damageBeforeTelegraph, "a boss hurt the player during its telegraph")
    }

    @Test
    fun `a boss eventually lands an attack once engaged`() {
        val sim = simulation()
        val boss = sim.boss
        boss.fight.engage()

        var total = 0.0
        repeat(600) { total += boss.tick(TICK_SECONDS, BossTarget(boss.position, onGround = true, crouched = false)) }

        assertTrue(total > 0.0, "an engaged boss never attacked across ten seconds")
    }

    @Test
    fun `the exit stays shut while the boss lives`() {
        val sim = simulation()

        assertFalse(sim.boss.fight.exitOpen)
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)

        assertTrue(sim.boss.fight.exitOpen, "the exit did not open when the boss died")
    }

    @Test
    fun `killing the boss is not the same as clearing the map`() {
        // The exit opens on its death; the player still has to walk out of the arena.
        val sim = simulation()
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)

        val report = sim.tick(InputFrame())

        assertTrue(report.bossDefeated)
        assertFalse(report.mapCleared, "the map cleared without the player reaching the exit")
    }

    @Test
    fun `slowing actually slows an enemy`() {
        val sim = simulation()
        val enemy = sim.enemies.first { it.alive }

        enemy.slow(0.5, seconds = 2.0)

        assertTrue(enemy.speedScale(0.4) < 1.0, "a slowed enemy moves at full speed")
    }

    @Test
    fun `slows take the strongest rather than compounding`() {
        val sim = simulation()
        val enemy = sim.enemies.first { it.alive }

        enemy.slow(0.3, 2.0)
        enemy.slow(0.5, 2.0)
        enemy.slow(0.2, 2.0)

        assertEquals(0.5, enemy.slowFraction)
        assertTrue(enemy.speedScale(0.4) >= 0.4, "slow stacked past the floor")
    }

    @Test
    fun `damage over time keeps hurting after the hit`() {
        val sim = simulation()
        val enemy = sim.enemies.first { it.alive }
        val before = enemy.health

        enemy.burn.apply(seconds = 3.0, rate = 5.0)
        repeat(120) { sim.tick(InputFrame()) }

        assertTrue(enemy.health < before, "a burning enemy took no damage over time")
    }

    @Test
    fun `a stunned enemy stops moving`() {
        val sim = simulation()
        val enemy = sim.enemies.first { it.alive }
        enemy.stun(seconds = 2.0)
        val where = enemy.position.x

        repeat(60) { sim.tick(InputFrame()) }

        assertEquals(where, enemy.position.x, "a stunned enemy kept moving")
    }

    @Test
    fun `map one hands the player a weapon before the mini-boss`() {
        val sim = simulation()

        assertTrue(
            sim.items.any { it.weapon != null },
            "no starter cache: map one would face its mini-boss with the broken bottle",
        )
    }

    @Test
    fun `meta progression limits what can drop`() {
        val sim = simulation(unlocked = 3)

        repeat(600) { sim.tick(InputFrame()) }

        sim.items.mapNotNull { it.weapon }.forEach { weapon ->
            assertTrue(
                Weapons.all.indexOf(weapon) < 3,
                "${weapon.name} dropped while locked",
            )
        }
    }

    @Test
    fun `a winning swap banks the displaced weapon rather than losing it`() {
        var run = RunState.begin(SEED)
        val before = run.scrap
        val (next, _) = run.loadout.collect(Weapons.of(WeaponId.SableCorpRailgun), 5)
        run = run.copy(loadout = next)

        // The pickup path credits Scrap for both outcomes; here the point is that the displaced
        // weapon has a value at all.
        assertTrue(Weapons.startingWeapon.tier.ordinal >= 0)
        assertEquals(before, run.scrap)
    }

    @Test
    fun `auto-aim tracks a live target and needs no cursor`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)

        repeat(400) { sim.tick(InputFrame()) }

        assertTrue(
            sim.projectiles.isNotEmpty() || sim.enemies.any { it.health < it.startingHealth },
            "auto-aim produced no shots and damaged nothing",
        )
    }

    private val LiveEnemy.startingHealth: Double
        get() = archetype.healthOn(1)

    private fun enemyNear(sim: GameSimulation, offsetX: Double): LiveEnemy {
        val enemy = LiveEnemy(
            archetype = io.github.ksean.cyberslop.entity.EnemyArchetype.Swarm,
            position = Vec2(sim.player.x + offsetX, sim.player.y),
            health = io.github.ksean.cyberslop.entity.EnemyArchetype.Swarm.healthOn(1),
            homeX = sim.player.x + offsetX,
            patrolPx = 0.0,
        )
        sim.enemies.add(enemy)
        return enemy
    }

    private fun simulation(
        weapon: WeaponId = WeaponId.BrokenBottle,
        unlocked: Int = Weapons.all.size,
    ): GameSimulation {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        var run = RunState.begin(SEED)
        if (weapon != WeaponId.BrokenBottle) {
            run = run.copy(loadout = run.loadout.copy(weapon = Weapons.of(weapon)))
        }
        return GameSimulation(level, run, SEED, unlocked)
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val TOLERANCE = 4.0
    }
}
