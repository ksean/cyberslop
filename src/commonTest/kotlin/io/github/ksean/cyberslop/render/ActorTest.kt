package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.physics.Physics
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PROD-041 and ENG-062: the player is animated across eight distinguishable states, weapon
 * animation composes over movement, and the whole thing is a pure function of simulation state.
 *
 * Posing is arithmetic, which is the reason `specs/presentation.md` chose a rig over a sprite atlas: "the
 * crouch pose is shorter than the standing pose" is an assertion here, where a frame index into an
 * atlas would only be a screenshot.
 */
class ActorTest {
    @Test
    fun `every locomotion clip is reachable`() {
        val selected = MOTIONS.map { Actor.pose(it, Physics.Default).clip }.toSet()

        assertEquals(
            Clip.entries.toSet(),
            selected,
            "these clips are unreachable: ${Clip.entries.toSet() - selected}",
        )
    }

    @Test
    fun `every action is reachable`() {
        val selected = MOTIONS.map { Actor.pose(it, Physics.Default).action }.toSet()

        assertEquals(Action.entries.toSet(), selected, "an action overlay can never be seen")
    }

    @Test
    fun `posing is pure`() {
        val motion = running()

        val once = Actor.pose(motion, Physics.Default)
        val again = Actor.pose(motion, Physics.Default)

        assertEquals(once, again, "the same motion posed differently twice")
    }

    @Test
    fun `a crouched figure is shorter by the physics own crouch height`() {
        val standing = Actor.pose(standing(), Physics.Default)
        val crouched = Actor.pose(crouching(), Physics.Default)

        assertEquals(
            Physics.Default.standingHeight,
            standing.height,
            absoluteTolerance = 1e-9,
            message = "the standing rig is not the height the physics says the player is",
        )
        assertEquals(
            Physics.Default.crouchingHeight,
            crouched.height,
            absoluteTolerance = 1e-9,
            message = "the crouched rig is not the height the physics says the player is",
        )
        assertTrue(
            crouched.head.y > standing.head.y,
            "the crouched head is no lower than the standing head",
        )
    }

    @Test
    fun `a run cycle alternates the lead foot`() {
        val physics = Physics.Default
        val forward = Actor.pose(running(stridePx = 0.0), physics)
        val opposite = Actor.pose(running(stridePx = Actor.STRIDE_PX / 2.0), physics)

        val gap = forward.leadFoot.x - forward.rearFoot.x
        val mirrored = opposite.leadFoot.x - opposite.rearFoot.x

        // The magnitude matters as much as the sign. Feet a rounding error apart also swap sign,
        // and a gait built on sine rather than cosine does exactly that — measured, by mutating
        // the implementation and watching a sign-only assertion still pass.
        assertTrue(
            abs(gap) > forward.height * MIN_STRIDE_FRACTION,
            "the feet are $gap apart, which is not a stride",
        )
        assertTrue(
            gap * mirrored < 0.0,
            "half a stride later the same foot is still in front, so the legs do not walk",
        )
    }

    /**
     * One variable at a time, which the first version of this did not do: its two "different" poses
     * were the same call, and the one comparison that differed changed the stride *and* the elapsed
     * time together — so a gait driven by the clock would have passed.
     */
    @Test
    fun `gait comes from distance travelled, not from elapsed time`() {
        val physics = Physics.Default

        // Time moves, distance does not — and **one time input at a time**, so a gait reading the
        // difference or the ratio of the two cannot hide along a diagonal. Every value is held past
        // its action window, so the clip and the action stay put.
        val base = running(stridePx = 100.0, secondsSinceShot = 5.0, secondsSinceSwing = 5.0)
        val early = Actor.pose(base, physics)

        listOf(
            base.copy(secondsSinceShot = 90.0),
            base.copy(secondsSinceSwing = 90.0),
            base.copy(secondsSinceShot = 90.0, secondsSinceSwing = 5.0),
            base.copy(secondsSinceShot = 5.0, secondsSinceSwing = 90.0),
            base.copy(secondsSinceShot = 90.0, secondsSinceSwing = 90.0),
        ).forEach { motion ->
            val later = Actor.pose(motion, physics)
            assertEquals(early.clip, later.clip, "the clip changed; the comparison is not isolated")
            assertEquals(early.action, later.action, "the action changed; not isolated")
            assertEquals(
                early.leadFoot,
                later.leadFoot,
                "time alone moved the gait: shot ${motion.secondsSinceShot}, " +
                    "swing ${motion.secondsSinceSwing}",
            )
            assertEquals(early.rearFoot, later.rearFoot, "time alone moved the gait")
        }

        // Distance moves, time does not.
        val walked = Actor.pose(
            running(
                stridePx = 100.0 + Actor.STRIDE_PX / 4.0,
                secondsSinceShot = 5.0,
                secondsSinceSwing = 5.0,
            ),
            physics,
        )
        assertTrue(
            abs(walked.leadFoot.x - early.leadFoot.x) > early.height * MIN_STRIDE_FRACTION,
            "a quarter of a stride moved the lead foot by " +
                "${walked.leadFoot.x - early.leadFoot.x} px, so the gait does not track distance",
        )
    }

    @Test
    fun `firing changes the arms and leaves the legs running`() {
        val physics = Physics.Default
        val plain = Actor.pose(running(), physics)
        val firing = Actor.pose(running(secondsSinceShot = 0.0), physics)

        assertEquals(Clip.Run, firing.clip, "firing replaced the locomotion clip instead of layering")
        assertEquals(Action.Fire, firing.action)
        assertEquals(plain.leadFoot, firing.leadFoot, "the legs stopped running to fire")
        assertEquals(plain.rearFoot, firing.rearFoot, "the legs stopped running to fire")
        assertNotEquals(plain.leadHand, firing.leadHand, "the weapon arm did not react to the shot")
    }

    @Test
    fun `a swing sweeps the weapon arm and leaves the legs running`() {
        val physics = Physics.Default
        val plain = Actor.pose(running(), physics)
        val early = Actor.pose(running(secondsSinceSwing = 0.0), physics)
        val late = Actor.pose(running(secondsSinceSwing = Actor.SWING_SECONDS * 0.75), physics)

        assertEquals(Action.Swing, early.action)
        assertEquals(plain.leadFoot, early.leadFoot, "the legs stopped running to swing")
        assertTrue(
            abs(early.leadHand.y - late.leadHand.y) > 1.0 ||
                abs(early.leadHand.x - late.leadHand.x) > 1.0,
            "the weapon hand does not move through the swing, so nothing sweeps",
        )
    }

    /** P-38: a telegraph is a held pose, and it is the arm that holds it. */
    @Test
    fun `a wind-up draws the lead arm back and raised and leaves the legs running`() {
        val physics = Physics.Default
        val plain = Actor.pose(running(), physics)
        val telegraph = Actor.pose(running(windingUp = true), physics)

        assertEquals(Action.WindUp, telegraph.action)
        assertEquals(Clip.Run, telegraph.clip, "the wind-up replaced the locomotion clip")
        assertEquals(plain.leadFoot, telegraph.leadFoot, "the legs stopped running to wind up")
        assertEquals(plain.rearFoot, telegraph.rearFoot, "the legs stopped running to wind up")
        assertTrue(
            telegraph.leadHand.x < telegraph.leadShoulder.x,
            "the lead hand is not drawn back behind the shoulder: ${telegraph.leadHand}",
        )
        assertTrue(
            telegraph.leadHand.y < telegraph.leadShoulder.y,
            "the lead hand is not raised above the shoulder: ${telegraph.leadHand}",
        )
    }

    @Test
    fun `facing mirrors the figure`() {
        val physics = Physics.Default
        val right = Actor.pose(running(facing = 1), physics)
        val left = Actor.pose(running(facing = -1), physics)

        assertEquals(
            right.leadHand.x,
            -left.leadHand.x,
            absoluteTolerance = 1e-9,
            message = "the figure does not mirror when it turns around",
        )
    }

    @Test
    fun `a rising figure tucks and a falling one reaches`() {
        val physics = Physics.Default
        val rising = Actor.pose(airborne(verticalSpeed = -400.0), physics)
        val falling = Actor.pose(airborne(verticalSpeed = 400.0), physics)

        assertEquals(Clip.JumpRise, rising.clip)
        assertEquals(Clip.JumpFall, falling.clip)
        assertTrue(
            rising.leadFoot.y < falling.leadFoot.y,
            "the rising figure's feet are not tucked higher than the falling figure's",
        )
    }

    private companion object {
        /** A stride has to be visible at 26 px, not merely non-zero. */
        const val MIN_STRIDE_FRACTION = 0.1

        fun standing() = Motion(facing = 1)

        fun crouching(speedX: Double = 0.0) =
            Motion(speedX = speedX, crouched = true, onGround = true, facing = 1)

        fun running(
            stridePx: Double = 0.0,
            facing: Int = 1,
            secondsSinceShot: Double = Double.MAX_VALUE,
            secondsSinceSwing: Double = Double.MAX_VALUE,
            windingUp: Boolean = false,
        ) = Motion(
            speedX = 200.0 * facing,
            onGround = true,
            stridePx = stridePx,
            facing = facing,
            secondsSinceShot = secondsSinceShot,
            secondsSinceSwing = secondsSinceSwing,
            windingUp = windingUp,
        )

        fun airborne(verticalSpeed: Double) =
            Motion(speedX = 100.0, onGround = false, verticalSpeed = verticalSpeed, facing = 1)

        val MOTIONS = listOf(
            standing(),
            running(),
            crouching(),
            crouching(speedX = 60.0),
            airborne(verticalSpeed = -400.0),
            airborne(verticalSpeed = 400.0),
            running(secondsSinceShot = 0.0),
            running(secondsSinceSwing = 0.0),
            running(windingUp = true),
        )
    }
}
