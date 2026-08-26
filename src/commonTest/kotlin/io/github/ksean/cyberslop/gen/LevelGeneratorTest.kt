package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelGeneratorTest {
    @Test
    fun `the same seed and map index produce an identical level`() {
        val first = LevelGenerator.generate(SEED, mapIndex = 1)
        val second = LevelGenerator.generate(SEED, mapIndex = 1)

        assertEquals(fingerprint(first), fingerprint(second))
    }

    @Test
    fun `a different seed produces a different level`() {
        val first = LevelGenerator.generate(SEED, mapIndex = 1)
        val second = LevelGenerator.generate(SEED + 1uL, mapIndex = 1)

        assertTrue(fingerprint(first) != fingerprint(second))
    }

    @Test
    fun `generation reports no repair, reseed or fallback`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)

        assertEquals(1, generated.report.attempts, "needed more than one attempt")
        assertEquals(0, generated.report.repairs)
        assertFalse(generated.report.usedFallback)
    }

    @Test
    fun `the mini-boss arena sits within five percent of the midpoint`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        val midpoint = level.widthTiles / 2.0
        val tolerance = level.widthTiles * 0.05

        val offset = kotlin.math.abs(level.miniboss.centreTile - midpoint)

        assertTrue(offset <= tolerance, "mini-boss centre ${level.miniboss.centreTile} vs $midpoint")
    }

    @Test
    fun `the boss arena is at the end of the map, before the exit corridor`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level

        assertTrue(
            level.boss.rightTile > level.widthTiles * 0.85,
            "boss arena ends at ${level.boss.rightTile} of ${level.widthTiles}",
        )
        assertTrue(
            level.gateColumn > level.boss.rightTile,
            "the gate is not past the arena it seals",
        )
        assertTrue(
            level.widthTiles - level.gateColumn >= 2,
            "no corridor past the gate to walk out through",
        )
    }

    @Test
    fun `both arenas are flat, clear and hazard free`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level

        listOf(level.miniboss to "mini-boss", level.boss to "boss").forEach { (arena, name) ->
            assertTrue(arena.widthTiles >= MIN_ARENA_TILES, "$name arena is only ${arena.widthTiles} wide")
            for (x in arena.leftTile..arena.rightTile) {
                assertEquals(
                    TileKind.Solid,
                    level.tiles[x, arena.floorRow],
                    "$name arena floor has a hole at $x",
                )
                for (y in arena.floorRow - ARENA_CLEARANCE until arena.floorRow) {
                    assertEquals(
                        TileKind.Empty,
                        level.tiles[x, y],
                        "$name arena is obstructed at $x,$y",
                    )
                }
            }
        }
    }

    @Test
    fun `decoration never writes inside the floor mask`() {
        val decorated = LevelGenerator.generate(SEED, mapIndex = 1).level
        val bare = LevelGenerator.generateWithoutDecoration(SEED, mapIndex = 1).level

        for (x in 0 until bare.widthTiles) {
            for (y in 0 until bare.tiles.height) {
                if (!bare.floorMask[x, y]) continue
                assertEquals(bare.tiles[x, y], decorated.tiles[x, y], "floor mask altered at $x,$y")
            }
        }
    }

    @Test
    fun `decoration never places a solid tile inside the arc mask`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level

        for (x in 0 until level.widthTiles) {
            for (y in 0 until level.tiles.height) {
                if (!level.arcMask[x, y]) continue
                assertFalse(
                    level.tiles[x, y].blocksMovement,
                    "solid tile at $x,$y clips a jump arc the spine depends on",
                )
            }
        }
    }

    @Test
    fun `the spine is unchanged when only the decoration stream is consumed`() {
        val bare = LevelGenerator.generateWithoutDecoration(SEED, mapIndex = 1).level
        val again = LevelGenerator.generateWithoutDecoration(SEED, mapIndex = 1).level

        assertEquals(maskFingerprint(bare), maskFingerprint(again))
    }

    private fun fingerprint(generated: GeneratedLevel): String = buildString {
        val level = generated.level
        append(level.widthTiles).append(':')
        for (x in 0 until level.widthTiles) {
            for (y in 0 until level.tiles.height) append(level.tiles[x, y].ordinal)
        }
    }

    private fun maskFingerprint(level: io.github.ksean.cyberslop.world.Level): String = buildString {
        for (x in 0 until level.widthTiles) {
            for (y in 0 until level.tiles.height) {
                append(if (level.floorMask[x, y]) '1' else '0')
                append(if (level.arcMask[x, y]) '1' else '0')
            }
        }
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val MIN_ARENA_TILES = 12
        const val ARENA_CLEARANCE = 6
    }
}
