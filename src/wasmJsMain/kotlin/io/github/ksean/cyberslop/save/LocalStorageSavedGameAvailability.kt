package io.github.ksean.cyberslop.save

import io.github.ksean.cyberslop.title.SavedGameAvailability
import kotlinx.browser.localStorage

internal const val SAVED_GAME_AVAILABLE_KEY = "cyberslop.saved-game.available"

internal class LocalStorageSavedGameAvailability : SavedGameAvailability {
    override fun hasSavedGame(): Boolean =
        runCatching { localStorage.getItem(SAVED_GAME_AVAILABLE_KEY) }
            .getOrNull() == "true"
}
