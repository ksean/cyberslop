package io.github.ksean.cyberslop.game

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/** Renders the keyboard-operable in-map pause dialog and returns its live announcement. */
internal fun renderPauseMenu(
    root: HTMLElement,
    onResume: () -> Unit,
    onReturnToTitle: () -> Unit,
): String {
    root.textContent = ""
    root.className = "pause-overlay"

    val heading = (document.createElement("h1") as HTMLElement).apply {
        id = "pause-heading"
        textContent = PAUSED
    }
    val dialog = (document.createElement("section") as HTMLElement).apply {
        className = "pause-dialog"
        setAttribute("role", "dialog")
        setAttribute("aria-modal", "true")
        setAttribute("aria-labelledby", heading.id)
    }
    dialog.appendChild(heading)
    val resume = pauseButton("Resume", onResume)
    dialog.appendChild(resume)
    dialog.appendChild(pauseButton("Return to title", onReturnToTitle))
    root.appendChild(dialog)
    resume.focus()

    return PAUSED
}

private fun pauseButton(name: String, action: () -> Unit): HTMLButtonElement =
    (document.createElement("button") as HTMLButtonElement).apply {
        type = "button"
        textContent = name
        onclick = { _ -> action() }
    }

private const val PAUSED = "Paused"
