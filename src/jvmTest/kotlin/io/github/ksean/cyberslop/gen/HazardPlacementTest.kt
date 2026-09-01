package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.verify.Foothold
import io.github.ksean.cyberslop.verify.WitnessReplay
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P-36 (`specs/hazards.md`): damaging hazards stand off the proven route, off the arc mask, out of
 * the arenas and away from pickups; the confirming replay reports no contact; and the confirming
 * pass really removes what the tape touches — shown by putting a hazard on the route by hand.
 */
class HazardPlacementTest {
    @Test
    fun `every hazard cell on every map is clear of the route, the mask, the arenas and the pickups`() {
        var checked = 0
        for (seed in 1uL..COHORT) {
            for (mapIndex in 1..10) {
                val generated = LevelGenerator.generate(seed * SPREAD, mapIndex)
                val level = generated.level
                val replay = WitnessReplay.replay(level, generated.witness)
                val label = "map $mapIndex seed $seed"

                assertEquals(0, replay.damagingContacts, "$label: the route touches a damaging hazard")

                footprints(level).forEach { cell ->
                    checked++
                    assertTrue(
                        cell.column <= level.gateColumn,
                        "$label: hazard cell $cell lies beyond completion column ${level.gateColumn}",
                    )
                    assertTrue(
                        replay.footholds.none { chebyshev(it, cell) < HazardPlacer.CLEARANCE },
                        "$label: hazard cell $cell is within ${HazardPlacer.CLEARANCE} of a foothold",
                    )
                    assertTrue(!level.arcMask[cell.column, cell.row], "$label: hazard cell $cell is in the arc mask")
                    assertTrue(
                        !level.miniboss.containsColumn(cell.column) && !level.boss.containsColumn(cell.column) &&
                            cell.column !in level.miniboss.leftTile - HazardPlacer.RAMP_TILES until level.miniboss.leftTile &&
                            cell.column !in level.boss.leftTile - HazardPlacer.RAMP_TILES until level.boss.leftTile,
                        "$label: hazard cell $cell is in an arena or its entry ramp",
                    )
                    assertTrue(
                        level.pickups.none { chebyshev(Foothold(it.column, it.row), cell) < HazardPlacer.CLEARANCE },
                        "$label: hazard cell $cell is within ${HazardPlacer.CLEARANCE} of a pickup",
                    )
                }
            }
        }
        assertTrue(checked > MIN_SAMPLE, "only $checked hazard cells checked")
    }

    @Test
    fun `the confirming pass removes a hazard injected onto the route and nothing else`() {
        val generated = LevelGenerator.generate(SEED, 8)
        val level = generated.level
        val footholds = WitnessReplay.replay(level, generated.witness).footholds.sortedWith(compareBy({ it.column }, { it.row }))
        assertTrue(Hazards.spikeCells(level).isNotEmpty() && level.barrels.isNotEmpty(), "fixture: map 8 placed too few hazards")

        val spikeAt = footholds.first { it.column > level.spawnColumn + 20 }
        val barrelAt = footholds.last { it.column < level.miniboss.leftTile - 20 }
        val spikesBefore = Hazards.spikeCells(level)
        val barrelsBefore = level.barrels
        level.tiles[spikeAt.column, spikeAt.row] = TileKind.Spikes
        val injected = Barrel(barrelAt.column, barrelAt.row)

        val kept = HazardPlacer.confirm(level, barrelsBefore + injected, generated.witness)
        val keptAgain = HazardPlacer.confirm(level, barrelsBefore + injected, generated.witness)

        assertEquals(TileKind.Empty, level.tiles[spikeAt.column, spikeAt.row], "the injected spike survived the confirming pass")
        assertTrue(injected !in kept, "the injected barrel survived the confirming pass")
        assertEquals(spikesBefore, Hazards.spikeCells(level), "the confirming pass removed spikes the route never touched")
        assertEquals(barrelsBefore, kept, "the confirming pass removed barrels the route never touched")
        assertEquals(kept, keptAgain, "the confirming pass is not deterministic")
    }

    @Test
    fun `the confirming pass removes fault injected hazards beyond the completion point`() {
        val generated = LevelGenerator.generate(SEED, 8)
        val level = generated.level
        val column = level.gateColumn + 1
        val row = (0 until level.tiles.height - 1).first { candidate ->
            level.tiles[column, candidate] == TileKind.Empty &&
                level.tiles[column, candidate + 1] == TileKind.Solid
        }
        level.tiles[column, row] = TileKind.Spikes
        val barrel = Barrel(column, row)

        val kept = HazardPlacer.confirm(level, level.barrels + barrel, generated.witness)

        assertEquals(TileKind.Empty, level.tiles[column, row])
        assertTrue(barrel !in kept)
    }

    @Test
    fun `there are no damaging hazards on map one and more of them as the maps go on`() {
        val means = (1..10).map { mapIndex ->
            (1uL..COHORT).sumOf { seed -> Hazards.count(LevelGenerator.generate(seed * SPREAD, mapIndex).level) }.toDouble() / COHORT.toDouble()
        }
        assertEquals(0.0, means[0], "map 1 carries damaging hazards")
        means.zipWithNext().forEachIndexed { index, (earlier, later) ->
            assertTrue(later > earlier, "map ${index + 2} averages $later hazards against map ${index + 1}'s $earlier: $means")
        }
    }

    private fun footprints(level: Level): List<Foothold> =
        Hazards.spikeCells(level).map { Foothold(it.column, it.row) } +
            level.barrels.flatMap { listOf(Foothold(it.column, it.row), Foothold(it.column, it.flameRow)) }

    private fun chebyshev(a: Foothold, b: Foothold): Int = maxOf(abs(a.column - b.column), abs(a.row - b.row))

    private companion object {
        const val COHORT = 20uL
        const val SPREAD = 0x9E3779B97F4A7C15uL
        val SEED = 0xC0FFEEuL
        const val MIN_SAMPLE = 200
    }
}
