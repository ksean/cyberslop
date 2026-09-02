package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.progression.DiscoveryEntry
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.progression.DiscoveryPause
import io.github.ksean.cyberslop.progression.DiscoveryUpdate

/** Browser orchestration around the common discovery timer. */
internal class DiscoverySession(
    private val record: (Iterable<DiscoveryId>) -> DiscoveryUpdate,
    private val clearInput: () -> Unit,
    private val announce: (String) -> Unit,
) {
    private val pause = DiscoveryPause()
    private var openedBeforeNextActiveFrame = false

    val active: DiscoveryEntry? get() = pause.active
    val paused: Boolean get() = pause.paused

    /** Persistence runs first; presentation never receives an id that was not already saved. */
    fun collect(collected: Iterable<DiscoveryId>): DiscoveryUpdate {
        val update = record(collected)
        if (pause.enqueue(update.entries)) {
            openedBeforeNextActiveFrame = true
            clearInput()
            announce(requireNotNull(active).announcement)
        }
        return update
    }

    /** Persists a lethal-tick discovery without letting its overlay obscure the death sequence. */
    fun recordOnly(collected: Iterable<DiscoveryId>): DiscoveryUpdate = record(collected)

    fun advance(deltaSeconds: Double, pageActive: Boolean): Boolean {
        // The animation-frame delta in which a simulation tick found the item predates the card.
        // Drop it at the first active render instead of charging invisible time to the interval.
        if (openedBeforeNextActiveFrame) {
            if (pageActive) openedBeforeNextActiveFrame = false
            return false
        }
        val before = active?.id
        pause.advance(deltaSeconds, pageActive)
        val after = active
        if (after?.id == before) return false
        if (after == null) {
            clearInput()
            return true
        }
        announce(after.announcement)
        return false
    }

    fun clear() {
        pause.clear()
        openedBeforeNextActiveFrame = false
    }
}
