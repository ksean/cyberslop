package io.github.ksean.cyberslop.screen

/**
 * Which screen the game is showing, and the only transitions between them.
 *
 * Platform-independent (ENG-010): the title screen's DOM and the game's canvas are two renderings of
 * this, not two sources of truth. Keeping the machine here is also what lets the death and victory
 * rules be tested without a browser.
 */
sealed interface ScreenState {
    data object Title : ScreenState

    data class Playing(val mapIndex: Int) : ScreenState

    /** The run is over. Permadeath (D1): there is no resuming from here. */
    data class Dead(val mapIndex: Int, val scrapEarned: Int) : ScreenState

    data class Victory(val scrapEarned: Int) : ScreenState
}

sealed interface ScreenEvent {
    data object NewGame : ScreenEvent
    data object ContinueGame : ScreenEvent
    data class BossDefeated(val scrapEarned: Int) : ScreenEvent
    data class PlayerDied(val scrapEarned: Int) : ScreenEvent
    data object ReturnToTitle : ScreenEvent
}

object ScreenRouter {
    const val FINAL_MAP = 10

    fun next(current: ScreenState, event: ScreenEvent, resumeAt: Int = 1): ScreenState =
        when (event) {
            ScreenEvent.NewGame -> ScreenState.Playing(mapIndex = 1)

            // Only meaningful from the title, and only when a run was actually in progress.
            ScreenEvent.ContinueGame ->
                if (current is ScreenState.Title) ScreenState.Playing(resumeAt) else current

            is ScreenEvent.BossDefeated -> when {
                current !is ScreenState.Playing -> current
                current.mapIndex >= FINAL_MAP -> ScreenState.Victory(event.scrapEarned)
                else -> ScreenState.Playing(current.mapIndex + 1)
            }

            is ScreenEvent.PlayerDied ->
                if (current is ScreenState.Playing) {
                    ScreenState.Dead(current.mapIndex, event.scrapEarned)
                } else {
                    current
                }

            ScreenEvent.ReturnToTitle -> ScreenState.Title
        }
}
