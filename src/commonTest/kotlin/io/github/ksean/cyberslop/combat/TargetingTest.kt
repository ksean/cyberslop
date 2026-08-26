package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Aiming is automatic and unconditional: there is no cursor and no setting, so the game needs no
 * pointing device rather than merely tolerating its absence.
 */
class TargetingTest {
    @Test
    fun `the weapon points at the nearest target`() {
        val near = Vec2(140.0, 100.0)
        val far = Vec2(300.0, 100.0)

        assertEquals(near, Targeting.aimPoint(MUZZLE, listOf(far, near), facing = 1))
    }

    @Test
    fun `targets are live positions, so a target that moves is followed`() {
        val started = Vec2(140.0, 100.0)
        val movedTo = Vec2(180.0, 130.0)

        assertEquals(started, Targeting.aimPoint(MUZZLE, listOf(started), facing = 1))
        assertEquals(movedTo, Targeting.aimPoint(MUZZLE, listOf(movedTo), facing = 1))
    }

    @Test
    fun `with nothing in range the weapon points the way the player faces`() {
        val right = Targeting.aimPoint(MUZZLE, emptyList(), facing = 1)
        val left = Targeting.aimPoint(MUZZLE, emptyList(), facing = -1)

        assertTrue(right.x > MUZZLE.x, "did not aim right when facing right")
        assertTrue(left.x < MUZZLE.x, "did not aim left when facing left")
    }

    @Test
    fun `targets beyond the auto range are ignored`() {
        assertNull(Targeting.nearest(MUZZLE, listOf(Vec2(9000.0, 9000.0))))
    }

    @Test
    fun `an empty target list never throws`() {
        assertNull(Targeting.nearest(MUZZLE, emptyList()))
    }

    private companion object {
        val MUZZLE = Vec2(100.0, 100.0)
    }
}
