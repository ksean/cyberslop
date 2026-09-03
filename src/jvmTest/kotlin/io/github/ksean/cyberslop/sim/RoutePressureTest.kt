package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.LootFloor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P-39, route pressure: mean gross incoming damage per 100 tiles of width, over the seed cohort,
 * rises by thirds of the run — and the guaranteed loadout survives the route on every floor-covered
 * map on every cohort seed.
 */
class RoutePressureTest {
    @Test
    fun `route pressure rises by thirds of the run`() {
        val perMap = (1..10).map { mapIndex ->
            (1uL..COHORT).sumOf { seed ->
                val generated = LevelGenerator.generate(seed * SPREAD, mapIndex)
                PressureHarness.route(seed * SPREAD, generated).grossDamage / (generated.level.widthTiles / 100.0)
            } / COHORT.toDouble()
        }
        val thirds = listOf(perMap.subList(0, 3), perMap.subList(3, 6), perMap.subList(6, 10)).map { it.average() }
        println("route pressure per map: $perMap; thirds: $thirds")
        assertTrue(thirds[0] < thirds[1] && thirds[1] < thirds[2], "pressure by thirds is not rising: $thirds (per map $perMap)")
    }

    @Test
    fun `the guaranteed loadout survives the route on every floor-covered map on every seed`() {
        for (mapIndex in 1..LootFloor.furthestClearableMap()) {
            for (seed in 1uL..COHORT) {
                val generated = LevelGenerator.generate(seed * SPREAD, mapIndex)
                assertTrue(!PressureHarness.route(seed * SPREAD, generated).died, "map $mapIndex seed $seed: the guaranteed loadout died on the route")
            }
        }
    }

    /** Gate-2 finding: guaranteed-only means the optional caches on the route are not picked up either. */
    @Test
    fun `the route harness collects no optional loot`() {
        val generated = LevelGenerator.generate(SPREAD, 3)
        val outcome = PressureHarness.route(SPREAD, generated)
        assertTrue(outcome.sim.items.none { !it.isGuaranteedEquipment }, "the harness had optional loot on offer")
        assertTrue(PressureHarness.floorRun(SPREAD, 1).loadout.slots.totalStacks == 0, "the map-1 bot starts with a powerup")
    }

    /** Round-3 finding: the map-1 bot models the starter cache already; it must not also pick it up. */
    @Test
    fun `the map-one harness does not take the starter cache it already models`() {
        for (seed in 1uL..COHORT) {
            val outcome = PressureHarness.route(seed * SPREAD, LevelGenerator.generate(seed * SPREAD, 1))
            if (outcome.sim.miniboss.fight.defeated) continue
            assertTrue(outcome.sim.run.loadout.weapon.id == LootFloor.weaponArrivingAt(1).id, "seed $seed: the bot ended map 1 holding ${outcome.sim.run.loadout.weapon.name}")
        }
    }

    private companion object {
        const val COHORT = 8uL
        const val SPREAD = 0x9E3779B97F4A7C15uL
    }
}
