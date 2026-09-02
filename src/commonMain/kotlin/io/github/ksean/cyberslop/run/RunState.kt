package io.github.ksean.cyberslop.run

import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.progression.UpgradeRanks

/** A run in progress. */
data class RunState(
    val seed: ULong,
    val mapIndex: Int,
    val loadout: Loadout,
    val health: Double,
    val scrap: Int,
    /** Immutable profile snapshot applied to this active simulation. */
    val upgrades: UpgradeRanks = UpgradeRanks(),
) {
    val maxHealth: Double get() = Balance.playerMaxHealth(mapIndex) * upgrades.healthMultiplier

    fun damaged(amount: Double): RunState = copy(health = (health - amount).coerceAtLeast(0.0))

    fun healed(amount: Double): RunState = copy(health = (health + amount).coerceAtMost(maxHealth))

    val dead: Boolean get() = health <= 0.0

    /** Entering the next map preserves the health earned or lost in this run. */
    fun advanced(): RunState = copy(mapIndex = mapIndex + 1)

    companion object {
        fun begin(seed: ULong, upgrades: UpgradeRanks = UpgradeRanks()): RunState = RunState(
            seed = seed,
            mapIndex = 1,
            loadout = Loadout.starting(),
            health = Balance.playerMaxHealth(1) * upgrades.healthMultiplier,
            scrap = 0,
            upgrades = upgrades,
        )
    }
}
