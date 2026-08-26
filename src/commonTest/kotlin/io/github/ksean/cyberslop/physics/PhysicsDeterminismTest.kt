package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on **both** declared targets against a committed golden, which is the only way the JVM and
 * Wasm results can be compared: the two test executions never see each other, so agreeing with each
 * other is not something either can assert. Running the model twice inside one target would pass
 * even if the targets disagreed.
 *
 * If this fails on one target only, the movement path has picked up an operation that is not
 * bit-identical across targets (ENG-054).
 */
class PhysicsDeterminismTest {
    @Test
    fun `a fixed input tape produces the committed state hash on every target`() {
        assertEquals(GOLDEN_HASH, replayTape().hash)
    }

    @Test
    fun `the tape actually exercises jumping, falling and landing`() {
        val outcome = replayTape()

        assertTrue(outcome.leftGround, "tape never left the ground")
        assertTrue(outcome.landed, "tape never landed")
        assertTrue(outcome.finalState.x > 64.0, "tape never moved right")
    }

    @Test
    fun `no non-finite value reaches the hashed state`() {
        val state = replayTape().finalState

        listOf(state.x, state.y, state.vx, state.vy).forEach {
            assertTrue(it.isFinite(), "non-finite physics value $it")
        }
    }

    private class Outcome(val finalState: PlayerState, val hash: ULong, val leftGround: Boolean, val landed: Boolean)

    private fun replayTape(): Outcome {
        val world = terrain()
        var state = PlayerState(
            x = 64.0,
            y = TileMap.toWorld(GROUND_ROW) - Physics.Default.standingHeight,
            onGround = true,
        )
        var hash = 0xCBF29CE484222325uL
        var leftGround = false
        var landed = false

        TAPE.forEach { frame ->
            state = MovementModel.step(state, frame, world)
            if (!state.onGround) leftGround = true else if (leftGround) landed = true
            hash = mix(hash, state)
        }
        return Outcome(state, hash, leftGround, landed)
    }

    private fun mix(accumulator: ULong, state: PlayerState): ULong {
        var value = accumulator
        listOf(state.x, state.y, state.vx, state.vy).forEach { component ->
            value = (value xor component.toRawBits().toULong()) * PRIME
        }
        value = (value xor (if (state.onGround) 1uL else 0uL)) * PRIME
        return (value xor state.stance.ordinal.toULong()) * PRIME
    }

    private fun terrain(): TileMap {
        val world = TileMap(width = 80, height = 64)
        for (x in 0 until 30) world[x, GROUND_ROW] = TileKind.Solid
        for (x in 34 until 80) world[x, GROUND_ROW + 2] = TileKind.Solid
        for (x in 20 until 24) world[x, GROUND_ROW - 4] = TileKind.Solid
        return world
    }

    private companion object {
        const val GROUND_ROW = 40
        const val PRIME = 0x100000001B3uL

        /** Deliberately mixed: run, jump, release early, fall, land, crouch, reverse. */
        val TAPE: List<InputFrame> = buildList {
            repeat(25) { add(InputFrame(right = true)) }
            add(InputFrame(right = true, jump = true, jumpStart = true))
            repeat(6) { add(InputFrame(right = true, jump = true)) }
            repeat(40) { add(InputFrame(right = true)) }
            repeat(15) { add(InputFrame(right = true, crouch = true)) }
            repeat(30) { add(InputFrame(left = true)) }
            repeat(20) { add(InputFrame()) }
        }

        // Committed so that a divergence between the JVM and Wasm results is visible as a failure
        // on exactly one target, rather than invisible because neither can see the other.
        const val GOLDEN_HASH = 13771843151335166226uL
    }
}
