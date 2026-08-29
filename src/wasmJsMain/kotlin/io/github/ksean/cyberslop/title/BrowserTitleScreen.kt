package io.github.ksean.cyberslop.title

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

internal fun renderTitleScreen(
    root: HTMLElement,
    state: TitleScreenState,
    onAction: (TitleScreenAction) -> Unit = {},
) {
    root.textContent = ""
    root.className = ""
    root.appendChild(titleElement(state.title))
    root.appendChild(tagline())
    state.actions.forEach { action ->
        root.appendChild(actionButton(action, onAction))
    }
}

/**
 * Says what the run is, in one line.
 *
 * A heading and two buttons told a first-time player nothing about the game they were starting. It
 * is a paragraph rather than anything focusable, so it adds nothing to the keyboard path
 * (PROD-004).
 */
private fun tagline(): HTMLElement =
    (document.createElement("p") as HTMLElement).apply {
        className = "tagline"
        textContent = "Ten maps. One way out."
    }

private fun titleElement(title: String): HTMLElement =
    (document.createElement("h1") as HTMLElement).apply {
        textContent = title
    }

private fun actionButton(
    action: TitleScreenAction,
    onAction: (TitleScreenAction) -> Unit,
): HTMLButtonElement =
    (document.createElement("button") as HTMLButtonElement).apply {
        type = "button"
        textContent = action.accessibleName
        onclick = { _ -> onAction(action) }
    }
