package io.github.ksean.cyberslop.loot

/** What happened when the player walked over a powerup. Contact always resolves (PROD-030). */
sealed interface Pickup {
    data class Applied(val id: PowerupId, val stacks: Int) : Pickup
    data class Scrapped(val id: PowerupId, val scrap: Int) : Pickup
}

/**
 * The player's build: at most [MAX_SLOTS] distinct powerups, each stacked at most
 * [Powerup.MAX_STACKS] times.
 *
 * Slots belong to the **player**, not to the weapon. Picking up a weapon therefore swaps base stats
 * and never wipes a build, which is what makes walk-over pickup safe to make automatic: there is no
 * pickup the player would want to refuse.
 */
class PowerupSlots private constructor(private val stacks: Map<PowerupId, Int>) {
    val held: Map<PowerupId, Int> get() = stacks
    val distinctCount: Int get() = stacks.size
    val totalStacks: Int get() = stacks.values.sum()

    fun stacksOf(id: PowerupId): Int = stacks[id] ?: 0

    fun magnitudeOf(id: PowerupId): Double {
        val count = stacksOf(id)
        return if (count == 0) 0.0 else Powerups.of(id).magnitude(count)
    }

    /**
     * Applies [id] if there is room, otherwise converts it to Scrap. Never refuses.
     *
     * **A full build refuses a sixth distinct powerup, and that is deliberate rather than an
     * oversight.** Review round seven pointed out that this can scrap a *guaranteed* boss award once
     * five optional powerups hold the slots, leaving a real player below the loadout [LootFloor]
     * models. Displacing the weakest slot instead was implemented and then withdrawn: it is unsound.
     * [Powerup.magnitude] is a generic strength scalar, not a contribution to damage, so displacing
     * by it swapped a damage powerup out for a stronger-but-useless one and made `LootFloor`'s own
     * DPS floor **fall** between maps four and five. It also churned, because a displaced powerup
     * re-collected later displaces its replacement.
     *
     * So collecting still never removes anything a player holds — their build only ever grows — and
     * the gap is in what [LootFloor] *claims*, which is corrected there. Closing it properly needs a
     * notion of "better" that respects what the floor measures, and that is a change to the powerup
     * economy rather than to this change's scope.
     */
    fun collect(id: PowerupId, scrapValue: Int = DEFAULT_SCRAP): Pair<PowerupSlots, Pickup> {
        val current = stacksOf(id)
        return when {
            current in 1 until Powerup.MAX_STACKS ->
                PowerupSlots(stacks + (id to current + 1)) to Pickup.Applied(id, current + 1)

            current >= Powerup.MAX_STACKS -> this to Pickup.Scrapped(id, scrapValue)

            stacks.size < MAX_SLOTS ->
                PowerupSlots(stacks + (id to 1)) to Pickup.Applied(id, 1)

            else -> this to Pickup.Scrapped(id, scrapValue)
        }
    }

    companion object {
        const val MAX_SLOTS = 5
        const val DEFAULT_SCRAP = 5

        fun empty(): PowerupSlots = PowerupSlots(emptyMap())
    }
}
