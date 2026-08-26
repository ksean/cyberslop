package io.github.ksean.cyberslop.entity

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.gen.DifficultyCurve
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots

/**
 * The difficulty curve, as numbers.
 *
 * Growth uses repeated multiplication rather than `pow`, which carries no cross-target guarantee
 * (ENG-054). Time-to-kill bands are **derived** from the health multipliers rather than chosen
 * separately: boss health is a fixed multiple of trash health, so at any given rate the two times
 * are in that same ratio, and picking them independently only guarantees they will contradict.
 */
object Balance {
    private const val TRASH_BASE = 12.0
    private const val TRASH_GROWTH = 1.63
    private const val CONTACT_BASE = 6.0
    private const val CONTACT_GROWTH = 1.32
    private const val PLAYER_BASE_HEALTH = 100.0
    private const val PLAYER_HEALTH_PER_MAP = 15.0

    /**
     * Boss health, as a multiple of trash health.
     *
     * Lowered from 9x and 20x once the fight was actually played. The required damage rate assumes
     * uninterrupted output, and a boss fight does not offer that: dodging a telegraph means moving
     * out of reach, so real uptime is around three-quarters. Sized for full uptime, the first boss
     * could not be killed by a player doing exactly the damage the curve asks for — measured at 25%
     * health remaining when the player died.
     */
    const val MINIBOSS_MULTIPLIER = 6.0
    const val BOSS_MULTIPLIER = 12.0

    private const val TRASH_SECONDS_FIRST = 2.0
    private const val TRASH_SECONDS_LAST = 1.2

    fun trashHealth(mapIndex: Int): Double = TRASH_BASE * growth(TRASH_GROWTH, mapIndex)

    fun minibossHealth(mapIndex: Int): Double = MINIBOSS_MULTIPLIER * trashHealth(mapIndex)

    fun bossHealth(mapIndex: Int): Double = BOSS_MULTIPLIER * trashHealth(mapIndex)

    fun contactDamage(mapIndex: Int): Double = CONTACT_BASE * growth(CONTACT_GROWTH, mapIndex)

    fun playerMaxHealth(mapIndex: Int): Double =
        PLAYER_BASE_HEALTH + PLAYER_HEALTH_PER_MAP * (mapIndex - 1)

    fun targetTrashSeconds(mapIndex: Int): Double {
        val d = (mapIndex - 1) / (DifficultyCurve.MAPS - 1).toDouble()
        return TRASH_SECONDS_FIRST + (TRASH_SECONDS_LAST - TRASH_SECONDS_FIRST) * d
    }

    fun targetMinibossSeconds(mapIndex: Int): Double =
        MINIBOSS_MULTIPLIER * targetTrashSeconds(mapIndex)

    fun targetBossSeconds(mapIndex: Int): Double = BOSS_MULTIPLIER * targetTrashSeconds(mapIndex)

    /** The damage rate the map is tuned around. */
    fun requiredDps(mapIndex: Int): Double = trashHealth(mapIndex) / targetTrashSeconds(mapIndex)

    /** The best single-target rate any weapon can reach with a full build. */
    fun peakAchievableDps(): Double {
        var slots = PowerupSlots.empty()
        listOf(
            PowerupId.HollowpointFirmware,
            PowerupId.OverclockCoil,
            PowerupId.ForkBomb,
            PowerupId.ThermitePayload,
            PowerupId.FractureLens,
        ).forEach { id -> repeat(3) { slots = slots.collect(id).first } }

        return Weapons.all.maxOf { weapon ->
            val resolved = DamagePipeline.resolve(weapon, slots)
            resolved.expectedDps * (1.0 + resolved.blastFraction)
        }
    }

    private fun growth(rate: Double, mapIndex: Int): Double {
        var value = 1.0
        repeat(mapIndex - 1) { value *= rate }
        return value
    }
}
