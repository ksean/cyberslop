package io.github.ksean.cyberslop.physics

/** The four controls (PROD-021), named by what they do rather than by which key sends them. */
enum class Key { Left, Right, Crouch, Jump }

/**
 * Joins key events, which arrive between animation frames, to the simulation, which samples once per
 * fixed tick (`specs/simulation.md`, key ledger).
 *
 * A press is latched until a sample consumes it, so a tap that begins and ends between two samples
 * is still seen exactly once. Without the latch a short press — or any press during a frame stall —
 * was simply never there as far as [IntentFilter] could tell (P-48).
 */
class KeyLedger {
    private val down = mutableSetOf<Key>()
    private val pressedSinceSample = mutableSetOf<Key>()

    fun press(key: Key) {
        if (down.add(key)) pressedSinceSample.add(key)
    }

    fun release(key: Key) {
        down.remove(key)
    }

    /** For when a `keyup` may have been lost: focus loss, a hidden page, a page being put away. */
    fun releaseAll() {
        down.clear()
        pressedSinceSample.clear()
    }

    fun sample(): Keys {
        val held = down + pressedSinceSample
        pressedSinceSample.clear()
        return Keys(
            left = Key.Left in held,
            right = Key.Right in held,
            crouch = Key.Crouch in held,
            jump = Key.Jump in held,
        )
    }
}
