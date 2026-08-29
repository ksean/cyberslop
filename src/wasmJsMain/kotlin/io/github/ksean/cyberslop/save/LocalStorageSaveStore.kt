package io.github.ksean.cyberslop.save

import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.progression.ProfileCodec
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.progression.DiscoveryRecorder
import io.github.ksean.cyberslop.progression.DiscoveryUpdate
import io.github.ksean.cyberslop.progression.UpgradeId
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.run.SaveCodec
import io.github.ksean.cyberslop.title.SavedGameAvailability
import kotlinx.browser.localStorage

internal const val RUN_KEY = "cyberslop.run.v1"
internal const val PROFILE_KEY = "cyberslop.profile.v1"
/** Legacy integer metadata, retained only as a migration source. */
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

    fun load(): Pair<RunState, PlayerProfile>? {
        val decoded = decodeStoredRun() ?: return null
        val profile = loadProfile(decoded.legacyMetaScrap)
        // A valid legacy run is immediately rewritten without its stale profile snapshot.
        if (decoded.legacyMetaScrap != null) save(decoded.run)
        return decoded.run to profile
    }

    fun save(run: RunState) {
        runCatching { localStorage.setItem(RUN_KEY, SaveCodec.encodeRun(run)) }
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
    fun loadProfile(): PlayerProfile = loadProfile(decodeStoredRun()?.legacyMetaScrap)

    fun saveProfile(profile: PlayerProfile) {
        runCatching { localStorage.setItem(PROFILE_KEY, ProfileCodec.encode(profile)) }
    }

    /** Rank-checked so a stale double activation cannot buy the next rank as a second transaction. */
    fun purchase(id: UpgradeId, expectedRank: Int): PlayerProfile {
        val current = loadProfile()
        if (current.upgrades.rankOf(id) != expectedRank) return current
        val purchased = current.purchasing(id)
        if (purchased !== current) saveProfile(purchased)
        return purchased
    }

    /** Saves newly seen ids before returning the entries that presentation may enqueue. */
    fun recordDiscoveries(collected: Iterable<DiscoveryId>): DiscoveryUpdate {
        val update = DiscoveryRecorder.record(loadProfile(), collected)
        if (update.entries.isNotEmpty()) saveProfile(update.profile)
        return update
    }

    private fun loadProfile(runLegacyScrap: Int?): PlayerProfile {
        val current = runCatching { localStorage.getItem(PROFILE_KEY) }.getOrNull()
            ?.let { ProfileCodec.decode(it).getOrNull() }
        if (current != null) return current

        val metadataScrap = runCatching { localStorage.getItem(META_KEY) }.getOrNull()
            ?.let { ProfileCodec.decodeLegacyScrap(it).getOrNull()?.lifetimeScrap }
        val legacyScrap = listOfNotNull(metadataScrap, runLegacyScrap).maxOrNull()
        return (legacyScrap?.let(PlayerProfile::fromLegacyScrap) ?: PlayerProfile()).also(::saveProfile)
    }

    private fun decodeStoredRun() = runCatching { localStorage.getItem(RUN_KEY) }
        .getOrNull()
        ?.let { SaveCodec.decodeRun(it).getOrNull() }
}
