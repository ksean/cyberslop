package io.github.ksean.cyberslop.world

const val TILE_SIZE = 16

enum class TileKind(val blocksMovement: Boolean, val isLethal: Boolean) {
    Empty(blocksMovement = false, isLethal = false),
    Solid(blocksMovement = true, isLethal = false),
    Acid(blocksMovement = false, isLethal = true),

    /** A damaging hazard on walkable floor: survivable, never blocking (`specs/hazards.md`). */
    Spikes(blocksMovement = false, isLethal = false),

    /** Everything below the world. Falling out of the map kills rather than strands. */
    Void(blocksMovement = false, isLethal = true),
}

/**
 * A fixed grid of tiles addressed in tile coordinates, with `y` increasing downward to match the
 * canvas.
 *
 * Reads outside the grid answer [TileKind.Solid] on every side except above, so collision and the
 * reachability search treat the map edges as walls without either needing a bounds check. Above the
 * map is [TileKind.Empty] instead, because a jump taken near the ceiling would otherwise be
 * clipped by nothing the player can see.
 */
class TileMap(val width: Int, val height: Int) {
    private val tiles = Array(height) { Array(width) { TileKind.Empty } }

    val widthPx: Double get() = (width * TILE_SIZE).toDouble()
    val heightPx: Double get() = (height * TILE_SIZE).toDouble()

    operator fun get(x: Int, y: Int): TileKind = when {
        // Side walls first. Testing the open top before the horizontal bounds would make every
        // column above the map empty, letting a player jump over the top and walk out sideways.
        x < 0 || x >= width -> TileKind.Solid
        y < 0 -> TileKind.Empty
        // Below the world is lethal, not solid. A solid floor down there is worse than a pit: the
        // player survives the fall, cannot climb back out, and the run persists across a refresh —
        // a soft-lock rather than a death.
        y >= height -> TileKind.Void
        else -> tiles[y][x]
    }

    operator fun set(x: Int, y: Int, kind: TileKind) {
        if (contains(x, y)) tiles[y][x] = kind
    }

    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun blocksMovement(x: Int, y: Int): Boolean = this[x, y].blocksMovement

    fun isLethal(x: Int, y: Int): Boolean = this[x, y].isLethal

    companion object {
        /** Floor division, so that world positions left of the origin map to negative tiles. */
        fun toTile(world: Double): Int {
            val scaled = world / TILE_SIZE
            val truncated = scaled.toInt()
            return if (scaled < 0.0 && scaled != truncated.toDouble()) truncated - 1 else truncated
        }

        fun toWorld(tile: Int): Double = (tile * TILE_SIZE).toDouble()
    }
}
