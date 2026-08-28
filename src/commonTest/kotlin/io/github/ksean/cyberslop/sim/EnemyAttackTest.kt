package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Telegraphed swings and shots, and the committed-span fairness rule (P-34; `specs/enemies.md`). */
class EnemyAttackTest {
    @Test
    fun `an enemy overlapping the player outside a strike deals nothing`() {
        val sim = TestLevels.simulation()
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN)
        swarm.stun(seconds = 5.0)

        repeat(120) { sim.tick(InputFrame()) }

        assertEquals(sim.run.maxHealth, sim.run.health, "a stunned enemy standing in the player hurt them")
    }

    @Test
    fun `a swing deals nothing during its wind-up and its damage exactly once per cooldown`() {
        val sim = TestLevels.simulation()
        TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1)
        val swing = EnemyAttacks.swing(EnemyArchetype.Swarm)
        val windUpTicks = (swing.windUpSeconds / TICK_SECONDS).roundToInt()
        val expected = Balance.contactDamage(1) * swing.damageShare

        repeat(windUpTicks - 1) { sim.tick(InputFrame()) }
        assertEquals(sim.run.maxHealth, sim.run.health, "damage landed during the wind-up")

        repeat(3) { sim.tick(InputFrame()) }
        assertEquals(sim.run.maxHealth - expected, sim.run.health, 1e-9, "the swing did not land once")

        val cooldownTicks = (swing.cooldownSeconds / TICK_SECONDS).roundToInt()
        repeat(cooldownTicks - 2) { sim.tick(InputFrame()) }
        assertEquals(sim.run.maxHealth - expected, sim.run.health, 1e-9, "a second strike landed inside the cooldown")

        repeat(windUpTicks + 4) { sim.tick(InputFrame()) }
        assertEquals(sim.run.maxHealth - 2 * expected, sim.run.health, 1e-9, "the second swing did not land")
    }

    @Test
    fun `a stun cancels a wind-up`() {
        val sim = TestLevels.simulation()
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1)

        repeat(5) { sim.tick(InputFrame()) }
        assertTrue(swarm.windingUp, "the swarm had not started a swing")
        swarm.stun(seconds = 2.0)
        repeat(60) { sim.tick(InputFrame()) }

        assertEquals(sim.run.maxHealth, sim.run.health, "a cancelled swing still landed")
    }

    @Test
    fun `a shot leaves after its wind-up at the specified speed and cadence`() {
        val sim = TestLevels.simulation()
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 8)
        val shot = EnemyAttacks.SHOT
        val windUpTicks = (shot.windUpSeconds / TICK_SECONDS).roundToInt()

        repeat(windUpTicks - 1) { sim.tick(InputFrame()) }
        assertTrue(sim.projectiles.isEmpty(), "a shot left before its wind-up ended")

        repeat(2) { sim.tick(InputFrame()) }
        assertEquals(1, sim.projectiles.size, "the shot did not leave after its wind-up")
        assertEquals(shot.speedPx, sim.projectiles.single().velocity.length, 1e-6)

        var fired = 1
        var previous = sim.projectiles.size
        repeat((5.0 / TICK_SECONDS).roundToInt()) {
            sim.tick(InputFrame())
            if (sim.projectiles.size > previous) fired++
            previous = sim.projectiles.size
        }
        val perShot = shot.windUpSeconds + shot.cooldownSeconds
        assertTrue(fired >= (5.0 / perShot).toInt(), "only $fired shots in five seconds at a $perShot s cadence")
    }

    @Test
    fun `a swing misses a player who got behind it during the wind-up`() {
        val sim = TestLevels.simulation()
        val brute = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = TestLevels.SPAWN_COLUMN + 1)
        val swing = EnemyAttacks.swing(EnemyArchetype.Brute)
        val windUpTicks = (swing.windUpSeconds / TICK_SECONDS).roundToInt()

        // The brute winds up facing left, at the player; the player runs through it and stops behind.
        repeat(10) { sim.tick(InputFrame(right = true)) }
        repeat(windUpTicks + 2 - 10) { sim.tick(InputFrame()) }

        val offset = sim.player.x + 6.0 - (brute.position.x + GameSimulation.ENEMY_HALF)
        assertTrue(offset > 0.0 && offset < swing.reachPx, "fixture: player at offset $offset is not behind and in reach")
        assertTrue(brute.lastSwing != null, "fixture: the brute never struck")
        assertEquals(sim.run.maxHealth, sim.run.health, "a swing aimed left hit a player standing to the right")
    }

    @Test
    fun `enemy damage waits out the landing grace after a committed column`() {
        val level = TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN)
        val sim = TestLevels.simulation(level)
        val damage = 5.0

        // Walk clear of the committed column, then stand still.
        repeat(12) { sim.tick(InputFrame(right = true)) }
        assertTrue(!sim.level.isCommitted(TileMap.toTile(sim.player.x)), "fixture: still over the committed column")
        repeat(4) { sim.tick(InputFrame()) }

        shootThePlayer(sim, damage)
        sim.tick(InputFrame())
        assertEquals(sim.run.maxHealth, sim.run.health, "a hit landed inside the landing grace")

        repeat((GameSimulation.LANDING_GRACE / TICK_SECONDS).roundToInt() + 2) { sim.tick(InputFrame()) }
        shootThePlayer(sim, damage)
        sim.tick(InputFrame())
        assertEquals(sim.run.maxHealth - damage, sim.run.health, 1e-9, "a hit after the grace did not land")
    }

    /** An enemy projectile that will overlap the player's centre on the next tick. */
    private fun shootThePlayer(sim: GameSimulation, damage: Double) {
        val centre = Vec2(sim.player.x + 6.0, sim.player.y + 13.0)
        sim.projectiles.add(
            LiveProjectile(
                position = centre - Vec2(4.0, 0.0),
                velocity = Vec2(4.0 / TICK_SECONDS, 0.0),
                damage = damage,
                pierceLeft = 0,
                secondsLeft = 1.0,
                passesTerrain = false,
                fromPlayer = false,
            ),
        )
    }

    @Test
    fun `no enemy damage lands while the player is over a committed column`() {
        val level = TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN)
        val sim = TestLevels.simulation(level)
        TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1)
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 6)

        repeat(240) { sim.tick(InputFrame()) }

        assertTrue(sim.level.isCommitted(TestLevels.SPAWN_COLUMN))
        assertEquals(sim.run.maxHealth, sim.run.health, "enemy damage landed on a committed span")
    }
}
