package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `onGround` drives ground friction, crouching, and the coyote window in [IntentFilter]. If it does
 * not clear on the way down, a falling player keeps ground acceleration, can crouch in mid-air, and
 * has their coyote window refreshed every tick — which permits a jump at any point during a fall.
 */
class GroundContactTest {
    @Test
    fun `walking off a ledge clears onGround`() {
        val world = ledge()
        var state = restingOnGround(world, x = TileMap.toWorld(LEDGE_TILE) - 20.0)

        var leftGround = false
        repeat(60) {
            state = MovementModel.step(state, InputFrame(right = true), world)
            if (!state.onGround) leftGround = true
        }

        assertTrue(leftGround, "player never left the ground walking off a ledge")
    }

    @Test
    fun `a falling player is not on the ground`() {
        val world = ledge()
        var state = PlayerState(
            x = TileMap.toWorld(LEDGE_TILE + 4),
            y = TileMap.toWorld(GROUND_ROW) - 200.0,
            onGround = true,
        )

        state = MovementModel.step(state, InputFrame(), world)

        assertFalse(state.onGround, "a player in open air reported onGround")
    }

    @Test
    fun `a falling player cannot crouch in mid air`() {
        val world = ledge()
        var state = PlayerState(
            x = TileMap.toWorld(LEDGE_TILE + 4),
            y = TileMap.toWorld(GROUND_ROW) - 200.0,
            onGround = true,
        )

        repeat(5) { state = MovementModel.step(state, InputFrame(crouch = true), world) }

        assertFalse(state.onGround)
        assertTrue(state.stance == Stance.Stand, "crouched in mid-air")
    }

    @Test
    fun `a falling player keeps horizontal speed instead of being braked by ground friction`() {
        val world = ledge()
        var state = restingOnGround(world, x = TileMap.toWorld(LEDGE_TILE) - 80.0)

        // Run to the edge and stop stepping the moment the ground is gone, so the measurement is
        // taken during the fall rather than after landing on the shelf below.
        var ticks = 0
        while (state.onGround && ticks < 120) {
            state = MovementModel.step(state, InputFrame(right = true), world)
            ticks++
        }
        assertFalse(state.onGround, "never left the ledge")
        val launchSpeed = state.vx
        assertTrue(launchSpeed > 0.0, "left the ledge with no horizontal speed")

        repeat(5) { state = MovementModel.step(state, InputFrame(), world) }

        assertFalse(state.onGround, "landed sooner than expected; not measuring the fall")
        assertTrue(
            state.vx > launchSpeed * 0.9,
            "ground friction braked an airborne player: $launchSpeed -> ${state.vx}",
        )
    }

    @Test
    fun `landing restores onGround`() {
        val world = ledge()
        var state = restingOnGround(world, x = TileMap.toWorld(LEDGE_TILE) - 20.0)

        repeat(300) { state = MovementModel.step(state, InputFrame(right = true), world) }

        assertTrue(state.onGround, "never landed on the lower shelf")
    }

    private fun restingOnGround(world: TileMap, x: Double): PlayerState =
        PlayerState(x = x, y = TileMap.toWorld(GROUND_ROW) - Physics.Default.standingHeight, onGround = true)

    /** High ground up to [LEDGE_TILE], then a long drop to a lower shelf. */
    private fun ledge(): TileMap {
        val world = TileMap(width = 200, height = 64)
        for (x in 0 until LEDGE_TILE) world[x, GROUND_ROW] = TileKind.Solid
        for (x in LEDGE_TILE until 200) world[x, GROUND_ROW + 12] = TileKind.Solid
        return world
    }

    private companion object {
        const val GROUND_ROW = 40
        const val LEDGE_TILE = 20
    }
}
