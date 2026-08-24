package io.github.ksean.cyberslop

import io.github.ksean.cyberslop.save.LocalStorageSavedGameAvailability
import io.github.ksean.cyberslop.title.createTitleScreenState
import io.github.ksean.cyberslop.title.renderTitleScreen
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

private const val GAME_ROOT_ID = "game-root"

fun main() {
    val root = requireNotNull(document.getElementById(GAME_ROOT_ID) as? HTMLElement) {
        "Missing #$GAME_ROOT_ID element"
    }
    val titleScreen = createTitleScreenState(LocalStorageSavedGameAvailability())

    renderTitleScreen(root, titleScreen)
}
