package io.github.ksean.cyberslop.physics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Key events arrive between animation frames and the simulation samples once per tick, so a press
 * that begins and ends between two samples must still be seen once (P-48).
 */
class KeyLedgerTest {
    @Test
    fun `a press and release between samples is held for exactly one sample`() {
        val ledger = KeyLedger()

        ledger.press(Key.Jump)
        ledger.release(Key.Jump)

        assertEquals(Keys(jump = true), ledger.sample())
        assertEquals(Keys(), ledger.sample())
    }

    @Test
    fun `a key still down is held in every sample`() {
        val ledger = KeyLedger()

        ledger.press(Key.Right)

        assertEquals(Keys(right = true), ledger.sample())
        assertEquals(Keys(right = true), ledger.sample())
        ledger.release(Key.Right)
        assertEquals(Keys(), ledger.sample())
    }

    @Test
    fun `auto-repeat does not re-latch a key released before the sample`() {
        val ledger = KeyLedger()

        ledger.press(Key.Left)
        ledger.sample()
        ledger.press(Key.Left)
        ledger.release(Key.Left)

        assertEquals(Keys(), ledger.sample(), "a repeat is not a new press; the release must show at once")
    }

    @Test
    fun `releaseAll empties the ledger including latched presses`() {
        val ledger = KeyLedger()

        ledger.press(Key.Crouch)
        ledger.press(Key.Right)
        ledger.release(Key.Right)
        ledger.releaseAll()

        assertEquals(Keys(), ledger.sample())
    }
}
