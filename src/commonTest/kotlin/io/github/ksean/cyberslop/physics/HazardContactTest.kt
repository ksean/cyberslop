package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The completability guarantee needs lethal contact observed over exactly the sweep that moved the
 * player. If a caller had to re-derive the sweep to ask "did this tick touch acid", it would be a
 * second movement model, which ENG-052 forbids.
 */
class HazardContactTest {
    @Test
    fun `crossing safe ground reports no lethal contact`() {
        val world = groundWithAcid()
        var state = resting(world, x = 32.0)

        repeat(10) { state = MovementModel.step(state, InputFrame(), world) }

        assertFalse(state.touchedLethal)
    }

    @Test
    fun `falling into acid reports lethal contact`() {
        val world = groundWithAcid()
        var state = PlayerState(x = TileMap.toWorld(ACID_TILE), y = TileMap.toWorld(GROUND_ROW) - 100.0)

        var touched = false
        repeat(60) {
            state = MovementModel.step(state, InputFrame(), world)
            if (state.touchedLethal) touched = true
        }

        assertTrue(touched, "fell through acid without reporting contact")
    }

    @Test
    fun `lethal contact is seen even when a single tick passes through at speed`() {
        val world = TileMap(width = 40, height = 64)
        for (x in 0 until 40) world[x, 30] = TileKind.Acid
        for (x in 0 until 40) world[x, 50] = TileKind.Solid

        var state = PlayerState(x = 64.0, y = 0.0, vy = Physics.Default.terminalVelocity)

        var touched = false
        repeat(120) {
            state = MovementModel.step(state, InputFrame(), world)
            if (state.touchedLethal) touched = true
        }

        assertTrue(touched, "terminal-velocity fall passed through an acid layer unnoticed")
    }

    @Test
    fun `acid does not block movement`() {
        val world = groundWithAcid()
        var state = resting(world, x = TileMap.toWorld(ACID_TILE - 3))
        val startX = state.x

        repeat(90) { state = MovementModel.step(state, InputFrame(right = true), world) }

        assertTrue(state.x > startX + 32.0, "acid acted as a wall")
    }

    private fun resting(world: TileMap, x: Double): PlayerState =
        PlayerState(x = x, y = TileMap.toWorld(GROUND_ROW) - Physics.Default.standingHeight, onGround = true)

    private fun groundWithAcid(): TileMap {
        val world = TileMap(width = 60, height = 64)
        for (x in 0 until 60) world[x, GROUND_ROW] = TileKind.Solid
        world[ACID_TILE, GROUND_ROW - 1] = TileKind.Acid
        return world
    }

    private companion object {
        const val GROUND_ROW = 40
        const val ACID_TILE = 20
    }
}
