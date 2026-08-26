package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind

/**
 * Adds structure away from the critical path.
 *
 * The two masks are the whole safety story here: nothing may be written where the spine carved, and
 * nothing solid may be written anywhere the player's box passes through. Decoration is otherwise
 * free, and deliberately tries to place blocks everywhere so that a mask failure shows up as a
 * broken witness rather than as a subtle absence.
 */
object Decorator {
    private const val ATTEMPT_DENSITY = 0.04

    /**
     * Only real structure counts. The world's out-of-bounds side walls read as solid, so counting
     * them let decoration grow a tower up column zero — which becomes a standable ledge at the
     * bottom of the map that the player can fall onto and never leave.
     */
    private fun touchesSolid(level: Level, x: Int, y: Int): Boolean =
        listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (nx, ny) ->
            level.tiles.contains(nx, ny) && level.tiles[nx, ny].blocksMovement
        }

    fun decorate(level: Level, rng: Rng) {
        val attempts = (level.widthTiles * level.tiles.height * ATTEMPT_DENSITY).toInt()

        repeat(attempts) {
            val x = rng.nextInt(level.widthTiles)
            val y = rng.nextInt(level.tiles.height)
            if (level.floorMask[x, y]) return@repeat
            if (level.arcMask[x, y]) return@repeat
            if (level.tiles[x, y] != TileKind.Empty) return@repeat
            // Grow against existing structure. Free-floating blocks read as noise and, worse, become
            // standable ledges in open air that the reachability analysis then has to reason about.
            if (!touchesSolid(level, x, y)) return@repeat
            level.tiles[x, y] = TileKind.Solid
        }
    }
}
