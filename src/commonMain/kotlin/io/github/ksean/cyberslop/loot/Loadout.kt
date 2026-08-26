package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.WeaponScore
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.combat.Weapons

sealed interface WeaponPickup {
    data class Equipped(val replaced: WeaponSpec, val scrap: Int) : WeaponPickup
    data class Scrapped(val weapon: WeaponSpec, val scrap: Int) : WeaponPickup
}

/**
 * What the player is carrying: one weapon, and the powerups that apply to whatever it is.
 *
 * Powerups belong here rather than to the weapon, so a swap changes base stats and never a build.
 * That is what makes automatic pickup safe: there is no contact the player would want to refuse, and
 * so no need for an input to decline one.
 *
 * A swap is decided by [WeaponScore] against the player's current build, never by rarity tier.
 * Deciding by tier looked simpler and was wrong twice over: the tier bands overlap enough that a
 * "higher tier" could be a downgrade, and it made the guaranteed boss drops worthless to anyone
 * already at that tier.
 */
data class Loadout(val weapon: WeaponSpec, val slots: PowerupSlots) {
    fun collect(found: WeaponSpec, mapIndex: Int): Pair<Loadout, WeaponPickup> {
        val mine = WeaponScore.of(weapon, slots, mapIndex)
        val theirs = WeaponScore.of(found, slots, mapIndex)

        return if (theirs > mine) {
            copy(weapon = found) to WeaponPickup.Equipped(weapon, scrapFor(weapon))
        } else {
            this to WeaponPickup.Scrapped(found, scrapFor(found))
        }
    }

    fun collect(powerup: PowerupId): Pair<Loadout, Pickup> {
        val (next, outcome) = slots.collect(powerup)
        return copy(slots = next) to outcome
    }

    private fun scrapFor(spec: WeaponSpec): Int = SCRAP_BY_TIER[spec.tier.ordinal]

    companion object {
        private val SCRAP_BY_TIER = intArrayOf(8, 20, 45, 100, 240)

        fun starting(): Loadout = Loadout(Weapons.startingWeapon, PowerupSlots.empty())
    }
}
