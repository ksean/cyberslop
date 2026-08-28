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
    fun `actions are keyboard focusable`() {
        renderTitleScreen(root, stateWithSavedGame(available = true))

        buttons().forEach { button ->
            button.focus()
            assertSame(button, document.activeElement)
        }
    }

    /**
     * Supersedes TITLE-005, which made activation a deliberate no-op with a test
     * asserting the screen did not change. `specs/product.md` gives the buttons their behaviour, so the
     * assertion is replaced rather than deleted: activating an action must report exactly which
     * action was chosen.
     */
    @Test
    fun `activating an action reports which one was chosen`() {
        val chosen = mutableListOf<TitleScreenAction>()
        renderTitleScreen(root, stateWithSavedGame(available = true)) { chosen.add(it) }

        buttons().forEach { it.click() }

        assertEquals(listOf(TitleScreenAction.ContinueGame, TitleScreenAction.NewGame), chosen)
    }

    /**
     * PROD-048 restyled these screens. The restyle must not have cost the keyboard path or the
     * accessible names PROD-004 requires, and decoration must not be reachable by tabbing.
     */
    @Test
    fun `the restyle adds nothing to the keyboard path`() {
        renderTitleScreen(root, stateWithSavedGame(available = true))

        val tagline = root.querySelector(".tagline") as? HTMLElement
        assertEquals("Ten maps. One way out.", tagline?.textContent)
        assertEquals(
            -1,
            tagline?.tabIndex ?: -1,
            "the tagline is focusable, so it sits between the title and the first action",
        )
        assertEquals(
            listOf("Continue game", "New game"),
            buttonNames(),
            "the restyle changed what the actions are called",
        )
    }

    private fun stateWithSavedGame(available: Boolean): TitleScreenState =
        createTitleScreenState(SavedGameAvailability { available })

    private fun buttonNames(): List<String> =
        buttons().map { it.textContent.orEmpty() }

    private fun buttons(): List<HTMLButtonElement> {
        val elements = root.getElementsByTagName("button")
        return (0 until elements.length).map { index ->
            elements.item(index) as HTMLButtonElement
        }
    }
}
