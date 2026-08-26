package io.github.ksean.cyberslop.gen

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The jet-heavy themes must actually contain jets.
 *
 * An earlier crossing estimate demanded a longer off-window than maps 8-10 ever offer, so every jet
 * proposal on exactly those maps became a plain stretch of ground — the themes built around jets had
 * none, and the difficulty curve was not monotone. A count is the only thing that catches that:
 * every other test passed while it was true.
 */
class JetCoverageTest {
    @Test
    fun `jet-bearing themes generate jets, including the hardest maps`() {
        val counts = (1..10).associateWith { mapIndex ->
            (0 until SEEDS).sumOf { i ->
                LevelGenerator.generate(BASE + i.toULong() * STRIDE, mapIndex).level.jets.size
            }
        }

        listOf(4, 8, 9, 10).forEach { mapIndex ->
            assertTrue(
                counts.getValue(mapIndex) > 0,
                "map $mapIndex generated no jets across $SEEDS seeds; counts=$counts",
            )
        }
    }

    private companion object {
        const val SEEDS = 12
        val BASE = 0x7E7uL
        val STRIDE = 0x9E3779B97F4A7C15uL
    }
}
