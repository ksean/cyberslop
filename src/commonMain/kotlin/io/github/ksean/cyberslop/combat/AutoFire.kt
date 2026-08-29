package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.PowerupSlots

/** One activation of a weapon, already resolved against the player's build. */
data class Shot(
    val origin: Vec2,
    val direction: Vec2,
    val weapon: ResolvedWeapon,
)

/**
 * Fires the held weapon on its own cooldown, aimed wherever the cursor is now.
 *
 * The cooldown is an accumulator, not a timestamp comparison. Setting `lastFired = now` on each
 * activation discards whatever overshoot the tick carried, which quantises every cooldown up to the
 * next whole tick: the minigun's 0.12 s would become 0.1333 s, firing at 7.5/s rather than the
 * published 8.33/s. Subtracting the cooldown instead keeps the remainder and the long-run rate
 * exact.
 */
class AutoFire(
    spec: WeaponSpec,
    slots: PowerupSlots,
    private val permanentDamageMultiplier: Double = 1.0,
) {
    var weapon: ResolvedWeapon = DamagePipeline.resolve(spec, slots, permanentDamageMultiplier)
        private set

    /** Seconds left on the cooldown; visible to the simulation's digest and its tests. */
    internal var remaining = 0.0

    /** Re-resolves the weapon against a changed build, keeping the cooldown already served. */
    fun rebuild(spec: WeaponSpec, slots: PowerupSlots) {
        weapon = DamagePipeline.resolve(spec, slots, permanentDamageMultiplier)
        remaining = remaining.coerceAtMost(weapon.cooldown)
    }

    /** Killstreak Cache: a kill can clear whatever cooldown is outstanding. */
    fun clearCooldown() {
        remaining = 0.0
    }

    fun tick(deltaSeconds: Double, muzzle: Vec2, cursor: Vec2): List<Shot> {
        remaining -= deltaSeconds
        if (remaining > 0.0) return emptyList()

        val aim = (cursor - muzzle).normalisedOr(Vec2.Right)
        val shots = mutableListOf<Shot>()
        var guard = 0
        while (remaining <= 0.0 && guard < MAX_ACTIVATIONS_PER_TICK) {
            shots.add(Shot(muzzle, aim, weapon))
            remaining += weapon.cooldown
            guard++
        }
        if (guard == MAX_ACTIVATIONS_PER_TICK) remaining = weapon.cooldown
        return shots
    }

    private companion object {
        /** A very long frame must not discharge a whole magazine at once. */
        const val MAX_ACTIVATIONS_PER_TICK = 4
    }
}
