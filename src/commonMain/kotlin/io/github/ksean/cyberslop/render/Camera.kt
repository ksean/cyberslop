package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.world.Level
import kotlin.math.max
import kotlin.math.min

/**
 * Where the view sits over the world.
 *
 * Platform-independent by design (ENG-010): what the camera follows and how far it may travel are
 * game rules, and only the drawing of them belongs in the browser layer. Keeping it here also makes
 * the aim conversion testable without a canvas, which matters because cursor-to-world aiming is
 * wrong the moment the camera is not accounted for.
 */
data class Camera(
    val x: Double,
    val y: Double,
    val viewWidth: Double,
    val viewHeight: Double,
) {
    fun worldToScreen(worldX: Double, worldY: Double): Pair<Double, Double> =
        (worldX - x) to (worldY - y)

    fun screenToWorld(screenX: Double, screenY: Double): Pair<Double, Double> =
        (screenX + x) to (screenY + y)

    companion object {
        /** Fraction of the view the player may move within before the camera follows. */
        private const val DEAD_ZONE = 0.25

        /** How far ahead of the player the view leads, as a fraction of the view width. */
        private const val LOOK_AHEAD = 0.12

        fun following(
            previous: Camera,
            playerX: Double,
            playerY: Double,
            facing: Int,
            level: Level,
        ): Camera {
            val targetX = playerX + facing * previous.viewWidth * LOOK_AHEAD -
                previous.viewWidth / 2.0
            val targetY = playerY - previous.viewHeight / 2.0

            val marginX = previous.viewWidth * DEAD_ZONE / 2.0
            val marginY = previous.viewHeight * DEAD_ZONE / 2.0

            val nextX = when {
                targetX > previous.x + marginX -> targetX - marginX
                targetX < previous.x - marginX -> targetX + marginX
                else -> previous.x
            }
            val nextY = when {
                targetY > previous.y + marginY -> targetY - marginY
                targetY < previous.y - marginY -> targetY + marginY
                else -> previous.y
            }

            return previous.copy(
                x = clamp(nextX, level.tiles.widthPx, previous.viewWidth),
                y = clamp(nextY, level.tiles.heightPx, previous.viewHeight),
            )
        }

        /** Never shows anything outside the generated map. A shorter world pins the view at zero. */
        private fun clamp(value: Double, worldExtent: Double, viewExtent: Double): Double =
            if (worldExtent <= viewExtent) 0.0 else min(max(value, 0.0), worldExtent - viewExtent)
    }
}
