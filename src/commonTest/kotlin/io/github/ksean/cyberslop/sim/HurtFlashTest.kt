package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PROD-076: a hit starts the hurt flash; a damage-over-time tick does not; it decays and leaves the digest alone. */
class HurtFlashTest {
    @Test
    fun `a hit starts the flash and it decays over the window`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 6)
        untilHit(sim, enemy)
        assertTrue(enemy.hurtSecondsLeft > 0.0, "the hit did not start the flash")
        assertTrue(enemy.hurtSecondsLeft <= GameSimulation.HURT_FLASH_SECONDS)
        val ticks = (GameSimulation.HURT_FLASH_SECONDS / TICK_SECONDS).toInt() + 1
        repeat(ticks) { sim.tick(InputFrame()) }
        assertEquals(0.0, enemy.hurtSecondsLeft, "the flash outlived its window")
    }

    @Test
    fun `a burn tick does not flash`() {
        val sim = simulation(WeaponId.BrokenBottle)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 30)
        enemy.burn.apply(3.0, 5.0)
        val before = enemy.health
        sim.tick(InputFrame())
        assertTrue(enemy.health < before, "fixture: the enemy did not burn")
        assertEquals(0.0, enemy.hurtSecondsLeft, "a burn tick flashed the enemy")
    }

    @Test
    fun `a hit boss flashes`() {
        val sim = simulation(WeaponId.ScraplineZipPistol, level = TestLevels.flat(bossArena = io.github.ksean.cyberslop.world.Arena(2, 12, TestLevels.FLOOR_ROW + 1), committedColumns = 1..5))
        sim.boss.fight.engage()
        var ticks = 0
        while (sim.boss.fight.health == sim.boss.spec.maxHealth && ticks < 300) { sim.tick(InputFrame()); ticks++ }
        assertTrue(ticks < 300, "fixture: the boss was never hit")
        assertTrue(sim.boss.hurtSecondsLeft > 0.0, "the hit boss did not flash")
    }

    @Test
    fun `the flash is not in the digest`() {
        val sim = simulation(WeaponId.BrokenBottle)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 30)
        val before = sim.digest()
        enemy.hurtSecondsLeft = 0.1
        sim.boss.hurtSecondsLeft = 0.1
        assertEquals(before, sim.digest())
    }

    private fun untilHit(sim: GameSimulation, enemy: LiveEnemy) {
        val full = enemy.health
        repeat(300) { sim.tick(InputFrame()); if (enemy.health < full) return }
        error("fixture: no hit in 300 ticks")
    }

    private fun simulation(weapon: WeaponId, level: io.github.ksean.cyberslop.world.Level = TestLevels.flat(committedColumns = 1..5)): GameSimulation {
        val run = RunState.begin(TestLevels.SEED).let { it.copy(loadout = Loadout(Weapons.of(weapon), PowerupSlots.empty())) }
        return GameSimulation(level, run, TestLevels.SEED)
    }
}
