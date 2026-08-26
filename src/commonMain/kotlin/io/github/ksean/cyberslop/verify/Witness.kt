package io.github.ksean.cyberslop.verify

import io.github.ksean.cyberslop.physics.InputFrame

/**
 * One generated move's worth of input frames.
 *
 * The witness is chunked per move rather than held as one flat array so that a truncated witness is
 * genuinely truncated, and so a replay failure localises to the move that produced it.
 */
data class WitnessStep(val frames: List<InputFrame>)

/**
 * A literal input sequence that crosses a level.
 *
 * Waiting for a fire jet is expressed as real idle frames, not as a symbolic instruction resolved at
 * replay time: the level clock runs from zero and is deterministic, so the generator computes the
 * wait while it carves. That keeps the stored witness exactly the input sequence PROD-024 says the
 * generator holds, rather than a program that produces one.
 */
class Witness(val steps: List<WitnessStep>) {
    val frameCount: Int get() = steps.sumOf { it.frames.size }

    companion object {
        val Empty = Witness(emptyList())
    }
}
