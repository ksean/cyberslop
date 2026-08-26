package io.github.ksean.cyberslop.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the verification target itself. Work placed here must run only on the JVM, because the
 * browser test runner caps a single test at 2000 ms and the map-generation seed sweeps do not fit
 * inside that (ENG-031). A test in `commonTest` would additionally execute in the browser.
 */
class SweepTargetTest {
    @Test
    fun `a long deterministic sweep completes on the verification target`() {
        val rng = Rng.derive(seed = 0xC0FFEEuL, mapIndex = 1, phase = "spine")
        var accumulator = 0uL

        repeat(SWEEP_DRAWS) { accumulator = accumulator xor rng.nextULong() }

        assertTrue(accumulator != 0uL)
    }

    private companion object {
        // Comfortably beyond what the browser runner's per-test timeout allows.
        const val SWEEP_DRAWS = 20_000_000
    }
}
