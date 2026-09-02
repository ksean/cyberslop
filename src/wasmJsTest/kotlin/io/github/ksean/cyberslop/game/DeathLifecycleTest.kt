package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.sim.TickReport
import kotlin.test.Test
import kotlin.test.assertEquals

class DeathLifecycleTest {
    @Test
    fun `terminal entry commits once and only completion requests the end screen`() {
        val committed = mutableListOf<Int>()
        val lifecycle = DeathLifecycle { scrap ->
            committed += scrap
            true
        }

        assertEquals(DeathDisposition.NotDying, lifecycle.handle(TickReport(), scrap = 40))
        assertEquals(
            DeathDisposition.Animating,
            lifecycle.handle(TickReport(playerDied = true), scrap = 40),
        )
        repeat(300) {
            assertEquals(
                DeathDisposition.Animating,
                lifecycle.handle(TickReport(playerDied = true), scrap = 40),
            )
        }
        assertEquals(listOf(40), committed)

        assertEquals(
            DeathDisposition.ShowEndScreen,
            lifecycle.handle(
                TickReport(playerDied = true, deathSequenceComplete = true),
                scrap = 40,
            ),
        )
        assertEquals(listOf(40), committed)
    }

    @Test
    fun `a rejected commit does not claim terminal ownership`() {
        var attempts = 0
        val lifecycle = DeathLifecycle {
            attempts++
            attempts > 1
        }
        val report = TickReport(playerDied = true)

        assertEquals(DeathDisposition.Animating, lifecycle.handle(report, 5))
        assertEquals(DeathDisposition.Animating, lifecycle.handle(report, 5))
        assertEquals(2, attempts)
    }
}
