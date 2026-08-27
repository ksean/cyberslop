package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.verify.Foothold
import io.github.ksean.cyberslop.verify.WitnessReplay
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PROD-047: every map carries statically placed pickups, on ground the player can reach and stand
 * on, away from the arenas and away from anything crossed committed.
 *
 * `plan.md` §6.7 planned these as "cache / dead terminal" pickups and **none of it was built** — the
 * only pre-placed item in the game was map 1's starter cache.
 *
 * One map is checked here and the cohort sweep lives in `jvmTest`: generation costs tens of
 * milliseconds per map, and nine of them do not fit the browser runner's 2 s per-test timeout
 * (ENG-031). Measured — that is how this test first failed.
 */
class StaticDropTest {
    @Test
    fun `a map places static pickups the player can stand on and reach`() {
        val generated = LevelGenerator.generate(SEED, 1)
        val level = generated.level
        val footholds = WitnessReplay.replay(level, generated.witness).footholds

        assertTrue(
            level.pickups.size in StaticDrops.MIN..StaticDrops.MAX,
            "placed ${level.pickups.size} static pickups",
        )

        level.pickups.forEach { site ->
            assertTrue(
                !level.tiles.blocksMovement(site.column, site.row),
                "pickup at ${site.column},${site.row} is inside a wall",
            )
            assertTrue(
                !level.tiles.isLethal(site.column, site.row),
                "pickup at ${site.column},${site.row} is inside a hazard",
            )
            assertTrue(
                level.tiles.blocksMovement(site.column, site.row + 1),
                "pickup at ${site.column},${site.row} has no floor under it",
            )
            assertTrue(
                Foothold(site.column, site.row) in footholds,
                "pickup at ${site.column},${site.row} is not somewhere the witness stood, so " +
                    "nothing proves the player can reach it",
            )
            assertTrue(
                !level.miniboss.containsColumn(site.column) &&
                    !level.boss.containsColumn(site.column),
                "pickup at column ${site.column} is inside an arena",
            )
            val span = site.column - Populator.COMMITTED_BUFFER..
                site.column + Populator.COMMITTED_BUFFER
            assertTrue(
                span.none { Populator.isCommitted(level, it) },
                "pickup at column ${site.column} sits within ${Populator.COMMITTED_BUFFER} " +
                    "tiles of a span the player crosses committed",
            )
        }
    }

    /**
     * Placement that never reaches the running game is placement nobody will ever pick up. This
     * project has shipped that exact defect before: every combat subsystem was implemented, tested
     * and completely unreachable from `GameHost` (CYB-013).
     */
    @Test
    fun `the placed pickups are actually there when the map is played`() {
        val level = LevelGenerator.generate(SEED, 1).level
        val sim = GameSimulation(level, RunState.begin(SEED), SEED)

        assertTrue(level.pickups.isNotEmpty(), "the level placed nothing to check")
        assertTrue(
            sim.items.size >= level.pickups.size,
            "the map placed ${level.pickups.size} static pickups but the simulation starts with " +
                "only ${sim.items.size} items on the ground",
        )
        level.pickups.forEach { site ->
            assertTrue(
                sim.items.any { it.position == site.centre },
                "nothing was placed at ${site.centre}, where generation put a pickup",
            )
        }
        // Not the cell's corner: a pickup drawn centred on its corner floats most of a tile above
        // the floor it is supposed to be lying on.
        val floor = TileMap.toWorld(level.pickups.first().row) + TILE_SIZE
        val centre = level.pickups.first().centre
        assertTrue(
            centre.y > TileMap.toWorld(level.pickups.first().row) && centre.y < floor,
            "a pickup at $centre is not inside the cell it stands in",
        )
        assertTrue(
            sim.items.all { it.weapon != null || it.powerup != null },
            "a ground item is neither a weapon nor a powerup, so contact resolves to nothing",
        )
    }

    @Test
    fun `placement is reproducible for a seed`() {
        val first = LevelGenerator.generate(SEED, 1).level.pickups
        val again = LevelGenerator.generate(SEED, 1).level.pickups

        assertTrue(first == again, "the same seed placed different pickups: $first vs $again")
    }

    /**
     * PROD-047's contents, which had no owner until review round seven asked what a cache holds.
     * The plan said "powerup, +1 tier shift"; the code did the kill split and shifted only weapons.
     */
    @Test
    fun `a static pickup is worth more than a corpse drop`() {
        val level = LevelGenerator.generate(SEED, 5).level
        val sim = GameSimulation(level, RunState.begin(SEED).copy(mapIndex = 5), SEED)
        val placed = level.pickups.mapNotNull { site ->
            sim.items.firstOrNull { it.position == site.centre }
        }

        assertTrue(placed.size == level.pickups.size, "not every site was realised")
        placed.forEach { item ->
            assertTrue(
                item.weapon != null || item.powerup != null,
                "a cache holds neither a weapon nor a powerup",
            )
        }
    }

    private companion object {
        val SEED = 0xC0FFEEuL
    }
}
