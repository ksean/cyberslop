package io.github.ksean.cyberslop.progression

enum class UpgradeId {
    ReinforcedChassis,
    BlackMarketFirmware,
    ReactiveDermalWeave,
}

data class UpgradeSpec(
    val id: UpgradeId,
    val name: String,
    val description: String,
    val percentPerRank: Int,
    val prices: List<Int>,
) {
    val maxRank: Int get() = prices.size

    fun nextPrice(currentRank: Int): Int? {
        require(currentRank in 0..maxRank) { "rank $currentRank is outside 0..$maxRank" }
        return prices.getOrNull(currentRank)
    }
}

/** The fixed, ordered catalog shown by the title-screen shop. */
object UpgradeCatalog {
    private val sharedPrices = listOf(100, 250, 500, 1_000, 2_000)

    val all: List<UpgradeSpec> = listOf(
        UpgradeSpec(
            UpgradeId.ReinforcedChassis,
            "Reinforced Chassis",
            "Raises maximum health by 10% per rank.",
            percentPerRank = 10,
            prices = sharedPrices,
        ),
        UpgradeSpec(
            UpgradeId.BlackMarketFirmware,
            "Black-Market Firmware",
            "Raises weapon damage by 5% per rank.",
            percentPerRank = 5,
            prices = sharedPrices,
        ),
        UpgradeSpec(
            UpgradeId.ReactiveDermalWeave,
            "Reactive Dermal Weave",
            "Reduces incoming non-lethal damage by 5% per rank.",
            percentPerRank = 5,
            prices = sharedPrices,
        ),
    )

    private val byId = all.associateBy { it.id }

    fun of(id: UpgradeId): UpgradeSpec = requireNotNull(byId[id]) { "missing upgrade $id" }
}

data class UpgradeRanks(
    val reinforcedChassis: Int = 0,
    val blackMarketFirmware: Int = 0,
    val reactiveDermalWeave: Int = 0,
) {
    init {
        listOf(reinforcedChassis, blackMarketFirmware, reactiveDermalWeave).forEach { rank ->
            require(rank in 0..MAX_RANK) { "upgrade rank $rank is outside 0..$MAX_RANK" }
        }
    }

    val healthMultiplier: Double get() = 1.0 + HEALTH_PER_RANK * reinforcedChassis
    val weaponDamageMultiplier: Double get() = 1.0 + DAMAGE_PER_RANK * blackMarketFirmware
    val incomingDamageMultiplier: Double get() = 1.0 - WEAVE_PER_RANK * reactiveDermalWeave

    fun rankOf(id: UpgradeId): Int = when (id) {
        UpgradeId.ReinforcedChassis -> reinforcedChassis
        UpgradeId.BlackMarketFirmware -> blackMarketFirmware
        UpgradeId.ReactiveDermalWeave -> reactiveDermalWeave
    }

    internal fun raising(id: UpgradeId): UpgradeRanks = when (id) {
        UpgradeId.ReinforcedChassis -> copy(reinforcedChassis = reinforcedChassis + 1)
        UpgradeId.BlackMarketFirmware -> copy(blackMarketFirmware = blackMarketFirmware + 1)
        UpgradeId.ReactiveDermalWeave -> copy(reactiveDermalWeave = reactiveDermalWeave + 1)
    }

    companion object {
        const val MAX_RANK = 5
        private const val HEALTH_PER_RANK = 0.10
        private const val DAMAGE_PER_RANK = 0.05
        private const val WEAVE_PER_RANK = 0.05
    }
}
