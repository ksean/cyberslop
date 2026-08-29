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
    /** Interpolation plus uncapped wall time; presentation timers decide which wall time is active. */
    private val render: (alpha: Double, frameDeltaSeconds: Double) -> Unit,
    private val isPaused: () -> Boolean = { false },
) {
    private var lastMillis = 0.0
    private val frames = FixedStepFrames()
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
        val rawDelta = (nowMillis - lastMillis).coerceAtLeast(0.0)
        lastMillis = nowMillis
        val alpha = frames.advance(rawDelta, isPaused, step)
        render(alpha, rawDelta / 1000.0)
        schedule()
    }
}

/** Pure fixed-step accumulator, split out so pause opening inside a catch-up frame is testable. */
internal class FixedStepFrames {
    private var accumulator = 0.0

    fun advance(deltaMillis: Double, isPaused: () -> Boolean, step: () -> Unit): Double {
        if (!isPaused()) {
            accumulator += deltaMillis.coerceIn(0.0, MAX_FRAME_MILLIS)
            var ticks = 0
            while (accumulator >= STEP_MILLIS && ticks < MAX_CATCH_UP_TICKS) {
                step()
                accumulator -= STEP_MILLIS
                ticks++
                // A pickup can open a discovery pause inside step. Discard older catch-up time so
                // no second simulation tick slips through before the loop checks pause next frame.
                if (isPaused()) {
                    accumulator = 0.0
                    break
                }
            }
            if (ticks == MAX_CATCH_UP_TICKS) accumulator = 0.0
        }
        return accumulator / STEP_MILLIS
    }

    private companion object {
        const val STEP_MILLIS = TICK_SECONDS * 1000.0
        const val MAX_FRAME_MILLIS = 250.0
        const val MAX_CATCH_UP_TICKS = 8
    }
}
