package io.github.ksean.cyberslop.progression

/** Deterministic, input-free timing for the unskippable first-pickup card queue. */
class DiscoveryPause(
    private val secondsPerCard: Double = SECONDS_PER_CARD,
) {
    private val queue = mutableListOf<DiscoveryEntry>()

    var activeElapsedSeconds: Double = 0.0
        private set

    init {
        require(secondsPerCard.isFinite() && secondsPerCard > 0.0) {
            "invalid discovery-card duration $secondsPerCard"
        }
    }

    val active: DiscoveryEntry? get() = queue.firstOrNull()
    val paused: Boolean get() = queue.isNotEmpty()
    val queuedCount: Int get() = queue.size

    /** Adds only ids that are not already active or waiting; returns true when a first card opens. */
    fun enqueue(entries: Iterable<DiscoveryEntry>): Boolean {
        val opened = queue.isEmpty()
        val queuedIds = queue.mapTo(mutableSetOf()) { it.id }
        entries.forEach { entry ->
            if (queuedIds.add(entry.id)) queue += entry
        }
        return opened && queue.isNotEmpty()
    }

    /**
     * Advances at most the card already visible at the start of this call. Any excess is discarded
     * so a newly revealed card receives its own full interval.
     */
    fun advance(deltaSeconds: Double, pageActive: Boolean) {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0.0) {
            "invalid discovery-card delta $deltaSeconds"
        }
        if (!pageActive || queue.isEmpty()) return
        activeElapsedSeconds += deltaSeconds
        if (activeElapsedSeconds + TIME_EPSILON < secondsPerCard) return
        queue.removeAt(0)
        activeElapsedSeconds = 0.0
    }

    fun clear() {
        queue.clear()
        activeElapsedSeconds = 0.0
    }

    companion object {
        const val SECONDS_PER_CARD = 3.0
        private const val TIME_EPSILON = 1e-9
    }
}
