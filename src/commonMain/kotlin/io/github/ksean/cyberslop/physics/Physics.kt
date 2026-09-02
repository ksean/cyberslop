package io.github.ksean.cyberslop.physics

import kotlin.math.sqrt

const val TICK_SECONDS = 1.0 / 60.0

/**
 * Every constant the movement model reads. It is a value rather than a set of top-level constants so
 * that a test can vary one and observe the measured envelope change — which is how the project
 * enforces that generation distances are derived rather than typed (ENG-055).
 *
 * `y` increases downward, so a jump is negative vertical velocity.
 */
data class Physics(
    val gravity: Double,
    val jumpImpulse: Double,
    val jumpReleaseClamp: Double,
    val groundAccel: Double,
    val airAccel: Double,
    val groundFriction: Double,
    val maxRunSpeed: Double,
    val terminalVelocity: Double,
    val crouchSpeedFactor: Double,
    val width: Double,
    val standingHeight: Double,
    val crouchingHeight: Double,
) {
    /**
     * Closed forms. These are design intuition only, and are deliberately **not** what generation
     * reads: the fixed-step integrator lands below them, because each tick applies a whole tick of
     * gravity. At the default constants the discrete apex is 90.67 px against a continuous 96.33 px.
     * Generation reads [MovementEnvelope], which is measured (ENG-055).
     */
    val apexHeight: Double get() = jumpImpulse * jumpImpulse / (2.0 * gravity)
    val airtime: Double get() = 2.0 * jumpImpulse / gravity
    val flatReach: Double get() = maxRunSpeed * airtime
    val stoppingDistance: Double get() = maxRunSpeed * maxRunSpeed / (2.0 * groundFriction)
    val runwayLength: Double get() = maxRunSpeed * maxRunSpeed / (2.0 * groundAccel)

    /** Horizontal reach of a running jump that lands `drop` pixels below the take-off. */
    fun reachFallingBy(drop: Double): Double =
        maxRunSpeed * (jumpImpulse / gravity + sqrt(2.0 * (apexHeight + drop) / gravity))

    companion object {
        val Default = Physics(
            gravity = 2400.0,
            jumpImpulse = 680.0,
            jumpReleaseClamp = 160.0,
            groundAccel = 1600.0,
            airAccel = 1200.0,
            groundFriction = 2400.0,
            maxRunSpeed = 240.0,
            terminalVelocity = 1000.0,
            crouchSpeedFactor = 0.5,
            width = 12.0,
            standingHeight = 26.0,
            crouchingHeight = 14.0,
        )
    }
}

enum class Stance { Stand, Crouch }

/** The lethal tile crossed by one movement step, retained so death presentation knows its cause. */
enum class LethalContact { Acid, Void }

/**
 * One tick of movement intent, already resolved. Coyote time and jump buffering are applied by
 * [IntentFilter] before this exists, so the movement model contains no assist logic and a recorded
 * sequence of these frames means exactly one thing when replayed (`specs/simulation.md`).
 */
data class InputFrame(
    val left: Boolean = false,
    val right: Boolean = false,
    val crouch: Boolean = false,
    /** Jump held this tick. Governs variable jump height only. */
    val jump: Boolean = false,
    /** Begin a jump this tick. The filter above owns the decision; the model just obeys. */
    val jumpStart: Boolean = false,
) {
    val direction: Int get() = (if (right) 1 else 0) - (if (left) 1 else 0)
}

/** `x` and `y` are the top-left corner of the player's box. */
data class PlayerState(
    val x: Double,
    val y: Double,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val onGround: Boolean = false,
    val stance: Stance = Stance.Stand,
    /**
     * Whether the sweep that produced this state passed through a lethal tile. Reported here, rather
     * than left for a caller to re-derive, because re-deriving it would mean a second movement model
     * disagreeing with the first (ENG-052).
     */
    val touchedLethal: Boolean = false,
    /** First lethal tile kind crossed by the swept step; null when [touchedLethal] is false. */
    val lethalContact: LethalContact? = null,
) {
    fun height(physics: Physics): Double =
        if (stance == Stance.Crouch) physics.crouchingHeight else physics.standingHeight
}
