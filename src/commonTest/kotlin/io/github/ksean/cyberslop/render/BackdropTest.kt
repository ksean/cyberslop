package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.ThemeId
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROD-040's backdrop, and ENG-053's determinism for it.
 *
 * A review round found the task record claiming this was covered by the scene and palette tests. It
 * was not: the scene test composes twice against one already-built backdrop, and the palette test
 * never builds one. Regenerating a skyline differently for the same seed, or anchoring its horizon
 * somewhere other than the spawn, would both have left the gate green.
 *
 * The level is built here rather than generated. A skyline reads four things off it and none of them
 * need a carved map, and generating ten of them would not fit the browser runner's per-test timeout
 * (ENG-031).
 */
class BackdropTest {
    @Test
    fun `a seed reproduces its own skyline`() {
        val level = level(ThemeId.NeonSlums)

        val once = Backdrops.of(SEED, level)
        val again = Backdrops.of(SEED, level)

        assertEquals(shape(once), shape(again), "the same seed built two different skylines")
    }

    @Test
    fun `different seeds build different skylines`() {
        val level = level(ThemeId.NeonSlums)

        assertTrue(
            shape(Backdrops.of(SEED, level)) != shape(Backdrops.of(OTHER_SEED, level)),
            "two seeds produced the same city",
        )
    }

    @Test
    fun `each sub-theme is tinted differently`() {
        val tints = ThemeId.entries.map { theme ->
            Backdrops.of(SEED, level(theme)).layers.map { it.tint }
        }

        assertEquals(
            ThemeId.entries.size,
            tints.distinct().size,
            "two sub-themes share a backdrop tint: $tints",
        )
    }

    @Test
    fun `the horizon is anchored to the level's own spawn height`() {
        val level = level(ThemeId.NeonSlums)

        assertEquals(
            TileMap.toWorld(level.spawnRow),
            Backdrops.of(SEED, level).referenceY,
            "the horizon is anchored somewhere other than where the player starts, so the city " +
                "sits wrong the moment the map is entered",
        )
    }

    @Test
    fun `the layers run back to front, each moving faster than the one behind it`() {
        val layers = Backdrops.of(SEED, level(ThemeId.NeonSlums)).layers

        assertEquals(listOf(0.12, 0.30, 0.55), layers.map { it.parallax })
        assertTrue(layers.size >= 3, "a skyline with fewer than three depths has no parallax to see")
        layers.zipWithNext { far, near ->
            assertTrue(
                near.parallax > far.parallax,
                "a nearer layer moves no faster than the one behind it: " +
                    "${far.parallax} then ${near.parallax}",
            )
            assertTrue(
                near.layer.ordinal > far.layer.ordinal,
                "a nearer layer paints behind the one further away",
            )
        }
    }

    @Test
    fun `every layer spans the whole distance its parallax can carry it`() {
        val level = level(ThemeId.NeonSlums)
        val width = level.tiles.widthPx

        Backdrops.of(SEED, level).layers.forEach { layer ->
            assertTrue(layer.buildings.isNotEmpty(), "a layer has no buildings at all")
            val furthest = layer.buildings.maxOf { it.x + it.width }
            assertTrue(
                furthest >= width * layer.parallax,
                "a layer at ${layer.parallax} reaches $furthest, short of the " +
                    "${width * layer.parallax} the camera can scroll it to — the city runs out " +
                    "before the map does",
            )
            assertTrue(
                layer.buildings.first().x <= 0.0,
                "a layer starts right of the origin, so the view opens on empty sky",
            )
        }
    }

    /**
     * A window grid is a bitmask in one `Int`, so a building with more than 32 cells would have
     * lights it can never light. The caps have to keep the grid inside that.
     */
    @Test
    fun `no building has more window cells than its mask can hold`() {
        Backdrops.of(SEED, level(ThemeId.NeonSlums)).layers.forEach { layer ->
            layer.buildings.forEach { building ->
                val cells = building.windowColumns * building.windowRows
                assertTrue(building.windowColumns > 0 && building.windowRows > 0)
                assertTrue(
                    cells <= Int.SIZE_BITS,
                    "a building asks for $cells window cells, more than an Int can name",
                )
            }
        }
    }

    private fun shape(backdrop: Backdrop): List<List<Building>> =
        backdrop.layers.map { it.buildings }

    /** The four things a skyline reads: theme, map index, level width, and the spawn height. */
    private fun level(theme: ThemeId) = Level(
        mapIndex = 1,
        theme = theme,
        tiles = TileMap(WIDTH_TILES, HEIGHT_TILES),
        floorMask = Mask(WIDTH_TILES, HEIGHT_TILES),
        arcMask = Mask(WIDTH_TILES, HEIGHT_TILES),
        spawnColumn = 2,
        spawnRow = SPAWN_ROW,
        miniboss = Arena(20, 30, SPAWN_ROW),
        boss = Arena(40, 50, SPAWN_ROW),
        jets = emptyList(),
    )

    private companion object {
        val SEED = 0xC0FFEEuL
        val OTHER_SEED = 0xBADCAFEuL
        const val WIDTH_TILES = 320
        const val HEIGHT_TILES = 40
        const val SPAWN_ROW = 30
    }
}
