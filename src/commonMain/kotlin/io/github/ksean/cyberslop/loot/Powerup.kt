package io.github.ksean.cyberslop.loot

/** Rarity band. Stronger powerups are rarer, which is what the drop weights encode. */
enum class PowerupTier { Street, Scav, Chromed, Blacksite, Ascended }

enum class PowerupId {
    FractureLens, KineticDamper, RangerOptics, GuillotineCodec,
    HollowpointFirmware, SpikeDriver, RedMarketSiphon, MassDriver,
    OverclockCoil, ChillProtocol, BurnRig, RicochetRom,
    SeekerDaemon, ArcCascade, BrownoutCharge,
    ForkBomb, ThermitePayload, KillstreakCache,
}

/** How a powerup's value combines with the others of its kind. */
enum class Combination { Additive, Multiplicative, Event }

/**
 * A powerup and its per-stack strength.
 *
 * [magnitude] is a single comparable number per stack count, which exists so "stacking always gets
 * stronger but never super-linearly" is a property that can be *tested* rather than eyeballed —
 * effects like stun (chance times duration) or seeking (turn rate and radius) otherwise have no
 * single value to compare.
 */
data class Powerup(
    val id: PowerupId,
    val name: String,
    val tier: PowerupTier,
    val combination: Combination,
    private val perStack: List<Double>,
) {
    init {
        require(perStack.size == MAX_STACKS) { "$id must define $MAX_STACKS stacks" }
    }

    fun magnitude(stacks: Int): Double = perStack[stacks.coerceIn(1, MAX_STACKS) - 1]

    companion object {
        const val MAX_STACKS = 3
    }
}
