package io.github.ksean.cyberslop.title

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BrowserTitleScreenTest {
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
    fun `renders the title and new game action without a saved game`() {
        renderTitleScreen(root, stateWithSavedGame(available = false))

        assertEquals("Cyberslop", root.querySelector("h1")?.textContent)
        assertEquals(listOf("New game"), buttonNames())
    }

    @Test
    fun `renders continue game before new game when a saved game exists`() {
        renderTitleScreen(root, stateWithSavedGame(available = true))

        assertEquals(listOf("Continue game", "New game"), buttonNames())
    }

    @Test
    fun `actions receive focus and remain placeholders when activated`() {
        renderTitleScreen(root, stateWithSavedGame(available = true))
        val originalMarkup = root.innerHTML

        buttons().forEach { button ->
            button.focus()
            assertSame(button, document.activeElement)

            button.click()
            assertEquals(originalMarkup, root.innerHTML)
        }
    }

    private fun stateWithSavedGame(available: Boolean): TitleScreenState =
        createTitleScreenState { available }

    private fun buttonNames(): List<String> =
        buttons().map { it.textContent.orEmpty() }

    private fun buttons(): List<HTMLButtonElement> {
        val elements = root.getElementsByTagName("button")
        return (0 until elements.length).map { index ->
            elements.item(index) as HTMLButtonElement
        }
    }
}
