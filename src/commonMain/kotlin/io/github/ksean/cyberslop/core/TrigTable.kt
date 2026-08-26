package io.github.ksean.cyberslop.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Angles, without `sin`/`cos` at runtime.
 *
 * Those carry no cross-target guarantee, and anything reachable from the simulation tick has to
 * produce identical results on the JVM and in the browser (ENG-054). The table is built once from
 * `kotlin.math` at class-init and read thereafter, so the *values* are whatever the host produced —
 * but every tick reads the same array, and the state hash test would catch a divergence.
 *
 * Arc containment avoids angles altogether: comparing a dot product against a cosine answers "is
 * this within the arc" without ever computing an inverse trig function.
 */
object TrigTable {
    private const val STEPS = 3600
    private const val PER_DEGREE = STEPS / 360.0

    private val sine = DoubleArray(STEPS) { sin(it / PER_DEGREE * PI / 180.0) }
    private val cosine = DoubleArray(STEPS) { cos(it / PER_DEGREE * PI / 180.0) }

    fun sinDegrees(degrees: Double): Double = sine[index(degrees)]

    fun cosDegrees(degrees: Double): Double = cosine[index(degrees)]

    fun rotate(vector: Vec2, degrees: Double): Vec2 {
        if (degrees == 0.0) return vector
        val c = cosDegrees(degrees)
        val s = sinDegrees(degrees)
        return Vec2(vector.x * c - vector.y * s, vector.x * s + vector.y * c)
    }

    /** True when [toTarget] lies inside an arc of `2 x halfArcDegrees` centred on [facing]. */
    fun withinArc(facing: Vec2, toTarget: Vec2, halfArcDegrees: Double): Boolean {
        if (halfArcDegrees >= 180.0) return true
        val dot = facing.x * toTarget.x + facing.y * toTarget.y
        return dot >= cosDegrees(halfArcDegrees)
    }

    /** Turns [from] toward [to] by at most [maxDegrees], returning a unit vector. */
    fun turnToward(from: Vec2, to: Vec2, maxDegrees: Double): Vec2 {
        val current = from.normalisedOr(Vec2.Right)
        val desired = to.normalisedOr(current)
        if (withinArc(current, desired, maxDegrees)) return desired
        // Cross product sign decides which way round is shorter.
        val cross = current.x * desired.y - current.y * desired.x
        return rotate(current, if (cross >= 0.0) maxDegrees else -maxDegrees)
    }

    private fun index(degrees: Double): Int {
        val wrapped = degrees % 360.0
        val positive = if (wrapped < 0.0) wrapped + 360.0 else wrapped
        return ((positive * PER_DEGREE).toInt()) % STEPS
    }
}
