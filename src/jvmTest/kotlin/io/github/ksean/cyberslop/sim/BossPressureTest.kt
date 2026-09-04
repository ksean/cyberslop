package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.LootFloor
import kotlin.test.Test
import kotlin.test.assertTrue

/** P-39, boss pressure: on the maps the loot floor covers, the guaranteed loadout wins on every cohort seed. */
class BossPressureTest {
    @Test
    fun `the guaranteed loadout wins the boss fight on every floor-covered map on every seed`() {
        var countedAttacks = 0
        var dodgedAttacks = 0
        var routeCountedAttacks = 0
        var routeDodgedAttacks = 0
        for (mapIndex in 1..LootFloor.furthestClearableMap()) {
            for (seed in 1uL..COHORT) {
                val generated = LevelGenerator.generate(seed * SPREAD, mapIndex)
                val route = PressureHarness.survivalRoute(seed * SPREAD, generated)
                assertTrue(
                    !route.died,
                    "map $mapIndex seed $seed: died before the boss at ${route.sim.player} " +
                        "after ${route.sim.grossDamageTaken} gross damage; " +
                        "dodged ${route.dodges.dodged}/${route.dodges.counted}",
                )
                // The route pinned the mini-boss award to the floor's weakest, so a route that took
                // it arrives holding exactly what the floor models; one that walked past the
                // mini-boss, or past its award lying in the arena, is put on the same footing
                // before the fight — the floor assumes every guaranteed award is taken.
                val awardTaken = route.sim.miniboss.fight.defeated &&
                    route.sim.items.none { it.isGuaranteedEquipment && it.equipmentPayload?.weapon != null }
                if (awardTaken) {
                    assertTrue(route.sim.run.loadout.weapon.id == LootFloor.weaponAt(mapIndex).id, "map $mapIndex seed $seed: the route ended holding ${route.sim.run.loadout.weapon.name}, not the floor's")
                    assertTrue(route.sim.run.loadout.slots.held == LootFloor.slotsAt(mapIndex).held, "map $mapIndex seed $seed: the route ended with ${route.sim.run.loadout.slots.held}")
                }
                routeCountedAttacks += route.dodges.counted
                routeDodgedAttacks += route.dodges.dodged
                TestLevels.isolateMainBoss(route.sim)
                PressureHarness.holdFloor(route.sim, mapIndex)
                assertTrue(route.sim.run.loadout.weapon.id == LootFloor.weaponAt(mapIndex).id, "map $mapIndex: not holding the floor weapon")
                assertTrue(route.sim.run.loadout.slots.held == LootFloor.slotsAt(mapIndex).held, "map $mapIndex: not holding the floor slots")
                val healthBeforeFight = route.sim.run.health
                val grossBeforeFight = route.sim.grossDamageTaken
                val playerBeforeFight = route.sim.player
                assertTrue(
                    PressureHarness.fight(route.sim, route.dodges),
                    "map $mapIndex seed $seed: the boss was not beaten " +
                        "(health $healthBeforeFight -> ${route.sim.run.health}, " +
                        "fight damage ${route.sim.grossDamageTaken - grossBeforeFight}, " +
                        "player $playerBeforeFight -> ${route.sim.player}, arena ${route.sim.level.boss})",
                )
                countedAttacks += route.dodges.counted
                dodgedAttacks += route.dodges.dodged
            }
        }
        assertTrue(countedAttacks > 0, "the cohort observed no incoming attack activations")
        assertTrue(
            dodgedAttacks.toDouble() / countedAttacks >= 0.90,
            "the policy dodged $dodgedAttacks of $countedAttacks incoming attack activations " +
                "(completed on route $routeDodgedAttacks/$routeCountedAttacks)",
        )
    }

    private companion object {
        const val COHORT = 8uL
        const val SPREAD = 0x9E3779B97F4A7C15uL
    }
}
