package io.github.ksean.cyberslop.entity

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.gen.Populator
import io.github.ksean.cyberslop.gen.DifficultyCurve
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enemies are excluded from the completability guarantee, so what protects the route is these
 * invariants rather than the witness. They are swept across seeds because a single map proves very
 * little about a placement rule driven by chance.
 */
class PopulationInvariantTest {
    @Test
    fun `complete patrols stay outside the player start exclusion without reducing population`() {
        forEachLevel { level, label ->
            val expected = (level.widthTiles / 100.0 * DifficultyCurve.at(level.mapIndex).enemiesPerHundredTiles)
                .toInt()
                .coerceIn(8, 72)
            assertTrue(level.enemies.size == expected, "$label: ${level.enemies.size} enemies against target $expected")
            level.enemies.forEach { spawn ->
                assertTrue(
                    Populator.isClearOfStart(level, spawn),
                    "$label: ${spawn.archetype} patrol ${spawn.leftTile}..${spawn.rightTile} enters " +
                        "start exclusion ${level.spawnColumn - Populator.START_CLEAR_TILES}.." +
                        "${level.spawnColumn + Populator.START_CLEAR_TILES}",
                )
            }
        }
    }

    @Test
    fun `start exclusion endpoints are rejected and the next columns are accepted`() {
        val level = LevelGenerator.generate(BASE, 1).level
        val sample = level.enemies.first()
        val left = level.spawnColumn - Populator.START_CLEAR_TILES
        val right = level.spawnColumn + Populator.START_CLEAR_TILES

        assertTrue(!Populator.isClearOfStart(level, sample.copy(column = left - sample.patrolTiles)))
        assertTrue(Populator.isClearOfStart(level, sample.copy(column = left - sample.patrolTiles - 1)))
        assertTrue(!Populator.isClearOfStart(level, sample.copy(column = right + sample.patrolTiles)))
        assertTrue(Populator.isClearOfStart(level, sample.copy(column = right + sample.patrolTiles + 1)))
    }

    @Test
    fun `no patrol sits on a span the player crosses committed`() {
        forEachLevel { level, label ->
            level.enemies.forEach { spawn ->
                assertTrue(
                    Populator.isClearOfCommittedSpans(level, spawn),
                    "$label: ${spawn.archetype} at ${spawn.column} patrols across a committed span",
                )
            }
        }
    }

    @Test
    fun `enemies do stand on the route, because meeting them is the game`() {
        // The point of the narrower invariant: an enemy on ordinary ground is fine. If none ever
        // were, the rule would be back to excluding the whole map.
        var onRoute = 0
        forEachLevel { level, _ ->
            level.enemies.forEach { spawn ->
                if (level.arcMask[spawn.column, spawn.row]) onRoute++
            }
        }
        assertTrue(onRoute > 0, "no enemy is anywhere the player walks")
    }

    @Test
    fun `nothing that shoots has a clear line into a committed span`() {
        forEachLevel { level, label ->
            level.enemies.filter { it.archetype.shoots }.forEach { spawn ->
                assertTrue(
                    !Populator.seesCommittedSpan(level, spawn),
                    "$label: ${spawn.archetype} at ${spawn.column} can shoot a committed jump",
                )
            }
        }
    }

    @Test
    fun `maps are actually populated`() {
        var total = 0
        forEachLevel { level, _ -> total += level.enemies.size }
        assertTrue(total > 0, "no enemies were placed anywhere; the invariants prove nothing")
    }

    @Test
    fun `every archetype appears somewhere`() {
        val seen = mutableSetOf<EnemyArchetype>()
        forEachLevel { level, _ -> level.enemies.forEach { seen.add(it.archetype) } }
        assertTrue(seen.size >= 3, "only $seen were ever placed")
    }

    private fun forEachLevel(check: (io.github.ksean.cyberslop.world.Level, String) -> Unit) {
        for (seedIndex in 0 until SEEDS) {
            val seed = BASE + seedIndex.toULong() * STRIDE
            for (mapIndex in 1..10) {
                check(LevelGenerator.generate(seed, mapIndex).level, "seed $seed map $mapIndex")
            }
        }
    }

    /** `specs/enemies.md`: at most 35 % ranged and at least three archetypes on **every** map. */
    @Test
    fun `every map is mostly melee and holds at least three archetypes`() {
        for (seed in 1uL..12uL) for (mapIndex in 1..10) {
            val level = LevelGenerator.generate(seed * 0x9E3779B97F4A7C15uL, mapIndex).level
            val ranged = level.enemies.count { it.archetype.shoots }
            assertTrue(ranged <= level.enemies.size * Populator.MAX_RANGED_SHARE, "map $mapIndex seed $seed: $ranged of ${level.enemies.size} are ranged")
            val kinds = level.enemies.map { it.archetype }.toSet()
            assertTrue(kinds.size >= Populator.MIN_ARCHETYPES, "map $mapIndex seed $seed: only $kinds")
        }
    }

    /**
     * The route ends at an arena the player must fight in; anything standing in it, or on the ramp
     * the witness walks in on, arrives at the boss with the player (`specs/enemies.md`, Population).
     */
    @Test
    fun `no spawn stands in an arena or on the approach to one`() {
        for (seed in 1uL..12uL) for (mapIndex in 1..10) {
            val level = LevelGenerator.generate(seed * 0x9E3779B97F4A7C15uL, mapIndex).level
            val keepOut = listOf(
                level.miniboss.leftTile - Populator.ARENA_APPROACH_TILES..level.miniboss.rightTile,
                level.boss.leftTile - Populator.ARENA_APPROACH_TILES..level.widthTiles,
            )
            level.enemies.forEach { spawn ->
                assertTrue(
                    keepOut.none { spawn.leftTile in it || spawn.rightTile in it },
                    "map $mapIndex seed $seed: ${spawn.archetype} patrols ${spawn.leftTile}..${spawn.rightTile} inside an arena or its approach $keepOut",
                )
            }
        }
    }

    private companion object {
        const val SEEDS = 8
        val BASE = 0xE4E4uL
        val STRIDE = 0x9E3779B97F4A7C15uL
    }
}
