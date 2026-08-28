package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import kotlin.test.Test
import kotlin.test.assertTrue

/** The starter cache's guarantee over a seed sweep; one seed of it runs on both targets. */
class LootFloorCohortTest {
    @Test
    fun `the starter cache never holds the starting weapon on any seed`() {
        for (seed in 1uL..120uL) {
            val sim = GameSimulation(LevelGenerator.generate(seed, 1).level, RunState.begin(seed), seed)
            val cache = sim.items.first { it.guaranteed && it.weapon != null }.weapon!!
            assertTrue(cache.id != Weapons.startingWeapon.id, "seed $seed: the starter cache holds the ${cache.name}")
        }
    }
}
