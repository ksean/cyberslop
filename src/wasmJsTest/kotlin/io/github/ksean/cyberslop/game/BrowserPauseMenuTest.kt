package io.github.ksean.cyberslop.game

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BrowserPauseMenuTest {
    private lateinit var root: HTMLElement

    @BeforeTest
    fun mountRoot() {
        root = document.createElement("main") as HTMLElement
        document.body?.appendChild(root)
    }

    @AfterTest
    fun removeRoot() {
        root.parentNode?.removeChild(root)
    }

    @Test
    fun `pause menu is an announced dialog with resume first and focused`() {
        val actions = mutableListOf<String>()

        val announcement = renderPauseMenu(
            root,
            onResume = { actions += "resume" },
            onReturnToTitle = { actions += "title" },
        )

        val dialog = root.querySelector("[role=dialog]") as HTMLElement
        val heading = root.querySelector("h1") as HTMLElement
        assertEquals("pause-overlay", root.className)
        assertEquals("true", dialog.getAttribute("aria-modal"))
        assertEquals(heading.id, dialog.getAttribute("aria-labelledby"))
        assertEquals("Paused", heading.textContent)
        assertEquals("Paused", announcement)
        assertEquals(listOf("Resume", "Return to title"), buttons().map { it.textContent })
        assertSame(buttons().first(), document.activeElement)

        buttons().forEach { it.click() }
        assertEquals(listOf("resume", "title"), actions)
    }

    private fun buttons(): List<HTMLButtonElement> {
        val elements = root.getElementsByTagName("button")
        return (0 until elements.length).map { elements.item(it) as HTMLButtonElement }
    }
}
