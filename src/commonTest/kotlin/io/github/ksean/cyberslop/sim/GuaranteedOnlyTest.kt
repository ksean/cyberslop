package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reference player of `LootFloor` takes only guaranteed awards. A harness cannot strip a drop
 * that is created and collected inside one tick, so the simulation itself has a guaranteed-only
 * mode: no static cache and no kill drop is ever created.
 */
class GuaranteedOnlyTest {
    @Test
    fun `a guaranteed-only simulation places no optional cache`() {
        val level = LevelGenerator.generate(SEED, 3).level
        assertTrue(level.pickups.isNotEmpty(), "fixture: the map placed no caches")
        val sim = GameSimulation(level, RunState.begin(SEED).copy(mapIndex = 3), SEED, optionalLoot = false)
        assertTrue(sim.items.none { !it.isGuaranteedEquipment }, "a cache was placed in guaranteed-only mode")
    }

    @Test
    fun `a guaranteed-only simulation drops nothing from a kill`() {
        val sim = GameSimulation(TestLevels.flat(), RunState.begin(SEED), SEED, optionalLoot = false)
        // Kills, many of them: at one drop in five something would appear within a few dozen.
        repeat(1800) {
            if (sim.enemies.none { e -> e.alive }) {
                TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1, health = 0.5)
            }
            sim.tick(InputFrame())
        }
        assertTrue(sim.run.scrap >= 30, "fixture: too few kills (scrap ${sim.run.scrap})")
        assertTrue(sim.items.none { !it.isGuaranteedEquipment }, "a kill dropped optional loot in guaranteed-only mode")
    }

    /** Round-3 finding: skipping the drop draws shifted the crit stream, so the mode was a different fight. */
    @Test
    fun `guaranteed-only mode does not change the combat trace`() {
        fun trace(optionalLoot: Boolean): List<Double> {
            val sim = GameSimulation(TestLevels.flat(), RunState.begin(SEED), SEED, optionalLoot = optionalLoot)
            repeat(6) { TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1 + it % 2, health = 3.0 + it) }
            repeat(600) { sim.tick(InputFrame()) }
            return sim.enemies.map { it.health } + sim.run.health + sim.grossDamageTaken
        }
        assertTrue(trace(true) == trace(false), "the guaranteed-only fight diverged from the shipping one")
    }

    private companion object {
        val SEED = 0xBEEFuL
    }
}
