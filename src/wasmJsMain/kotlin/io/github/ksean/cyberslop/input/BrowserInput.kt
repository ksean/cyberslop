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
 * The whole control scheme: four actions on arrows or A/D/S/W, with Space also jumping.
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
    private val downBindings = mutableMapOf<String, Key>()
    private val listeners = mutableListOf<Pair<EventTarget, Pair<String, (Event) -> Unit>>>()

    var paused: Boolean = false
        private set
    /** Changes at every window/page pause boundary, even if both edges occur between frames. */
    internal var activityRevision: Int = 0
        private set

    fun attach() {
        listen(window, "keydown") { event ->
            val keyEvent = event as KeyboardEvent
            val binding = bindingOf(keyEvent) ?: return@listen
            press(binding)
            // Otherwise arrows and Space scroll the page out from under the game.
            event.preventDefault()
        }
        listen(window, "keyup") { event -> bindingOf(event as KeyboardEvent)?.let(::release) }

        listen(window, "blur") { _ -> releaseAll(); setPaused(true) }
        listen(window, "focus") { _ -> setPaused(false) }
        listen(window, "pagehide") { _ -> releaseAll() }
        listen(document, "visibilitychange") { _ ->
            if (documentHidden()) {
                releaseAll()
                setPaused(true)
            } else {
                // Shown again in a window that is not focused stays paused until focus returns.
                setPaused(!documentHasFocus())
            }
        }
        listen(canvas, "blur") { _ -> releaseAll() }
    }

    fun detach() {
        listeners.forEach { (target, listener) -> target.removeEventListener(listener.first, listener.second) }
        listeners.clear()
        releaseAll()
    }

    fun keys(): Keys = ledger.sample()

    /** Discovery and lifecycle boundaries discard both held sources and unsampled presses. */
    fun clear() {
        releaseAll()
    }

    private fun press(binding: Binding) {
        if (binding.source in downBindings) return
        val actionAlreadyDown = downBindings.values.any { it == binding.action }
        downBindings[binding.source] = binding.action
        if (!actionAlreadyDown) ledger.press(binding.action)
    }

    private fun release(binding: Binding) {
        val action = downBindings.remove(binding.source) ?: return
        if (downBindings.values.none { it == action }) ledger.release(action)
    }

    private fun releaseAll() {
        downBindings.clear()
        ledger.releaseAll()
    }

    private fun setPaused(next: Boolean) {
        if (paused == next) return
        paused = next
        activityRevision++
    }

    private fun listen(target: EventTarget, type: String, handler: (Event) -> Unit) {
        target.addEventListener(type, handler)
        listeners.add(target to (type to handler))
    }

    private companion object {
        val CODE_BINDINGS = mapOf(
            "ArrowLeft" to Key.Left,
            "ArrowRight" to Key.Right,
            "ArrowDown" to Key.Crouch,
            "ArrowUp" to Key.Jump,
            "KeyA" to Key.Left,
            "KeyD" to Key.Right,
            "KeyS" to Key.Crouch,
            "KeyW" to Key.Jump,
            "Space" to Key.Jump,
        )

        val VALUE_BINDINGS = mapOf(
            "arrowleft" to Key.Left,
            "arrowright" to Key.Right,
            "arrowdown" to Key.Crouch,
            "arrowup" to Key.Jump,
            "a" to Key.Left,
            "d" to Key.Right,
            "s" to Key.Crouch,
            "w" to Key.Jump,
            " " to Key.Jump,
            "spacebar" to Key.Jump,
        )

        /** By physical position first; by value so keypad arrows and assistive events work too. */
        fun bindingOf(event: KeyboardEvent): Binding? {
            CODE_BINDINGS[event.code]?.let { return Binding("code:${event.code}", it) }
            val value = event.key.lowercase()
            return VALUE_BINDINGS[value]?.let { Binding("key:$value", it) }
        }
    }

    private data class Binding(val source: String, val action: Key)
}
