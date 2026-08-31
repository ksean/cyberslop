package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.loot.PowerupSlots

/**
 * What a build is worth: the held weapon under a candidate set of powerups. It decides which slot
 * a powerup displaces (`Loadout.collect(powerup)`); it decides nothing about weapon pickup, which
 * resolves solely by exact weapon identity (PROD-070).
 *
 * Comparison happens against a **declared reference target**, because half the registry's damage is
 * conditional — execute thresholds, falloff, chain decay, damage-over-time — and without a target to
 * resolve against, "which weapon is better" has no answer. The reference is deliberately plain: one
 * enemy at a middling health fraction, at medium range, unslowed, with full uptime.
 *
 * The score is a build applied to the weapon it feeds, so a powerup that suits one weapon and not
 * another is accounted for rather than ignored.
 */
object WeaponScore {
    /** Distance the reference target stands at. */
    private const val REFERENCE_RANGE_PX = 64.0

    /** How much a pattern is worth beyond its single-target damage, for hitting several things. */
    private fun crowdFactor(pattern: FirePattern): Double = when (pattern) {
        is FirePattern.ArcSwing -> 1.0 + pattern.arcDegrees / 360.0
        is FirePattern.Blast -> 1.8
        is FirePattern.Chain -> 1.0 + pattern.jumps * 0.25
        is FirePattern.Beam -> 1.4
        is FirePattern.Orbit -> 1.6
        is FirePattern.Strike -> 1.5
        is FirePattern.Pull -> 1.5
        is FirePattern.Projectile -> 1.0
    }

    fun of(spec: WeaponSpec, slots: PowerupSlots, mapIndex: Int): Double {
        val resolved = DamagePipeline.resolve(spec, slots)

        val perActivation = resolved.expectedPerActivation *
            falloffAt(spec.falloff, REFERENCE_RANGE_PX) *
            (1.0 + resolved.blastFraction) *
            (1.0 + conditionalBonus(spec, mapIndex))

        val overTime = resolved.igniteFraction * resolved.damagePerProjectile
        val effective = (perActivation + overTime) / resolved.cooldown

        // Wind-up is paid on every activation, so a long telegraph really does cost damage.
        val windUpPenalty = resolved.cooldown / (resolved.cooldown + spec.windUpSeconds)

        return effective * crowdFactor(spec.pattern) * windUpPenalty *
            (1.0 + resolved.chainTargets * 0.2)
    }

    private fun falloffAt(falloff: Falloff, distance: Double): Double = when (falloff) {
        Falloff.None -> 1.0
        is Falloff.Linear -> when {
            distance <= falloff.startPx -> 1.0
            distance >= falloff.endPx -> falloff.minimum
            else -> {
                val travelled = (distance - falloff.startPx) / (falloff.endPx - falloff.startPx)
                1.0 - travelled * (1.0 - falloff.minimum)
            }
        }
    }

    /** Bleed, execute and the like, valued against the reference target rather than ignored. */
    private fun conditionalBonus(spec: WeaponSpec, mapIndex: Int): Double =
        spec.onHit.sumOf { effect ->
            when (effect) {
                is HitEffect.Bleed -> effect.perSecond * effect.seconds / spec.damage
                is HitEffect.Execute -> effect.healthFraction
                is HitEffect.Shock -> effect.extraTargets * 0.2
                is HitEffect.BlastOnHit -> effect.damageFraction
                is HitEffect.Ignite -> effect.fractionPerSecond * effect.seconds
                is HitEffect.Stun -> effect.chance * effect.seconds * 0.2
                is HitEffect.Slow -> effect.fraction * 0.1
            }
        }.coerceAtMost(MAX_CONDITIONAL_BONUS)

    private const val MAX_CONDITIONAL_BONUS = 1.0
}
