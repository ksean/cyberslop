package io.github.ksean.cyberslop.core

import kotlin.math.sqrt

/**
 * A 2D vector.
 *
 * `sqrt` is used rather than avoided: unlike `sin`, `cos` and `pow`, it is a correctly-rounded
 * IEEE-754 operation and produces identical results on every target (ENG-054).
 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
    operator fun times(scalar: Double): Vec2 = Vec2(x * scalar, y * scalar)

    val lengthSquared: Double get() = x * x + y * y
    val length: Double get() = sqrt(lengthSquared)

    /** Unit vector, or [fallback] when there is no direction to speak of. */
    fun normalisedOr(fallback: Vec2): Vec2 {
        val magnitude = length
        return if (magnitude < EPSILON) fallback else Vec2(x / magnitude, y / magnitude)
    }

    companion object {
        const val EPSILON = 1e-9
        val Zero = Vec2(0.0, 0.0)
        val Right = Vec2(1.0, 0.0)
    }
}
