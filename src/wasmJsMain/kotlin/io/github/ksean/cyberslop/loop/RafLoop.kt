package io.github.ksean.cyberslop.loop

import io.github.ksean.cyberslop.physics.TICK_SECONDS
import kotlinx.browser.window

/**
 * A fixed-timestep loop.
 *
 * Simulation advances in whole ticks of [TICK_SECONDS] so that behaviour does not depend on the
 * display's refresh rate, and so a recorded input tape means the same thing here as it does in a
 * test. Rendering interpolates between the last two states using whatever remains in the
 * accumulator, which is what keeps motion smooth on a 144 Hz display without running the simulation
 * faster.
 */
class RafLoop(
    private val step: () -> Unit,
    private val render: (alpha: Double) -> Unit,
    private val isPaused: () -> Boolean = { false },
) {
    private var lastMillis = 0.0
    private var accumulator = 0.0
    private var handle = 0
    private var running = false

    fun start() {
        if (running) return
        running = true
        lastMillis = window.performance.now()
        schedule()
    }

    fun stop() {
        running = false
        window.cancelAnimationFrame(handle)
    }

    private fun schedule() {
        handle = window.requestAnimationFrame { now -> frame(now) }
    }

    private fun frame(nowMillis: Double) {
        if (!running) return

        // Clamped so that a backgrounded tab does not return and run thousands of catch-up ticks,
        // which would advance hazards and enemies while the player had no input.
        val delta = (nowMillis - lastMillis).coerceIn(0.0, MAX_FRAME_MILLIS)
        lastMillis = nowMillis

        if (!isPaused()) {
            accumulator += delta
            var ticks = 0
            while (accumulator >= STEP_MILLIS && ticks < MAX_CATCH_UP_TICKS) {
                step()
                accumulator -= STEP_MILLIS
                ticks++
            }
            if (ticks == MAX_CATCH_UP_TICKS) accumulator = 0.0
        }

        render(accumulator / STEP_MILLIS)
        schedule()
    }

    private companion object {
        const val STEP_MILLIS = TICK_SECONDS * 1000.0
        const val MAX_FRAME_MILLIS = 250.0
        const val MAX_CATCH_UP_TICKS = 8
    }
}
