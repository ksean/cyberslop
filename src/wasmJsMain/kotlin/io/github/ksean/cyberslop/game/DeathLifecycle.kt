package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.sim.TickReport

internal enum class DeathDisposition { NotDying, Animating, ShowEndScreen }

/** One-time run commitment plus the report-driven boundary that reveals the death screen. */
internal class DeathLifecycle(
    private val commitRun: (scrap: Int) -> Boolean,
) {
    private var committed = false

    fun handle(report: TickReport, scrap: Int): DeathDisposition {
        if (!report.playerDied) return DeathDisposition.NotDying
        if (!committed) committed = commitRun(scrap)
        return if (report.deathSequenceComplete && committed) {
            DeathDisposition.ShowEndScreen
        } else {
            DeathDisposition.Animating
        }
    }

    fun reset() {
        committed = false
    }
}
