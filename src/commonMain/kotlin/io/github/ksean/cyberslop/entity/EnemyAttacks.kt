package io.github.ksean.cyberslop.entity

import io.github.ksean.cyberslop.world.TILE_SIZE

/** A telegraphed melee strike: nothing during the wind-up, one hit, then a cooldown. */
data class EnemySwing(
    val windUpSeconds: Double,
    /** Multiplied by the map's contact damage. */
    val damageShare: Double,
    val cooldownSeconds: Double,
    val reachPx: Double = SWING_REACH_PX,
    val arcDegrees: Double = 90.0,
) {
    companion object {
        const val SWING_REACH_PX = 1.5 * TILE_SIZE
    }
}

/** A telegraphed shot: the aim is taken at the start of the wind-up and the projectile leaves after. */
data class EnemyShot(
    val windUpSeconds: Double,
    val damageShare: Double,
    val cooldownSeconds: Double,
    val speedPx: Double,
    val rangePx: Double,
    val lifetimeSeconds: Double,
)

/**
 * What each archetype does to the player (`specs/enemies.md`, Attacks): a telegraphed swing or
 * shot, and — separately, with no wind-up — the drain of a living body the player overlaps
 * ([CONTACT_DRAIN], PROD-069).
 */
object EnemyAttacks {
    /** Public so the renderer's tracking pose reads the same range the shot does. */
    const val SHOT_RANGE_PX = 220.0

    /** A living body drains this many `contactDamage` per second of overlap (`specs/enemies.md`). */
    const val CONTACT_DRAIN = 1.0

    val SHOT = EnemyShot(
        windUpSeconds = 0.25,
        damageShare = 0.45,
        cooldownSeconds = 0.75,
        speedPx = 340.0,
        rangePx = SHOT_RANGE_PX,
        lifetimeSeconds = 2.5,
    )

    fun swing(archetype: EnemyArchetype): EnemySwing = when (archetype) {
        EnemyArchetype.Swarm -> EnemySwing(windUpSeconds = 0.30, damageShare = 0.6, cooldownSeconds = 0.6)
        EnemyArchetype.Flyer -> EnemySwing(windUpSeconds = 0.30, damageShare = 0.8, cooldownSeconds = 0.8)
        EnemyArchetype.Brute -> EnemySwing(windUpSeconds = 0.45, damageShare = 1.2, cooldownSeconds = 1.1)
        EnemyArchetype.Shooter, EnemyArchetype.Turret ->
            error("$archetype shoots; it has no swing")
    }
}
