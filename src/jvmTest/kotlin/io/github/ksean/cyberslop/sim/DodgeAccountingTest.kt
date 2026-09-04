package io.github.ksean.cyberslop.sim

import kotlin.test.Test
import kotlin.test.assertEquals

class DodgeAccountingTest {
    @Test
    fun `one multi-event activation counts as one successful dodge`() {
        val accounting = DodgeAccounting()

        accounting.observe(IncomingAttackEvent(7, IncomingAttackPhase.Started))
        accounting.recordResponse(setOf(7))
        accounting.observe(IncomingAttackEvent(7, IncomingAttackPhase.Opportunity))
        accounting.observe(IncomingAttackEvent(7, IncomingAttackPhase.Opportunity))
        accounting.observe(IncomingAttackEvent(7, IncomingAttackPhase.Completed))

        assertEquals(1, accounting.counted)
        assertEquals(1, accounting.dodged)
        assertEquals(1.0, accounting.dodgeRate)
    }

    @Test
    fun `damage from an activation makes its attempted dodge unsuccessful`() {
        val accounting = DodgeAccounting()

        accounting.observe(IncomingAttackEvent(9, IncomingAttackPhase.Started))
        accounting.recordResponse(setOf(9))
        accounting.observe(IncomingAttackEvent(9, IncomingAttackPhase.Opportunity))
        accounting.observe(IncomingAttackEvent(9, IncomingAttackPhase.DamagedPlayer))
        accounting.observe(IncomingAttackEvent(9, IncomingAttackPhase.Completed))

        assertEquals(1, accounting.counted)
        assertEquals(0, accounting.dodged)
        assertEquals(0.0, accounting.dodgeRate)
    }
}
