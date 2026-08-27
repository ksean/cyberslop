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
    fun `at most five distinct powerups are held, however many arrive`() {
        var slots = PowerupSlots.empty()
        Powerups.all.forEach { slots = slots.collect(it.id).first }

        assertEquals(PowerupSlots.MAX_SLOTS, slots.distinctCount, "PROD-028's cap was exceeded")
    }

    @Test
    fun `a sixth distinct powerup does not displace an existing one`() {
        var slots = PowerupSlots.empty()
        val firstFive = Powerups.all.take(5).map { it.id }
        firstFive.forEach { slots = slots.collect(it).first }

        val (after, pickup) = slots.collect(Powerups.all[5].id)

        assertIs<Pickup.Scrapped>(pickup)
        firstFive.forEach { assertEquals(1, after.stacksOf(it), "$it was displaced") }
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
                assertTrue(
                    pickup is Pickup.Applied || pickup is Pickup.Scrapped,
                    "contact resolved to nothing: $pickup",
                )
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

    /**
     * What collecting actually guarantees: **it never takes anything away.**
     *
     * This is the honest form of the property. Review round seven observed that a full build scraps
     * a *guaranteed* award once five optional powerups hold the slots, so a real player can be
     * carrying less than `LootFloor` models. Displacement was tried as the fix and withdrawn — see
     * [PowerupSlots.collect] — because it made the floor's own DPS measure fall. What remains true,
     * and is worth pinning, is that no pickup can shrink a build the player already has.
     */
    @Test
    fun `no sequence of pickups can take away something already held`() {
        var slots = PowerupSlots.empty()
        val order = Powerups.all.sortedBy { it.magnitude(1) } +
            Powerups.all.sortedByDescending { it.magnitude(1) }

        order.forEach { powerup ->
            val before = slots
            val (next, _) = slots.collect(powerup.id)
            before.held.forEach { (id, count) ->
                assertTrue(
                    next.stacksOf(id) >= count,
                    "collecting ${powerup.name} reduced $id from $count to ${next.stacksOf(id)}",
                )
            }
            slots = next
        }
    }

    /**
     * The gap round seven named, pinned so it is a recorded fact rather than a paragraph.
     *
     * A build filled with the weakest optional powerups refuses a stronger guaranteed one. This is
     * a known limitation raised with the owner, not a passing test dressed up as a guarantee.
     */
    @Test
    fun `a full build of weak powerups refuses a stronger one, which is the open gap`() {
        val ranked = Powerups.all.sortedBy { it.magnitude(1) }
        var slots = PowerupSlots.empty()
        ranked.take(PowerupSlots.MAX_SLOTS).forEach { slots = slots.collect(it.id).first }

        val (after, outcome) = slots.collect(ranked.last().id)

        assertIs<Pickup.Scrapped>(outcome)
        assertEquals(0, after.stacksOf(ranked.last().id))
        assertTrue(
            ranked.last().magnitude(1) > ranked.first().magnitude(1),
            "the fixture does not actually offer something stronger",
        )
    }
}
