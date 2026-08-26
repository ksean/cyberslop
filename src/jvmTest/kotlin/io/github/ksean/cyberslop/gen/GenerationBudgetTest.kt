package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.verify.WitnessReplay
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Generation runs in the shipping build (ENG-056), so its cost is something a player waits for. The
 * plan quotes a p99, and a p99 is not an average — reporting the mean of a cohort and calling it a
 * p99 would be the same overclaim this project has already made once.
 */
class GenerationBudgetTest {
    @Test
    fun `generation and verification stay inside the per-map budget`() {
        val samples = mutableListOf<Long>()

        // The widest map, which is the one the budget has to hold for.
        repeat(SAMPLES) { index ->
            val seed = BASE + index.toULong() * STRIDE
            val start = System.nanoTime()
            val generated = LevelGenerator.generate(seed, WIDEST_MAP)
            WitnessReplay.replay(generated.level, generated.witness)
            samples.add(System.nanoTime() - start)
        }

        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2] / 1_000_000.0
        val p99 = sorted[(sorted.size * 99 / 100).coerceAtMost(sorted.size - 1)] / 1_000_000.0

        assertTrue(
            p99 < BUDGET_MILLIS,
            "p99 was ${p99} ms against a ${BUDGET_MILLIS} ms budget (median ${median} ms)",
        )
    }

    private companion object {
        const val SAMPLES = 100
        const val WIDEST_MAP = 10
        const val BUDGET_MILLIS = 400.0
        val BASE = 0xB0DEuL
        val STRIDE = 0x9E3779B97F4A7C15uL
    }
}
