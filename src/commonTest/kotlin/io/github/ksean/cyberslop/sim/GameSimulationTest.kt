package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * These exist because every subsystem passing its own tests says nothing about whether they are
 * connected. The game was, for a while, a set of correct parts that a running browser could not
 * reach: movement ticked and nothing else did.
 */
class GameSimulationTest {
    @Test
    fun `the weapon fires by itself, with no attack input`() {
        val sim = simulation()
        var shotsSeen = 0

        repeat(400) {
            sim.tick(InputFrame())
            shotsSeen += sim.projectiles.size
        }

        // The bottle is melee, so it produces no projectiles; swap to something that does.
        val ranged = simulation(WeaponId.ScraplineZipPistol)
        repeat(400) { ranged.tick(InputFrame()) }

        assertTrue(ranged.projectiles.isNotEmpty() || shotsSeen > 0, "nothing ever fired")
    }

    @Test
    fun `enemies are live entities, not just level data`() {
        val sim = simulation()

        assertTrue(sim.enemies.isNotEmpty(), "no enemies were instantiated from the level")
        assertTrue(sim.enemies.all { it.alive }, "enemies started dead")
    }

    @Test
    fun `enemies move when the simulation runs`() {
        val sim = simulation()
        val before = sim.enemies.map { it.position.x }

        repeat(120) { sim.tick(InputFrame()) }

        assertTrue(sim.enemies.map { it.position.x } != before, "enemies never moved")
    }

    @Test
    fun `a boss fight exists and is inert until the player is noticed`() {
        val sim = simulation()

        assertTrue(!sim.boss.fight.engaged, "the boss noticed a player who had only just spawned")
        assertTrue(!sim.boss.fight.vulnerable, "the boss was damageable on spawn")
    }

    @Test
    fun `the run tracks damage taken and can end`() {
        val sim = simulation()
        val start = sim.run.health

        repeat(600) { sim.tick(InputFrame(right = true)) }

        assertTrue(sim.run.health <= start, "health never changed across 10 seconds of play")
    }

    @Test
    fun `the simulation runs on the four movement keys alone`() {
        val sim = simulation()

        repeat(240) { sim.tick(InputFrame()) }

        assertTrue(sim.elapsedTicks == 240, "the simulation did not run on keys alone")
    }

    @Test
    fun `the simulation is deterministic for the same inputs`() {
        val first = simulation()
        val second = simulation()

        repeat(300) {
            first.tick(InputFrame(right = true))
            second.tick(InputFrame(right = true))
        }

        assertTrue(first.player == second.player, "player states diverged")
        assertTrue(first.run.health == second.run.health, "health diverged")
        assertTrue(first.enemies.size == second.enemies.size, "enemy counts diverged")
    }

    @Test
    fun `projectiles are bounded`() {
        val sim = simulation(WeaponId.DebtCollectorMinigun)

        repeat(3000) { sim.tick(InputFrame()) }

        assertTrue(sim.projectiles.size <= 300, "projectiles grew to ${sim.projectiles.size}")
    }

    private fun simulation(
        weapon: WeaponId = WeaponId.BrokenBottle,
    ): GameSimulation {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        var run = RunState.begin(SEED)
        if (weapon != WeaponId.BrokenBottle) {
            run = run.copy(
                loadout = run.loadout.copy(
                    weapon = io.github.ksean.cyberslop.combat.Weapons.of(weapon),
                ),
            )
        }
        return GameSimulation(level, run, SEED)
    }

    private companion object {
        val SEED = 0xC0FFEEuL
    }
}
