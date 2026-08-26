package io.github.ksean.cyberslop.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenRouterTest {
    @Test
    fun `a new game starts on the first map`() {
        assertEquals(
            ScreenState.Playing(mapIndex = 1),
            ScreenRouter.next(ScreenState.Title, ScreenEvent.NewGame),
        )
    }

    @Test
    fun `continuing resumes the map the run reached`() {
        assertEquals(
            ScreenState.Playing(mapIndex = 4),
            ScreenRouter.next(ScreenState.Title, ScreenEvent.ContinueGame, resumeAt = 4),
        )
    }

    @Test
    fun `defeating a boss advances to the next map`() {
        assertEquals(
            ScreenState.Playing(mapIndex = 3),
            ScreenRouter.next(ScreenState.Playing(2), ScreenEvent.BossDefeated(scrapEarned = 10)),
        )
    }

    @Test
    fun `defeating the last boss wins the run`() {
        assertEquals(
            ScreenState.Victory(scrapEarned = 40),
            ScreenRouter.next(
                ScreenState.Playing(ScreenRouter.FINAL_MAP),
                ScreenEvent.BossDefeated(scrapEarned = 40),
            ),
        )
    }

    @Test
    fun `dying ends the run`() {
        assertEquals(
            ScreenState.Dead(mapIndex = 7, scrapEarned = 22),
            ScreenRouter.next(ScreenState.Playing(7), ScreenEvent.PlayerDied(scrapEarned = 22)),
        )
    }

    @Test
    fun `a dead run cannot be continued`() {
        val dead = ScreenState.Dead(mapIndex = 7, scrapEarned = 22)

        assertEquals(dead, ScreenRouter.next(dead, ScreenEvent.ContinueGame, resumeAt = 7))
    }

    @Test
    fun `a dead run restarts from the first map, not where it ended`() {
        val dead = ScreenState.Dead(mapIndex = 7, scrapEarned = 22)

        assertEquals(
            ScreenState.Playing(mapIndex = 1),
            ScreenRouter.next(dead, ScreenEvent.NewGame),
        )
    }

    @Test
    fun `events that do not apply to the current screen change nothing`() {
        assertEquals(
            ScreenState.Title,
            ScreenRouter.next(ScreenState.Title, ScreenEvent.BossDefeated(scrapEarned = 5)),
        )
        assertEquals(
            ScreenState.Title,
            ScreenRouter.next(ScreenState.Title, ScreenEvent.PlayerDied(scrapEarned = 5)),
        )
    }

    @Test
    fun `returning to the title is always possible`() {
        listOf(
            ScreenState.Playing(3),
            ScreenState.Dead(3, 0),
            ScreenState.Victory(0),
        ).forEach {
            assertEquals(ScreenState.Title, ScreenRouter.next(it, ScreenEvent.ReturnToTitle))
        }
    }
}
