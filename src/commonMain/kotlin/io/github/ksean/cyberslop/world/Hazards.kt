package io.github.ksean.cyberslop.world

/** A tile-coordinate cell, for hazard footprints. */
data class Cell(val column: Int, val row: Int)

/**
 * A burning barrel standing in a floor cell, with its flame in the cell above (`specs/hazards.md`).
 *
 * An object on the level rather than a tile kind because it occupies two cells with one identity:
 * the confirming replay removes the whole barrel when the tape touches either.
 */
data class Barrel(val column: Int, val row: Int) {
    val flameRow: Int get() = row - 1
    val cells: List<Cell> get() = listOf(Cell(column, row), Cell(column, flameRow))
}

/**
 * Damaging hazards: survivable, off the witness route, and the only thing besides enemies and
 * bosses that hurts the player (PROD-064). Nothing here moves the player (ENG-051): overlap is read
 * against the AABB the movement model produced and turns into a drain, never a displacement.
 */
object Hazards {
    /** Multiples of `contactDamage` per second of overlap. */
    const val SPIKE_RATE = 1.0
    const val BARREL_RATE = 1.5

    /**
     * The summed rate of every hazard the box overlaps: a spike strip counts once however many of
     * its tiles are under the box, and each barrel counts once for its body or its flame.
     */
    fun ratePerSecond(level: Level, x: Double, y: Double, width: Double, height: Double): Double {
        return spikeRatePerSecond(level, x, y, width, height) +
            fireRatePerSecond(level, x, y, width, height)
    }

    /** Spike-only part of [ratePerSecond], kept separate so terminal damage retains its source. */
    fun spikeRatePerSecond(level: Level, x: Double, y: Double, width: Double, height: Double): Double =
        if (spikeTiles(level, x, y, width, height).isNotEmpty()) SPIKE_RATE else 0.0

    /** Burning-barrel part of [ratePerSecond], including both drum and flame overlap. */
    fun fireRatePerSecond(level: Level, x: Double, y: Double, width: Double, height: Double): Double =
        BARREL_RATE * barrels(level, x, y, width, height).size

    /** Every hazard cell the box overlaps, for the confirming replay. */
    fun overlapped(level: Level, x: Double, y: Double, width: Double, height: Double): List<Cell> =
        spikeTiles(level, x, y, width, height) + barrels(level, x, y, width, height).flatMap { barrel ->
            barrel.cells.filter { it.row in rows(y, height) }
        }

    /** Every spike tile on the map, in row-major order. */
    fun spikeCells(level: Level): List<Cell> = buildList {
        for (row in 0 until level.tiles.height) for (column in 0 until level.widthTiles) {
            if (level.tiles[column, row] == TileKind.Spikes) add(Cell(column, row))
        }
    }

    /** Every spike strip: a maximal run of spike tiles along one row. */
    fun spikeStrips(level: Level): List<List<Cell>> {
        val strips = mutableListOf<List<Cell>>()
        for (row in 0 until level.tiles.height) {
            var strip = mutableListOf<Cell>()
            for (column in 0..level.widthTiles) {
                if (column < level.widthTiles && level.tiles[column, row] == TileKind.Spikes) {
                    strip.add(Cell(column, row))
                } else if (strip.isNotEmpty()) {
                    strips.add(strip)
                    strip = mutableListOf()
                }
            }
        }
        return strips
    }

    /** How many damaging hazards a map carries: strips plus barrels. */
    fun count(level: Level): Int = spikeStrips(level).size + level.barrels.size

    private fun columns(x: Double, width: Double): IntRange =
        TileMap.toTile(x)..TileMap.toTile(x + width - EDGE)

    private fun rows(y: Double, height: Double): IntRange =
        TileMap.toTile(y)..TileMap.toTile(y + height - EDGE)

    private fun spikeTiles(level: Level, x: Double, y: Double, width: Double, height: Double): List<Cell> =
        buildList {
            for (column in columns(x, width)) for (row in rows(y, height)) {
                if (level.tiles[column, row] == TileKind.Spikes) add(Cell(column, row))
            }
        }

    private fun barrels(level: Level, x: Double, y: Double, width: Double, height: Double): List<Barrel> {
        if (level.barrels.isEmpty()) return emptyList()
        val columns = columns(x, width)
        val rows = rows(y, height)
        return level.barrels.filter { it.column in columns && (it.row in rows || it.flameRow in rows) }
    }

    /** A box resting exactly on a tile boundary does not overlap the tile beyond it. */
    private const val EDGE = 0.001
}
