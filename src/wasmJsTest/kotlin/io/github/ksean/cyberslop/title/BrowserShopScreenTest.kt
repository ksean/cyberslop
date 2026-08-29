package io.github.ksean.cyberslop.title

import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.progression.UpgradeId
import io.github.ksean.cyberslop.progression.UpgradeRanks
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserShopScreenTest {
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
    fun `renders balance and every row as readable text`() {
        renderShopScreen(
            root,
            createShopScreenState(PlayerProfile(400, 2_000, UpgradeRanks(2, 5, 0))),
        )

        val text = root.textContent.orEmpty()
        assertTrue(text.contains("Available Scrap: 400"))
        assertTrue(text.contains("Reinforced Chassis"))
        assertTrue(text.contains("Rank 2 of 5"))
        assertTrue(text.contains("Maximum health +20%"))
        assertTrue(text.contains("Next rank: 500 Scrap"))
        assertTrue(text.contains("Black-Market Firmware"))
        assertTrue(text.contains("Max rank"))
        assertTrue(text.contains("Reactive Dermal Weave"))
    }

    @Test
    fun `purchase buttons expose catalog order and disabled states then Back`() {
        renderShopScreen(
            root,
            createShopScreenState(PlayerProfile(400, 2_000, UpgradeRanks(2, 5, 0))),
        )

        assertEquals(
            listOf(
                "Buy Reinforced Chassis — 500 Scrap",
                "Max rank — Black-Market Firmware",
                "Buy Reactive Dermal Weave — 100 Scrap",
                "Back",
            ),
            buttons().map { it.textContent.orEmpty() },
        )
        assertTrue(buttons()[0].disabled, "unaffordable purchase is enabled")
        assertTrue(buttons()[1].disabled, "max-rank purchase is enabled")
        assertFalse(buttons()[2].disabled, "affordable purchase is disabled")

        listOf(buttons()[2], buttons()[3]).forEach { button ->
            button.focus()
            assertSame(button, document.activeElement)
        }
    }

    @Test
    fun `purchase reports id and expected rank and a refresh shows the new profile`() {
        var profile = PlayerProfile(350, 1_000)
        val purchases = mutableListOf<Pair<UpgradeId, Int>>()

        fun draw() {
            renderShopScreen(
                root,
                createShopScreenState(profile),
                onPurchase = { id, expectedRank ->
                    purchases += id to expectedRank
                    if (profile.upgrades.rankOf(id) == expectedRank) profile = profile.purchasing(id)
                    draw()
                },
            )
        }
        draw()
        buttons().first().click()

        assertEquals(listOf(UpgradeId.ReinforcedChassis to 0), purchases)
        assertTrue(root.textContent.orEmpty().contains("Available Scrap: 250"))
        assertTrue(root.textContent.orEmpty().contains("Rank 1 of 5"))
        assertTrue(root.textContent.orEmpty().contains("Maximum health +10%"))
    }

    @Test
    fun `Back is a real button and invokes its route`() {
        var returned = false
        renderShopScreen(root, createShopScreenState(PlayerProfile()), onBack = { returned = true })

        buttons().last().click()

        assertTrue(returned)
    }

    private fun buttons(): List<HTMLButtonElement> {
        val elements = root.getElementsByTagName("button")
        return (0 until elements.length).map { elements.item(it) as HTMLButtonElement }
    }
}
