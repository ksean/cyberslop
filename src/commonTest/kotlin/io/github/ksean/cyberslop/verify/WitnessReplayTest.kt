package io.github.ksean.cyberslop.verify

import io.github.ksean.cyberslop.gen.LevelGenerator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The completability guarantee (PROD-024) in executable form. The generator emits a witness as it
 * carves; this replays that witness through the game's own movement model and asserts it arrives.
 * A generator that produced an uncrossable map would fail here rather than reaching a player.
 */
class WitnessReplayTest {
    @Test
    fun `the witness reaches the boss arena alive`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)

        val result = WitnessReplay.replay(generated.level, generated.witness)

        assertTrue(result.reachedBoss, "witness stopped at ${result.finalState.x}")
        assertFalse(result.touchedLethal, "witness path passes through a lethal tile")
    }

    @Test
    fun `the witness transits the mini-boss arena on the way`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)

        val result = WitnessReplay.replay(generated.level, generated.witness)

        assertTrue(result.reachedMiniboss, "witness never entered the mini-boss arena")
    }

    @Test
    fun `replaying the same witness twice gives the same result`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)

        val first = WitnessReplay.replay(generated.level, generated.witness)
        val second = WitnessReplay.replay(generated.level, generated.witness)

        assertTrue(first.ticks == second.ticks && first.finalState == second.finalState)
    }

    @Test
    fun `a witness truncated before the end does not report success`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)
        val truncated = Witness(generated.witness.steps.dropLast(generated.witness.steps.size / 2))

        val result = WitnessReplay.replay(generated.level, truncated)

        assertFalse(result.reachedBoss, "a half witness claimed to reach the boss")
    }

    private companion object {
        val SEED = 0xC0FFEEuL
    }
}
