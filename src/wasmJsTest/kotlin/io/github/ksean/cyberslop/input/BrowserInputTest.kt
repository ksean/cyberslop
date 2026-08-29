package io.github.ksean.cyberslop.input

import io.github.ksean.cyberslop.physics.Keys
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Input wiring (P-50): what the browser delivers must reach the simulation, and stuck keys must be let go. */
class BrowserInputTest {
    private lateinit var canvas: HTMLCanvasElement
    private lateinit var input: BrowserInput

    @BeforeTest
    fun mount() {
        canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.setAttribute("tabindex", "0")
        document.body?.appendChild(canvas)
        input = BrowserInput(canvas).also { it.attach() }
    }

    @AfterTest
    fun unmount() {
        input.detach()
        canvas.parentNode?.removeChild(canvas)
    }

    @Test
    fun `keydown holds a key and keyup releases it`() {
        key("keydown", "ArrowRight")
        assertEquals(Keys(right = true), input.keys())

        key("keyup", "ArrowRight")
        assertEquals(Keys(), input.keys())
    }

    @Test
    fun `a press and release between samples is still seen once`() {
        key("keydown", "ArrowUp")
        key("keyup", "ArrowUp")

        assertEquals(Keys(jump = true), input.keys())
        assertEquals(Keys(), input.keys())
    }

    @Test
    fun `keypad arrows are read by key value when the code is not an arrow`() {
        window.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowLeft", code = "Numpad4")))

        assertEquals(Keys(left = true), input.keys())
    }

    @Test
    fun `wasd and space map to the four gameplay actions`() {
        binding("KeyA", Keys(left = true))
        binding("KeyD", Keys(right = true))
        binding("KeyS", Keys(crouch = true))
        binding("KeyW", Keys(jump = true))
        binding("Space", Keys(jump = true), keyValue = " ")
    }

    @Test
    fun `key value fallbacks are case insensitive and include legacy space`() {
        binding("Unidentified", Keys(left = true), keyValue = "A")
        binding("Unidentified", Keys(right = true), keyValue = "d")
        binding("Unidentified", Keys(crouch = true), keyValue = "S")
        binding("Unidentified", Keys(jump = true), keyValue = "w")
        binding("Unidentified", Keys(jump = true), keyValue = "Spacebar")
    }

    @Test
    fun `gameplay keydown prevents browser handling`() {
        listOf(
            "ArrowLeft" to "ArrowLeft",
            "KeyA" to "a",
            "KeyD" to "d",
            "KeyS" to "s",
            "KeyW" to "w",
            "Space" to " ",
        ).forEach { (code, value) ->
            val event = KeyboardEvent(
                "keydown",
                KeyboardEventInit(key = value, code = code, cancelable = true),
            )

            window.dispatchEvent(event)

            assertTrue(event.defaultPrevented, "$code should not scroll or activate the page")
            key("keyup", code, value)
            input.keys()
        }
    }

    @Test
    fun `releasing one of two aliases keeps their action held`() {
        key("keydown", "ArrowLeft")
        key("keydown", "KeyA", "a")
        assertEquals(Keys(left = true), input.keys())

        key("keyup", "ArrowLeft")
        assertEquals(Keys(left = true), input.keys())

        key("keyup", "KeyA", "a")
        assertEquals(Keys(), input.keys())
    }

    @Test
    fun `focus loss clears every physical alias`() {
        key("keydown", "ArrowUp")
        key("keydown", "Space", " ")
        input.keys()

        window.dispatchEvent(Event("blur"))
        window.dispatchEvent(Event("focus"))

        assertEquals(Keys(), input.keys())
        key("keyup", "ArrowUp")
        key("keyup", "Space", " ")
        assertEquals(Keys(), input.keys())
    }

    @Test
    fun `window blur releases every key and pauses`() {
        key("keydown", "ArrowRight")
        input.keys()

        window.dispatchEvent(Event("blur"))

        assertEquals(Keys(), input.keys())
        assertEquals(true, input.paused)
        window.dispatchEvent(Event("focus"))
        assertEquals(false, input.paused)
    }

    @Test
    fun `pagehide releases every key`() {
        key("keydown", "ArrowRight")
        input.keys()

        window.dispatchEvent(Event("pagehide"))

        assertEquals(Keys(), input.keys())
    }

    @Test
    fun `canvas focus loss releases every key without pausing`() {
        key("keydown", "ArrowRight")
        input.keys()

        // Dispatch the event directly: headless Firefox does not guarantee that a synthetic
        // `focus()` made this detached test surface the active element.
        canvas.dispatchEvent(Event("blur"))

        assertEquals(Keys(), input.keys())
        assertEquals(false, input.paused)
    }

    @Test
    fun `an explicit gameplay clear drops held sources and latched presses`() {
        key("keydown", "KeyD", "d")
        key("keydown", "Space", " ")
        key("keyup", "Space", " ")

        input.clear()

        assertEquals(Keys(), input.keys())
        key("keyup", "KeyD", "d")
        assertEquals(Keys(), input.keys(), "a cleared physical source survived until keyup")
    }

    private fun binding(code: String, expected: Keys, keyValue: String = code) {
        key("keydown", code, keyValue)
        assertEquals(expected, input.keys(), code)
        key("keyup", code, keyValue)
        assertEquals(Keys(), input.keys(), "$code released")
    }

    private fun key(type: String, code: String, keyValue: String = code) {
        window.dispatchEvent(KeyboardEvent(type, KeyboardEventInit(key = keyValue, code = code)))
    }
}
