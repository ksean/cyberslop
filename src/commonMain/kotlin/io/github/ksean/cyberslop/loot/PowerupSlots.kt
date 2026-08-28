package io.github.ksean.cyberslop.loot

/** What happened when the player walked over a powerup. Contact always resolves (PROD-030). */
sealed interface Pickup {
    data class Applied(val id: PowerupId, val stacks: Int) : Pickup
    data class Scrapped(val id: PowerupId, val scrap: Int) : Pickup

    /** Applied over a full build by displacing a slot worth less, which converts to Scrap. */
    data class Displaced(val id: PowerupId, val removed: PowerupId, val scrap: Int) : Pickup
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

    val full: Boolean get() = stacks.size >= MAX_SLOTS

    /**
     * This build with [removed] gone and [added] in its place at one stack.
     *
     * A container operation only: **which** slot is worth giving up is not something this type can
     * judge, because it does not know what weapon the build is feeding. [Loadout] decides.
     */
    fun replacing(removed: PowerupId, added: PowerupId): PowerupSlots =
        PowerupSlots(stacks - removed + (added to 1))

    fun magnitudeOf(id: PowerupId): Double {
        val count = stacksOf(id)
        return if (count == 0) 0.0 else Powerups.of(id).magnitude(count)
    }

    /**
     * Applies [id] if there is room, otherwise converts it to Scrap. Never refuses.
     *
     * A full build refuses a sixth distinct powerup **here**, and [Loadout.collect] then decides
     * whether giving up a slot is worth it. The judgement needs the weapon the build is feeding and
     * this type does not have one — which is exactly why an earlier attempt to decide it here, by
     * ranking [Powerup.magnitude], was unsound: magnitude is generic strength, not contribution to
     * damage, so it swapped a damage powerup out for a stronger-but-useless one and made
     * `LootFloor`'s own damage floor fall between maps four and five.
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
