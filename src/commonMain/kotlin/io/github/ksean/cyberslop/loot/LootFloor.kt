package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.entity.Balance

/**
 * The worst loadout a player can be holding on a given map: only guaranteed drops, and the weakest
 * outcome each could yield.
 *
 * **What this is for, and what it is not.** "The map is completable" and "the boss is beatable" are
 * different claims. The witness proves the player can reach the arena door; the arena then seals.
 * The floor exists to bound what they can be carrying when it does.
 *
 * **It bounds the guaranteed awards, not the player.** Review round seven of change 0005 was right
 * to press on this, and the correction is to the claim rather than to the arithmetic. A build holds
 * five distinct powerups; contact resolves automatically (PROD-030) and cannot be declined; so a
 * player who has collected five optional powerups on the way has a full build, and a *guaranteed*
 * award arriving afterwards is scrapped. Their real loadout can therefore be weaker than the one
 * computed here. Raising the drop rate to one kill in five (PROD-046) and putting static pickups on
 * the witness's own footholds (PROD-047) both widened that gap; neither opened it.
 *
 * Displacing the weakest slot instead of scrapping was implemented and withdrawn, and the reason is
 * worth keeping: [Powerup.magnitude] ranks strength generically, not contribution to damage, so
 * displacing by it swapped a damage powerup out for a stronger-but-useless one and made
 * [damagePerSecondAt] **fall** between maps four and five — this file's own monotonicity test caught
 * it. Closing the gap needs a notion of "better" that respects what this measures, which is a change
 * to the powerup economy and the owner's to make.
 *
 * **And the fallback below does not hold either.** This file has said since change 0003 that an
 * underpowered player is never sealed in "because sealing is their own deliberate act". Review round
 * eight checked it: `BossFight.playerMoved` commits on crossing a column and nothing else, so what
 * is deliberate is *walking*, not fighting. A player carrying too little is sealed in exactly as
 * readily as one carrying enough. My round-seven correction leaned on that sentence, which was a
 * false premise, and repeating it would have been worse than the gap it was covering for.
 *
 * It does **not** carry a player to the final map, and it is not meant to. The required damage rate
 * grows about 81x across a run while a worst-case loadout grows far less, so optional loot is
 * genuinely required past the early maps — that is the difficulty curve working, not a defect. The
 * commit line makes sealing the player's own act rather than something automatic fire can do for
 * them, which is what it was built for; it does not, and was never able to, check whether they can
 * win the fight they are walking into.
 */
object LootFloor {
    /** The weakest weapon the guaranteed awards can have produced by [mapIndex]. */
    fun weaponAt(mapIndex: Int): WeaponSpec = weakestOf(
        when {
            mapIndex <= 1 -> Tier.Street
            mapIndex <= 3 -> Tier.Scav
            mapIndex <= 5 -> Tier.Chromed
            mapIndex <= 7 -> Tier.Blacksite
            else -> Tier.Ascended
        },
        // Map 1 opens with a starter cache, so the bottle is never carried into a mini-boss fight.
        excludeStartingWeapon = true,
    )

    /** Guaranteed powerup stacks by the time [mapIndex] is reached, at the weakest tiers. */
    fun slotsAt(mapIndex: Int): PowerupSlots {
        var slots = PowerupSlots.empty()
        val pool = Powerups.ofTier(PowerupTier.Street) + Powerups.ofTier(PowerupTier.Scav)
        repeat(guaranteedPowerups(mapIndex)) { index ->
            slots = slots.collect(pool[index % pool.size].id).first
        }
        return slots
    }

    /** One from each boss, plus one from each mini-boss once they begin awarding them. */
    fun guaranteedPowerups(mapIndex: Int): Int =
        (1..mapIndex).sumOf { map -> if (map >= MINIBOSS_POWERUP_FROM) 2 else 1 }

    fun damagePerSecondAt(mapIndex: Int): Double =
        DamagePipeline.resolve(weaponAt(mapIndex), slotsAt(mapIndex)).expectedDps

    /**
     * The last map whose boss the floor can kill inside its band, having cleared every map before
     * it. Beyond this the run needs loot the player was not guaranteed.
     */
    fun furthestClearableMap(slack: Double = BAND_SLACK): Int {
        var furthest = 0
        for (map in 1..Balance.let { 10 }) {
            val seconds = Balance.bossHealth(map) / damagePerSecondAt(map)
            if (seconds > Balance.targetBossSeconds(map) * slack) return furthest
            furthest = map
        }
        return furthest
    }

    private fun weakestOf(tier: Tier, excludeStartingWeapon: Boolean): WeaponSpec {
        val candidates = Weapons.ofTier(tier)
            .filter { !excludeStartingWeapon || it.id != Weapons.startingWeapon.id }
        return candidates.minByOrNull { it.baseDps } ?: Weapons.startingWeapon
    }

    private const val MINIBOSS_POWERUP_FROM = 4
    const val BAND_SLACK = 1.6
}
