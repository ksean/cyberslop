package io.github.ksean.cyberslop.title

import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.progression.UpgradeCatalog
import io.github.ksean.cyberslop.progression.UpgradeId

data class ShopRow(
    val id: UpgradeId,
    val name: String,
    val description: String,
    val rank: Int,
    val maxRank: Int,
    val effectText: String,
    val nextPrice: Int?,
    val canPurchase: Boolean,
) {
    val rankText: String get() = "Rank $rank of $maxRank"
    val priceText: String get() = nextPrice?.let { "Next rank: $it Scrap" } ?: "Max rank"
    val purchaseAccessibleName: String
        get() = nextPrice?.let { "Buy $name — $it Scrap" } ?: "Max rank — $name"
}

data class ShopScreenState(
    val spendableScrap: Int,
    val rows: List<ShopRow>,
)

fun createShopScreenState(profile: PlayerProfile): ShopScreenState = ShopScreenState(
    spendableScrap = profile.spendableScrap,
    rows = UpgradeCatalog.all.map { spec ->
        val rank = profile.upgrades.rankOf(spec.id)
        val nextPrice = spec.nextPrice(rank)
        ShopRow(
            id = spec.id,
            name = spec.name,
            description = spec.description,
            rank = rank,
            maxRank = spec.maxRank,
            effectText = effectText(spec.id, spec.percentPerRank * rank),
            nextPrice = nextPrice,
            canPurchase = nextPrice != null && profile.spendableScrap >= nextPrice,
        )
    },
)

private fun effectText(id: UpgradeId, percent: Int): String = when (id) {
    UpgradeId.ReinforcedChassis -> "Maximum health +$percent%"
    UpgradeId.BlackMarketFirmware -> "Weapon damage +$percent%"
    UpgradeId.ReactiveDermalWeave -> "Incoming non-lethal damage -$percent%"
}
