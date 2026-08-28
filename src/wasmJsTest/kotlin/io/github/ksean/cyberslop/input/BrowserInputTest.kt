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
        canvas.focus()
        key("keydown", "ArrowRight")
        input.keys()

        canvas.blur()

        assertEquals(Keys(), input.keys())
        assertEquals(false, input.paused)
    }

    private fun key(type: String, code: String) {
        window.dispatchEvent(KeyboardEvent(type, KeyboardEventInit(key = code, code = code)))
    }
}
