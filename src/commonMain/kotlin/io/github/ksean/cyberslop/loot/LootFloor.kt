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
 * It does **not** carry a player to the final map, and it is not meant to. The required damage rate
 * grows about 81x across a run while a worst-case loadout grows far less, so optional loot is
 * genuinely required past the early maps — that is the difficulty curve working, not a defect. What
 * makes that safe is the commit line: an underpowered player is never sealed in with a boss they
 * cannot beat, because sealing is their own deliberate act.
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
