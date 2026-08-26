package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.world.TILE_SIZE

/**
 * Where the weapon points.
 *
 * Always at the nearest valid target. There is no cursor and no setting: the game is played on the
 * four arrow keys, which means it needs no pointing device at all rather than merely offering a way
 * to do without one. PROD-004 is satisfied outright instead of by amendment.
 */
object Targeting {
    /** Enemies further than this are ignored, so auto-aim does not snap across the whole map. */
    const val AUTO_RANGE_PX = 22.0 * TILE_SIZE

    /**
     * Takes **live** target positions, not the level's spawn records.
     *
     * An earlier version was handed the immutable spawn list, so it aimed at where things started
     * and kept aiming at corpses — the setting existed and did nothing useful.
     */
    fun aimPoint(muzzle: Vec2, targets: List<Vec2>, facing: Int): Vec2 =
        nearest(muzzle, targets) ?: Vec2(muzzle.x + facing * TILE_SIZE * 4.0, muzzle.y)

    fun nearest(muzzle: Vec2, targets: List<Vec2>): Vec2? {
        var best: Vec2? = null
        var bestDistance = AUTO_RANGE_PX * AUTO_RANGE_PX
        targets.forEach { point ->
            val distance = (point - muzzle).lengthSquared
            if (distance < bestDistance) {
                bestDistance = distance
                best = point
            }
        }
        return best
    }
}
