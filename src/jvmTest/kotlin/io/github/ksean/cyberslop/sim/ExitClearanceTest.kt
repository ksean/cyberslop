package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Killing the boss must leave nothing standing between the player and the end of the map, on every
 * map.
 *
 * A playtester reached the arena with every enemy dead and found a wall. The wall was the gate doing
 * its job — but with the boss drawn nowhere there was no way to know that, and no way through. The
 * rule is now blunt: once the boss is dead, anything at head height between the arena and the map's
 * edge is removed, whatever put it there.
 */
class ExitClearanceTest {
    @Test
    fun `no wall survives the boss on any map`() {
        (1..MAPS).forEach { map ->
            val generated = LevelGenerator.generate(SEED, map)
            val sim = GameSimulation(generated.level, RunState.begin(SEED), SEED)
            val level = sim.level
            val floor = level.boss.floorRow

            fun blocked() = (level.boss.leftTile until level.widthTiles).filter { column ->
                (floor - PLAYER_TILES until floor).any { level.tiles.blocksMovement(column, it) }
            }

            assertTrue(blocked().isNotEmpty(), "map $map: the exit was never sealed")

            sim.boss.fight.engage()
            sim.boss.fight.damage(sim.boss.spec.maxHealth)
            sim.tick(InputFrame())

            assertTrue(blocked().isEmpty(), "map $map: still walled at ${blocked()}")
        }
    }

    @Test
    fun `the exit stays sealed while the boss lives`() {
        (1..MAPS).forEach { map ->
            val generated = LevelGenerator.generate(SEED, map)
            val sim = GameSimulation(generated.level, RunState.begin(SEED), SEED)
            val level = sim.level

            repeat(120) { sim.tick(InputFrame(right = true)) }

            assertTrue(
                (level.boss.floorRow - PLAYER_TILES until level.boss.floorRow)
                    .any { level.tiles.blocksMovement(level.gateColumn, it) },
                "map $map: the gate opened with the boss alive",
            )
        }
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val MAPS = 10
        const val PLAYER_TILES = 2
    }
}
