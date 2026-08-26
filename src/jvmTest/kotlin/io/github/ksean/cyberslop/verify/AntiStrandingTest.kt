package io.github.ksean.cyberslop.verify

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.physics.measureEnvelope
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reaching the boss is not enough. A level can be completable and still trap a player who steps off
 * the intended route into a pocket they cannot climb out of — and because a run persists across a
 * refresh, that soft-lock survives reloading.
 *
 * The invariant is deliberately asymmetric: the set of places the player *might* end up is computed
 * conservatively, and each of those must be provably escapable using only traversals the movement
 * model actually performs.
 */
class AntiStrandingTest {
    @Test
    fun `everywhere the player can reach, the player can leave`() {
        val apexTiles = (measureEnvelope(Physics.Default).apexPx / 16).toInt()

        for (mapIndex in 1..MAPS) {
            val level = LevelGenerator.generate(SEED, mapIndex).level
            val spawn = RestCell(level.spawnColumn, level.spawnRow - 1)
            val cells = Reachability.restCells(level)
            val edges = Reachability.underReachEdges(level, cells)
            val backward = Reachability.reversed(edges)

            val bossCells = cells.filter { level.boss.containsColumn(it.column) }
            val canReachBoss = bossCells
                .flatMap { Reachability.reachableFrom(it, backward) }
                .toSet()

            val possible = Reachability.overReach(level, spawn, apexTiles)
            val stranded = cells
                .filter { (it.column to it.row) in possible }
                .filter { it !in canReachBoss }
                // The arena and the exit corridor behind its gate are the goal, not pockets. The
                // approach to them is not exempt, so stranding on the way there still fails.
                .filterNot { level.boss.containsColumn(it.column) || it.column >= level.gateColumn }

            assertTrue(
                stranded.isEmpty(),
                "map $mapIndex has ${stranded.size} inescapable rest cells, " +
                    "first at ${stranded.firstOrNull()}",
            )
        }
    }

    @Test
    fun `the spawn point can reach the boss through sound traversals alone`() {
        for (mapIndex in 1..MAPS) {
            val level = LevelGenerator.generate(SEED, mapIndex).level
            val cells = Reachability.restCells(level)
            val edges = Reachability.underReachEdges(level, cells)
            val spawn = cells.first { it.column == level.spawnColumn && it.stance == Stance.Stand }

            val forward = Reachability.reachableFrom(spawn, edges)

            assertTrue(
                forward.any { it.column >= level.boss.leftTile },
                "map $mapIndex: the sound solver cannot get from spawn to the boss arena",
            )
            assertTrue(
                forward.any { level.miniboss.containsColumn(it.column) },
                "map $mapIndex: the sound solver cannot reach the mini-boss arena",
            )
        }
    }

    @Test
    fun `rest cells exist along the whole map, not just at the spawn`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        val cells = Reachability.restCells(level)

        val spread = cells.map { it.column }
        assertTrue(spread.min() < 10, "no rest cells near the spawn")
        assertTrue(
            spread.max() > level.widthTiles - 20,
            "no rest cells near the boss arena; ${TileMap.toWorld(spread.max())}",
        )
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val MAPS = 10
    }
}
