package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.CombatBody
import io.github.ksean.cyberslop.core.Vec2

/** A finite world-space camera rectangle supplied to one fixed simulation tick. */
data class GameplayViewport(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left < right && top < bottom)
    }

    /** Edges terminate ranged shots, so only the strict interior is live projectile space. */
    fun contains(point: Vec2): Boolean =
        point.x > left && point.x < right && point.y > top && point.y < bottom

    /** First fraction of [from]..[to] that reaches an edge, or null while the segment stays inside. */
    fun exitFraction(from: Vec2, to: Vec2): Double? {
        if (!contains(from)) return 0.0
        if (contains(to)) return null
        val travel = to - from
        var first = Double.POSITIVE_INFINITY
        if (travel.x < 0.0) first = minOf(first, (left - from.x) / travel.x)
        if (travel.x > 0.0) first = minOf(first, (right - from.x) / travel.x)
        if (travel.y < 0.0) first = minOf(first, (top - from.y) / travel.y)
        if (travel.y > 0.0) first = minOf(first, (bottom - from.y) / travel.y)
        return first.takeIf { it in 0.0..1.0 }
    }

    /** Positive-area circle/rectangle overlap; edge tangency alone is still off-screen. */
    fun overlaps(body: CombatBody): Boolean {
        val nearestX = body.centre.x.coerceIn(left, right)
        val nearestY = body.centre.y.coerceIn(top, bottom)
        val dx = body.centre.x - nearestX
        val dy = body.centre.y - nearestY
        return dx * dx + dy * dy < body.radius * body.radius
    }
}
