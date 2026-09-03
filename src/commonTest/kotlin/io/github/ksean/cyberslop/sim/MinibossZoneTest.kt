package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.gen.Populator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-91: engaged rank-and-file movement and damage are allowed on mini-boss ground only. */
class MinibossZoneTest {
    @Test
    fun `every engaged rank-and-file archetype can enter the mini-boss approach`() {
        EnemyArchetype.entries.forEach { archetype ->
            val sim = miniZoneSimulation(spawnColumn = MINI_APPROACH_LEFT + 15)
            val enemy = TestLevels.enemyAt(
                sim,
                archetype,
                column = MINI_APPROACH_LEFT - 2,
                row = if (archetype == EnemyArchetype.Flyer) TestLevels.FLOOR_ROW - 3 else TestLevels.FLOOR_ROW,
            )
            enemy.engaged = true

            var entered = false
            repeat(180) {
                if (!entered) {
                    sim.tick(InputFrame())
                    val leading = TileMap.toTile(enemy.position.x + LiveEnemy.BODY_SIZE - EDGE)
                    entered = leading >= MINI_APPROACH_LEFT
                }
            }

            assertTrue(entered, "$archetype was stopped at the mini-boss approach")
        }
    }

    @Test
    fun `walkers and flyers can leave mini-boss ground`() {
        listOf(EnemyArchetype.Swarm, EnemyArchetype.Flyer).forEach { archetype ->
            val sim = miniZoneSimulation(spawnColumn = MINI_APPROACH_LEFT - 5)
            val enemy = TestLevels.enemyAt(
                sim,
                archetype,
                column = MINI_APPROACH_LEFT + 1,
                row = if (archetype == EnemyArchetype.Flyer) TestLevels.FLOOR_ROW - 3 else TestLevels.FLOOR_ROW,
            )
            enemy.engaged = true

            repeat(180) { sim.tick(InputFrame()) }

            val trailing = TileMap.toTile(enemy.position.x + EDGE)
            assertTrue(trailing < MINI_APPROACH_LEFT, "$archetype was trapped on mini-boss ground")
        }
    }

    @Test
    fun `rank-and-file contact projectiles and swings damage the player on mini-boss ground`() {
        val contact = miniZoneSimulation(spawnColumn = MINI_ARENA.centreTile)
        TestLevels.enemyAt(contact, EnemyArchetype.Swarm, column = MINI_ARENA.centreTile).stun(5.0)
        contact.tick(InputFrame())
        val contactDamage = EnemyAttacks.CONTACT_DRAIN * Balance.contactDamage(1) * TICK_SECONDS
        assertEquals(contact.run.maxHealth - contactDamage, contact.run.health, 1e-9, "contact was suppressed")

        val projectile = miniZoneSimulation(spawnColumn = MINI_ARENA.centreTile)
        projectile.projectiles += LiveProjectile(
            position = projectile.player.centre(Physics.Default),
            velocity = Vec2.Zero,
            damage = PROJECTILE_DAMAGE,
            pierceLeft = 0,
            secondsLeft = 1.0,
            passesTerrain = false,
            fromPlayer = false,
        )
        projectile.tick(InputFrame())
        assertEquals(projectile.run.maxHealth - PROJECTILE_DAMAGE, projectile.run.health, 1e-9, "a shot was suppressed")

        val swing = miniZoneSimulation(spawnColumn = MINI_ARENA.centreTile)
        val swarm = TestLevels.enemyAt(swing, EnemyArchetype.Swarm, column = MINI_ARENA.centreTile + 1)
        repeat(60) { if (swarm.lastSwing == null) swing.tick(InputFrame()) }
        assertTrue(swarm.lastSwing != null, "fixture: the normal enemy never swung")
        val swingDamage = Balance.contactDamage(1) * EnemyAttacks.swing(EnemyArchetype.Swarm).damageShare
        assertEquals(swing.run.maxHealth - swingDamage, swing.run.health, 1e-9, "a swing was suppressed")
    }

    @Test
    fun `main-boss ground still blocks rank-and-file movement and damage`() {
        val mainArena = Arena(40, 56, TestLevels.FLOOR_ROW + 1)
        val boundary = mainArena.leftTile - Populator.ARENA_APPROACH_TILES
        val movement = TestLevels.simulation(
            TestLevels.flat(
                minibossArena = MINI_ARENA,
                bossArena = mainArena,
                spawnColumn = boundary + 10,
            ),
        )
        defeatMiniboss(movement)
        val swarm = TestLevels.enemyAt(movement, EnemyArchetype.Swarm, column = boundary - 2)
        swarm.engaged = true
        repeat(180) { movement.tick(InputFrame()) }
        val leading = TileMap.toTile(swarm.position.x + LiveEnemy.BODY_SIZE - EDGE)
        assertTrue(leading < boundary, "a normal enemy entered main-boss protected ground")

        val damage = TestLevels.simulation(
            TestLevels.flat(
                minibossArena = MINI_ARENA,
                bossArena = Arena(2, 12, TestLevels.FLOOR_ROW + 1),
            ),
        )
        TestLevels.enemyAt(damage, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN).stun(5.0)
        damage.tick(InputFrame())
        assertEquals(damage.run.maxHealth, damage.run.health, "rank-and-file contact landed on main-boss ground")
    }

    private fun miniZoneSimulation(spawnColumn: Int): GameSimulation =
        TestLevels.simulation(
            TestLevels.flat(
                minibossArena = MINI_ARENA,
                bossArena = MAIN_ARENA,
                spawnColumn = spawnColumn,
            ),
        ).also(::defeatMiniboss)

    private companion object {
        const val EDGE = 0.001
        const val PROJECTILE_DAMAGE = 7.0
        val MINI_ARENA = Arena(40, 52, TestLevels.FLOOR_ROW + 1)
        val MAIN_ARENA = Arena(90, 105, TestLevels.FLOOR_ROW + 1)
        val MINI_APPROACH_LEFT = MINI_ARENA.leftTile - Populator.ARENA_APPROACH_TILES

        fun defeatMiniboss(sim: GameSimulation) {
            sim.miniboss.fight.engage()
            sim.miniboss.fight.damage(sim.miniboss.spec.maxHealth)
        }
    }
}
