package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.WeaponScore
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.combat.Weapons

/** What happened when the player walked over a weapon (PROD-070). */
sealed interface WeaponPickup {
    val scrap: Int

    /** A different weapon was equipped; the old weapon and every cleared slot paid [scrap]. */
    data class Equipped(
        val replaced: WeaponSpec,
        override val scrap: Int,
        val cleared: Map<PowerupId, Int>,
    ) : WeaponPickup

    /** Another copy of the held weapon paid [scrap] without changing the loadout. */
    data class Scrapped(val pickup: WeaponSpec, override val scrap: Int) : WeaponPickup
}

/**
 * What the player is carrying: one weapon, and the powerups applied to it.
 *
 * A build is made around one weapon identity (PROD-070). Walking over a different weapon equips it
 * and empties the slots — the previous weapon and each cleared slot convert to Scrap at their tier
 * value. Another copy of the held weapon converts to Scrap instead and leaves the build unchanged.
 * There is no score comparison or input to refuse either resolution. `WeaponScore` still ranks
 * powerups against the weapon they feed; it no longer ranks weapons.
 */
data class Loadout(val weapon: WeaponSpec, val slots: PowerupSlots) {
    fun collect(found: WeaponSpec): Pair<Loadout, WeaponPickup> {
        if (found.id == weapon.id) {
            return this to WeaponPickup.Scrapped(found, scrapFor(found))
        }
        val cleared = slots.held
        val scrap = scrapFor(weapon) + cleared.keys.sumOf { scrapFor(Powerups.of(it)) }
        return Loadout(found, PowerupSlots.empty()) to WeaponPickup.Equipped(weapon, scrap, cleared)
    }

    /**
     * Takes a powerup, giving up a slot for it when the build is full and the swap makes the build
     * **do more** (PROD-028).
     *
     * Decided here because this is the only place that knows both the build and the weapon it is
     * feeding, and "worth more" is meaningless without the weapon.
     *
     * **A swap must raise [WeaponScore] and must not give up `expectedDps`**, and it took three
     * review rounds to arrive at the conjunction. Each single measure was implemented and each broke
     * the other:
     *
     * - `Powerup.magnitude` is generic strength rather than contribution to damage; it made the
     *   guaranteed-loot floor *fall* between maps four and five.
     * - `expectedDps` alone counts only what lands on one target; with a Static Lash it displaced a
     *   three-stack Thermite Payload for a Hollowpoint Firmware, raising single-target damage from
     *   23.3 to 29.2 while the score fell 50.6 to 40.8 as the 55% splash vanished.
     * - `WeaponScore` alone is what the *game* judges a build by, but the floor is stated in damage;
     *   with an Ashfall Grenade Lobber a legal route of accepted swaps ended at 30.3 damage against
     *   a map-four floor of 32.0, every step obeying the score rule.
     *
     * Requiring both keeps the build monotone in the measure the game uses **and** in the measure
     * the floor is written in — but monotonicity along one route says nothing about how that route's
     * end compares with a *different* route's end, which is what the floor is. Round eleven found
     * the gap: measured over every three-powerup optional route on all ten maps, **10 of 8,160**
     * still ended below the floor, worst by 21.1 damage on map ten, and a Pareto rule failed on
     * exactly the same ten. What closes it is [guaranteed], below: with a guaranteed award always
     * landing, **0 of 8,160** end below.
     *
     * This is what makes the floor a floor again. Contact cannot be declined (PROD-030), so at one
     * kill in five a player's slots fill with whatever the route hands over — and a *guaranteed*
     * award arriving afterwards used to be thrown away, leaving them below the loadout `LootFloor`
     * models. A swap is taken only on a strict improvement, so the build cannot cycle.
     */
    fun collect(
        powerup: PowerupId,
        mapIndex: Int,
        guaranteed: Boolean = false,
    ): Pair<Loadout, Pickup> {
        val (next, outcome) = slots.collect(powerup)
        if (outcome !is Pickup.Scrapped) return copy(slots = next) to outcome
        // Refused for want of a slot, not for a full stack: only the former is worth a swap.
        if (!slots.full || slots.stacksOf(powerup) > 0) return copy(slots = next) to outcome

        // A guaranteed award is never thrown away. It takes whichever slot costs least damage to
        // lose, which is what makes `LootFloor` a bound on a real player rather than on one
        // particular route: the floor is computed assuming every guaranteed award lands, and until
        // this rule existed a player who had picked things up could arrive without them.
        if (guaranteed) {
            val cheapest = slots.held.keys.maxByOrNull {
                DamagePipeline.resolve(weapon, slots.replacing(it, powerup)).expectedDps
            } ?: return copy(slots = next) to outcome
            return copy(slots = slots.replacing(cheapest, powerup)) to
                Pickup.Displaced(powerup, cheapest, PowerupSlots.DEFAULT_SCRAP)
        }

        val currentScore = WeaponScore.of(weapon, slots, mapIndex)
        val currentDps = DamagePipeline.resolve(weapon, slots).expectedDps
        var best: PowerupId? = null
        var bestScore = currentScore
        slots.held.keys.forEach { held ->
            val candidate = slots.replacing(held, powerup)
            val score = WeaponScore.of(weapon, candidate, mapIndex)
            // Both measures, because the two things this has to satisfy are measured differently:
            // the *game* judges a build by its score, and the guaranteed-loot floor is stated in
            // damage. Ranking by either alone was implemented and each broke the other — a swap
            // must therefore raise the score and never give up damage to do it.
            val dps = DamagePipeline.resolve(weapon, candidate).expectedDps
            if (score > bestScore && dps >= currentDps) {
                bestScore = score
                best = held
            }
        }

        val displaced = best ?: return copy(slots = next) to outcome
        return copy(slots = slots.replacing(displaced, powerup)) to
            Pickup.Displaced(powerup, displaced, PowerupSlots.DEFAULT_SCRAP)
    }

    private fun scrapFor(spec: WeaponSpec): Int = SCRAP_BY_TIER[spec.tier.ordinal]
    private fun scrapFor(powerup: Powerup): Int = SCRAP_BY_TIER[powerup.tier.ordinal]

    companion object {
        private val SCRAP_BY_TIER = intArrayOf(8, 20, 45, 100, 240)

        fun starting(): Loadout = Loadout(Weapons.startingWeapon, PowerupSlots.empty())
    }
}
