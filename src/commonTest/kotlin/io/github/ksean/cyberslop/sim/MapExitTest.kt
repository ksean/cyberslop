package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.verify.Witness
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reaching the right-hand edge should finish the map, not kill the player.
 *
 * Driven by the generated witness rather than by holding right: holding right walks into the first
 * gap and falls out of the world, which is a fair thing for a player to do but tells us nothing
 * about the exit.
 */
class MapExitTest {
    @Test
    fun `the way out stays shut for as long as the boss lives`() {
        // Checked every tick rather than once at the end: the boss closes on the player and the
        // weapon fires itself, so it may well die partway through — and after that the exit is
        // supposed to open.
        val fixture = walkToTheBoss()
        val sim = fixture.sim

        repeat(1200) {
            val report = sim.tick(InputFrame(right = true))
            if (!sim.boss.fight.defeated) {
                assertTrue(
                    TileMap.toTile(sim.player.x) <= sim.level.gateColumn,
                    "the player passed a closed gate to ${TileMap.toTile(sim.player.x)}",
                )
                assertFalse(report.mapCleared, "the map cleared with the boss alive")
            }
        }
    }

    @Test
    fun `an undamaged boss keeps the exit shut indefinitely`() {
        val generated = LevelGenerator.generate(SEED, 1)
        val sim = GameSimulation(generated.level, RunState.begin(SEED), SEED)
        val level = sim.level

        // Never commits, so the boss is never vulnerable and never dies.
        repeat(600) { sim.tick(InputFrame(left = true)) }

        assertFalse(sim.boss.fight.defeated)
        assertTrue(
            (level.boss.floorRow - 2 until level.boss.floorRow)
                .any { level.tiles.blocksMovement(level.gateColumn, it) },
            "the gate opened without the boss being beaten",
        )
    }

    @Test
    fun `killing the boss opens the gate and walking out clears the map`() {
        val fixture = walkToTheBoss()
        val sim = fixture.sim

        sim.boss.fight.playerMoved(sim.level.boss.leftTile + COMMIT_DEPTH)
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        sim.tick(InputFrame())

        var cleared = false
        var died = false
        repeat(2000) {
            val report = sim.tick(InputFrame(right = true))
            if (report.mapCleared) cleared = true
            if (report.playerDied) died = true
        }

        assertFalse(died, "reaching the end of the map killed the player")
        assertTrue(cleared, "the exit never opened; the player ended at ${TileMap.toTile(sim.player.x)}")
    }

    /**
     * The generated route is a *geometry* proof: [io.github.ksean.cyberslop.verify.WitnessReplay]
     * shows it crosses the tiles and the jets alive. The full simulation adds enemies, which the
     * completability guarantee explicitly excludes — so surviving it is a question about balance,
     * not about the map.
     *
     * What is asserted here is the balance claim: a player carrying what the run *guarantees* them
     * survives the route to the boss arena on the maps the guaranteed floor covers. Following it
     * with the starting bottle on map 4 does not, and should not — that is the difficulty curve.
     */
    @Test
    fun `a player with the guaranteed loadout survives the route on the first map`() {
        val fixture = walkToTheBoss(1, guaranteedLoadout = true)

        assertFalse(fixture.diedOnTheWay, "the guaranteed loadout died following the intended route")
    }

    private class Fixture(val sim: GameSimulation, val diedOnTheWay: Boolean)

    /** Replays the generated route, which is the only input sequence known to cross the map. */
    private fun walkToTheBoss(mapIndex: Int = 1, guaranteedLoadout: Boolean = false): Fixture {
        val generated = LevelGenerator.generate(SEED, mapIndex)
        var run = RunState.begin(SEED).copy(mapIndex = mapIndex)
        run = run.copy(health = run.maxHealth)
        if (guaranteedLoadout) {
            run = run.copy(
                loadout = run.loadout.copy(
                    weapon = io.github.ksean.cyberslop.loot.LootFloor.weaponAt(mapIndex),
                    slots = io.github.ksean.cyberslop.loot.LootFloor.slotsAt(mapIndex),
                ),
            )
        }
        val sim = GameSimulation(generated.level, run, SEED)
        var died = false
        play(generated.witness) { frame ->
            if (sim.tick(frame).playerDied) died = true
        }
        return Fixture(sim, died)
    }

    private inline fun play(witness: Witness, action: (InputFrame) -> Unit) {
        witness.steps.forEach { step -> step.frames.forEach(action) }
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val COMMIT_DEPTH = 8
    }
}
