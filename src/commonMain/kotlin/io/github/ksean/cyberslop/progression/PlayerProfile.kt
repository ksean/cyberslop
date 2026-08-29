package io.github.ksean.cyberslop.progression

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId

/** Persistent state shared by every run. */
data class PlayerProfile(
    val spendableScrap: Int = 0,
    val lifetimeScrap: Int = 0,
    val upgrades: UpgradeRanks = UpgradeRanks(),
    val discoveredWeapons: Set<WeaponId> = setOf(WeaponId.BrokenBottle),
    val discoveredPowerups: Set<PowerupId> = emptySet(),
) {
    init {
        require(spendableScrap >= 0) { "spendable Scrap cannot be negative" }
        require(lifetimeScrap >= 0) { "lifetime Scrap cannot be negative" }
        require(spendableScrap <= lifetimeScrap) {
            "spendable Scrap $spendableScrap exceeds lifetime Scrap $lifetimeScrap"
        }
        require(WeaponId.BrokenBottle in discoveredWeapons) { "the starting weapon must be discovered" }
    }

    val unlockedWeapons: Int get() = unlocksFor(lifetimeScrap)

    fun banking(runScrap: Int): PlayerProfile {
        require(runScrap >= 0) { "run Scrap cannot be negative" }
        if (runScrap == 0) return this
        return copy(
            spendableScrap = checkedSum(spendableScrap, runScrap),
            lifetimeScrap = checkedSum(lifetimeScrap, runScrap),
        )
    }

    fun purchasing(id: UpgradeId): PlayerProfile {
        val rank = upgrades.rankOf(id)
        val price = UpgradeCatalog.of(id).nextPrice(rank) ?: return this
        if (spendableScrap < price) return this
        return copy(spendableScrap = spendableScrap - price, upgrades = upgrades.raising(id))
    }

    /** String boundary used by decoded/browser actions: an unknown id is an unchanged transition. */
    fun purchasing(encodedId: String): PlayerProfile =
        UpgradeId.entries.firstOrNull { it.name == encodedId }?.let(::purchasing) ?: this

    fun discovering(id: WeaponId): PlayerProfile =
        if (id in discoveredWeapons) this else copy(discoveredWeapons = discoveredWeapons + id)

    fun discovering(id: PowerupId): PlayerProfile =
        if (id in discoveredPowerups) this else copy(discoveredPowerups = discoveredPowerups + id)

    companion object {
        const val STARTING_UNLOCKS = 8
        const val SCRAP_PER_UNLOCK = 400
        const val MAX_UNLOCKS = 26

        fun fromLegacyScrap(scrap: Int): PlayerProfile {
            require(scrap >= 0) { "legacy Scrap cannot be negative" }
            return PlayerProfile(spendableScrap = scrap, lifetimeScrap = scrap)
        }

        fun unlocksFor(lifetimeScrap: Int): Int =
            (STARTING_UNLOCKS + lifetimeScrap / SCRAP_PER_UNLOCK).coerceAtMost(MAX_UNLOCKS)

        private fun checkedSum(left: Int, right: Int): Int {
            val total = left.toLong() + right.toLong()
            require(total <= Int.MAX_VALUE) { "Scrap total exceeds ${Int.MAX_VALUE}" }
            return total.toInt()
        }
    }
}
