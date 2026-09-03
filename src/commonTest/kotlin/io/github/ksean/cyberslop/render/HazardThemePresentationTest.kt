package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PROD-114 and P-93: trap bodies follow the map palette while barrel fire stays flame-coloured. */
class HazardThemePresentationTest {
    @Test
    fun `spikes and barrel bodies use every map theme while spike blades stay filled`() {
        ThemeId.entries.forEachIndexed { index, theme ->
            val frame = frame(theme, index + 1)
            val hazardBatches = frame.batches.filter { it.layer == Layer.Hazard }
            val palette = Palettes.of(theme)

            assertEquals(
                setOf(palette.theme, palette.tileEdge),
                hazardBatches.filter { it.primitive == Primitive.Rect }.map { it.style }.toSet(),
                "$theme did not theme the spike base, barrel drum and barrel bands",
            )
            assertEquals(
                setOf(palette.theme),
                hazardBatches.filter { it.primitive == Primitive.Triangle }.map { it.style }.toSet(),
                "$theme did not theme the filled spike blades",
            )
            assertEquals(
                SPIKE_BLADES,
                hazardBatches.filter { it.primitive == Primitive.Triangle }.sumOf { it.size },
                "$theme did not draw three filled spike blades",
            )
            assertEquals(
                setOf(FIRE_OUTER, FIRE_CORE),
                hazardBatches.filter { it.primitive == Primitive.Segment }.map { it.style }.toSet(),
                "$theme recoloured the barrel flame or retained outlined spike blades",
            )
        }
    }

    @Test
    fun `more themed traps reuse the same body blade band and flame batches`() {
        val theme = ThemeId.NeonSlums
        val one = hazardBatchKeys(frame(theme, mapIndex = 5))
        val many = hazardBatchKeys(
            frame(
                theme,
                mapIndex = 5,
                spikeColumns = SPIKE_COLUMN..SPIKE_COLUMN + 2,
                barrels = listOf(BARREL, BARREL.copy(column = BARREL.column + 2)),
            ),
        )

        assertEquals(one, many)
        assertTrue(many.isNotEmpty())
    }

    private fun frame(
        theme: ThemeId,
        mapIndex: Int,
        spikeColumns: IntRange = SPIKE_COLUMN..SPIKE_COLUMN,
        barrels: List<Barrel> = listOf(BARREL),
    ): DrawList {
        val sim = GameSimulation(
            TestLevels.flat(
                spikeColumns = spikeColumns,
                barrels = barrels,
                mapIndex = mapIndex,
                theme = theme,
            ),
            io.github.ksean.cyberslop.run.RunState.begin(SEED).copy(mapIndex = mapIndex),
            SEED,
        )
        return Scene.compose(
            sim,
            CAMERA,
            Backdrops.of(SEED, sim.level),
            HudModel.of(sim),
            timeSeconds = 0.0,
            builder = SceneBuilder(),
        )
    }

    private fun hazardBatchKeys(frame: DrawList): Set<BatchKey> = frame.batches
        .filter { it.layer == Layer.Hazard }
        .map { BatchKey(it.style, it.primitive, it.width) }
        .toSet()

    private data class BatchKey(val style: String, val primitive: Primitive, val width: Double)

    private companion object {
        const val SEED = 0x7A2FuL
        const val FIRE_OUTER = "#ff5a1f"
        const val FIRE_CORE = "#ffd166"
        const val SPIKE_BLADES = 3
        const val SPIKE_COLUMN = 6
        val BARREL = Barrel(9, TestLevels.FLOOR_ROW)
        val CAMERA = Camera(0.0, 180.0, 240.0, 130.0)
    }
}
