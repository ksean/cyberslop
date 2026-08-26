package io.github.ksean.cyberslop.input

import io.github.ksean.cyberslop.physics.Keys
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.KeyboardEvent

/**
 * The whole control scheme: four arrow keys.
 *
 * There is no pointer handling at all. Aiming is automatic, so the game needs no mouse rather than
 * merely offering a way to manage without one — which is a stronger position than the accessibility
 * requirement asked for, and a simpler one to keep true.
 */
class BrowserInput(@Suppress("UNUSED_PARAMETER") canvas: HTMLCanvasElement) {
    private val held = mutableSetOf<String>()

    var paused: Boolean = false
        private set

    fun attach() {
        window.onkeydown = { event: KeyboardEvent ->
            held.add(event.code)
            // Otherwise the arrows scroll the page out from under the game.
            if (event.code in MOVEMENT_KEYS) event.preventDefault()
        }
        window.onkeyup = { event: KeyboardEvent -> held.remove(event.code) }

        // A key held while the tab loses focus would otherwise stay held forever.
        window.onblur = { _ -> held.clear(); paused = true }
        window.onfocus = { _ -> paused = false }
    }

    fun keys(): Keys = Keys(
        left = "ArrowLeft" in held,
        right = "ArrowRight" in held,
        crouch = "ArrowDown" in held,
        jump = "ArrowUp" in held,
    )

    private companion object {
        val MOVEMENT_KEYS = setOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")
    }
}
