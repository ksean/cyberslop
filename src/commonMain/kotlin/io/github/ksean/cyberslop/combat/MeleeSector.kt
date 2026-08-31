package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2

/** A target's closed, circular combat footprint. */
data class CombatBody(val centre: Vec2, val radius: Double) {
    init {
        require(radius >= 0.0)
    }
}

/**
 * The cumulative closed sector exposed by one player melee swing.
 *
 * The interval begins at `-arc / 2` from [direction] and grows toward `+arc / 2`. Keeping this
 * geometry immutable lets simulation and presentation consume the same snapshot.
 */
data class MeleeSector(
    val origin: Vec2,
    val direction: Vec2,
    val reachPx: Double,
    val arcDegrees: Double,
    val progress: Double,
) {
    init {
        require(reachPx >= 0.0)
        require(arcDegrees in 0.0..360.0)
        require(progress in 0.0..1.0)
    }

    val sweptDegrees: Double get() = arcDegrees * progress
    val trailingDirection: Vec2 get() = TrigTable.rotate(unitDirection, -arcDegrees / 2.0)
    val leadingDirection: Vec2 get() = TrigTable.rotate(trailingDirection, sweptDegrees)

    fun contains(point: Vec2): Boolean {
        val offset = point - origin
        if (offset.lengthSquared > (reachPx + EPSILON) * (reachPx + EPSILON)) return false
        if (offset.lengthSquared <= EPSILON * EPSILON) return true
        return directionInside(offset)
    }

    /** True when the closed target disc touches any part of this closed sector. */
    fun intersects(body: CombatBody): Boolean {
        val offset = body.centre - origin
        val distance = offset.length
        if (distance <= body.radius + EPSILON || contains(body.centre)) return true
        if (distance > reachPx + body.radius + EPSILON) return false

        // A centre beyond the outer arc can still touch it when its bearing is inside the fan.
        if (directionInside(offset) && distance - body.radius <= reachPx + EPSILON) return true

        val radiusSquared = (body.radius + EPSILON) * (body.radius + EPSILON)
        return distanceToSegmentSquared(body.centre, origin, origin + trailingDirection * reachPx) <= radiusSquared ||
            distanceToSegmentSquared(body.centre, origin, origin + leadingDirection * reachPx) <= radiusSquared
    }

    private fun directionInside(offset: Vec2): Boolean {
        if (sweptDegrees >= 360.0) return true
        val unit = offset.normalisedOr(trailingDirection)
        val middle = TrigTable.rotate(trailingDirection, sweptDegrees / 2.0)
        val dot = middle.x * unit.x + middle.y * unit.y
        return dot >= TrigTable.cosDegrees(sweptDegrees / 2.0) - EPSILON
    }

    private val unitDirection: Vec2 get() = direction.normalisedOr(Vec2.Right)

    private fun distanceToSegmentSquared(point: Vec2, from: Vec2, to: Vec2): Double {
        val segment = to - from
        if (segment.lengthSquared <= EPSILON * EPSILON) return (point - from).lengthSquared
        val offset = point - from
        val projection = ((offset.x * segment.x + offset.y * segment.y) / segment.lengthSquared)
            .coerceIn(0.0, 1.0)
        return (point - (from + segment * projection)).lengthSquared
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}

/** Conservative combat discs for damaging silhouettes; decorative hardware is deliberately out. */
object CombatBodies {
    const val ENEMY_RADIUS = 24.0
    const val MINIBOSS_RADIUS = 36.0
    const val BOSS_RADIUS = 56.0

    fun enemy(centre: Vec2): CombatBody = CombatBody(centre, ENEMY_RADIUS)
    fun boss(centre: Vec2, isMain: Boolean): CombatBody =
        CombatBody(centre, if (isMain) BOSS_RADIUS else MINIBOSS_RADIUS)
}
