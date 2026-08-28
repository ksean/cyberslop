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
                // The route pinned the mini-boss award to the floor's weakest, so a route that took
                // it arrives holding exactly what the floor models; one that walked past the
                // mini-boss, or past its award lying in the arena, is put on the same footing
                // before the fight — the floor assumes every guaranteed award is taken.
                val awardTaken = route.sim.miniboss.fight.defeated && route.sim.items.none { it.guaranteed && it.weapon != null }
                if (awardTaken) {
                    assertTrue(route.sim.run.loadout.weapon.id == LootFloor.weaponAt(mapIndex).id, "map $mapIndex seed $seed: the route ended holding ${route.sim.run.loadout.weapon.name}, not the floor's")
                    assertTrue(route.sim.run.loadout.slots.held == LootFloor.slotsAt(mapIndex).held, "map $mapIndex seed $seed: the route ended with ${route.sim.run.loadout.slots.held}")
                }
                PressureHarness.holdFloor(route.sim, mapIndex)
                assertTrue(route.sim.run.loadout.weapon.id == LootFloor.weaponAt(mapIndex).id, "map $mapIndex: not holding the floor weapon")
                assertTrue(route.sim.run.loadout.slots.held == LootFloor.slotsAt(mapIndex).held, "map $mapIndex: not holding the floor slots")
                assertTrue(PressureHarness.fight(route.sim), "map $mapIndex seed $seed: the boss was not beaten (player health ${route.sim.run.health})")
            }
        }
    }

    private companion object {
        const val COHORT = 8uL
        const val SPREAD = 0x9E3779B97F4A7C15uL
    }
}
