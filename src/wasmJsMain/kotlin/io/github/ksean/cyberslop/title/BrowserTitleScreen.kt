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
    root.appendChild(titleElement(state.title))
    state.actions.forEach { action ->
        root.appendChild(actionButton(action, onAction))
    }
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
