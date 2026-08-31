package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.PickupSite
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeathDropPickupTest {
    @Test
    fun `a generated static pickup still resolves from grounded contact`() {
        val site = PickupSite(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW)
        val level = TestLevels.flat(mapIndex = MAP_INDEX, pickups = listOf(site))
        val sim = GameSimulation(level, RunState.begin(1uL).copy(mapIndex = MAP_INDEX), 1uL)
        val item = sim.items.single()
        assertEquals(site.centre, item.position)

        sim.tick(InputFrame())

        assertTrue(sim.player.onGround)
        assertFalse(item in sim.items)
    }

    @Test
    fun `the starter cache still resolves while running on flat ground`() {
        val sim = GameSimulation(TestLevels.flat(), RunState.begin(1uL), 1uL)
        val starter = sim.items.single { it.guaranteed }

        repeat(MAX_ALIGN_TICKS) {
            if (starter in sim.items) sim.tick(InputFrame(right = true))
            assertTrue(sim.player.onGround, "the starter-cache approach required a jump")
        }

        assertFalse(starter in sim.items, "running over the starter cache did not collect it")
    }

    @Test
    fun `running under a rank-and-file weapon does not collect it but jumping does`() {
        val (sim, item) = simulationWithDrop { it.weapon != null }
        val weapon = item.weapon ?: error("fixture: no weapon")

        approachOnGroundThenJump(sim, item)

        assertEquals(weapon.id, sim.run.loadout.weapon.id)
    }

    @Test
    fun `running under a rank-and-file powerup does not collect it but jumping does`() {
        val (sim, item) = simulationWithDrop { it.powerup != null }
        val powerup = item.powerup ?: error("fixture: no powerup")

        approachOnGroundThenJump(sim, item)

        assertEquals(1, sim.run.loadout.slots.stacksOf(powerup.id))
    }

    private fun approachOnGroundThenJump(sim: GameSimulation, item: GroundItem) {
        repeat(MAX_ALIGN_TICKS) {
            val centreX = sim.player.x + Physics.Default.width / 2.0
            val delta = item.position.x - centreX
            val braking = sim.player.vx * sim.player.vx / (2.0 * Physics.Default.groundFriction)
            val press = abs(delta) > braking + ALIGN_TOLERANCE
            sim.tick(
                InputFrame(
                    left = press && delta < 0.0,
                    right = press && delta > 0.0,
                ),
            )
            if (abs(delta) <= ALIGN_TOLERANCE && abs(sim.player.vx) < 0.001) return@repeat
        }

        assertTrue(sim.player.onGround, "fixture: the approach left the ground")
        assertTrue(item in sim.items, "the item was collected by a grounded approach")

        var wasAirborne = false
        repeat(MAX_JUMP_TICKS) { tick ->
            sim.tick(InputFrame(jump = true, jumpStart = tick == 0))
            if (!sim.player.onGround) wasAirborne = true
            if (item !in sim.items) return@repeat
        }

        assertTrue(wasAirborne, "fixture: the player never jumped")
        assertFalse(item in sim.items, "the jump did not collect the item")
    }

    private fun simulationWithDrop(matches: (GroundItem) -> Boolean): Pair<GameSimulation, GroundItem> {
        for (seed in 1uL..MAX_SEED) {
            val level = TestLevels.flat(mapIndex = MAP_INDEX)
            val run = RunState.begin(seed).copy(mapIndex = MAP_INDEX)
            val sim = GameSimulation(level, run, seed)
            val enemy = TestLevels.enemyAt(
                sim,
                EnemyArchetype.Swarm,
                column = DROP_COLUMN,
                health = 0.01,
            )
            enemy.burn.apply(seconds = 1.0, rate = 1.0)

            sim.tick(InputFrame())

            sim.items.singleOrNull()?.takeIf(matches)?.let { return sim to it }
        }
        error("no matching drop in the first $MAX_SEED seeds")
    }

    private companion object {
        const val MAP_INDEX = 2
        const val DROP_COLUMN = 20
        const val MAX_SEED = 500uL
        const val MAX_ALIGN_TICKS = 240
        const val MAX_JUMP_TICKS = 240
        const val ALIGN_TOLERANCE = 1.0
    }
}
