package io.github.ksean.cyberslop.loot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PowerupSlotsTest {
    @Test
    fun `a powerup stacks up to three times`() {
        var slots = PowerupSlots.empty()

        repeat(3) { index ->
            val (next, pickup) = slots.collect(PowerupId.HollowpointFirmware)
            slots = next
            assertEquals(Pickup.Applied(PowerupId.HollowpointFirmware, index + 1), pickup)
        }

        assertEquals(3, slots.stacksOf(PowerupId.HollowpointFirmware))
    }

    @Test
    fun `a fourth copy becomes scrap rather than a fourth stack`() {
        var slots = PowerupSlots.empty()
        repeat(3) { slots = slots.collect(PowerupId.HollowpointFirmware).first }

        val (after, pickup) = slots.collect(PowerupId.HollowpointFirmware)

        assertIs<Pickup.Scrapped>(pickup)
        assertEquals(3, after.stacksOf(PowerupId.HollowpointFirmware))
    }

    @Test
    fun `at most five distinct powerups are held`() {
        var slots = PowerupSlots.empty()
        Powerups.all.take(5).forEach { slots = slots.collect(it.id).first }

        val sixth = Powerups.all[5].id
        val (after, pickup) = slots.collect(sixth)

        assertIs<Pickup.Scrapped>(pickup)
        assertEquals(5, after.distinctCount)
        assertEquals(0, after.stacksOf(sixth))
    }

    @Test
    fun `a sixth distinct powerup does not displace an existing one`() {
        var slots = PowerupSlots.empty()
        val firstFive = Powerups.all.take(5).map { it.id }
        firstFive.forEach { slots = slots.collect(it).first }

        slots = slots.collect(Powerups.all[5].id).first

        firstFive.forEach { assertEquals(1, slots.stacksOf(it), "$it was displaced") }
    }

    @Test
    fun `an existing powerup still stacks once all slots are full`() {
        var slots = PowerupSlots.empty()
        val firstFive = Powerups.all.take(5).map { it.id }
        firstFive.forEach { slots = slots.collect(it).first }

        val (after, pickup) = slots.collect(firstFive.first())

        assertIs<Pickup.Applied>(pickup)
        assertEquals(2, after.stacksOf(firstFive.first()))
    }

    @Test
    fun `contact always resolves to something`() {
        var slots = PowerupSlots.empty()
        Powerups.all.forEach { powerup ->
            repeat(4) {
                val (next, pickup) = slots.collect(powerup.id)
                slots = next
                assertTrue(pickup is Pickup.Applied || pickup is Pickup.Scrapped)
            }
        }

        assertEquals(PowerupSlots.MAX_SLOTS, slots.distinctCount)
        assertEquals(PowerupSlots.MAX_SLOTS * Powerup.MAX_STACKS, slots.totalStacks)
    }

    @Test
    fun `the build never exceeds fifteen stacks`() {
        var slots = PowerupSlots.empty()
        repeat(200) { index -> slots = slots.collect(Powerups.all[index % Powerups.all.size].id).first }

        assertTrue(slots.totalStacks <= 15, "held ${slots.totalStacks} stacks")
    }
}
