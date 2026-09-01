package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerHurtFlashTest {
    @Test
    fun `actual hazard damage starts and refreshes the player flash then it decays`() {
        val level = TestLevels.flat(
            spikeColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN,
        )
        val sim = TestLevels.simulation(level)

        sim.tick(InputFrame())
        assertEquals(GameSimulation.HURT_FLASH_SECONDS, sim.playerHurtSecondsLeft)

        sim.tick(InputFrame())
        assertEquals(GameSimulation.HURT_FLASH_SECONDS, sim.playerHurtSecondsLeft)

        level.tiles[TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW] = TileKind.Empty
        repeat((GameSimulation.HURT_FLASH_SECONDS / TICK_SECONDS).toInt() + 1) {
            sim.tick(InputFrame())
        }
        assertEquals(0.0, sim.playerHurtSecondsLeft)
    }

    @Test
    fun `suppressed enemy contact does not start the flash`() {
        val sim = TestLevels.simulation(
            TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN),
        )
        TestLevels.enemyAt(sim, EnemyArchetype.Brute, TestLevels.SPAWN_COLUMN)
        val health = sim.run.health

        sim.tick(InputFrame())

        assertEquals(health, sim.run.health)
        assertEquals(0.0, sim.playerHurtSecondsLeft)
    }

    @Test
    fun `the player flash is presentation only and excluded from the digest`() {
        val sim = TestLevels.simulation()
        val before = sim.digest()

        sim.playerHurtSecondsLeft = 0.1

        assertTrue(sim.playerHurtSecondsLeft > 0.0)
        assertEquals(before, sim.digest())
    }
}
