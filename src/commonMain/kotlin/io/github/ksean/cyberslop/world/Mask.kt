package io.github.ksean.cyberslop.world

/**
 * A grid of protected cells.
 *
 * Two of these guard the critical path. [Level.floorMask] covers the geometry the spine carved and
 * may not be written at all after generation; [Level.arcMask] covers the volume the player's box
 * sweeps through while executing the spine, and may never receive a solid tile. Without the second,
 * decoration is free to hang a sign in the middle of a jump and break a corridor while every check
 * on the floor still passes.
 */
class Mask(val width: Int, val height: Int) {
    private val cells = Array(height) { BooleanArray(width) }

    operator fun get(x: Int, y: Int): Boolean =
        if (contains(x, y)) cells[y][x] else false

    operator fun set(x: Int, y: Int, value: Boolean) {
        if (contains(x, y)) cells[y][x] = value
    }

    fun markRect(left: Int, top: Int, right: Int, bottom: Int) {
        for (x in left..right) for (y in top..bottom) this[x, y] = true
    }

    private fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height
}
