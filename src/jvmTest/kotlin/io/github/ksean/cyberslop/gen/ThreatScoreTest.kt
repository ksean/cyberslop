package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.entity.EnemySpawn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-39/P-80: generated threat rises by map and accounts for in-reach melee cadence. */
class ThreatScoreTest {
    @Test
    fun `melee pressure uses accelerated in-reach timings while ranged pressure does not`() {
        listOf(EnemyArchetype.Swarm, EnemyArchetype.Flyer, EnemyArchetype.Brute).forEach { archetype ->
            val swing = EnemyAttacks.swing(archetype)
            val expected = swing.damageShare / ((swing.windUpSeconds + swing.cooldownSeconds) / 2.0)
            assertEquals(expected, ThreatScore.pressureOf(spawn(archetype)), 1e-12, "$archetype pressure")
        }

        listOf(EnemyArchetype.Shooter, EnemyArchetype.Turret).forEach { archetype ->
            val shot = EnemyAttacks.SHOT
            val expected = shot.damageShare / (shot.windUpSeconds + shot.cooldownSeconds)
            assertEquals(expected, ThreatScore.pressureOf(spawn(archetype)), 1e-12, "$archetype pressure")
        }
    }

    @Test
    fun `the cohort mean of the threat score rises strictly from each map to the next`() {
        val means = (1..10).map { mapIndex ->
            (1uL..COHORT).sumOf { seed -> ThreatScore.of(LevelGenerator.generate(seed * SPREAD, mapIndex).level) } / COHORT.toDouble()
        }
        means.zipWithNext().forEachIndexed { index, (earlier, later) ->
            assertTrue(later > earlier, "map ${index + 2} scores $later against map ${index + 1}'s $earlier: $means")
        }
    }

    private companion object {
        const val COHORT = 24uL
        const val SPREAD = 0x9E3779B97F4A7C15uL

        fun spawn(archetype: EnemyArchetype) = EnemySpawn(archetype, column = 1, row = 1, patrolTiles = 1)
    }
}
