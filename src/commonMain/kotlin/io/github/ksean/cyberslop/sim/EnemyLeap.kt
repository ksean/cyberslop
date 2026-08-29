package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs
import kotlin.math.ceil

/** A fixed, previewed pursuit jump whose horizontal direction cannot change in flight. */
data class EnemyLeap(
    val direction: Int,
    val landingX: Double,
    val landingColumns: IntRange,
) {
    init {
        require(direction == -1 || direction == 1)
    }

    companion object {
        const val VX = 240.0
        const val VY = -680.0
        const val LANDING_COOLDOWN = 0.25
        const val LOOK_AHEAD_PX = 3.0 * TILE_SIZE
        private const val MAX_TICKS = 120
        private const val EDGE = 0.001
        private const val MIN_FORWARD_PX = TILE_SIZE.toDouble()

        /**
         * Replays the shipping fixed step with the caller's real body. A plan exists only when the
         * swept box clears solids and hazards and descends onto two safe supported corners.
         */
        fun plan(
            tiles: TileMap,
            level: Level? = null,
            topLeft: Vec2,
            width: Double,
            height: Double,
            feetOffset: Double,
            direction: Int,
            timeSeconds: Double,
            landingAllowed: (IntRange) -> Boolean = { true },
        ): EnemyLeap? {
            if (direction != -1 && direction != 1) return null
            var position = topLeft
            var vy = VY
            repeat(MAX_TICKS) { tick ->
                vy = (vy + Physics.Default.gravity * TICK_SECONDS)
                    .coerceAtMost(Physics.Default.terminalVelocity)
                val travel = Vec2(direction * VX * TICK_SECONDS, vy * TICK_SECONDS)
                val pieces = maxOf(1, ceil(maxOf(abs(travel.x), abs(travel.y)) / (TILE_SIZE / 2.0)).toInt())
                repeat(pieces) {
                    val next = position + travel * (1.0 / pieces)
                    if (solidOverlap(tiles, next, width, height)) {
                        // The shipping mover treats a downward collision as a landing. Merely
                        // rejecting it here made the preview agree only when a substep happened to
                        // touch a surface exactly, and rejected the same arc onto a lower floor.
                        if (vy > 0.0) {
                            val feetRow = TileMap.toTile(next.y + feetOffset)
                            val landed = Vec2(next.x, TileMap.toWorld(feetRow) - feetOffset)
                            val columns = columns(landed.x, width)
                            if (abs(landed.x - topLeft.x) >= MIN_FORWARD_PX &&
                                !solidOverlap(tiles, landed, width, height) &&
                                !damagingOverlap(tiles, level, landed, width, height) &&
                                safeSupport(tiles, level, landed, width, height, columns, feetRow) &&
                                landingAllowed(columns)
                            ) return EnemyLeap(direction, landed.x, columns)
                        }
                        return null
                    }
                    if (damagingOverlap(tiles, level, next, width, height)) return null
                    if (level != null && activeJetOverlap(
                            level,
                            next,
                            width,
                            height,
                            timeSeconds + tick * TICK_SECONDS
                        )
                    ) return null
                    position = next
                }

                if (vy <= 0.0 || abs(position.x - topLeft.x) < MIN_FORWARD_PX) return@repeat
                val columns = columns(position.x, width)
                val feetRow = TileMap.toTile(position.y + feetOffset)
                val surface = TileMap.toWorld(feetRow)
                if (position.y + feetOffset < surface) return@repeat
                if (!safeSupport(
                        tiles,
                        level,
                        position,
                        width,
                        height,
                        columns,
                        feetRow
                    ) || !landingAllowed(columns)
                ) return@repeat
                return EnemyLeap(direction, position.x, columns)
            }
            return null
        }

        private fun safeSupport(
            tiles: TileMap,
            level: Level?,
            at: Vec2,
            width: Double,
            height: Double,
            columns: IntRange,
            row: Int,
        ): Boolean {
            val corners = intArrayOf(columns.first, columns.last)
            if (corners.any { !tiles.blocksMovement(it, row) || tiles.isLethal(it, row) }) return false
            return level == null || Hazards.overlapped(level, at.x, at.y, width, height).isEmpty()
        }

        private fun solidOverlap(tiles: TileMap, at: Vec2, width: Double, height: Double): Boolean {
            for (column in columns(at.x, width)) {
                for (row in rows(at.y, height)) if (tiles.blocksMovement(column, row)) return true
            }
            return false
        }

        private fun damagingOverlap(
            tiles: TileMap,
            level: Level?,
            at: Vec2,
            width: Double,
            height: Double,
        ): Boolean {
            for (column in columns(at.x, width)) {
                for (row in rows(at.y, height)) if (tiles.isLethal(column, row)) return true
            }
            return level != null && Hazards.overlapped(level, at.x, at.y, width, height).isNotEmpty()
        }

        private fun activeJetOverlap(
            level: Level,
            at: Vec2,
            width: Double,
            height: Double,
            timeSeconds: Double,
        ): Boolean {
            val columns = columns(at.x, width)
            val rows = rows(at.y, height)
            return level.jets.any { jet ->
                jet.column in columns && rows.any(jet::coversRow) && jet.isOnAt(timeSeconds)
            }
        }

        private fun columns(x: Double, width: Double): IntRange =
            TileMap.toTile(x)..TileMap.toTile(x + width - EDGE)

        private fun rows(y: Double, height: Double): IntRange =
            TileMap.toTile(y)..TileMap.toTile(y + height - EDGE)
    }
}
