package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.LiveEnemy
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EnemyStatusSceneTest {
    @Test
    fun `burn and bleed indicators draw on every enemy form and can coexist`() {
        listOf(EnemyArchetype.Swarm, EnemyArchetype.Flyer, EnemyArchetype.Turret).forEach { archetype ->
            val sim = simulation()
            val enemy = enemyAt(sim, archetype)
            enemy.burn.apply(seconds = 2.0, rate = 3.0)
            enemy.bleed.apply(seconds = 2.0, rate = 3.0)

            val statuses = statusBatches(frame(sim, 0.0))

            assertTrue(statuses.any { it.style == Scene.BURN_OUTER }, "$archetype has no burn flames")
            assertTrue(statuses.any { it.style == Scene.BLEED }, "$archetype has no bleed drops")
            assertTrue(statuses.all { it.layer == Layer.ActorStatus })
        }
    }

    @Test
    fun `status motion is periodic in simulation presentation time`() {
        val sim = simulation()
        val enemy = enemyAt(sim, EnemyArchetype.Brute)
        enemy.burn.apply(seconds = 2.0, rate = 3.0)
        val burnStart = signature(frame(sim, 0.0), setOf(Scene.BURN_OUTER, Scene.BURN_CORE))
        val burnMoved = signature(frame(sim, 0.2), setOf(Scene.BURN_OUTER, Scene.BURN_CORE))
        val burnLooped = signature(frame(sim, Scene.BURN_PERIOD), setOf(Scene.BURN_OUTER, Scene.BURN_CORE))
        assertNotEquals(burnStart, burnMoved)
        assertEquals(burnStart, burnLooped)

        enemy.burn.secondsLeft = 0.0
        enemy.bleed.apply(seconds = 2.0, rate = 3.0)
        val bleedStart = signature(frame(sim, 0.0), setOf(Scene.BLEED))
        val bleedMoved = signature(frame(sim, 0.2), setOf(Scene.BLEED))
        val bleedLooped = signature(frame(sim, Scene.BLEED_PERIOD), setOf(Scene.BLEED))
        assertNotEquals(bleedStart, bleedMoved)
        assertEquals(bleedStart, bleedLooped)
    }

    @Test
    fun `expired status and dead enemies draw no indicators`() {
        val sim = simulation()
        val enemy = enemyAt(sim, EnemyArchetype.Shooter)
        assertTrue(statusBatches(frame(sim, 0.0)).isEmpty())

        enemy.burn.apply(seconds = 1.0, rate = 1.0)
        enemy.bleed.apply(seconds = 1.0, rate = 1.0)
        enemy.burn.secondsLeft = 0.0
        enemy.bleed.secondsLeft = 0.0
        assertTrue(statusBatches(frame(sim, 0.0)).isEmpty())

        enemy.burn.apply(seconds = 1.0, rate = 1.0)
        enemy.health = 0.0
        assertTrue(statusBatches(frame(sim, 0.0)).isEmpty())
    }

    private fun signature(frame: DrawList, styles: Set<String>): List<List<Double>> =
        statusBatches(frame).filter { it.style in styles }.flatMap { batch ->
            (0 until batch.size).map { primitive ->
                (0 until batch.primitive.stride).map { offset ->
                    batch[primitive * batch.primitive.stride + offset]
                }
            }
        }

    private fun statusBatches(frame: DrawList): List<DrawBatch> =
        frame.batches.filter { it.layer == Layer.ActorStatus }

    private fun frame(sim: GameSimulation, time: Double): DrawList = Scene.compose(
        sim,
        Camera(0.0, 0.0, 560.0, 320.0),
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim),
        time,
        SceneBuilder(),
    )

    private fun simulation(): GameSimulation =
        GameSimulation(TestLevels.flat(), RunState.begin(SEED), SEED).also { it.enemies.clear() }

    private fun enemyAt(sim: GameSimulation, archetype: EnemyArchetype): LiveEnemy {
        val x = sim.player.x + 40.0
        return LiveEnemy(
            archetype,
            Vec2(x, sim.player.y),
            archetype.healthOn(sim.level.mapIndex),
            x,
            0.0,
        ).also(sim.enemies::add)
    }

    private companion object {
        val SEED = 0x57A7uL
    }
}
