package io.github.ksean.cyberslop

import io.github.ksean.cyberslop.game.GameHost
import io.github.ksean.cyberslop.save.LocalStorageSaveStore
import io.github.ksean.cyberslop.title.createTitleScreenState
import io.github.ksean.cyberslop.title.createShopScreenState
import io.github.ksean.cyberslop.title.renderShopScreen
import io.github.ksean.cyberslop.title.renderTitleScreen
import io.github.ksean.cyberslop.title.TitleScreenAction
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

private const val GAME_ROOT_ID = "game-root"
private const val GAME_CANVAS_ID = "game-canvas"
private const val GAME_STATUS_ID = "game-status"
private const val GAMEPLAY_ACCESSIBLE_NAME =
    "Cyberslop gameplay. Use Arrow keys or WASD to move and crouch; use Arrow Up, W, or Space " +
        "to jump. The weapon fires automatically."

internal fun configureGameplayCanvas(canvas: HTMLCanvasElement) {
    canvas.tabIndex = 0
    canvas.setAttribute("role", "application")
    canvas.setAttribute("aria-label", GAMEPLAY_ACCESSIBLE_NAME)
    canvas.setAttribute("aria-describedby", GAME_STATUS_ID)
}

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
    configureGameplayCanvas(canvas)

    val saves = LocalStorageSaveStore()
    lateinit var showTitle: () -> Unit
    lateinit var showShop: () -> Unit
    lateinit var host: GameHost

    showShop = {
        renderShopScreen(
            root,
            createShopScreenState(saves.loadProfile()),
            onPurchase = { id, expectedRank ->
                saves.purchase(id, expectedRank)
                showShop()
            },
            onBack = showTitle,
        )
    }
    showTitle = {
        canvas.style.display = "none"
        root.style.display = "flex"
        renderTitleScreen(root, createTitleScreenState(saves)) { action ->
            when (action) {
                TitleScreenAction.Shop -> showShop()
                else -> host.start(action)
            }
        }
    }
    host = GameHost(root, canvas, saves, onReturnToTitle = showTitle)

    showTitle()
}
