package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import kotlin.math.max
import kotlin.math.min

/** A weapon with the player's build applied. */
data class ResolvedWeapon(
    val spec: WeaponSpec,
    val damagePerProjectile: Double,
    val cooldown: Double,
    val projectileCount: Int,
    val pierce: Int,
    val critChance: Double,
    val critMultiplier: Double,
    val chainTargets: Int,
    /** Terrain bounces a projectile survives (Ricochet ROM, PROD-074). */
    val bounces: Int,
    val homing: Homing,
    /** Mass Driver widens what a hit covers; it does not secretly add damage. */
    val hitboxScale: Double,
    val reachScale: Double,
    val knockbackScale: Double,
    val stunChance: Double,
    val killRefundChance: Double,
    val slowFraction: Double,
    val blastFraction: Double,
    val igniteFraction: Double,
    val lifestealFraction: Double,
) {
    /** Expected damage per activation, crit included, against a single target. */
    val expectedPerActivation: Double
        get() = damagePerProjectile * projectileCount * expectedCritFactor

    val expectedCritFactor: Double
        get() = (1.0 - critChance) + critChance * critMultiplier

    val expectedDps: Double get() = expectedPerActivation / cooldown
}

/**
 * Turns a weapon plus a build into the numbers the simulation uses.
 *
 * The order is fixed and matters: additive damage bonuses are summed and applied once, multiplicative
 * ones are multiplied, the multi-projectile penalty divides among the extra projectiles, and crit is
 * applied last. Every cap here exists because some combination without it is degenerate.
 */
object DamagePipeline {
    /** Never faster than this, however much attack speed is stacked. */
    private const val ABSOLUTE_COOLDOWN_FLOOR = 0.08

    /** Nor faster than this multiple of the weapon's own rate. */
    private const val RELATIVE_COOLDOWN_FLOOR = 0.35

    private const val MAX_CRIT_CHANCE = 0.75
    private const val BASE_CRIT_MULTIPLIER = 2.0

    /** Enemies never drop below this fraction of their speed, so slow cannot become a stun-lock. */
    const val MIN_ENEMY_SPEED_FRACTION = 0.40

    fun resolve(spec: WeaponSpec, slots: PowerupSlots): ResolvedWeapon {
        val additiveDamage = slots.magnitudeOf(PowerupId.HollowpointFirmware)
        val hitboxBonus = slots.magnitudeOf(PowerupId.MassDriver)

        val extraProjectiles = slots.stacksOf(PowerupId.ForkBomb).let {
            if (it == 0) 0 else it
        }
        val forkGain = slots.magnitudeOf(PowerupId.ForkBomb)

        // The penalty is per *extra* projectile and is relative to what the weapon already fires, so
        // a two-projectile weapon gains proportionally less than a one-projectile weapon.
        val base = spec.projectileCount
        val totalProjectiles = base + extraProjectiles
        val splitFactor =
            if (extraProjectiles == 0) 1.0 else (base + forkGain) / totalProjectiles.toDouble()

        val damage = spec.damage * (1.0 + additiveDamage) * splitFactor

        val speedFactor = 1.0 - slots.magnitudeOf(PowerupId.OverclockCoil)
        val cooldown = clampCooldown(spec.cooldown, spec.cooldown * speedFactor)

        return ResolvedWeapon(
            spec = spec,
            damagePerProjectile = damage,
            cooldown = cooldown,
            projectileCount = totalProjectiles,
            pierce = spec.pierce.saturatingPlus(slots.magnitudeOf(PowerupId.SpikeDriver).toInt()),
            critChance = min(spec.critChance + slots.magnitudeOf(PowerupId.FractureLens), MAX_CRIT_CHANCE),
            critMultiplier = BASE_CRIT_MULTIPLIER + slots.magnitudeOf(PowerupId.GuillotineCodec),
            chainTargets = slots.magnitudeOf(PowerupId.ArcCascade).toInt(),
            bounces = slots.magnitudeOf(PowerupId.RicochetRom).toInt(),
            homing = resolveHoming(spec, slots),
            hitboxScale = 1.0 + hitboxBonus,
            reachScale = 1.0 + slots.magnitudeOf(PowerupId.RangerOptics),
            knockbackScale = 1.0 + slots.magnitudeOf(PowerupId.KineticDamper),
            stunChance = slots.magnitudeOf(PowerupId.BrownoutCharge),
            killRefundChance = slots.magnitudeOf(PowerupId.KillstreakCache),
            slowFraction = slots.magnitudeOf(PowerupId.ChillProtocol),
            blastFraction = slots.magnitudeOf(PowerupId.ThermitePayload),
            igniteFraction = slots.magnitudeOf(PowerupId.BurnRig),
            lifestealFraction = slots.magnitudeOf(PowerupId.RedMarketSiphon),
        )
    }

    fun clampCooldown(base: Double, requested: Double): Double =
        max(requested, max(ABSOLUTE_COOLDOWN_FLOOR, base * RELATIVE_COOLDOWN_FLOOR))
            .coerceAtMost(base * 2.0)

    /** Multiple slows take the strongest, never the product, and never pass the floor. */
    fun applySlow(current: Double, incoming: Double): Double =
        max(MIN_ENEMY_SPEED_FRACTION, min(current, 1.0 - incoming))

    private fun resolveHoming(spec: WeaponSpec, slots: PowerupSlots): Homing {
        val seeker = slots.magnitudeOf(PowerupId.SeekerDaemon)
        if (seeker <= 0.0) return spec.homing
        // Homing on a melee arc would be meaningless, so the slot pays out as reach instead.
        if (spec.cls == WeaponClass.Melee) return spec.homing
        val existing = spec.homing as? Homing.Seek
        return Homing.Seek(
            turnDegreesPerSecond = min((existing?.turnDegreesPerSecond ?: 0.0) + seeker, MAX_TURN),
            radiusPx = min((existing?.radiusPx ?: 0.0) + seeker * RADIUS_PER_DEGREE, MAX_SEEK_PX),
        )
    }

    private fun Int.saturatingPlus(other: Int): Int =
        if (this == Int.MAX_VALUE) this else this + other

    private const val MAX_TURN = 300.0
    private const val RADIUS_PER_DEGREE = 0.45
    private const val MAX_SEEK_PX = 96.0
}
