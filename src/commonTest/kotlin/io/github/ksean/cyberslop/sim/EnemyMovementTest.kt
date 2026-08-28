package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.gen.Populator
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Awareness, pursuit, retreat, gravity and the ledge rule (P-32, P-33; `specs/enemies.md`).
 *
 * The player stands still at the spawn column throughout; enemies are placed at chosen distances so
 * that what each test measures is the enemy's own decision.
 */
class EnemyMovementTest {
    @Test
    fun `an enemy outside the awareness radius patrols within its span`() {
        val sim = TestLevels.simulation()
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 30, patrolTiles = 2)

        repeat(300) { sim.tick(InputFrame()) }

        assertFalse(enemy.engaged, "an enemy 30 tiles away noticed the player")
        assertTrue(
            kotlin.math.abs(enemy.position.x - enemy.homeX) <= enemy.patrolPx + TOLERANCE,
            "an unengaged enemy left its patrol span: ${enemy.position.x} from ${enemy.homeX}",
        )
    }

    @Test
    fun `an enemy inside the awareness radius engages and closes on the player`() {
        val sim = TestLevels.simulation()
        val start = TestLevels.SPAWN_COLUMN + 10
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = start, patrolTiles = 1)

        repeat(120) { sim.tick(InputFrame()) }

        assertTrue(enemy.engaged, "an enemy 10 tiles away did not notice the player")
        assertTrue(enemy.position.x < enemy.homeX - enemy.patrolPx - TOLERANCE, "it stayed inside its patrol span")
        assertTrue(enemy.position.x < TileMap.toWorld(start) - TileMap.toWorld(3), "it did not close on the player")
    }

    @Test
    fun `awareness has hysteresis`() {
        val sim = TestLevels.simulation()
        val justOutside = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 23)
        val farAway = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 34)
        justOutside.engaged = true
        farAway.engaged = true

        sim.tick(InputFrame())

        assertTrue(justOutside.engaged, "an engaged enemy just outside the radius forgot the player")
        assertFalse(farAway.engaged, "an enemy beyond the disengage distance stayed engaged")
    }

    @Test
    fun `a shooter approaches beyond its range, holds inside it and retreats when closed on`() {
        val sim = TestLevels.simulation()
        val far = TestLevels.enemyAt(sim, EnemyArchetype.Shooter, column = TestLevels.SPAWN_COLUMN + 18)
        val mid = TestLevels.enemyAt(sim, EnemyArchetype.Shooter, column = TestLevels.SPAWN_COLUMN + 10)
        val near = TestLevels.enemyAt(sim, EnemyArchetype.Shooter, column = TestLevels.SPAWN_COLUMN + 3)
        val farStart = far.position.x
        val midStart = mid.position.x
        val nearStart = near.position.x

        repeat(60) { sim.tick(InputFrame()) }

        assertTrue(far.position.x < farStart - TOLERANCE, "a shooter out of range did not approach")
        assertEquals(midStart, mid.position.x, TOLERANCE, "a shooter in range did not hold")
        assertTrue(near.position.x > nearStart + TOLERANCE, "a shooter that was closed on did not retreat")
    }

    @Test
    fun `a turret never moves`() {
        val sim = TestLevels.simulation()
        val turret = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 8)
        val start = turret.position

        repeat(120) { sim.tick(InputFrame()) }

        assertEquals(start, turret.position)
    }

    @Test
    fun `a walker stops at a ledge instead of stepping off`() {
        val gap = 8..11
        val sim = TestLevels.simulation(TestLevels.flat(gapColumns = gap))
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 14)

        repeat(600) {
            sim.tick(InputFrame())
            val feetColumn = TileMap.toTile(swarm.position.x + GameSimulation.ENEMY_HALF)
            assertFalse(feetColumn in gap, "a walker stood over the gap at column $feetColumn")
        }
        assertTrue(swarm.engaged)
        assertTrue(swarm.position.x <= TileMap.toWorld(13), "it never reached the ledge: ${swarm.position.x}")
    }

    @Test
    fun `a walker stops before acid`() {
        val acid = 8..11
        val sim = TestLevels.simulation(TestLevels.flat(acidColumns = acid))
        val brute = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = 14)

        repeat(900) {
            sim.tick(InputFrame())
            val feetColumn = TileMap.toTile(brute.position.x + GameSimulation.ENEMY_HALF)
            assertFalse(feetColumn in acid, "a walker stood over acid at column $feetColumn")
        }
        assertTrue(brute.alive, "a walker that stopped at acid died")
    }

    @Test
    fun `a walker facing a wall does not walk into it`() {
        val sim = TestLevels.simulation(TestLevels.flat(wallColumn = 10))
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 14)

        repeat(600) { sim.tick(InputFrame()) }

        assertTrue(swarm.position.x >= TileMap.toWorld(11) - TOLERANCE, "it walked into the wall: ${swarm.position.x}")
    }

    @Test
    fun `an unsupported walker falls and lands`() {
        val sim = TestLevels.simulation()
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 40, row = TestLevels.FLOOR_ROW - 8)

        repeat(120) { sim.tick(InputFrame()) }

        assertEquals(TileMap.toWorld(TestLevels.FLOOR_ROW), swarm.position.y, 0.01, "it did not land on the floor")
    }

    @Test
    fun `a walker that falls into acid dies`() {
        val acid = 40..41
        val sim = TestLevels.simulation(TestLevels.flat(acidColumns = acid))
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 40, row = TestLevels.FLOOR_ROW - 8)

        repeat(120) { sim.tick(InputFrame()) }

        assertFalse(swarm.alive, "a walker in acid survived")
    }

    /** Round-3 finding: engagement holds until the distance *exceeds* the radius, so equality keeps it. */
    @Test
    fun `an engaged enemy at exactly the disengage radius stays engaged`() {
        val sim = TestLevels.simulation()
        val playerCentre = Vec2(sim.player.x + 6.0, sim.player.y + 13.0)
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 60)
        swarm.position = playerCentre + Vec2(GameSimulation.DISENGAGE_PX, 0.0) - Vec2(7.0, 7.0)
        swarm.engaged = true

        sim.tick(InputFrame())
        assertTrue(swarm.engaged, "an enemy exactly at the disengage radius dropped off")

        swarm.position = playerCentre + Vec2(GameSimulation.DISENGAGE_PX + 1.0, 0.0) - Vec2(7.0, 7.0)
        sim.tick(InputFrame())
        assertFalse(swarm.engaged, "an enemy beyond the disengage radius stayed engaged")
    }

    /**
     * A boss fight is a boss fight (`specs/enemies.md`, Pursuit): an engaged enemy holds at an
     * arena's approach rather than joining the fight inside it. Walkers stop as at a ledge, Flyers
     * as at a committed column.
     */
    @Test
    fun `an engaged walker stops at the boss arena's approach`() {
        val sim = TestLevels.simulation(TestLevels.flat(bossArena = Arena(40, 56, TestLevels.FLOOR_ROW + 1)))
        val boundary = 40 - Populator.ARENA_APPROACH_TILES
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = boundary - 3)
        swarm.engaged = true
        // The player runs into the arena and stands there: the swarm wants to close and must not.
        repeat(600) { tick ->
            sim.tick(InputFrame(right = tick < 170))
            val leading = TileMap.toTile(swarm.position.x + 2 * GameSimulation.ENEMY_HALF - 0.001)
            assertTrue(leading < boundary, "a walker entered the arena approach: column $leading")
        }
        assertTrue(swarm.engaged, "fixture: the swarm disengaged")
        assertTrue(swarm.position.x > TileMap.toWorld(boundary - 3), "the swarm did not close on the approach at all")
    }

    @Test
    fun `an engaged flyer holds at the boss arena's approach`() {
        val sim = TestLevels.simulation(TestLevels.flat(bossArena = Arena(40, 56, TestLevels.FLOOR_ROW + 1)))
        val boundary = 40 - Populator.ARENA_APPROACH_TILES
        val flyer = TestLevels.enemyAt(sim, EnemyArchetype.Flyer, column = boundary - 3, row = TestLevels.FLOOR_ROW - 4)
        flyer.engaged = true
        repeat(600) { tick ->
            sim.tick(InputFrame(right = tick < 170))
            val leading = TileMap.toTile(flyer.position.x + 2 * GameSimulation.ENEMY_HALF - 0.001)
            assertTrue(leading < boundary, "a flyer entered the arena approach: column $leading")
        }
        assertTrue(flyer.position.x > TileMap.toWorld(boundary - 3), "the flyer did not close on the approach at all")
    }

    @Test
    fun `a flyer pursues but never enters a committed column`() {
        val gap = 8..11
        val sim = TestLevels.simulation(TestLevels.flat(gapColumns = gap))
        val flyer = TestLevels.enemyAt(sim, EnemyArchetype.Flyer, column = 16, row = TestLevels.FLOOR_ROW - 4)
        val start = flyer.position.x

        repeat(600) {
            sim.tick(InputFrame())
            // The whole body, not its centre: a 14 px pod could otherwise hang half a tile over the gap.
            val leading = TileMap.toTile(flyer.position.x)
            val trailing = TileMap.toTile(flyer.position.x + 2 * GameSimulation.ENEMY_HALF - 0.001)
            assertFalse(leading in gap || trailing in gap, "a flyer's body entered a committed column: $leading..$trailing")
        }
        assertTrue(flyer.position.x < start - TileMap.toWorld(2), "the flyer did not pursue")
    }

    @Test
    fun `no enemy is as fast as the player`() {
        EnemyArchetype.entries.forEach { archetype ->
            val speed = GameSimulation.ENEMY_SPEED * archetype.speedScale
            assertTrue(speed < Physics.Default.maxRunSpeed, "$archetype at $speed px/s can outrun the player")
        }
    }

    private companion object {
        const val TOLERANCE = 2.0
    }
}
