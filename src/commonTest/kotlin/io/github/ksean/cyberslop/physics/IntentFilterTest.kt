package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Assists must live here and never inside [MovementModel]. A witness is a tape of [InputFrame]s, so
 * if the model interpreted a frame differently depending on assist state, replaying a tape would not
 * mean one thing and the completability guarantee would not hold (`specs/simulation.md`).
 */
class IntentFilterTest {
    @Test
    fun `a grounded jump press starts a jump`() {
        val filter = IntentFilter()

        val frame = filter.next(Keys(jump = true), grounded = true)

        assertTrue(frame.jumpStart)
    }

    @Test
    fun `a jump held under a low ceiling starts once the player can stand`() {
        val filter = IntentFilter()

        val blocked = List(20) { filter.next(Keys(jump = true), grounded = true, standingBlocked = true) }
        val clear = filter.next(Keys(jump = true), grounded = true, standingBlocked = false)
        val after = filter.next(Keys(jump = true), grounded = false)

        assertTrue(blocked.none { it.jumpStart }, "jumped while a ceiling forced the crouch")
        assertTrue(clear.jumpStart, "the pending jump did not start when standing became possible")
        assertFalse(after.jumpStart, "the pending jump started twice")
    }

    @Test
    fun `a jump held while crouching starts when crouch is released`() {
        val filter = IntentFilter()

        repeat(20) { filter.next(Keys(crouch = true, jump = true), grounded = true) }
        val released = filter.next(Keys(jump = true), grounded = true)

        assertTrue(released.jumpStart, "the pending jump did not start on crouch release")
    }

    @Test
    fun `holding jump does not restart the jump every tick`() {
        val filter = IntentFilter()

        filter.next(Keys(jump = true), grounded = true)
        val second = filter.next(Keys(jump = true), grounded = false)

        assertFalse(second.jumpStart, "held jump restarted mid-air")
        assertTrue(second.jump, "hold must still be reported for variable jump height")
    }

    @Test
    fun `coyote time allows a jump shortly after walking off a ledge`() {
        val filter = IntentFilter()
        filter.next(Keys(), grounded = true)

        filter.next(Keys(), grounded = false)
        val frame = filter.next(Keys(jump = true), grounded = false)

        assertTrue(frame.jumpStart, "coyote window did not allow the jump")
    }

    @Test
    fun `coyote time expires`() {
        val filter = IntentFilter()
        filter.next(Keys(), grounded = true)

        repeat(20) { filter.next(Keys(), grounded = false) }
        val frame = filter.next(Keys(jump = true), grounded = false)

        assertFalse(frame.jumpStart, "jumped long after leaving the ground")
    }

    @Test
    fun `a jump pressed just before landing is buffered until it can fire`() {
        val filter = IntentFilter()
        filter.next(Keys(), grounded = false)

        val pressed = filter.next(Keys(jump = true), grounded = false)
        assertFalse(pressed.jumpStart, "jumped while airborne")

        val landed = filter.next(Keys(jump = true), grounded = true)
        assertTrue(landed.jumpStart, "buffered jump did not fire on landing")
    }

    @Test
    fun `a buffered jump expires rather than firing much later`() {
        val filter = IntentFilter()

        filter.next(Keys(jump = true), grounded = false)
        repeat(20) { filter.next(Keys(), grounded = false) }
        val landed = filter.next(Keys(), grounded = true)

        assertFalse(landed.jumpStart, "a stale buffered jump fired on landing")
    }

    @Test
    fun `the movement model itself performs no assist`() {
        // Replaying a frame with jumpStart set is what makes the player jump. The model must not
        // add a jump of its own from a held key, or a witness tape would gain jumps on replay.
        val world = TileMap(width = 40, height = 48).apply {
            for (x in 0 until 40) this[x, 30] = TileKind.Solid
        }
        var state = PlayerState(
            x = 64.0,
            y = TileMap.toWorld(30) - Physics.Default.standingHeight,
            onGround = true,
        )

        repeat(30) { state = MovementModel.step(state, InputFrame(jump = true), world) }

        assertTrue(state.onGround, "the model jumped without jumpStart")
    }
}
