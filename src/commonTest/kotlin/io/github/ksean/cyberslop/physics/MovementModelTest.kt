package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovementModelTest {
    @Test
    fun `a held jump rises close to, but never above, the closed form apex`() {
        val world = flatGround()
        val measured = peakHeight(world, InputFrame(jump = true))
        val closedForm = Physics.Default.apexHeight

        // The fixed-step integrator applies a whole tick of gravity each step, so it always falls
        // short of the continuous solution. Undershooting is safe; overshooting would mean the
        // integrator is wrong, and generation would be budgeting reach the player does not have.
        assertTrue(measured <= closedForm, "measured $measured exceeded closed form $closedForm")
        assertTrue(
            measured >= closedForm * 0.90,
            "measured $measured is more than 10% below closed form $closedForm",
        )
    }

    @Test
    fun `a released jump rises less than a held jump`() {
        val world = flatGround()

        val held = peakHeight(world, InputFrame(jump = true))
        val tapped = peakHeightTapping(world, holdTicks = 4)

        assertTrue(tapped < held, "tapped $tapped should be below held $held")
        assertTrue(tapped > 0.0, "a tap should still leave the ground")
    }

    @Test
    fun `a running jump reaches close to the closed form flat reach`() {
        val world = flatGround()

        val measured = runningJumpDistance(world)

        assertClose(Physics.Default.flatReach, measured, tolerance = 0.10, what = "flat reach")
    }

    @Test
    fun `a running player stops within the closed form stopping distance`() {
        val world = flatGround()
        var state = runUpToFullSpeed(world)
        val startX = state.x

        var ticks = 0
        while (abs(state.vx) > 0.001 && ticks < 600) {
            state = MovementModel.step(state, InputFrame(), world)
            ticks++
        }

        val measured = state.x - startX
        val closedForm = Physics.Default.stoppingDistance

        // Same discretisation direction as the jump: the integrator stops sooner than the
        // continuous solution. A jet corridor's safe zone is sized from the measured value.
        assertTrue(measured <= closedForm, "measured $measured exceeded closed form $closedForm")
        assertTrue(measured >= closedForm * 0.80, "measured $measured far below $closedForm")
    }

    @Test
    fun `a player falling at terminal velocity does not tunnel through a single tile floor`() {
        val world = TileMap(width = 8, height = 64)
        for (x in 0 until 8) world[x, 40] = TileKind.Solid

        var state = PlayerState(x = 64.0, y = 16.0, vy = Physics.Default.terminalVelocity)
        repeat(600) { state = MovementModel.step(state, InputFrame(), world) }

        assertTrue(state.onGround, "should have landed, ended at y=${state.y}")
        assertClose(
            TileMap.toWorld(40) - Physics.Default.standingHeight,
            state.y,
            tolerance = 0.01,
            what = "resting y",
        )
    }

    @Test
    fun `a crouched player cannot jump`() {
        val world = flatGround()
        var state = restingOnGround(world)

        repeat(30) { tick ->
            state = MovementModel.step(
                state,
                InputFrame(crouch = true, jump = true, jumpStart = tick == 0),
                world,
            )
        }

        assertTrue(state.onGround, "crouching player left the ground")
        assertEquals(Stance.Crouch, state.stance)
    }

    @Test
    fun `a player cannot stand up under a low ceiling`() {
        val world = flatGround()
        val groundTile = GROUND_ROW
        for (x in 0 until world.width) world[x, groundTile - 2] = TileKind.Solid

        var state = restingOnGround(world)
        repeat(10) { state = MovementModel.step(state, InputFrame(crouch = true), world) }
        assertEquals(Stance.Crouch, state.stance)

        repeat(10) { state = MovementModel.step(state, InputFrame(), world) }

        assertEquals(Stance.Crouch, state.stance, "stood up into a ceiling")
    }

    @Test
    fun `a crouched player fits through a one tile gap that blocks standing`() {
        val world = flatGround()
        for (x in 0 until world.width) world[x, GROUND_ROW - 2] = TileKind.Solid

        var state = restingOnGround(world)
        val startX = state.x
        repeat(120) { state = MovementModel.step(state, InputFrame(crouch = true, right = true), world) }

        assertTrue(state.x > startX + 32.0, "crouched player did not move through the duct")
    }

    @Test
    fun `movement is deterministic for the same inputs`() {
        val world = flatGround()
        val inputs = InputFrame(right = true, jump = true, jumpStart = true)

        val first = generateSequence(restingOnGround(world)) { MovementModel.step(it, inputs, world) }
            .take(200).last()
        val second = generateSequence(restingOnGround(world)) { MovementModel.step(it, inputs, world) }
            .take(200).last()

        assertEquals(first, second)
    }

    @Test
    fun `the player never leaves the map sideways`() {
        val world = flatGround()
        var state = restingOnGround(world)

        repeat(600) { state = MovementModel.step(state, InputFrame(left = true), world) }

        assertTrue(state.x >= 0.0, "player escaped to x=${state.x}")
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, what: String) {
        val error = abs(actual - expected) / abs(expected)
        assertTrue(
            error <= tolerance,
            "$what: expected ~$expected, was $actual (relative error ${error * 100}%)",
        )
    }

    private fun peakHeight(world: TileMap, input: InputFrame): Double {
        var state = restingOnGround(world)
        val startY = state.y
        var peak = startY
        repeat(200) { tick ->
            state = MovementModel.step(state, input.copy(jumpStart = tick == 0), world)
            if (state.y < peak) peak = state.y
        }
        return startY - peak
    }

    private fun peakHeightTapping(world: TileMap, holdTicks: Int): Double {
        var state = restingOnGround(world)
        val startY = state.y
        var peak = startY
        repeat(200) { tick ->
            state = MovementModel.step(
                state,
                InputFrame(jump = tick < holdTicks, jumpStart = tick == 0),
                world,
            )
            if (state.y < peak) peak = state.y
        }
        return startY - peak
    }

    private fun runningJumpDistance(world: TileMap): Double {
        var state = runUpToFullSpeed(world)
        val launchX = state.x
        val airborne = InputFrame(right = true, jump = true)
        state = MovementModel.step(state, airborne.copy(jumpStart = true), world)
        while (!state.onGround) {
            state = MovementModel.step(state, airborne, world)
        }
        return state.x - launchX
    }

    private fun runUpToFullSpeed(world: TileMap): PlayerState {
        var state = restingOnGround(world)
        repeat(60) { state = MovementModel.step(state, InputFrame(right = true), world) }
        return state
    }

    private fun restingOnGround(world: TileMap): PlayerState =
        PlayerState(
            x = 64.0,
            y = TileMap.toWorld(GROUND_ROW) - Physics.Default.standingHeight,
            onGround = true,
        )

    private fun flatGround(): TileMap {
        val world = TileMap(width = 200, height = 64)
        for (x in 0 until world.width) world[x, GROUND_ROW] = TileKind.Solid
        return world
    }

    private companion object {
        const val GROUND_ROW = 40
    }
}
