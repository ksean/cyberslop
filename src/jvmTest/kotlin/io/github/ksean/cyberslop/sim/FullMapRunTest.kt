package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.LootFloor
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One continuous run of a whole map: cross it, fight the boss, walk out.
 *
 * Every part of this was already covered separately, and a playtester still got stuck — because
 * nothing had ever joined the parts up. The boss was never killed by gameplay in any test; it was
 * always handed its own death so the exit could be checked. This plays it.
 */
class FullMapRunTest {
    @Test
    fun `a map can be crossed, its boss killed, and its exit walked out of`() {
        val generated = LevelGenerator.generate(SEED, MAP)
        val sim = GameSimulation(generated.level, startingRun(MAP), SEED)

        // Cross to the arena using the route the generator proved.
        generated.witness.steps.forEach { step ->
            step.frames.forEach { sim.tick(it) }
        }
        assertTrue(!sim.run.dead, "died before reaching the arena at ${sim.run.health}")

        // Walk in and fight, reacting to telegraphs the way the design expects a player to.
        var cleared = false
        var ticks = 0
        while (ticks < FIGHT_TICKS && !cleared && !sim.run.dead) {
            cleared = sim.tick(play(sim)).mapCleared
            ticks++
        }

        assertTrue(sim.boss.fight.engaged, "never engaged the boss")
        assertTrue(
            sim.boss.fight.defeated,
            "boss survived $ticks ticks at ${(sim.boss.healthFraction * 100).toInt()}% health",
        )
        assertTrue(cleared, "boss died but the map never cleared; player at ${TileMap.toTile(sim.player.x)}")
    }

    @Test
    fun `the fight is not instant, so it reads as a fight`() {
        val generated = LevelGenerator.generate(SEED, MAP)
        val sim = GameSimulation(generated.level, startingRun(MAP), SEED)
        generated.witness.steps.forEach { step -> step.frames.forEach { sim.tick(it) } }

        var ticks = 0
        while (ticks < FIGHT_TICKS && !sim.boss.fight.defeated && !sim.run.dead) {
            sim.tick(play(sim))
            ticks++
        }

        assertTrue(ticks > MIN_FIGHT_TICKS, "the boss died in $ticks ticks; that is not a fight")
    }

    /**
     * A minimal policy: close on the boss, and answer a telegraph the way the attack says it can be
     * answered.
     *
     * Holding right instead is a worst case, and it loses — which is the design working. A boss
     * fight tuned so that a player who never reacts still wins would make the telegraphs pointless.
     */
    private fun play(sim: GameSimulation): InputFrame {
        // With the boss down, the only thing left is to leave.
        if (sim.boss.fight.defeated) return InputFrame(right = true)

        val attack = sim.boss.currentAttack
        val towardBoss = sim.boss.centre.x > sim.player.x

        // For the whole attack, not merely its telegraph: the damage lands *after* the wind-up, so
        // reacting to the tell and then stepping back in is the same as not reacting at all.
        if (attack != null) {
            return when (attack.dodge) {
                Dodge.Jump -> InputFrame(jump = true, jumpStart = sim.player.onGround)
                Dodge.Crouch -> InputFrame(crouch = true)
                Dodge.MoveAside -> InputFrame(left = towardBoss, right = !towardBoss)
            }
        }
        return InputFrame(right = towardBoss, left = !towardBoss)
    }

    private fun startingRun(mapIndex: Int): RunState {
        val run = RunState.begin(SEED).copy(mapIndex = mapIndex)
        return run.copy(
            health = run.maxHealth,
            loadout = run.loadout.copy(
                weapon = LootFloor.weaponArrivingAt(mapIndex),
                slots = LootFloor.slotsArrivingAt(mapIndex),
            ),
        )
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val MAP = 1
        const val FIGHT_TICKS = 12_000
        const val MIN_FIGHT_TICKS = 60
    }
}
