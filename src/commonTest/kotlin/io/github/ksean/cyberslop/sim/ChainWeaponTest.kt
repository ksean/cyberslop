package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Arena
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A chain weapon chained over trash only, so a run whose guaranteed weapon was one could stand
 * beside a boss forever without scratching it. Found by the boss-pressure harness (P-39).
 */
class ChainWeaponTest {
    @Test
    fun `a chain weapon strikes a boss standing beside the player`() {
        val level = TestLevels.flat(bossArena = Arena(2, 12, TestLevels.FLOOR_ROW + 1))
        val run = RunState.begin(TestLevels.SEED).let {
            it.copy(loadout = it.loadout.copy(weapon = Weapons.of(WeaponId.VoiceOfTheDeadNet)))
        }
        val sim = GameSimulation(level, run, TestLevels.SEED)
        sim.boss.fight.engage()

        repeat(180) { sim.tick(InputFrame()) }

        assertTrue(sim.boss.fight.health < sim.boss.spec.maxHealth, "a chain weapon never hurt the boss beside the player")
    }
}
