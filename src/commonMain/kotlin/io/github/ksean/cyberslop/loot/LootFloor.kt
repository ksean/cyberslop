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
 * **It is a bound on the player because [Loadout] makes it one.** Three review rounds pressed on a
 * real hole here. A build holds five distinct powerups; contact resolves automatically (PROD-030)
 * and cannot be declined; so at one kill in five a player's slots fill with whatever the route hands
 * over, and a *guaranteed* award arriving afterwards used to be thrown away — leaving them below the
 * loadout computed here, which is precisely what this file claims cannot happen.
 *
 * It is closed by [Loadout.collect] giving up a slot when the swap makes the build do more. The
 * ranking matters and was got wrong once: [Powerup.magnitude] is generic strength rather than
 * contribution to damage, and displacing by it swapped a damage powerup out for a
 * stronger-but-useless one and made [damagePerSecondAt] **fall** between maps four and five. Ranking
 * by what the build actually does, against the weapon it is feeding, is what makes the floor hold.
 *
 * **Do not lean on the commit line for this.** This file said until now that an underpowered player
 * is never sealed in "because sealing is their own deliberate act". `BossFight.playerMoved` commits
 * on crossing a column and nothing else, so what is deliberate is *walking*, not fighting. That
 * sentence was never a safety property and is no longer offered as one.
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

    /**
     * Guaranteed powerup stacks by the time [mapIndex] is reached, at the weakest tiers.
     *
     * Built through a [Loadout] rather than through [PowerupSlots] directly, so the model collects
     * the way the game collects — including giving up a slot when doing so makes the build do more.
     * Modelling one policy and shipping another is how this file came to claim a bound it did not
     * have.
     */
    fun slotsAt(mapIndex: Int): PowerupSlots = loadoutAt(mapIndex).slots

    private fun loadoutAt(mapIndex: Int): Loadout {
        var loadout = Loadout(weaponAt(mapIndex), PowerupSlots.empty())
        val pool = Powerups.ofTier(PowerupTier.Street) + Powerups.ofTier(PowerupTier.Scav)
        repeat(guaranteedPowerups(mapIndex)) { index ->
            loadout = loadout.collect(pool[index % pool.size].id, mapIndex, guaranteed = true).first
        }
        return loadout
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
