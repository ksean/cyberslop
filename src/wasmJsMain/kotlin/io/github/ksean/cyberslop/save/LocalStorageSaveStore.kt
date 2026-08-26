package io.github.ksean.cyberslop.save

import io.github.ksean.cyberslop.run.MetaProgression
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.run.SaveCodec
import io.github.ksean.cyberslop.title.SavedGameAvailability
import kotlinx.browser.localStorage

internal const val RUN_KEY = "cyberslop.run.v1"
internal const val META_KEY = "cyberslop.meta.v1"

/**
 * Browser persistence for a run in progress.
 *
 * Availability is decided by **decoding the save**, not by a separate flag. A boolean marker can
 * outlive the thing it describes — a save written by an older deployment, or cleared storage — and
 * then offers `Continue game` for something unreadable. TITLE-003 asks for a *valid* previous save,
 * and the only way to know is to read it.
 *
 * Every access is wrapped: storage throws outright in a private window or when site data is blocked,
 * and a game that cannot save should still be a game that runs.
 */
class LocalStorageSaveStore : SavedGameAvailability {
    override fun hasSavedGame(): Boolean = load() != null

    fun load(): Pair<RunState, MetaProgression>? =
        runCatching { localStorage.getItem(RUN_KEY) }
            .getOrNull()
            ?.let { SaveCodec.decodeRun(it).getOrNull() }

    fun save(run: RunState, meta: MetaProgression) {
        runCatching { localStorage.setItem(RUN_KEY, SaveCodec.encodeRun(run, meta)) }
    }

    /** Death ends the run (PROD-031), so the in-progress save must not survive it. */
    fun clearRun() {
        runCatching { localStorage.removeItem(RUN_KEY) }
    }

    /**
     * Meta-progression outlives any single run, so it is stored separately. Keeping it only inside
     * the run save meant pressing `New game` replaced it with defaults and threw away every Scrap
     * the player had banked.
     */
    fun loadMeta(): MetaProgression =
        runCatching { localStorage.getItem(META_KEY) }
            .getOrNull()
            ?.let { encoded ->
                val scrap = encoded.toIntOrNull() ?: return@let null
                MetaProgression(scrap, MetaProgression.unlocksFor(scrap))
            }
            ?: MetaProgression()

    fun saveMeta(meta: MetaProgression) {
        runCatching { localStorage.setItem(META_KEY, meta.scrap.toString()) }
    }
}
