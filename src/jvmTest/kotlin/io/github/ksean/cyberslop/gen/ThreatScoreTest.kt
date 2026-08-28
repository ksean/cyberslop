package io.github.ksean.cyberslop.gen

import kotlin.test.Test
import kotlin.test.assertTrue

/** P-39: the population's threat, measured from what was generated, rises strictly across the maps. */
class ThreatScoreTest {
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
    }
}
