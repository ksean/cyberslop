package io.github.ksean.cyberslop.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileMapTest {
    @Test
    fun `a new map is entirely empty`() {
        val map = TileMap(width = 4, height = 3)

        for (x in 0 until 4) {
            for (y in 0 until 3) {
                assertEquals(TileKind.Empty, map[x, y], "tile at $x,$y")
            }
        }
    }

    @Test
    fun `a written tile reads back`() {
        val map = TileMap(width = 4, height = 3)

        map[2, 1] = TileKind.Solid

        assertEquals(TileKind.Solid, map[2, 1])
        assertEquals(TileKind.Empty, map[1, 1])
    }

    @Test
    fun `the sides are solid so the player cannot leave the map sideways`() {
        val map = TileMap(width = 4, height = 3)

        assertEquals(TileKind.Solid, map[-1, 1])
        assertEquals(TileKind.Solid, map[4, 1])
    }

    @Test
    fun `below the map is lethal rather than solid, so a fall kills instead of stranding`() {
        val map = TileMap(width = 4, height = 3)

        assertEquals(TileKind.Void, map[1, 3])
        assertTrue(TileKind.Void.isLethal)
        assertFalse(TileKind.Void.blocksMovement)
    }

    @Test
    fun `above the map is empty so a jump near the ceiling is not blocked`() {
        val map = TileMap(width = 4, height = 3)

        assertEquals(TileKind.Empty, map[1, -1])
    }

    @Test
    fun `out of bounds writes are ignored`() {
        val map = TileMap(width = 4, height = 3)

        map[-1, 0] = TileKind.Solid
        map[0, -1] = TileKind.Solid

        assertEquals(TileKind.Solid, map[-1, 0])
        assertEquals(TileKind.Empty, map[0, 0])
    }

    @Test
    fun `solid tiles block movement and acid does not`() {
        assertTrue(TileKind.Solid.blocksMovement)
        assertFalse(TileKind.Acid.blocksMovement)
        assertFalse(TileKind.Empty.blocksMovement)
    }

    @Test
    fun `acid is lethal and solid is not`() {
        assertTrue(TileKind.Acid.isLethal)
        assertFalse(TileKind.Solid.isLethal)
        assertFalse(TileKind.Empty.isLethal)
    }

    @Test
    fun `a player who falls out of the world dies rather than resting on the boundary`() {
        val map = TileMap(width = 4, height = 3)

        assertFalse(map.blocksMovement(1, 3), "the world floor would catch a falling player")
        assertTrue(map.isLethal(1, 3))
    }

    @Test
    fun `world coordinates convert to tile coordinates by flooring`() {
        assertEquals(0, TileMap.toTile(0.0))
        assertEquals(0, TileMap.toTile(15.9))
        assertEquals(1, TileMap.toTile(16.0))
        assertEquals(-1, TileMap.toTile(-0.1))
        assertEquals(-1, TileMap.toTile(-16.0))
        assertEquals(-2, TileMap.toTile(-16.1))
    }

    @Test
    fun `tile coordinates convert to the world position of their top left corner`() {
        assertEquals(0.0, TileMap.toWorld(0))
        assertEquals(16.0, TileMap.toWorld(1))
        assertEquals(-16.0, TileMap.toWorld(-1))
    }

    @Test
    fun `map dimensions are exposed in world units`() {
        val map = TileMap(width = 4, height = 3)

        assertEquals(64.0, map.widthPx)
        assertEquals(48.0, map.heightPx)
    }
}
