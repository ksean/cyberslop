package io.github.ksean.cyberslop.entity

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.gen.Populator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enemies are excluded from the completability guarantee, so what protects the route is these
 * invariants rather than the witness. They are swept across seeds because a single map proves very
 * little about a placement rule driven by chance.
 */
class PopulationInvariantTest {
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

    private companion object {
        const val SEEDS = 8
        val BASE = 0xE4E4uL
        val STRIDE = 0x9E3779B97F4A7C15uL
    }
}
