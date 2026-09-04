package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.LootFloor
import io.github.ksean.cyberslop.verify.WitnessReplay
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two different claims about the intended route, kept apart because they are different claims.
 *
 * As geometry it must be crossable on every map — that is PROD-024 and it holds everywhere. In the
 * full simulation it must also be *survivable*, which brings in enemies and is therefore a balance
 * question. A human playtest is what surfaced the gap: enemies were placed uniformly across five
 * archetypes, so 40% of every map shot at the player, and a guaranteed loadout following the route
 * died to accumulated chip damage on map four.
 */
class RouteSurvivalTest {
    @Test
    fun `the route is crossable as geometry on every map`() {
        (1..MAPS).forEach { map ->
            val generated = LevelGenerator.generate(SEED, map)
            val result = WitnessReplay.replay(generated.level, generated.witness)

            assertFalse(result.touchedLethal, "map $map: the route crosses something lethal")
            assertTrue(result.reachedBoss, "map $map: the route does not reach the boss arena")
        }
    }

    @Test
    fun `a guaranteed loadout survives the route on the maps that floor covers`() {
        (1..LootFloor.furthestClearableMap()).forEach { map ->
            assertFalse(diesOnTheRoute(map), "map $map: the guaranteed loadout died on the route")
        }
    }

    @Test
    fun `most of what the player meets is not shooting at them`() {
        (1..MAPS).forEach { map ->
            val level = LevelGenerator.generate(SEED, map).level
            val shooters = level.enemies.count { it.archetype.shoots }

            assertTrue(
                shooters <= level.enemies.size * MAX_SHOOTER_SHARE,
                "map $map is $shooters of ${level.enemies.size} ranged",
            )
        }
    }

    private fun diesOnTheRoute(mapIndex: Int): Boolean {
        val generated = LevelGenerator.generate(SEED, mapIndex)
        return PressureHarness.survivalRoute(SEED, generated).died
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val MAPS = 10
        const val MAX_SHOOTER_SHARE = 0.35
    }
}
