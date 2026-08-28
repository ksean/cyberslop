package io.github.ksean.cyberslop.core

private const val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL
private const val MIX_A = 0xBF58476D1CE4E5B9uL
private const val MIX_B = 0x94D049BB133111EBuL

/**
 * SplitMix64. The standard library's generator makes no guarantee that a seed keeps producing the
 * same stream across versions, so a recorded seed could silently change meaning on a toolchain
 * upgrade. Map generation records seeds, so it needs a stream that is part of this project
 * (ENG-053).
 */
class Rng(seed: ULong) {
    var state: ULong = seed
        private set

    fun nextULong(): ULong {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z shr 30)) * MIX_A
        z = (z xor (z shr 27)) * MIX_B
        return z xor (z shr 31)
    }

    /** Uniform in `0 until bound`, rejecting the biased tail rather than taking a modulus. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }
        val limit = bound.toULong()
        val ceiling = ULong.MAX_VALUE - (ULong.MAX_VALUE % limit) - 1uL
        var draw = nextULong()
        while (draw > ceiling) draw = nextULong()
        return (draw % limit).toInt()
    }

    /**
     * Widths are computed in [Long]. Subtracting in [Int] overflows: `nextInt(MAX, MIN)` wrapped to
     * a width of 1 and returned a value from an empty interval, while the legitimate
     * `MIN until MAX` wrapped to -1 and was rejected.
     */
    fun nextInt(fromInclusive: Int, toExclusive: Int): Int {
        val width = toExclusive.toLong() - fromInclusive.toLong()
        require(width > 0L) { "empty range $fromInclusive until $toExclusive" }
        return (fromInclusive.toLong() + nextLong(width)).toInt()
    }

    private fun nextLong(bound: Long): Long {
        val limit = bound.toULong()
        val ceiling = ULong.MAX_VALUE - (ULong.MAX_VALUE % limit) - 1uL
        var draw = nextULong()
        while (draw > ceiling) draw = nextULong()
        return (draw % limit).toLong()
    }

    /** Uniform in `[0, 1)`, using the 53 bits a `Double` can hold exactly. */
    fun nextDouble(): Double = (nextULong() shr 11).toDouble() * DOUBLE_UNIT

    fun nextBoolean(): Boolean = nextULong() and 1uL == 1uL

    companion object {
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        /**
         * A stream of its own for one phase of one map. Phases are separated so that adding, say, a
         * decoration feature cannot shift what the spine generator draws and invalidate every
         * recorded test seed.
         */
        fun derive(seed: ULong, mapIndex: Int, phase: String): Rng =
            Rng(mix(mix(seed, mapIndex.toULong()), hash(phase)))

        private fun mix(left: ULong, right: ULong): ULong {
            var z = left xor (right + GOLDEN_GAMMA + (left shl 6) + (left shr 2))
            z = (z xor (z shr 30)) * MIX_A
            z = (z xor (z shr 27)) * MIX_B
            return z xor (z shr 31)
        }

        private fun hash(phase: String): ULong {
            var accumulator = 0xCBF29CE484222325uL
            for (character in phase) {
                accumulator = accumulator xor character.code.toULong()
                accumulator *= 0x100000001B3uL
            }
            return accumulator
        }
    }
}
