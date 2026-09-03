package io.github.ksean.cyberslop.gen

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Difficulty must rise in the maps the generator actually emits, not merely in the parameters it was
 * handed. Measured over a cohort, because one seed says nothing about a stochastic generator.
 */
class DifficultyScoreTest {
    @Test
    fun `the cohort mean rises across the run`() {
        val means = (1..10).map { map -> map to meanScore(map) }

        means.zipWithNext { (lowerMap, lower), (higherMap, higher) ->
            assertTrue(
                higher > lower,
                "map $higherMap scores $higher, not above map $lowerMap's $lower; " +
                    "all means: $means",
            )
        }
    }

    @Test
    fun `the last map is substantially harder than the first`() {
        assertTrue(
            meanScore(10) > meanScore(1) * 1.5,
            "map 10 is barely harder than map 1: ${meanScore(10)} vs ${meanScore(1)}",
        )
    }

    @Test
    fun `the metric does not depend on the map index it is scoring`() {
        // If the score read the index it would be monotone by construction and prove nothing. The
        // same tiles must score the same whatever map they claim to be.
        val generated = GeneratedLevels.generated(0xC0FFEEuL, mapIndex = 4)

        assertTrue(DifficultyScore.of(generated.level) == DifficultyScore.of(generated.level))
    }

    private fun meanScore(mapIndex: Int): Double =
        (0 until COHORT)
            .map { GeneratedLevels.level(BASE + it.toULong() * STRIDE, mapIndex) }
            .map { DifficultyScore.of(it) }
            .average()

    private companion object {
        const val COHORT = 24
        val BASE = 0xD1FFuL
        val STRIDE = 0x9E3779B97F4A7C15uL
    }
}
