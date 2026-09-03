package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.gen.Populator
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.FireJet
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
    fun `a turret stays folded until engagement then crawls after the player`() {
        val sim = TestLevels.simulation()
        val turret = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 23)
        val start = turret.position

        repeat(120) { sim.tick(InputFrame()) }
        assertEquals(start, turret.position, "an unengaged turret left its folded emplacement")

        turret.engaged = true
        repeat(120) { sim.tick(InputFrame()) }

        assertTrue(turret.position.x < start.x, "an engaged turret did not unfold and pursue")
    }

    @Test
    fun `an engaged walker leaps a pit and keeps pursuing`() {
        val gap = 8..11
        val sim = TestLevels.simulation(TestLevels.flat(gapColumns = gap))
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 14)
        var airborne = false

        repeat(600) {
            sim.tick(InputFrame())
            airborne = airborne || swarm.vy != 0.0
        }
        assertTrue(swarm.engaged)
        assertTrue(airborne, "the walker crossed without beginning a leap")
        assertTrue(swarm.position.x < TileMap.toWorld(gap.first), "it did not cross the pit: ${swarm.position.x}")
        assertTrue(swarm.alive, "the walker crossed the pit by falling into it")
    }

    @Test
    fun `an engaged walker leaps acid without touching it`() {
        val acid = 8..11
        val sim = TestLevels.simulation(TestLevels.flat(acidColumns = acid))
        val brute = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = 14)

        repeat(900) {
            sim.tick(InputFrame())
        }
        assertTrue(brute.position.x < TileMap.toWorld(acid.first), "the brute did not cross acid")
        assertTrue(brute.alive, "the brute touched acid instead of clearing it")
    }

    @Test
    fun `ground enemies leap spikes and barrels with a locked direction and do not attack in air`() {
        val hazards = listOf(
            TestLevels.flat(spikeColumns = 20..22),
            TestLevels.flat(barrels = listOf(Barrel(21, TestLevels.FLOOR_ROW))),
        )
        hazards.forEach { level ->
            listOf(
                EnemyArchetype.Swarm,
                EnemyArchetype.Brute,
                EnemyArchetype.Shooter,
                EnemyArchetype.Turret,
            ).forEach { archetype ->
                val sim = TestLevels.simulation(level)
                val enemy = TestLevels.enemyAt(sim, archetype, column = 30)
                enemy.engaged = true
                var started = false
                var direction = 0
                repeat(900) {
                    sim.tick(InputFrame())
                    enemy.leap?.let { leap ->
                        if (!started) { started = true; direction = leap.direction }
                        assertEquals(direction, leap.direction, "$archetype changed direction in flight")
                        assertEquals(0.0, enemy.windUpLeft, "$archetype began an attack in flight")
                    }
                }
                assertTrue(started, "$archetype never leapt over ${level.barrels.ifEmpty { listOf("spikes") }}")
                assertTrue(enemy.position.x < TileMap.toWorld(20), "$archetype did not clear the hazard")
                assertTrue(enemy.alive, "$archetype touched the hazard")
            }
        }
    }

    @Test
    fun `a walker refuses a leap when no landing fits its preview`() {
        val gap = 4..13
        val sim = TestLevels.simulation(TestLevels.flat(gapColumns = gap))
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 14)

        repeat(300) { sim.tick(InputFrame()) }

        assertTrue(swarm.alive)
        assertTrue(swarm.position.x >= TileMap.toWorld(14) - TOLERANCE, "it launched into an over-wide pit")
        assertEquals(null, swarm.leap)
    }

    @Test
    fun `a walker waits for an active fire jet then crosses during its off window`() {
        val jet = FireJet(
            column = 10,
            topRow = TestLevels.FLOOR_ROW - 5,
            bottomRow = TestLevels.FLOOR_ROW,
            periodSeconds = 2.0,
            onSeconds = 1.0,
            phaseSeconds = 0.0,
        )
        val sim = TestLevels.simulation(TestLevels.flat(jets = listOf(jet)))
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 14)

        repeat(30) { sim.tick(InputFrame()) }
        assertEquals(null, swarm.leap, "the walker entered a jet that was still active")

        repeat(300) { sim.tick(InputFrame()) }
        assertTrue(swarm.position.x < TileMap.toWorld(jet.column), "the walker never used the safe window")
        assertTrue(swarm.alive)
    }

    @Test
    fun `an engaged walker leaps a low obstacle`() {
        val sim = TestLevels.simulation(TestLevels.flat(wallColumn = 10))
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 14)

        repeat(600) { sim.tick(InputFrame()) }

        assertTrue(swarm.position.x < TileMap.toWorld(10), "it did not clear the obstacle: ${swarm.position.x}")
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
        val playerCentre = sim.player.centre(Physics.Default)
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 60)
        swarm.position = playerCentre + Vec2(GameSimulation.DISENGAGE_PX, 0.0) -
            Vec2(LiveEnemy.BODY_HALF, LiveEnemy.BODY_HALF)
        swarm.engaged = true

        sim.tick(InputFrame())
        assertTrue(swarm.engaged, "an enemy exactly at the disengage radius dropped off")

        swarm.position = playerCentre + Vec2(GameSimulation.DISENGAGE_PX + 1.0, 0.0) -
            Vec2(LiveEnemy.BODY_HALF, LiveEnemy.BODY_HALF)
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
            val leading = TileMap.toTile(swarm.position.x + LiveEnemy.BODY_SIZE - 0.001)
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
            val leading = TileMap.toTile(flyer.position.x + LiveEnemy.BODY_SIZE - 0.001)
            assertTrue(leading < boundary, "a flyer entered the arena approach: column $leading")
        }
        assertTrue(flyer.position.x > TileMap.toWorld(boundary - 3), "the flyer did not close on the approach at all")
    }

    @Test
    fun `a flyer pursues across committed columns`() {
        val gap = 8..11
        val sim = TestLevels.simulation(TestLevels.flat(gapColumns = gap))
        val flyer = TestLevels.enemyAt(sim, EnemyArchetype.Flyer, column = 16, row = TestLevels.FLOOR_ROW - 4)
        val start = flyer.position.x

        repeat(600) {
            sim.tick(InputFrame())
        }
        assertTrue(flyer.position.x < TileMap.toWorld(gap.first), "the flyer stopped at the committed span")
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
