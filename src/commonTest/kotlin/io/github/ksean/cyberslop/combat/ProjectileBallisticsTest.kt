package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** P-71: the lob solver targets the fixed-step integrator rather than a cosmetic curve. */
class ProjectileBallisticsTest {
    @Test
    fun `a same-height target receives an upward whole-tick launch that reaches its snapshot`() {
        val origin = Vec2(80.0, 160.0)
        val target = Vec2(320.0, 160.0)
        val gravity = 600.0
        val launch = ProjectileBallistics.solve(
            origin = origin,
            target = target,
            nominalSpeed = 420.0,
            gravity = gravity,
            lifetimeSeconds = 2.0,
            tickSeconds = TICK_SECONDS,
        )

        assertTrue(launch.velocity.y <= -ProjectileBallistics.MIN_UPWARD_SPEED)
        val position = land(origin, launch, gravity)

        assertClose(target.x, position.x)
        assertClose(target.y, position.y)
    }

    @Test
    fun `higher and lower targets on either side receive exact upward solutions`() {
        val origin = Vec2(400.0, 200.0)
        listOf(
            Vec2(160.0, 136.0),
            Vec2(160.0, 264.0),
            Vec2(640.0, 136.0),
            Vec2(640.0, 264.0),
        ).forEach { target ->
            val launch = solve(origin, target)
            val landing = land(origin, launch, GRAVITY)
            assertTrue(launch.velocity.y <= -ProjectileBallistics.MIN_UPWARD_SPEED, "$target: ${launch.velocity}")
            assertClose(target.x, landing.x)
            assertClose(target.y, landing.y)
        }
    }

    @Test
    fun `resolved speed shortens the nominal flight bound without losing the upward launch`() {
        val origin = Vec2.Zero
        val target = Vec2(240.0, 0.0)
        val base = solve(origin, target, nominalSpeed = 420.0)
        val optics = solve(origin, target, nominalSpeed = 630.0)

        assertTrue(optics.flightTicks < base.flightTicks, "$base did not shorten to $optics")
        assertTrue(optics.velocity.y <= -ProjectileBallistics.MIN_UPWARD_SPEED)
        assertClose(target.x, land(origin, optics, GRAVITY).x)
    }

    @Test
    fun `an invalid lifetime is rejected instead of silently firing straight`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectileBallistics.solve(
                origin = Vec2.Zero,
                target = Vec2(0.0, Targeting.AUTO_RANGE_PX),
                nominalSpeed = 420.0,
                gravity = GRAVITY,
                lifetimeSeconds = 0.4,
                tickSeconds = TICK_SECONDS,
            )
        }
    }

    private fun solve(origin: Vec2, target: Vec2, nominalSpeed: Double = 420.0) =
        ProjectileBallistics.solve(origin, target, nominalSpeed, GRAVITY, 2.0, TICK_SECONDS)

    private fun land(origin: Vec2, launch: BallisticLaunch, gravity: Double): Vec2 {
        var position = origin
        var velocity = launch.velocity
        repeat(launch.flightTicks) {
            velocity = Vec2(velocity.x, velocity.y + gravity * TICK_SECONDS)
            position += velocity * TICK_SECONDS
        }
        return position
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    private companion object {
        const val GRAVITY = 600.0
    }
}
