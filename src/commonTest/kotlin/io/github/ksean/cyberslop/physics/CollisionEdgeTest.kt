package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boundary behaviour of collision, including the corner cases that axis-separated resolution
 * decides rather than derives.
 *
 * Resolution moves the whole X component of a sub-step before any of the Y component. That is not a
 * true simultaneous sweep, so a trajectory grazing a tile's corner is judged by the L-shaped path
 * rather than the diagonal one. These tests pin the resulting behaviour rather than assert it is
 * ideal: the guarantee in plan.md §3 rests on the verifier and the game using the *same* model, and
 * both call this code, so whatever it decides they agree on. What would break the guarantee is the
 * behaviour changing silently, which is what these tests prevent.
 */
class CollisionEdgeTest {
    @Test
    fun `a fast horizontal move does not tunnel through a one tile wall`() {
        val world = flatGround()
        world[WALL_TILE, GROUND_ROW - 1] = TileKind.Solid
        world[WALL_TILE, GROUND_ROW - 2] = TileKind.Solid

        var state = resting(x = 64.0).copy(vx = 4000.0)
        repeat(20) { state = MovementModel.step(state, InputFrame(right = true), world) }

        assertTrue(
            state.x + Physics.Default.width <= TileMap.toWorld(WALL_TILE) + 0.001,
            "tunnelled past the wall to x=${state.x}",
        )
    }

    @Test
    fun `a fast leftward move does not tunnel through a one tile wall`() {
        val world = flatGround()
        world[WALL_TILE, GROUND_ROW - 1] = TileKind.Solid
        world[WALL_TILE, GROUND_ROW - 2] = TileKind.Solid

        var state = resting(x = TileMap.toWorld(WALL_TILE + 6)).copy(vx = -4000.0)
        repeat(20) { state = MovementModel.step(state, InputFrame(left = true), world) }

        assertTrue(state.x >= TileMap.toWorld(WALL_TILE + 1) - 0.001, "tunnelled to x=${state.x}")
    }

    @Test
    fun `jumping into a ceiling stops upward motion instead of passing through`() {
        val world = flatGround()
        for (x in 0 until world.width) world[x, GROUND_ROW - 3] = TileKind.Solid

        var state = resting(x = 64.0)
        repeat(30) { tick ->
            state = MovementModel.step(
                state,
                InputFrame(jump = true, jumpStart = tick == 0),
                world,
            )
        }

        assertTrue(state.y >= TileMap.toWorld(GROUND_ROW - 2), "passed through the ceiling")
    }

    @Test
    fun `the player comes to rest exactly on top of the floor`() {
        val world = flatGround()
        var state = resting(x = 64.0).copy(y = TileMap.toWorld(GROUND_ROW) - 200.0, onGround = false)

        repeat(120) { state = MovementModel.step(state, InputFrame(), world) }

        assertEquals(
            TileMap.toWorld(GROUND_ROW) - Physics.Default.standingHeight,
            state.y,
            absoluteTolerance = 0.001,
        )
        assertTrue(state.onGround)
    }

    @Test
    fun `canStand is false under a low ceiling and true in the open`() {
        val open = flatGround()
        val closed = flatGround().apply {
            for (x in 0 until width) this[x, GROUND_ROW - 2] = TileKind.Solid
        }
        val crouched = resting(x = 64.0).copy(
            y = TileMap.toWorld(GROUND_ROW) - Physics.Default.crouchingHeight,
            stance = Stance.Crouch,
        )

        assertTrue(MovementModel.canStand(crouched, open))
        assertFalse(MovementModel.canStand(crouched, closed))
    }

    @Test
    fun `standing resumes once the ceiling clears`() {
        val world = flatGround()
        for (x in 0 until DUCT_END) world[x, GROUND_ROW - 2] = TileKind.Solid

        var state = resting(x = 64.0)
        repeat(200) { state = MovementModel.step(state, InputFrame(crouch = true, right = true), world) }
        assertEquals(Stance.Crouch, state.stance, "should still be in the duct")

        repeat(200) { state = MovementModel.step(state, InputFrame(right = true), world) }

        assertEquals(Stance.Stand, state.stance, "never stood back up after leaving the duct")
    }

    @Test
    fun `holding both directions cancels out`() {
        val world = flatGround()
        var state = resting(x = 64.0)
        val startX = state.x

        repeat(60) { state = MovementModel.step(state, InputFrame(left = true, right = true), world) }

        assertEquals(startX, state.x, absoluteTolerance = 0.001)
    }

    @Test
    fun `a crouched player moves more slowly than a standing one`() {
        val world = flatGround()
        var standing = resting(x = 64.0)
        var crouching = resting(x = 64.0)

        repeat(60) {
            standing = MovementModel.step(standing, InputFrame(right = true), world)
            crouching = MovementModel.step(crouching, InputFrame(right = true, crouch = true), world)
        }

        assertTrue(crouching.x < standing.x, "crouching was not slower")
    }

    @Test
    fun `the player cannot climb out over the top of the map`() {
        val world = TileMap(width = 12, height = 20)
        for (x in 0 until 12) world[x, 18] = TileKind.Solid

        var state = PlayerState(x = 4.0, y = -40.0, vx = -600.0, onGround = false)
        repeat(120) { state = MovementModel.step(state, InputFrame(left = true), world) }

        assertTrue(state.x >= 0.0, "escaped over the top and out the side to x=${state.x}")
    }

    private fun resting(x: Double): PlayerState =
        PlayerState(x = x, y = TileMap.toWorld(GROUND_ROW) - Physics.Default.standingHeight, onGround = true)

    private fun flatGround(): TileMap {
        val world = TileMap(width = 120, height = 64)
        for (x in 0 until world.width) world[x, GROUND_ROW] = TileKind.Solid
        return world
    }

    private companion object {
        const val GROUND_ROW = 40
        const val WALL_TILE = 30
        const val DUCT_END = 40
    }
}
