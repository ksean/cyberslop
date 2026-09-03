package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.verify.Witness
import io.github.ksean.cyberslop.verify.WitnessStep
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/** ENG-036: one generation per key, with isolated mutable level state for every caller. */
class GeneratedLevelCorpusTest {
    @Test
    fun `a cached key is generated once and every result owns its mutable grids`() {
        val source = fixture()
        var calls = 0
        val corpus = GeneratedLevelCorpus { _, _ ->
            calls++
            source
        }

        val first = corpus.generated(SEED, MAP)
        first.level.tiles[COLUMN, ROW] = TileKind.Acid
        first.level.floorMask[COLUMN, ROW] = false
        first.level.arcMask[COLUMN, ROW] = false
        first.level.committedColumns[COLUMN] = !first.level.committedColumns[COLUMN]
        val second = corpus.generated(SEED, MAP)

        assertEquals(1, calls)
        assertEquals(1, corpus.generationCount)
        assertEquals(1, corpus.cachedKeyCount)
        assertEquals(TileKind.Spikes, second.level.tiles[COLUMN, ROW])
        assertEquals(true, second.level.floorMask[COLUMN, ROW])
        assertEquals(true, second.level.arcMask[COLUMN, ROW])
        assertNotSame(first.level.tiles, second.level.tiles)
        assertNotSame(first.level.floorMask, second.level.floorMask)
        assertNotSame(first.level.arcMask, second.level.arcMask)
        assertNotSame(first.level.committedColumns, second.level.committedColumns)
        assertNotSame(first.witness.steps, second.witness.steps)

        val levelOnly = corpus.level(SEED, MAP)
        levelOnly.tiles[COLUMN, ROW] = TileKind.Empty
        assertEquals(1, calls)
        assertEquals(TileKind.Spikes, corpus.level(SEED, MAP).tiles[COLUMN, ROW])

        corpus.generated(SEED, MAP + 1)
        assertEquals(2, calls)
        assertEquals(2, corpus.generationCount)
        assertEquals(2, corpus.cachedKeyCount)
    }

    private fun fixture(): GeneratedLevel {
        val level = TestLevels.flat()
        level.tiles[COLUMN, ROW] = TileKind.Spikes
        level.floorMask[COLUMN, ROW] = true
        level.arcMask[COLUMN, ROW] = true
        return GeneratedLevel(
            level,
            Witness(listOf(WitnessStep(listOf(InputFrame(right = true))))),
            GenerationReport(attempts = 1, repairs = 0, usedFallback = false),
        )
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val MAP = 4
        const val COLUMN = 4
        const val ROW = 4
    }
}
