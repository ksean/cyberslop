package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Both of these come from a human walking the level, and neither was visible to any existing test.
 *
 * Enemies were placed only where the player's route did not reach — which, in a side-scroller, is
 * almost nowhere, so every one of them ended up in the far end of the boss arena. And past that
 * arena there was no floor at all, so reaching the right-hand edge of the map was a pit rather than
 * an exit: the player died on arrival instead of finishing.
 */
class LevelLayoutTest {
    @Test
    fun `enemies are spread across the map rather than pooled at the end`() {
        // Aggregated over seeds: one map is a few dozen samples in three buckets, and asking each
        // bucket to be non-empty there would fail on ordinary randomness rather than on a defect.
        // The defect this guards against was systematic — every enemy on every map at the far end.
        (1..10).forEach { map ->
            val thirds = IntArray(3)
            var total = 0
            repeat(COHORT) { index ->
                val level = GeneratedLevels.level(SEED + index.toULong() * STRIDE, map)
                // Thirds of the ground a spawn may stand on: nothing stands on the boss's ground
                // (the arena, its approach and the exit corridor), so the raw map's last third
                // would be short by rule rather than by pooling.
                val spawnable = level.boss.leftTile - Populator.ARENA_APPROACH_TILES
                level.enemies.forEach { spawn ->
                    thirds[(spawn.column * 3 / spawnable).coerceIn(0, 2)]++
                    total++
                }
            }

            assertTrue(total >= COHORT * 8, "map $map placed only $total enemies across $COHORT seeds")
            thirds.forEachIndexed { index, count ->
                assertTrue(
                    count >= total * MIN_SHARE,
                    "map $map puts only $count of $total enemies in third $index: ${thirds.toList()}",
                )
            }
        }
    }

    @Test
    fun `the ground runs all the way to the right-hand edge`() {
        (1..10).forEach { map ->
            val level = GeneratedLevels.level(SEED, map)
            for (column in level.boss.rightTile + 1 until level.widthTiles) {
                assertTrue(
                    (0 until level.tiles.height).any { level.tiles[column, it] == TileKind.Solid },
                    "map $map has no floor at column $column; the exit is a pit",
                )
            }
        }
    }

    @Test
    fun `an exit corridor exists past the boss arena`() {
        (1..10).forEach { map ->
            val level = GeneratedLevels.level(SEED, map)
            assertTrue(
                level.widthTiles - level.boss.rightTile > MIN_EXIT_TILES,
                "map $map leaves only ${level.widthTiles - level.boss.rightTile} tiles past the arena",
            )
        }
    }

    @Test
    fun `a gate seals the exit while the boss is alive`() {
        (1..10).forEach { map ->
            val level = GeneratedLevels.level(SEED, map)
            assertTrue(
                (0 until level.tiles.height).any {
                    level.tiles.blocksMovement(level.gateColumn, it)
                },
                "map $map has no gate: the exit is open with the boss alive",
            )
        }
    }

    @Test
    fun `the witness still arrives with the gate in place`() {
        (1..10).forEach { map ->
            val generated = GeneratedLevels.generated(SEED, map)
            val result = io.github.ksean.cyberslop.verify.WitnessReplay
                .replay(generated.level, generated.witness)
            assertTrue(result.reachedBoss, "map $map: the gate broke the witness")
        }
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        val STRIDE = 0x9E3779B97F4A7C15uL
        const val COHORT = 12
        const val MIN_SHARE = 0.20
        const val MIN_EXIT_TILES = 6
    }
}
