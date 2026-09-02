package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.Vec2

/** Initial velocity, unobstructed flight length and point selected for one fixed-step launch. */
data class BallisticLaunch(val velocity: Vec2, val flightTicks: Int, val intercept: Vec2)

/** Deterministic launch solution for positive-gravity projectile patterns (PROD-097). */
object ProjectileBallistics {
    const val MIN_FLIGHT_SECONDS = 0.40
    const val MIN_UPWARD_SPEED = 120.0

    fun solve(
        origin: Vec2,
        target: Vec2,
        nominalSpeed: Double,
        gravity: Double,
        lifetimeSeconds: Double,
        tickSeconds: Double,
        targetVelocity: Vec2 = Vec2.Zero,
    ): BallisticLaunch {
        require(nominalSpeed.isFinite() && nominalSpeed > 0.0) { "invalid projectile speed $nominalSpeed" }
        require(gravity.isFinite() && gravity > 0.0) { "invalid projectile gravity $gravity" }
        require(lifetimeSeconds.isFinite() && lifetimeSeconds > 0.0) { "invalid lifetime $lifetimeSeconds" }
        require(tickSeconds.isFinite() && tickSeconds > 0.0) { "invalid tick $tickSeconds" }
        require(targetVelocity.x.isFinite() && targetVelocity.y.isFinite()) {
            "invalid target velocity $targetVelocity"
        }

        findLaunch(origin, target, targetVelocity, nominalSpeed, gravity, lifetimeSeconds, tickSeconds)
            ?.let { return it }
        if (targetVelocity != Vec2.Zero) {
            findLaunch(origin, target, Vec2.Zero, nominalSpeed, gravity, lifetimeSeconds, tickSeconds)
                ?.let { return it }
        }
        throw IllegalArgumentException(
            "no upward ballistic solution from $origin to $target within $lifetimeSeconds seconds",
        )
    }

    private fun findLaunch(
        origin: Vec2,
        target: Vec2,
        targetVelocity: Vec2,
        nominalSpeed: Double,
        gravity: Double,
        lifetimeSeconds: Double,
        tickSeconds: Double,
    ): BallisticLaunch? {
        var ticks = 1
        while (ticks * tickSeconds < lifetimeSeconds) {
            val duration = ticks * tickSeconds
            val intercept = target + targetVelocity * duration
            val displacement = intercept - origin
            val minimumSeconds = maxOf(displacement.length / nominalSpeed, MIN_FLIGHT_SECONDS)
            if (duration < minimumSeconds) {
                ticks++
                continue
            }
            val velocity = Vec2(
                displacement.x / duration,
                displacement.y / duration - gravity * tickSeconds * (ticks + 1) / 2.0,
            )
            if (velocity.y <= -MIN_UPWARD_SPEED) return BallisticLaunch(velocity, ticks, intercept)
            ticks++
        }
        return null
    }
}
