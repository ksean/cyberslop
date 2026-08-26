package io.github.ksean.cyberslop.title

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleScreenStateTest {
    @Test
    fun `continue game is withheld without a saved game`() {
        val state = createTitleScreenState(SavedGameAvailability { false })

        assertEquals(listOf(TitleScreenAction.NewGame), state.actions)
    }

    @Test
    fun `continue game precedes new game when a saved game exists`() {
        val state = createTitleScreenState(SavedGameAvailability { true })

        assertEquals(
            listOf(TitleScreenAction.ContinueGame, TitleScreenAction.NewGame),
            state.actions,
        )
    }

    @Test
    fun `the game needs no pointing device, so there is no aim setting to offer`() {
        val state = createTitleScreenState(SavedGameAvailability { false })

        assertEquals(1, state.actions.size, "an unexpected action is on the title screen")
    }
}
