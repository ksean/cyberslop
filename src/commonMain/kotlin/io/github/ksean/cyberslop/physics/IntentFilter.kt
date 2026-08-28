package io.github.ksean.cyberslop.physics

/** Raw key state for one tick, before any assist is applied. */
data class Keys(
    val left: Boolean = false,
    val right: Boolean = false,
    val crouch: Boolean = false,
    val jump: Boolean = false,
)

/**
 * Turns raw key state into the [InputFrame] the simulation consumes, applying coyote time and jump
 * buffering.
 *
 * These assists live here, above [MovementModel], for a specific reason: a witness is a tape of
 * `InputFrame`s, and if the model's reading of a frame depended on assist state then replaying a
 * tape would not be deterministic and the completability guarantee would be undischargeable. With
 * the split, a frame means exactly one thing, and the assists only ever widen what a human can
 * express — never what a tape means (`specs/simulation.md`).
 */
class IntentFilter {
    private var ticksSinceGrounded = COYOTE_TICKS + 1
    private var ticksSinceJumpPressed = BUFFER_TICKS + 1
    private var jumpHeld = false
    private var jumpInProgress = false

    /**
     * [standingBlocked] is whether a ceiling is holding the player in a crouch. The movement model
     * ignores a `jumpStart` while crouched, so without knowing this the filter would emit the start,
     * spend the buffer, and the press would be lost at the boundary between the two (P-49).
     */
    fun next(keys: Keys, grounded: Boolean, standingBlocked: Boolean = false): InputFrame {
        ticksSinceGrounded = if (grounded) 0 else ticksSinceGrounded + 1
        val crouched = keys.crouch || standingBlocked
        // A jump held while the player cannot stand stays pending rather than expiring: releasing
        // Down, or clearing the ceiling, is when it fires.
        ticksSinceJumpPressed =
            if (keys.jump && (!jumpHeld || crouched)) 0 else ticksSinceJumpPressed + 1
        jumpHeld = keys.jump

        if (grounded) jumpInProgress = false

        val mayJump = ticksSinceGrounded <= COYOTE_TICKS
        val wantsJump = ticksSinceJumpPressed <= BUFFER_TICKS
        val start = mayJump && wantsJump && !jumpInProgress && !crouched

        if (start) {
            jumpInProgress = true
            ticksSinceJumpPressed = BUFFER_TICKS + 1
            ticksSinceGrounded = COYOTE_TICKS + 1
        }

        return InputFrame(
            left = keys.left,
            right = keys.right,
            crouch = keys.crouch,
            jump = keys.jump,
            jumpStart = start,
        )
    }

    private companion object {
        const val COYOTE_SECONDS = 0.10
        const val BUFFER_SECONDS = 0.12
        val COYOTE_TICKS = (COYOTE_SECONDS / TICK_SECONDS).toInt()
        val BUFFER_TICKS = (BUFFER_SECONDS / TICK_SECONDS).toInt()
    }
}
