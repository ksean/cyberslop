package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Arena
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A living enemy's body hurts to touch: the contact drain of `specs/enemies.md` (PROD-069, P-41). */
class ContactDrainTest {
    private fun perTick(mapIndex: Int = 1) = EnemyAttacks.CONTACT_DRAIN * Balance.contactDamage(mapIndex) * TICK_SECONDS

    /** A stunned Swarm standing in the player: it cannot swing, so only its body can hurt. */
    private fun overlappingSwarm(sim: GameSimulation): LiveEnemy {
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN)
        swarm.stun(seconds = 30.0)
        return swarm
    }

    private fun overlaps(sim: GameSimulation, enemy: LiveEnemy): Boolean {
        val width = Physics.Default.width
        val height = sim.player.height(Physics.Default)
        return enemy.position.x < sim.player.x + width && enemy.position.x + GameSimulation.ENEMY_SIZE > sim.player.x &&
            enemy.position.y < sim.player.y + height && enemy.position.y + GameSimulation.ENEMY_SIZE > sim.player.y
    }

    @Test
    fun `overlapping a living enemy drains the contact rate per tick`() {
        val sim = TestLevels.simulation()
        val swarm = overlappingSwarm(sim)

        repeat(60) { sim.tick(InputFrame()) }

        assertTrue(overlaps(sim, swarm), "fixture: the swarm is not standing in the player")
        assertEquals(sim.run.maxHealth - 60 * perTick(), sim.run.health, 1e-6, "contact did not drain at its rate")
    }

    @Test
    fun `one tick of contact does not kill`() {
        val sim = TestLevels.simulation()
        overlappingSwarm(sim)

        sim.tick(InputFrame())

        assertTrue(sim.run.health > sim.run.maxHealth * 0.9, "one tick of contact took ${sim.run.maxHealth - sim.run.health}")
    }

    @Test
    fun `two overlapping enemies drain both`() {
        val sim = TestLevels.simulation()
        overlappingSwarm(sim)
        val brute = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = TestLevels.SPAWN_COLUMN)
        brute.stun(seconds = 30.0)

        repeat(60) { sim.tick(InputFrame()) }

        assertEquals(sim.run.maxHealth - 120 * perTick(), sim.run.health, 1e-6, "two bodies did not drain twice")
    }

    @Test
    fun `a dead enemy and an enemy one pixel clear drain nothing`() {
        val sim = TestLevels.simulation()
        val dead = overlappingSwarm(sim)
        dead.health = 0.0
        val clear = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = TestLevels.SPAWN_COLUMN)
        clear.stun(seconds = 30.0)
        sim.tick(InputFrame())
        clear.position = Vec2(sim.player.x + Physics.Default.width + 1.0, clear.position.y)
        val before = sim.run.health

        repeat(30) {
            sim.tick(InputFrame())
            clear.position = Vec2(sim.player.x + Physics.Default.width + 1.0, clear.position.y)
        }

        assertTrue(!overlaps(sim, clear), "fixture: the clear enemy overlaps")
        assertEquals(before, sim.run.health, 1e-9, "a dead or a clear enemy drained")
    }

    @Test
    fun `contact never displaces the player`() {
        val sim = TestLevels.simulation()
        overlappingSwarm(sim)
        repeat(5) { sim.tick(InputFrame()) }
        val x = sim.player.x
        val y = sim.player.y

        repeat(60) { sim.tick(InputFrame()) }

        assertEquals(x, sim.player.x, "contact moved the player in x")
        assertEquals(y, sim.player.y, "contact moved the player in y")
        assertTrue(sim.run.health < sim.run.maxHealth, "fixture: nothing drained")
    }

    @Test
    fun `no contact drains over a committed column or on the boss's ground`() {
        val committed = TestLevels.simulation(
            TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN),
        )
        overlappingSwarm(committed)
        repeat(60) { committed.tick(InputFrame()) }
        assertEquals(committed.run.maxHealth, committed.run.health, "contact drained over a committed column")

        val ground = TestLevels.simulation(TestLevels.flat(bossArena = Arena(2, 12, TestLevels.FLOOR_ROW + 1)))
        ground.boss.fight.engage()
        ground.boss.fight.damage(ground.boss.spec.maxHealth)
        overlappingSwarm(ground)
        repeat(60) { ground.tick(InputFrame()) }
        assertEquals(ground.run.maxHealth, ground.run.health, "contact drained on the boss's ground")
    }

    @Test
    fun `neither boss body drains anything`() {
        listOf<(GameSimulation) -> LiveBoss>({ it.boss }, { it.miniboss }).forEach { pick ->
            val sim = TestLevels.simulation()
            val live = pick(sim)
            live.fight.engage()
            // Inside the rest before its first attack, standing in the player: only a body could hurt.
            repeat(20) {
                live.placeAt(Vec2(sim.player.x + 6.0, sim.player.y + sim.player.height(Physics.Default)))
                sim.tick(InputFrame())
            }
            assertEquals(sim.run.maxHealth, sim.run.health, "${live.spec.name}'s body drained on contact")
        }
    }

    @Test
    fun `no contact drains inside the landing grace after a committed column`() {
        val level = TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN)
        val sim = TestLevels.simulation(level)
        val swarm = overlappingSwarm(sim)
        fun follow() { swarm.position = Vec2(sim.player.x, swarm.position.y) }
        fun overCommitted() = sim.level.isCommitted(io.github.ksean.cyberslop.world.TileMap.toTile(sim.player.x))

        // Walk right, the swarm riding in the player's box, until the column is behind them.
        var walked = 0
        while (overCommitted() && walked < 120) { follow(); sim.tick(InputFrame(right = true)); walked++ }
        assertTrue(!overCommitted(), "fixture: never left the committed column")
        assertEquals(sim.run.maxHealth, sim.run.health, "contact drained over the committed column")

        repeat(2) { follow(); sim.tick(InputFrame()) }
        assertEquals(sim.run.maxHealth, sim.run.health, "contact drained inside the landing grace")

        repeat((GameSimulation.LANDING_GRACE / TICK_SECONDS).toInt() + 2) { follow(); sim.tick(InputFrame()) }
        assertTrue(sim.run.health < sim.run.maxHealth, "contact never drained after the grace")
    }
}
