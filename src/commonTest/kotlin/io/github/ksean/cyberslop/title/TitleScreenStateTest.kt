package io.github.ksean.cyberslop.title

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleScreenStateTest {
    @Test
    fun `new game is the only action without a saved game`() {
        val state = createTitleScreenState { false }

        assertEquals(listOf(TitleScreenAction.NewGame), state.actions)
    }

    @Test
    fun `continue game precedes new game when a saved game exists`() {
        val state = createTitleScreenState { true }

        assertEquals(
            listOf(TitleScreenAction.ContinueGame, TitleScreenAction.NewGame),
            state.actions,
        )
    }
}
