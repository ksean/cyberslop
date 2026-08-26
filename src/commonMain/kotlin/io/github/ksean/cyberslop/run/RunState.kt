package io.github.ksean.cyberslop.run

import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.loot.Loadout

/** A run in progress. */
data class RunState(
    val seed: ULong,
    val mapIndex: Int,
    val loadout: Loadout,
    val health: Double,
    val scrap: Int,
) {
    val maxHealth: Double get() = Balance.playerMaxHealth(mapIndex)

    fun damaged(amount: Double): RunState = copy(health = (health - amount).coerceAtLeast(0.0))

    val dead: Boolean get() = health <= 0.0

    /** Entering the next map restores health, which is the reward for clearing a boss. */
    fun advanced(): RunState = copy(
        mapIndex = mapIndex + 1,
        health = Balance.playerMaxHealth(mapIndex + 1),
    )

    companion object {
        fun begin(seed: ULong): RunState = RunState(
            seed = seed,
            mapIndex = 1,
            loadout = Loadout.starting(),
            health = Balance.playerMaxHealth(1),
            scrap = 0,
        )
    }
}

/**
 * What survives a run.
 *
 * Death ends the run and returns the player to the first map with the starting weapon (PROD-031).
 * Scrap persists and widens the pool of things later runs can find, which is the only thing that
 * carries forward — there are no permanent stat bonuses, so a run is always won by playing it.
 */
data class MetaProgression(
    val scrap: Int = 0,
    val unlockedWeapons: Int = STARTING_UNLOCKS,
) {
    fun banking(runScrap: Int): MetaProgression {
        val total = scrap + runScrap
        return copy(scrap = total, unlockedWeapons = unlocksFor(total))
    }

    companion object {
        const val STARTING_UNLOCKS = 8
        const val SCRAP_PER_UNLOCK = 400
        const val MAX_UNLOCKS = 26

        fun unlocksFor(scrap: Int): Int =
            (STARTING_UNLOCKS + scrap / SCRAP_PER_UNLOCK).coerceAtMost(MAX_UNLOCKS)
    }
}
