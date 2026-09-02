package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.physics.TICK_SECONDS

/** The presentation attached to a terminal player-damage source (PROD-103). */
enum class PlayerDeathEffect { None, Poison, Flame, Bleed }

/**
 * Semantic source of damage to the player.
 *
 * The source travels with the damage event so a death never has to be guessed from whatever still
 * happens to overlap the player when the frame is composed.
 */
enum class PlayerDamageSource(val effect: PlayerDeathEffect) {
    Acid(PlayerDeathEffect.Poison),
    Fire(PlayerDeathEffect.Flame),
    Laser(PlayerDeathEffect.Flame),
    Spike(PlayerDeathEffect.Bleed),
    Projectile(PlayerDeathEffect.Bleed),
    Melee(PlayerDeathEffect.Bleed),
    Void(PlayerDeathEffect.None),
    Contact(PlayerDeathEffect.None),
}

/** Fixed-tick terminal interval between lethal damage and the browser end screen. */
data class DeathSequence(
    val cause: PlayerDamageSource,
    val elapsedTicks: Int = 0,
) {
    val effect: PlayerDeathEffect get() = cause.effect
    val collapseProgress: Double
        get() = (elapsedTicks.toDouble() / COLLAPSE_TICKS).coerceIn(0.0, 1.0)
    val ageSeconds: Double get() = elapsedTicks * TICK_SECONDS
    val complete: Boolean get() = elapsedTicks >= TOTAL_TICKS

    fun advance(): DeathSequence =
        if (complete) this else copy(elapsedTicks = elapsedTicks + 1)

    companion object {
        const val COLLAPSE_SECONDS = 2.0
        const val TOTAL_SECONDS = 4.0
        val COLLAPSE_TICKS: Int = (COLLAPSE_SECONDS / TICK_SECONDS).toInt()
        val TOTAL_TICKS: Int = (TOTAL_SECONDS / TICK_SECONDS).toInt()
    }
}
