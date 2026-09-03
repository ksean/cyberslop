package io.github.ksean.cyberslop.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalUnsignedTypes::class)
class RngTest {
    @Test
    fun `the same seed replays the same sequence`() {
        val first = Rng(SEED).take(TAKE)
        val second = Rng(SEED).take(TAKE)

        assertContentEquals(first, second)
    }

    @Test
    fun `a different seed produces a different sequence`() {
        val first = Rng(SEED).take(TAKE)
        val second = Rng(SEED + 1uL).take(TAKE)

        assertTrue(first.toList() != second.toList())
    }

    @Test
    fun `derived streams do not shift each other`() {
        val spineAlone = Rng.derive(SEED, MAP, "spine").take(TAKE)

        Rng.derive(SEED, MAP, "decor").take(TAKE)
        val spineAfterDecor = Rng.derive(SEED, MAP, "spine").take(TAKE)

        assertContentEquals(spineAlone, spineAfterDecor)
    }

    @Test
    fun `different phases of one seed are different streams`() {
        val spine = Rng.derive(SEED, MAP, "spine").take(TAKE)
        val decor = Rng.derive(SEED, MAP, "decor").take(TAKE)

        assertTrue(spine.toList() != decor.toList())
    }

    @Test
    fun `different map indices are different streams`() {
        val first = Rng.derive(SEED, 1, "spine").take(TAKE)
        val second = Rng.derive(SEED, 2, "spine").take(TAKE)

        assertTrue(first.toList() != second.toList())
    }

    @Test
    fun `nextInt stays within the requested range`() {
        val rng = Rng(SEED)

        repeat(10_000) {
            val value = rng.nextInt(BOUND)
            assertTrue(value in 0 until BOUND, "$value outside 0 until $BOUND")
        }
    }

    @Test
    fun `a bound of one always yields zero and still terminates`() {
        val rng = Rng(SEED)

        repeat(1_000) { assertEquals(0, rng.nextInt(1)) }
    }

    @Test
    fun `a range spanning the whole int domain is accepted`() {
        val rng = Rng(SEED)

        repeat(1_000) {
            val value = rng.nextInt(Int.MIN_VALUE, Int.MAX_VALUE)
            assertTrue(value >= Int.MIN_VALUE && value < Int.MAX_VALUE, "$value out of range")
        }
    }

    @Test
    fun `an empty or reversed range is rejected`() {
        val rng = Rng(SEED)

        assertFailsWith<IllegalArgumentException> { rng.nextInt(5, 5) }
        assertFailsWith<IllegalArgumentException> { rng.nextInt(Int.MAX_VALUE, Int.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { rng.nextInt(0) }
    }

    @Test
    fun `a negative range yields values inside it`() {
        val rng = Rng(SEED)

        repeat(1_000) {
            val value = rng.nextInt(-20, -5)
            assertTrue(value in -20..-6, "$value outside -20 until -5")
        }
    }

    @Test
    fun `nextDouble stays in the unit interval`() {
        val rng = Rng(SEED)

        repeat(10_000) {
            val value = rng.nextDouble()
            assertTrue(value >= 0.0 && value < 1.0, "$value outside [0, 1)")
        }
    }

    @Test
    fun `sequences are reproducible across targets`() {
        // Golden values from the reference SplitMix64 definition, not from this implementation,
        // so a recorded seed cannot silently change meaning on a toolchain upgrade (ENG-053).
        val actual = Rng(1uL).take(4).toList()

        assertEquals(
            listOf(
                10451216379200822465uL,
                13757245211066428519uL,
                17911839290282890590uL,
                8196980753821780235uL,
            ),
            actual,
        )
    }

    private fun Rng.take(count: Int): ULongArray =
        ULongArray(count) { nextULong() }

    private companion object {
        const val TAKE = 16
        const val MAP = 3
        const val BOUND = 7
        val SEED = 0xC0FFEEuL
    }
}
