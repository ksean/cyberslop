package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.verify.WitnessReplay
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The completability guarantee, swept across seeds. JVM-only: this is far beyond what the browser
 * test runner's 2000 ms per-test limit allows, and it is exactly the work the verification target
 * exists for (ENG-031).
 *
 * A failure here is a **generator bug**, not an unlucky seed. The spine is built from moves clamped
 * to the measured envelope and performed by the movement model as it is carved, so a map that
 * cannot be crossed means the construction broke — which is why zero reseeds is the assertion
 * rather than some acceptable rate.
 */
class SeedCohortTest {
    @Test
    fun `every seed and map produces a witness that reaches the boss alive`() {
        var checked = 0
        forEachSeedAndMap { generated, label ->
            val result = WitnessReplay.replay(generated.level, generated.witness)

            assertTrue(result.reachedMiniboss, "$label: never transited the mini-boss arena")
            assertTrue(result.reachedBoss, "$label: stopped at x=${result.finalState.x}")
            assertFalse(result.touchedLethal, "$label: witness took lethal damage")
            checked++
        }
        assertEquals(COHORT * MAPS, checked)
    }

    @Test
    fun `no seed needs a reseed, repair or fallback`() {
        forEachSeedAndMap { generated, label ->
            assertEquals(
                1,
                generated.report.attempts,
                "$label needed ${generated.report.attempts} attempts: " +
                    generated.report.discarded.joinToString("; "),
            )
            assertEquals(0, generated.report.repairs, "$label needed repair")
            assertFalse(generated.report.usedFallback, "$label fell back")
        }
    }

    @Test
    fun `arenas are always flat, clear and hazard free`() {
        forEachSeedAndMap { generated, label ->
            val level = generated.level
            listOf(level.miniboss to "mini-boss", level.boss to "boss").forEach { (arena, name) ->
                for (x in arena.leftTile..arena.rightTile) {
                    assertEquals(
                        TileKind.Solid, level.tiles[x, arena.floorRow],
                        "$label $name floor hole at $x",
                    )
                    for (y in arena.floorRow - CLEARANCE until arena.floorRow) {
                        assertFalse(
                            level.tiles[x, y].blocksMovement,
                            "$label $name obstructed at $x,$y",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `no solid tile ever lands inside a jump arc`() {
        forEachSeedAndMap { generated, label ->
            val level = generated.level
            for (x in 0 until level.widthTiles) {
                for (y in 0 until level.tiles.height) {
                    if (!level.arcMask[x, y]) continue
                    assertFalse(
                        level.tiles[x, y].blocksMovement,
                        "$label: solid tile at $x,$y clips the spine's own path",
                    )
                }
            }
        }
    }

    @Test
    fun `every fire jet corridor is crossable from its safe zone`() {
        var jetsSeen = 0
        forEachSeedAndMap { generated, label ->
            val level = generated.level
            level.jets.forEachIndexed { index, jet ->
                jetsSeen++
                assertTrue(
                    jet.offWindowSeconds > 0.0,
                    "$label jet $index is never off",
                )
                assertEquals(
                    1,
                    level.jets.count { it.column == jet.column },
                    "$label has two jets sharing column ${jet.column}",
                )
            }
        }
        assertTrue(jetsSeen > 0, "no jets were generated, so this asserts nothing")
    }

    private fun forEachSeedAndMap(check: (GeneratedLevel, String) -> Unit) {
        cohort.forEach { (generated, label) -> check(generated, label) }
    }

    private companion object {
        const val COHORT = 40
        const val MAPS = 10
        const val CLEARANCE = 6
        val BASE_SEED = 0x5EEDuL
        val STRIDE = 0x9E3779B97F4A7C15uL

        /**
         * Generated once and shared. Each assertion below examines a different property of the same
         * cohort, so regenerating per test multiplied a 30-second sweep by the number of tests for
         * no additional coverage.
         */
        val cohort: List<Pair<GeneratedLevel, String>> by lazy {
            buildList {
                for (seedIndex in 0 until COHORT) {
                    val seed = BASE_SEED + seedIndex.toULong() * STRIDE
                    for (mapIndex in 1..MAPS) {
                        add(LevelGenerator.generate(seed, mapIndex) to "seed $seed map $mapIndex")
                    }
                }
            }
        }
    }
}
