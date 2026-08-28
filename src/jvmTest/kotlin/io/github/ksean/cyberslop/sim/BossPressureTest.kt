package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.LootFloor
import kotlin.test.Test
import kotlin.test.assertTrue

/** P-39, boss pressure: on the maps the loot floor covers, the guaranteed loadout wins on every cohort seed. */
class BossPressureTest {
    @Test
    fun `the guaranteed loadout wins the boss fight on every floor-covered map on every seed`() {
        for (mapIndex in 1..LootFloor.furthestClearableMap()) {
            for (seed in 1uL..COHORT) {
                val generated = LevelGenerator.generate(seed * SPREAD, mapIndex)
                val route = PressureHarness.route(seed * SPREAD, generated)
                assertTrue(!route.died, "map $mapIndex seed $seed: died before the boss")
                assertTrue(PressureHarness.fight(route.sim), "map $mapIndex seed $seed: the boss was not beaten (player health ${route.sim.run.health})")
            }
        }
    }

    private companion object {
        const val COHORT = 8uL
        const val SPREAD = 0x9E3779B97F4A7C15uL
    }
}
