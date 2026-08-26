package io.github.ksean.cyberslop.title

const val GAME_TITLE = "Cyberslop"

fun interface SavedGameAvailability {
    fun hasSavedGame(): Boolean
}

enum class TitleScreenAction(val accessibleName: String) {
    ContinueGame("Continue game"),
    NewGame("New game"),
}

data class TitleScreenState(
    val title: String,
    val actions: List<TitleScreenAction>,
)

fun createTitleScreenState(savedGames: SavedGameAvailability): TitleScreenState =
    TitleScreenState(
        title = GAME_TITLE,
        actions = availableActions(savedGames.hasSavedGame()),
    )

private fun availableActions(hasSavedGame: Boolean): List<TitleScreenAction> = buildList {
    if (hasSavedGame) add(TitleScreenAction.ContinueGame)
    add(TitleScreenAction.NewGame)
}
