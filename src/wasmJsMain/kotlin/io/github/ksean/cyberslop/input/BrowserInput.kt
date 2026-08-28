package io.github.ksean.cyberslop.input

import io.github.ksean.cyberslop.physics.Key
import io.github.ksean.cyberslop.physics.KeyLedger
import io.github.ksean.cyberslop.physics.Keys
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent

/** `Document.hidden` is not in kotlinx-browser's declarations. */
private fun documentHidden(): Boolean = js("document.hidden")
private fun documentHasFocus(): Boolean = js("document.hasFocus()")

/**
 * The whole control scheme: four arrow keys.
 *
 * There is no pointer handling at all. Aiming is automatic, so the game needs no mouse rather than
 * merely offering a way to manage without one — which is a stronger position than the accessibility
 * requirement asked for, and a simpler one to keep true.
 *
 * Events go into a [KeyLedger], which latches presses until the simulation samples them; the
 * browser delivers events between frames and a tap could otherwise fall between two ticks and
 * never be seen. Every path that can lose a `keyup` — the window losing focus, the page being
 * hidden or put away, the canvas losing focus — releases all keys, since a key the browser never
 * reports released would otherwise be held forever (`specs/simulation.md`, key ledger).
 */
class BrowserInput(private val canvas: HTMLCanvasElement) {
    private val ledger = KeyLedger()
    private val listeners = mutableListOf<Pair<EventTarget, Pair<String, (Event) -> Unit>>>()

    var paused: Boolean = false
        private set

    fun attach() {
        listen(window, "keydown") { event ->
            val key = keyOf(event as KeyboardEvent) ?: return@listen
            ledger.press(key)
            // Otherwise the arrows scroll the page out from under the game.
            event.preventDefault()
        }
        listen(window, "keyup") { event -> keyOf(event as KeyboardEvent)?.let(ledger::release) }

        listen(window, "blur") { _ -> ledger.releaseAll(); paused = true }
        listen(window, "focus") { _ -> paused = false }
        listen(window, "pagehide") { _ -> ledger.releaseAll() }
        listen(document, "visibilitychange") { _ ->
            if (documentHidden()) {
                ledger.releaseAll()
                paused = true
            } else {
                // Shown again in a window that is not focused stays paused until focus returns.
                paused = !documentHasFocus()
            }
        }
        listen(canvas, "blur") { _ -> ledger.releaseAll() }
    }

    fun detach() {
        listeners.forEach { (target, listener) -> target.removeEventListener(listener.first, listener.second) }
        listeners.clear()
    }

    fun keys(): Keys = ledger.sample()

    private fun listen(target: EventTarget, type: String, handler: (Event) -> Unit) {
        target.addEventListener(type, handler)
        listeners.add(target to (type to handler))
    }

    private companion object {
        val KEYS = mapOf(
            "ArrowLeft" to Key.Left,
            "ArrowRight" to Key.Right,
            "ArrowDown" to Key.Crouch,
            "ArrowUp" to Key.Jump,
        )

        /** By physical position first; by value so the keypad's arrows work with NumLock off. */
        fun keyOf(event: KeyboardEvent): Key? = KEYS[event.code] ?: KEYS[event.key]
    }
}
