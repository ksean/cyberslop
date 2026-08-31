package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Chooses the fixed simulation position of loot created by an actor's death (PROD-090).
 *
 * The slain actor's x is preferred. Invalid projections fall back through a stable list of safe
 * supports, without consuming randomness or depending on presentation state.
 */
class DeathDropPlacement(
    private val level: Level,
    private val physics: Physics = Physics.Default,
) {
    private data class Site(
        val position: Vec2,
        val column: Int,
        val openRow: Int,
        val supportRow: Int,
    )

    private val reachableCells: Set<Pair<Int, Int>> by lazy(::findReachableCells)

    fun place(death: Vec2, paired: Boolean): Vec2 {
        projectedSite(death)?.takeIf { valid(it, paired) }?.let { return it.position }

        val fallback = fallbackSites()
            .sortedWith(
                compareBy(
                    { site: Site -> abs(site.position.x - death.x) },
                    { abs(it.position.y - death.y) },
                    { it.column },
                    { it.openRow },
                ),
            )
            .firstOrNull { valid(it, paired) }
            ?: error("a valid level has no jump-collectable death-drop site for $death")
        return fallback.position
    }

    private fun projectedSite(death: Vec2): Site? {
        val column = TileMap.toTile(death.x)
        if (column !in 0 until level.widthTiles) return null
        val firstRow = TileMap.toTile(death.y).coerceAtLeast(0)
        for (supportRow in firstRow until level.tiles.height) {
            if (safeSupport(column, supportRow)) {
                return site(death.x, column, supportRow)
            }
        }
        return null
    }

    private fun fallbackSites(): List<Site> = buildList {
        for (column in 0 until level.widthTiles) {
            for (supportRow in 1 until level.tiles.height) {
                if (!safeSupport(column, supportRow)) continue
                add(site(TileMap.toWorld(column) + TILE_SIZE / 2.0, column, supportRow))
            }
        }
    }

    private fun site(x: Double, column: Int, supportRow: Int): Site =
        Site(
            position = Vec2(x, TileMap.toWorld(supportRow) - DEATH_DROP_RISE),
            column = column,
            openRow = supportRow - 1,
            supportRow = supportRow,
        )

    private fun safeSupport(column: Int, row: Int): Boolean =
        level.tiles.blocksMovement(column, row) && !level.tiles.isLethal(column, row)

    private fun valid(site: Site, paired: Boolean): Boolean {
        val icons = buildList {
            add(site.position)
            if (paired) add(site.position + Vec2(GroundItem.PAIRED_OFFSET, 0.0))
        }
        return site.column to site.openRow in reachableCells &&
            icons.all(::clearIcon) &&
            icons.none(::groundedPoseCanCollect) &&
            collectingJumpExists(site, icons)
    }

    private fun clearIcon(icon: Vec2): Boolean {
        val column = TileMap.toTile(icon.x)
        val row = TileMap.toTile(icon.y)
        return !level.tiles.blocksMovement(column, row) && !level.tiles.isLethal(column, row)
    }

    /**
     * Searches every grounded height that could be in radial range. Half-pixel horizontal samples
     * use a quarter-pixel conservative margin, so an unsampled walk-over pose rejects rather than
     * admits a site.
     */
    private fun groundedPoseCanCollect(icon: Vec2): Boolean {
        val conservativeReach = PICKUP_REACH + POSE_MARGIN
        for (stance in Stance.entries) {
            val height = heightOf(stance)
            for (supportRow in 1 until level.tiles.height) {
                val centreY = TileMap.toWorld(supportRow) - height / 2.0
                val vertical = abs(icon.y - centreY)
                if (vertical >= conservativeReach) continue
                val horizontal = sqrt(conservativeReach * conservativeReach - vertical * vertical)
                var centreX = icon.x - horizontal
                while (centreX <= icon.x + horizontal) {
                    if (canRestAt(centreX, supportRow, stance)) return true
                    centreX += POSE_SAMPLE
                }
                if (canRestAt(icon.x + horizontal, supportRow, stance)) return true
            }
        }
        return false
    }

    private fun collectingJumpExists(site: Site, icons: List<Vec2>): Boolean {
        val centreXs = buildList {
            icons.forEach { icon ->
                add(icon.x)
                add(icon.x - TILE_SIZE / 2.0)
                add(icon.x + TILE_SIZE / 2.0)
            }
        }.distinct()

        return centreXs.any { centreX ->
            if (!canRestAt(centreX, site.supportRow, Stance.Stand)) return@any false
            var state = PlayerState(
                x = centreX - physics.width / 2.0,
                y = TileMap.toWorld(site.supportRow) - physics.standingHeight,
                onGround = true,
            )
            var airborne = false
            repeat(MAX_JUMP_TICKS) { tick ->
                state = MovementModel.step(
                    state,
                    InputFrame(jump = true, jumpStart = tick == 0),
                    level.tiles,
                    physics,
                )
                if (state.touchedLethal || touchesHazard(state)) return@any false
                if (!state.onGround) airborne = true
                if (airborne && icons.any { inReach(it, state) }) return@any true
                if (airborne && state.onGround) return@any false
            }
            false
        }
    }

    private fun inReach(icon: Vec2, state: PlayerState): Boolean {
        val centre = Vec2(
            state.x + physics.width / 2.0,
            state.y + state.height(physics) / 2.0,
        )
        return (icon - centre).lengthSquared < PICKUP_REACH * PICKUP_REACH
    }

    private fun canRestAt(centreX: Double, supportRow: Int, stance: Stance): Boolean {
        val height = heightOf(stance)
        val x = centreX - physics.width / 2.0
        val y = TileMap.toWorld(supportRow) - height
        if (overlaps(x, y, physics.width, height) { column, row ->
                level.tiles.blocksMovement(column, row) || level.tiles.isLethal(column, row)
            }
        ) {
            return false
        }
        if (Hazards.overlapped(level, x, y, physics.width, height).isNotEmpty()) return false

        val probeY = y + height + GROUND_PROBE
        var supported = false
        forTiles(x, probeY, physics.width, GROUND_PROBE) { column, row ->
            if (level.tiles.isLethal(column, row)) return false
            if (level.tiles.blocksMovement(column, row)) supported = true
        }
        return supported
    }

    private fun touchesHazard(state: PlayerState): Boolean =
        Hazards.overlapped(
            level,
            state.x,
            state.y,
            physics.width,
            state.height(physics),
        ).isNotEmpty()

    private inline fun overlaps(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        predicate: (Int, Int) -> Boolean,
    ): Boolean {
        var found = false
        forTiles(x, y, width, height) { column, row ->
            if (predicate(column, row)) found = true
        }
        return found
    }

    private inline fun forTiles(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        visit: (Int, Int) -> Unit,
    ) {
        val left = TileMap.toTile(x)
        val right = TileMap.toTile(x + width - EDGE)
        val top = TileMap.toTile(y)
        val bottom = TileMap.toTile(y + height - EDGE)
        for (column in left..right) for (row in top..bottom) visit(column, row)
    }

    /** A bounded support flood used only to reject isolated platforms. */
    private fun findReachableCells(): Set<Pair<Int, Int>> {
        val maxRise = ceil(physics.apexHeight / TILE_SIZE).toInt()
        val maxHorizontal = ceil(physics.flatReach / TILE_SIZE).toInt()
        val reachedSupports = mutableSetOf<Pair<Int, Int>>()
        val pendingSupports = ArrayDeque<Pair<Int, Int>>()
        val start = level.spawnColumn to level.spawnRow - 1
        if (safeSupport(start.first, start.second + 1)) {
            reachedSupports.add(start)
            pendingSupports.add(start)
        }

        while (pendingSupports.isNotEmpty()) {
            val from = pendingSupports.removeFirst()
            val open = openJumpRegion(from, maxRise, maxHorizontal)
            open.forEach { cell ->
                if (safeSupport(cell.first, cell.second + 1) && reachedSupports.add(cell)) {
                    pendingSupports.add(cell)
                }
            }
        }
        return reachedSupports
    }

    /**
     * Open cells one jump may traverse from [support]. The local rectangle bounds both horizontal
     * travel and rise/drop, while the flood itself rejects walls and lethal cells inside it.
     */
    private fun openJumpRegion(
        support: Pair<Int, Int>,
        maxRise: Int,
        maxHorizontal: Int,
    ): Set<Pair<Int, Int>> {
        val left = (support.first - maxHorizontal).coerceAtLeast(0)
        val right = (support.first + maxHorizontal).coerceAtMost(level.widthTiles - 1)
        val top = (support.second - maxRise).coerceAtLeast(0)
        val bottom = (support.second + maxHorizontal).coerceAtMost(level.tiles.height - 1)
        val seen = mutableSetOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()

        fun offer(cell: Pair<Int, Int>) {
            if (cell.first !in left..right || cell.second !in top..bottom) return
            if (level.tiles.blocksMovement(cell.first, cell.second) ||
                level.tiles.isLethal(cell.first, cell.second)
            ) {
                return
            }
            if (seen.add(cell)) queue.add(cell)
        }

        offer(support)
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            offer(cell.first - 1 to cell.second)
            offer(cell.first + 1 to cell.second)
            offer(cell.first to cell.second - 1)
            offer(cell.first to cell.second + 1)
        }
        return seen
    }

    private fun heightOf(stance: Stance): Double =
        if (stance == Stance.Stand) physics.standingHeight else physics.crouchingHeight

    companion object {
        const val DEATH_DROP_RISE = 2.0 * TILE_SIZE
        const val PICKUP_REACH = TILE_SIZE.toDouble()
        private const val MAX_JUMP_TICKS = 240
        private const val POSE_SAMPLE = 0.5
        private const val POSE_MARGIN = POSE_SAMPLE / 2.0
        private const val GROUND_PROBE = 0.05
        private const val EDGE = 0.001
    }
}
