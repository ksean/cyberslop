package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.progression.PlayerProfile
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/** Draws the browser-only end screen and returns the same text for its live announcement. */
internal fun renderRunEndedScreen(
    root: HTMLElement,
    victory: Boolean,
    scrapBanked: Int,
    profile: PlayerProfile,
    onReturnToTitle: () -> Unit,
    onNewGame: () -> Unit,
): String {
    root.textContent = ""
    root.className = ""

    val headingText = if (victory) "Run complete" else "You died"
    val summaryText = "Scrap banked: $scrapBanked. Available: ${profile.spendableScrap}."
    root.appendChild((document.createElement("h1") as HTMLElement).apply { textContent = headingText })
    root.appendChild((document.createElement("p") as HTMLElement).apply { textContent = summaryText })

    val title = button("Return to title", onReturnToTitle)
    root.appendChild(title)
    root.appendChild(button("New game", onNewGame))
    title.focus()

    return "$headingText. $summaryText"
}

private fun button(name: String, action: () -> Unit): HTMLButtonElement =
    (document.createElement("button") as HTMLButtonElement).apply {
        type = "button"
        textContent = name
        onclick = { _ -> action() }
    }
