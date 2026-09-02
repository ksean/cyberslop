package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.ThemeId
import io.github.ksean.cyberslop.world.TileMap

/** Structural details whose combinations give each sub-theme a colour-independent silhouette. */
enum class BackdropMotif {
    RoofDamage,
    Stack,
    Tank,
    Pipe,
    Gantry,
    Cable,
    Antenna,
    SignFrame,
    Buttress,
    Vent,
    BridgeFragment,
    LightStrip,
}

enum class BackdropRoof { Flat, Broken, Stepped, Sawtooth, Crowned, Ribbed }

enum class BackdropWindows { Grid, Bands, Columns, Sparse }

data class BackdropProfile(
    val theme: ThemeId,
    val motifs: List<BackdropMotif>,
    val roofs: List<BackdropRoof>,
    val windows: List<BackdropWindows>,
    val widthScale: Double,
    val heightScale: Double,
)

/** The authored part of each skyline. The level seed varies these ingredients; it never replaces them. */
object BackdropProfiles {
    private val profiles = listOf(
        profile(
            ThemeId.RuinedCitySprawl,
            BackdropMotif.RoofDamage, BackdropMotif.Antenna,
            BackdropMotif.BridgeFragment, BackdropMotif.Buttress,
            roofs = listOf(BackdropRoof.Broken, BackdropRoof.Stepped),
            windows = listOf(BackdropWindows.Sparse, BackdropWindows.Grid),
            widthScale = 0.90, heightScale = 1.35,
        ),
        profile(
            ThemeId.RustFlats,
            BackdropMotif.Tank, BackdropMotif.Gantry, BackdropMotif.Stack, BackdropMotif.Pipe,
            roofs = listOf(BackdropRoof.Flat, BackdropRoof.Sawtooth),
            windows = listOf(BackdropWindows.Sparse, BackdropWindows.Bands),
            widthScale = 1.30, heightScale = 0.70,
        ),
        profile(
            ThemeId.FloodedUndercity,
            BackdropMotif.Pipe, BackdropMotif.BridgeFragment, BackdropMotif.Vent, BackdropMotif.Tank,
            roofs = listOf(BackdropRoof.Flat, BackdropRoof.Stepped),
            windows = listOf(BackdropWindows.Bands, BackdropWindows.Sparse),
            widthScale = 1.20, heightScale = 0.76,
        ),
        profile(
            ThemeId.ChemFoundry,
            BackdropMotif.Stack, BackdropMotif.Tank, BackdropMotif.Pipe, BackdropMotif.Vent,
            roofs = listOf(BackdropRoof.Crowned, BackdropRoof.Sawtooth),
            windows = listOf(BackdropWindows.Columns, BackdropWindows.Sparse),
            widthScale = 1.05, heightScale = 1.08,
        ),
        profile(
            ThemeId.NeonSlums,
            BackdropMotif.SignFrame, BackdropMotif.Antenna,
            BackdropMotif.Cable, BackdropMotif.Gantry,
            roofs = listOf(BackdropRoof.Stepped, BackdropRoof.Broken),
            windows = listOf(BackdropWindows.Grid, BackdropWindows.Columns),
            widthScale = 0.86, heightScale = 1.05,
        ),
        profile(
            ThemeId.SableRefinery,
            BackdropMotif.Stack, BackdropMotif.Pipe, BackdropMotif.Tank,
            BackdropMotif.Gantry, BackdropMotif.Buttress,
            roofs = listOf(BackdropRoof.Crowned, BackdropRoof.Flat),
            windows = listOf(BackdropWindows.Sparse, BackdropWindows.Bands),
            widthScale = 1.16, heightScale = 1.18,
        ),
        profile(
            ThemeId.ServerStacks,
            BackdropMotif.Vent, BackdropMotif.Cable,
            BackdropMotif.Buttress, BackdropMotif.LightStrip,
            roofs = listOf(BackdropRoof.Ribbed, BackdropRoof.Flat),
            windows = listOf(BackdropWindows.Columns, BackdropWindows.Bands),
            widthScale = 0.92, heightScale = 1.30,
        ),
        profile(
            ThemeId.SkybridgeRuin,
            BackdropMotif.BridgeFragment, BackdropMotif.Cable,
            BackdropMotif.Antenna, BackdropMotif.Buttress,
            roofs = listOf(BackdropRoof.Broken, BackdropRoof.Ribbed),
            windows = listOf(BackdropWindows.Sparse, BackdropWindows.Columns),
            widthScale = 0.78, heightScale = 1.48,
        ),
        profile(
            ThemeId.ReactorCore,
            BackdropMotif.Tank, BackdropMotif.Stack, BackdropMotif.Pipe,
            BackdropMotif.Buttress, BackdropMotif.LightStrip,
            roofs = listOf(BackdropRoof.Crowned, BackdropRoof.Ribbed),
            windows = listOf(BackdropWindows.Bands, BackdropWindows.Sparse),
            widthScale = 1.42, heightScale = 1.20,
        ),
        profile(
            ThemeId.ArcologyVault,
            BackdropMotif.Buttress, BackdropMotif.Antenna, BackdropMotif.BridgeFragment,
            BackdropMotif.Gantry, BackdropMotif.LightStrip,
            roofs = listOf(BackdropRoof.Crowned, BackdropRoof.Stepped),
            windows = listOf(BackdropWindows.Columns, BackdropWindows.Grid),
            widthScale = 1.26, heightScale = 1.52,
        ),
    ).associateBy { it.theme }

    fun of(theme: ThemeId): BackdropProfile = profiles.getValue(theme)

    private fun profile(
        theme: ThemeId,
        vararg motifs: BackdropMotif,
        roofs: List<BackdropRoof>,
        windows: List<BackdropWindows>,
        widthScale: Double,
        heightScale: Double,
    ) = BackdropProfile(theme, motifs.toList(), roofs, windows, widthScale, heightScale)
}

/** One seeded instance of a theme motif on a building. Geometry is resolved by [Scene]. */
data class BackdropFeature(
    val motif: BackdropMotif,
    /** Horizontal anchor as a fraction of building width. */
    val anchor: Double,
    /** Seeded scale within the owning parallax depth. */
    val scale: Double,
    val variant: Int,
)

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
    val roof: BackdropRoof,
    val windowLayout: BackdropWindows,
    val features: List<BackdropFeature>,
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
        val profile = BackdropProfiles.of(level.theme)
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
                buildings = skyline(rng, depth, profile, levelWidthPx),
            )
        }
        return Backdrop(layers, HORIZON, referenceY)
    }

    private fun skyline(
        rng: Rng,
        depth: Depth,
        profile: BackdropProfile,
        levelWidthPx: Double,
    ): List<Building> {
        // Only as wide as the layer can ever be scrolled to, plus a screen's worth of margin.
        val span = levelWidthPx * depth.parallax + MARGIN_PX
        val buildings = mutableListOf<Building>()

        var x = -MARGIN_PX
        while (x < span) {
            val width = ((depth.minWidth + rng.nextInt(depth.widthRange)) * profile.widthScale)
                .toInt().coerceAtLeast(MIN_BUILDING_PX)
            val height = ((depth.minHeight + rng.nextInt(depth.heightRange)) * profile.heightScale)
                .toInt().coerceAtLeast(MIN_BUILDING_PX)
            val columns = (width / WINDOW_PITCH).toInt().coerceIn(1, MAX_WINDOW_COLUMNS)
            val rows = (height / WINDOW_PITCH).toInt().coerceIn(1, MAX_WINDOW_ROWS)

            var lit = 0
            repeat(minOf(columns * rows, Int.SIZE_BITS)) { bit ->
                if (rng.nextDouble() < depth.litFraction) lit = lit or (1 shl bit)
            }

            buildings.add(
                Building(
                    x = x,
                    width = width.toDouble(),
                    height = height.toDouble(),
                    windowColumns = columns,
                    windowRows = rows,
                    windows = lit,
                    roof = profile.roofs[rng.nextInt(profile.roofs.size)],
                    windowLayout = profile.windows[rng.nextInt(profile.windows.size)],
                    features = List(depth.detailCount) {
                        BackdropFeature(
                            motif = profile.motifs[rng.nextInt(profile.motifs.size)],
                            anchor = FEATURE_MARGIN + rng.nextDouble() * (1.0 - FEATURE_MARGIN * 2.0),
                            scale = FEATURE_MIN_SCALE + rng.nextDouble() * FEATURE_SCALE_RANGE,
                            variant = rng.nextInt(FEATURE_VARIANTS),
                        )
                    },
                ),
            )
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
        val detailCount: Int,
    )

    /**
     * Sizes are world pixels, so the zoom multiplies them. The first pass wrote them as though they
     * were screen pixels and a single tower filled the view three times over.
     */
    private val DEPTHS = listOf(
        Depth(0.12, 14, 22, 22, 44, 3, 8, 0.16, 1),
        Depth(0.30, 11, 18, 15, 34, 4, 10, 0.21, 2),
        Depth(0.55, 8, 14, 9, 22, 5, 14, 0.27, 3),
    )

    /** Where the horizon sits in the view, as a fraction of its height. */
    private const val HORIZON = 0.62
    private const val MARGIN_PX = 2200.0
    private const val WINDOW_PITCH = 3.6
    private const val MAX_WINDOW_COLUMNS = 5
    private const val MAX_WINDOW_ROWS = 6
    private const val MIN_BUILDING_PX = 6
    private const val FEATURE_MARGIN = 0.12
    private const val FEATURE_MIN_SCALE = 0.70
    private const val FEATURE_SCALE_RANGE = 0.60
    private const val FEATURE_VARIANTS = 4
}
