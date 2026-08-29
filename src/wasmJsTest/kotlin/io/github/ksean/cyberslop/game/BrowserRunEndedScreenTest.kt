package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.progression.PlayerProfile
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BrowserRunEndedScreenTest {
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
    fun `ended run offers Return to title before New game and focuses it`() {
        val actions = mutableListOf<String>()
        renderRunEndedScreen(
            root,
            victory = false,
            scrapBanked = 220,
            profile = PlayerProfile(720, 1_400),
            onReturnToTitle = { actions += "title" },
            onNewGame = { actions += "new" },
        )

        assertEquals("You died", root.querySelector("h1")?.textContent)
        assertEquals("Scrap banked: 220. Available: 720.", root.querySelector("p")?.textContent)
        assertEquals(listOf("Return to title", "New game"), buttons().map { it.textContent })
        assertSame(buttons().first(), document.activeElement)

        buttons().forEach { it.click() }
        assertEquals(listOf("title", "new"), actions)
    }

    private fun buttons(): List<HTMLButtonElement> {
        val elements = root.getElementsByTagName("button")
        return (0 until elements.length).map { elements.item(it) as HTMLButtonElement }
    }
}
