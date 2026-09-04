package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.world.TILE_SIZE

/**
 * Legacy distance-bounded targeting for melee and psychic weapons.
 *
 * Ranged weapons instead select the nearest visible combat body from the gameplay viewport in
 * `GameSimulation` (PROD-116). There is no player-controlled cursor or targeting setting.
 */
object Targeting {
    /** The retained melee/psychic cap and enemy-awareness distance; ranged auto-aim does not use it. */
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
