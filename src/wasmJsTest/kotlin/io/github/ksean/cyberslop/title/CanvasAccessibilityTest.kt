package io.github.ksean.cyberslop.title

import io.github.ksean.cyberslop.configureGameplayCanvas
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The gameplay surface has to be reachable and describable, because a canvas exposes nothing by
 * itself. PROD-004 is met by keeping non-gameplay UI keyboard-operable *and* by making the canvas
 * announce what is happening.
 */
class CanvasAccessibilityTest {
    private lateinit var canvas: HTMLCanvasElement
    private lateinit var status: HTMLElement

    @BeforeTest
    fun mount() {
        canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.setAttribute("id", "game-canvas")
        configureGameplayCanvas(canvas)

        status = document.createElement("p") as HTMLElement
        status.setAttribute("id", "game-status")
        status.setAttribute("role", "status")
        status.setAttribute("aria-live", "polite")

        document.body?.appendChild(canvas)
        document.body?.appendChild(status)
    }

    @AfterTest
    fun unmount() {
        canvas.parentNode?.removeChild(canvas)
        status.parentNode?.removeChild(status)
    }

    @Test
    fun `the canvas can take keyboard focus`() {
        canvas.focus()

        assertEquals(canvas, document.activeElement)
    }

    @Test
    fun `the canvas carries a role and an accessible name`() {
        assertEquals("application", canvas.getAttribute("role"))
        val accessibleName = canvas.getAttribute("aria-label").orEmpty()
        assertTrue(accessibleName.contains("Arrow"))
        assertTrue(accessibleName.contains("WASD"))
        assertTrue(accessibleName.contains("Space"))
        assertTrue(accessibleName.contains("automatically"))
        assertTrue(accessibleName.contains("Escape"))
    }

    @Test
    fun `run state is announced through a live region`() {
        assertEquals("polite", status.getAttribute("aria-live"))
        assertEquals("status", status.getAttribute("role"))
        assertEquals("game-status", canvas.getAttribute("aria-describedby"))

        status.textContent = "Map 3 of 10, Flooded Undercity. Health 140."

        assertNotNull(document.getElementById("game-status")?.textContent)
        assertTrue(status.textContent.orEmpty().contains("Map 3"))
    }
}
