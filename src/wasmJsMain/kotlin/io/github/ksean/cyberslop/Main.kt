package io.github.ksean.cyberslop

import io.github.ksean.cyberslop.game.GameHost
import io.github.ksean.cyberslop.save.LocalStorageSaveStore
import io.github.ksean.cyberslop.title.createTitleScreenState
import io.github.ksean.cyberslop.title.renderTitleScreen
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

private const val GAME_ROOT_ID = "game-root"
private const val GAME_CANVAS_ID = "game-canvas"

/**
 * Composition root only (ENG-012). It wires the browser to the simulation and holds no game rules:
 * what the camera follows, how screens transition and how the player moves all live in `commonMain`,
 * where they can be tested without a browser.
 */
fun main() {
    val root = requireNotNull(document.getElementById(GAME_ROOT_ID) as? HTMLElement) {
        "Missing #$GAME_ROOT_ID element"
    }
    val canvas = requireNotNull(document.getElementById(GAME_CANVAS_ID) as? HTMLCanvasElement) {
        "Missing #$GAME_CANVAS_ID element"
    }

    val saves = LocalStorageSaveStore()
    val host = GameHost(root, canvas, saves)

    renderTitleScreen(root, createTitleScreenState(saves)) { action -> host.start(action) }
}
