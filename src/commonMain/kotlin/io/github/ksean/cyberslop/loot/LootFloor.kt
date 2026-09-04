package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.gen.DifficultyCurve

/**
 * The worst loadout a player can be holding on a given map: only guaranteed drops, and the weakest
 * outcome each could yield.
 *
 * **What this is for, and what it is not.** "The map is completable" and "the boss is beatable" are
 * different claims. The witness proves the player can reach the arena; the floor bounds what they
 * can be carrying when they do.
 *
 * **A different weapon pickup is a reset (PROD-070).** Every consecutive weapon in this reference
 * route differs because its guaranteed tiers alternate, so the loadout at any point is the last
 * guaranteed weapon before it, at its weakest, plus only the guaranteed powerups awarded after it.
 * Nothing accumulates across maps, and a forced pickup can be a downgrade: the mini-boss's Scav
 * weapon replaces the previous boss's Chromed one before the main boss is fought. The floor also
 * verifies the same-id exception for optional routes — modelling one policy and shipping another
 * is how an earlier version of this file came to claim a bound it did not have.
 *
 * It does **not** carry a player to the final map, and it is not meant to. A boss-only damage
 * calculation can outpace the route-survival guarantee, so the two bounds stay separate rather
 * than claiming that reaching a winnable fight is the same as clearing the map.
 */
object LootFloor {
    /**
     * The weakest weapon the guaranteed awards leave a player holding when they *arrive* on
     * [mapIndex]: the starter cache on map 1, and from map 2 the previous main boss's award, which
     * guarantees Chromed and nothing above it (`specs/combat.md`).
     */
    fun weaponArrivingAt(mapIndex: Int): WeaponSpec = weakestOf(
        if (mapIndex <= 1) Tier.Street else Tier.Chromed,
        // Map 1 opens with a starter cache, so the bottle is never carried into a mini-boss fight.
        excludeStartingWeapon = true,
    )

    /**
     * The weakest weapon the guaranteed awards leave a player holding at [mapIndex]'s **main
     * boss**: the mini-boss award, which guarantees Scav. Its id necessarily differs from the
     * previous boss's Chromed weapon, so that weapon is gone by then — the floor says so rather
     * than assuming the player kept the better one.
     */
    fun weaponAt(mapIndex: Int): WeaponSpec = weakestOf(Tier.Scav, excludeStartingWeapon = true)

    /**
     * The powerups held at [mapIndex]'s main boss: only what was awarded after the mini-boss
     * weapon emptied the build — the mini-boss's own powerup from map 4, nothing before.
     */
    fun slotsAt(mapIndex: Int): PowerupSlots =
        if (mapIndex >= MINIBOSS_POWERUP_FROM) one(weakestPowerupFor(weaponAt(mapIndex), MINIBOSS_POWERUP_POOL)) else PowerupSlots.empty()

    /**
     * What a player *arrives* on [mapIndex] holding: the previous main boss's powerup on its
     * weapon (the award applies weapon then powerup), and nothing on map 1.
     */
    fun slotsArrivingAt(mapIndex: Int): PowerupSlots =
        if (mapIndex <= 1) PowerupSlots.empty() else one(weakestPowerupFor(weaponArrivingAt(mapIndex), BOSS_POWERUP_POOL))

    fun damagePerSecondAt(mapIndex: Int): Double =
        DamagePipeline.resolve(weaponAt(mapIndex), slotsAt(mapIndex)).expectedDps

    /** The rate a player *arrives* with: what the route is walked with. */
    fun damagePerSecondArrivingAt(mapIndex: Int): Double =
        DamagePipeline.resolve(weaponArrivingAt(mapIndex), slotsArrivingAt(mapIndex)).expectedDps

    /** The last map whose boss the held guaranteed loadout can kill inside its damage band. */
    fun furthestDamageClearableMap(slack: Double = BAND_SLACK): Int {
        var furthest = 0
        for (map in 1..DifficultyCurve.MAPS) {
            val seconds = Balance.bossHealth(map) / damagePerSecondAt(map)
            if (seconds > Balance.targetBossSeconds(map) * slack) return furthest
            furthest = map
        }
        return furthest
    }

    /** The last map covered by both the damage bound and the full route-and-boss simulation. */
    fun furthestClearableMap(slack: Double = BAND_SLACK): Int =
        minOf(furthestDamageClearableMap(slack), FULL_SIMULATION_MAPS)

    /** The award's weakest outcome: the powerup in [pool] that adds least single-target damage to [weapon]. */
    private fun weakestPowerupFor(weapon: WeaponSpec, pool: List<Powerup>): Powerup =
        pool.minBy { DamagePipeline.resolve(weapon, one(it)).expectedDps }

    private fun one(powerup: Powerup): PowerupSlots = PowerupSlots.empty().collect(powerup.id).first

    private fun weakestOf(tier: Tier, excludeStartingWeapon: Boolean): WeaponSpec {
        val candidates = Weapons.ofTier(tier)
            .filter { !excludeStartingWeapon || it.id != Weapons.startingWeapon.id }
        return candidates.minByOrNull { it.baseDps } ?: Weapons.startingWeapon
    }

    private const val MINIBOSS_POWERUP_FROM = 4
    private const val FULL_SIMULATION_MAPS = 1
    const val BAND_SLACK = 1.6

    /** A main boss's powerup is floored at Scav (`specs/combat.md`); a mini-boss's is not. */
    private val BOSS_POWERUP_POOL: List<Powerup> get() = Powerups.ofTier(PowerupTier.Scav)
    private val MINIBOSS_POWERUP_POOL: List<Powerup> get() = Powerups.ofTier(PowerupTier.Street) + Powerups.ofTier(PowerupTier.Scav)
}
