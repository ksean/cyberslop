package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileMap

/**
 * A silhouette in a parallax layer, in layer-local pixels with `y` measured up from the horizon.
 *
 * Lit windows are a bitmask rather than a list of rectangles. A level carries several hundred of
 * these, and a list per building would be tens of thousands of objects held for the whole map to
 * describe something a single `Int` describes exactly.
 */
data class Building(
    val x: Double,
    val width: Double,
    val height: Double,
    val windowColumns: Int,
    val windowRows: Int,
    val windows: Int,
) {
    fun hasWindow(column: Int, row: Int): Boolean {
        val bit = row * windowColumns + column
        return bit < Int.SIZE_BITS && (windows shr bit) and 1 == 1
    }
}

/** One depth of skyline. [parallax] is the fraction of camera movement the layer takes. */
data class BackdropLayer(
    val parallax: Double,
    val tint: String,
    val layer: Layer,
    val buildings: List<Building>,
)

/**
 * The city behind the level (PROD-040).
 *
 * Generated **once per level** and posed per frame by an offset. Regenerating it per frame would
 * make the skyline crawl, and would put a few hundred allocations into every frame for something
 * that never changes.
 *
 * It is drawn behind everything, reads no tile and writes no tile, so it cannot touch the
 * completability guarantee.
 *
 * [referenceY] is the world height [horizonFraction] was calibrated at — the player's spawn. Without
 * it the horizon is pinned to the screen and the city slides vertically past a world that is not
 * moving, which is what the first rendered frame showed when the player fell down a shaft.
 */
class Backdrop(
    val layers: List<BackdropLayer>,
    val horizonFraction: Double,
    val referenceY: Double,
)

object Backdrops {
    /**
     * Takes the level rather than four things pulled out of it.
     *
     * Where the horizon is anchored is a presentation decision, and it was being made twice in the
     * browser layer — once per call site, free to drift apart, and outside anything `commonMain`
     * could test (ENG-060).
     */
    fun of(seed: ULong, level: Level): Backdrop {
        val palette = Palettes.of(level.theme)
        val rng = Rng.derive(seed, level.mapIndex, "backdrop")
        val levelWidthPx = level.tiles.widthPx
        // The player's spawn: the height the horizon fraction is calibrated at.
        val referenceY = TileMap.toWorld(level.spawnRow)

        val tints = listOf(palette.backdropFar, palette.backdropMid, palette.backdropNear)
        val order = listOf(Layer.BackdropFar, Layer.BackdropMid, Layer.BackdropNear)

        val layers = DEPTHS.mapIndexed { index, depth ->
            BackdropLayer(
                parallax = depth.parallax,
                tint = tints[index],
                layer = order[index],
                buildings = skyline(rng, depth, levelWidthPx),
            )
        }
        return Backdrop(layers, HORIZON, referenceY)
    }

    private fun skyline(rng: Rng, depth: Depth, levelWidthPx: Double): List<Building> {
        // Only as wide as the layer can ever be scrolled to, plus a screen's worth of margin.
        val span = levelWidthPx * depth.parallax + MARGIN_PX
        val buildings = mutableListOf<Building>()

        var x = -MARGIN_PX
        while (x < span) {
            val width = depth.minWidth + rng.nextInt(depth.widthRange)
            val height = depth.minHeight + rng.nextInt(depth.heightRange)
            val columns = (width / WINDOW_PITCH).toInt().coerceIn(1, MAX_WINDOW_COLUMNS)
            val rows = (height / WINDOW_PITCH).toInt().coerceIn(1, MAX_WINDOW_ROWS)

            var lit = 0
            repeat(minOf(columns * rows, Int.SIZE_BITS)) { bit ->
                if (rng.nextDouble() < depth.litFraction) lit = lit or (1 shl bit)
            }

            buildings.add(Building(x, width.toDouble(), height.toDouble(), columns, rows, lit))
            x += width + depth.minGap + rng.nextInt(depth.gapRange)
        }
        return buildings
    }

    private class Depth(
        val parallax: Double,
        val minWidth: Int,
        val widthRange: Int,
        val minHeight: Int,
        val heightRange: Int,
        val minGap: Int,
        val gapRange: Int,
        val litFraction: Double,
    )

    /**
     * Sizes are world pixels, so the zoom multiplies them. The first pass wrote them as though they
     * were screen pixels and a single tower filled the view three times over.
     */
    private val DEPTHS = listOf(
        Depth(0.12, 14, 22, 22, 44, 3, 8, 0.16),
        Depth(0.30, 11, 18, 15, 34, 4, 10, 0.21),
        Depth(0.55, 8, 14, 9, 22, 5, 14, 0.27),
    )

    /** Where the horizon sits in the view, as a fraction of its height. */
    private const val HORIZON = 0.62
    private const val MARGIN_PX = 2200.0
    private const val WINDOW_PITCH = 3.6
    private const val MAX_WINDOW_COLUMNS = 5
    private const val MAX_WINDOW_ROWS = 6
}
