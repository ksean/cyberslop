package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.sim.EnemyLeap
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap

/** The two real collision boxes generation promises can pursue across its obstacles. */
enum class PursuitBody(val width: Double, val height: Double) {
    RankAndFile(14.0, 14.0),
    Boss(44.0, 56.0),
}

data class PursuitViolation(
    val body: PursuitBody,
    val takeoffColumn: Int,
    val floorRow: Int,
    val direction: Int,
)

/**
 * Replays [EnemyLeap] from the first safe take-off which can see each generated pursuit obstacle.
 * Fire jets are omitted from the static envelope because enemies wait for their off-window; their
 * geometry is still checked by the runtime planner at the actual clock time.
 */
object EnemyPursuitEnvelope {
    fun accepts(level: Level): Boolean = audit(level).isEmpty()

    /** Minimum platform depth which catches either fixed arc after the largest generated drop. */
    fun requiredLandingTiles(dropTiles: Int): Int = landingTilesByDrop[dropTiles]
        ?: measureRequiredLandingTiles(dropTiles).also { landingTilesByDrop[dropTiles] = it }

    private fun measureRequiredLandingTiles(dropTiles: Int): Int = PursuitBody.entries.maxOf { body ->
        val tiles = TileMap(MEASURE_WIDTH, MEASURE_HEIGHT)
        val lip = MEASURE_LIP
        val startFloor = MEASURE_FLOOR
        for (column in 0 until lip) tiles[column, startFloor] = TileKind.Solid
        for (column in lip until tiles.width) tiles[column, startFloor + dropTiles] = TileKind.Solid
        val startX = TileMap.toWorld(lip) - body.width - EnemyLeap.LOOK_AHEAD_PX
        val plan = EnemyLeap.plan(
            tiles = tiles,
            topLeft = Vec2(startX, TileMap.toWorld(startFloor) - body.height),
            width = body.width,
            height = body.height,
            feetOffset = body.height,
            direction = 1,
            timeSeconds = 0.0,
        ) ?: error("the ${body.name} leap cannot descend $dropTiles tiles")
        // A generated rest platform is both the landing for one move and the runway from which the
        // next obstacle is first seen. Count the whole measured take-off-to-far-corner span, not
        // just the portion beyond the lip; the latter produced seven-tile shelves which caught one
        // jump but could not launch the next one safely.
        plan.landingColumns.last - TileMap.toTile(startX) + LANDING_MARGIN_TILES
    }

    fun audit(level: Level): List<PursuitViolation> {
        val staticLevel = withoutJets(level)
        val violations = mutableListOf<PursuitViolation>()
        for (floorRow in 1 until level.tiles.height) {
            for (column in 0 until level.widthTiles) {
                // Almost every cell is empty air. Cull it once before trying either body instead
                // of asking both full AABB checks about all width × height cells.
                if (!level.floorMask[column, floorRow] || !level.arcMask[column, floorRow - 1]) continue
                if (!level.tiles.blocksMovement(column, floorRow) || level.tiles.isLethal(column, floorRow)) continue
                PursuitBody.entries.forEach { body ->
                    val topLeft = Vec2(
                        TileMap.toWorld(column),
                        TileMap.toWorld(floorRow) - body.height,
                    )
                    if (!safeStand(staticLevel, topLeft, body)) continue
                    for (direction in intArrayOf(-1, 1)) {
                        if (!firstTakeoffForObstacle(staticLevel, topLeft, body, direction)) continue
                        val plan = EnemyLeap.plan(
                            tiles = level.tiles,
                            level = staticLevel,
                            topLeft = topLeft,
                            width = body.width,
                            height = body.height,
                            feetOffset = body.height,
                            direction = direction,
                            timeSeconds = 0.0,
                        )
                        if (plan == null) {
                            violations += PursuitViolation(body, column, floorRow, direction)
                        }
                    }
                }
            }
        }
        return violations
    }

    private fun firstTakeoffForObstacle(
        level: Level,
        topLeft: Vec2,
        body: PursuitBody,
        direction: Int,
    ): Boolean {
        if (protectedArenaNear(level, topLeft, body, direction)) return false
        if (!obstacleAhead(level, topLeft, body, direction)) return false
        val previous = topLeft - Vec2(direction * TILE_SIZE.toDouble(), 0.0)
        return !safeStand(level, previous, body) || !obstacleAhead(level, previous, body, direction)
    }

    private fun protectedArenaNear(level: Level, topLeft: Vec2, body: PursuitBody, direction: Int): Boolean {
        val reach = topLeft.x + direction * EnemyLeap.LOOK_AHEAD_PX
        val left = minOf(topLeft.x, reach)
        val right = maxOf(topLeft.x + body.width - EDGE, reach + body.width - EDGE)
        if (left < 0.0 || right >= TileMap.toWorld(level.widthTiles)) return true
        return (TileMap.toTile(left)..TileMap.toTile(right)).any { column ->
            level.isMainBossGround(column, Populator.ARENA_APPROACH_TILES)
        }
    }

    private fun obstacleAhead(level: Level, topLeft: Vec2, body: PursuitBody, direction: Int): Boolean {
        val pieces = (EnemyLeap.LOOK_AHEAD_PX / (TILE_SIZE / 2.0)).toInt()
        repeat(pieces) { index ->
            val at = topLeft + Vec2(direction * (index + 1) * (TILE_SIZE / 2.0), 0.0)
            if (pursuitObstacle(level, at, body)) return true
        }
        return false
    }

    /** Gaps/lethal support, static hazards and raised floor are pursuit obstacles; a duct roof is not. */
    private fun pursuitObstacle(level: Level, at: Vec2, body: PursuitBody): Boolean {
        if (Hazards.overlapped(level, at.x, at.y, body.width, body.height).isNotEmpty()) return true
        val feetRow = TileMap.toTile(at.y + body.height)
        val corners = cornerColumns(at.x, body.width)
        if (corners.any { column ->
                !level.tiles.contains(column, feetRow) ||
                    !level.tiles.blocksMovement(column, feetRow) ||
                    level.tiles.isLethal(column, feetRow)
            }
        ) return true

        // A solid in the body with no floor at the old height is a generated step. When the old
        // floor remains, the obstruction is a crouch-duct roof and not part of this audit.
        return corners.any { column ->
            val oldFloorRemains = level.tiles.contains(column, feetRow) && level.tiles.blocksMovement(column, feetRow)
            !oldFloorRemains && bodyRows(at.y, body.height).any { row ->
                level.tiles.contains(column, row) && level.tiles.blocksMovement(column, row)
            }
        }
    }

    private fun safeStand(level: Level, at: Vec2, body: PursuitBody): Boolean {
        val feetRow = TileMap.toTile(at.y + body.height)
        val corners = cornerColumns(at.x, body.width)
        if (corners.any { column -> !level.floorMask[column, feetRow] }) return false
        // `floorMask` also protects the ceiling of a crouch duct. The route's arc occupies the cell
        // immediately above a real foothold, but runs below a duct roof, which keeps roofs from
        // being mistaken for chase platforms merely because their top face is standable.
        if (corners.any { column -> !level.arcMask[column, feetRow - 1] }) return false
        if (corners.any { column ->
                !level.tiles.contains(column, feetRow) ||
                    !level.tiles.blocksMovement(column, feetRow) ||
                    level.tiles.isLethal(column, feetRow)
            }
        ) return false
        if (Hazards.overlapped(level, at.x, at.y, body.width, body.height).isNotEmpty()) return false
        return columns(at.x, body.width).none { column ->
            bodyRows(at.y, body.height).any { row ->
                !level.tiles.contains(column, row) || level.tiles.blocksMovement(column, row)
            }
        }
    }

    private fun cornerColumns(x: Double, width: Double): IntArray =
        intArrayOf(TileMap.toTile(x), TileMap.toTile(x + width - EDGE))

    private fun columns(x: Double, width: Double): IntRange =
        TileMap.toTile(x)..TileMap.toTile(x + width - EDGE)

    private fun bodyRows(y: Double, height: Double): IntRange =
        TileMap.toTile(y)..TileMap.toTile(y + height - EDGE)

    private fun withoutJets(level: Level): Level = Level(
        mapIndex = level.mapIndex,
        theme = level.theme,
        tiles = level.tiles,
        floorMask = level.floorMask,
        arcMask = level.arcMask,
        spawnColumn = level.spawnColumn,
        spawnRow = level.spawnRow,
        miniboss = level.miniboss,
        boss = level.boss,
        jets = emptyList(),
        enemies = level.enemies,
        pickups = level.pickups,
        gateColumn = level.gateColumn,
        barrels = level.barrels,
    )

    private const val EDGE = 0.001
    private const val MEASURE_WIDTH = 80
    private const val MEASURE_HEIGHT = 80
    private const val MEASURE_LIP = 16
    private const val MEASURE_FLOOR = 30
    private const val LANDING_MARGIN_TILES = 2
    private val landingTilesByDrop = mutableMapOf<Int, Int>()
}
