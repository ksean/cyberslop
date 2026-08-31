package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.LootFloor
import io.github.ksean.cyberslop.physics.InputFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guaranteed awards are pinned before creation, then taken through the player's real contact path. */
class PressureHarnessTest {
    @Test
    fun `a mini-boss award is pinned before its collecting jump`() {
        val sim = TestLevels.simulation()
        val mapIndex = sim.level.mapIndex
        PressureHarness.pinAwards(sim, mapIndex)
        sim.items.clear() // the starter cache, which the harness also clears
        val live = sim.miniboss
        live.fight.engage()
        // The award drops at the body's centre; put that centre in the player's reach.
        live.placeAt(Vec2(sim.player.x + 6.0, sim.player.y + 13.0 + live.height / 2.0))
        live.fight.damage(live.spec.maxHealth)

        sim.tick(InputFrame())

        assertTrue(live.fight.defeated, "fixture: the mini-boss did not die")
        assertTrue(sim.items.any { it.guaranteed }, "fixture: the raised award was collected on the death tick")
        val routeX = sim.player.x
        val beforeCollection = sim.elapsedTicks
        assertTrue(PressureHarness.collectGuaranteedAwards(sim), "the harness could not jump into the award")
        assertTrue(sim.elapsedTicks > beforeCollection, "the harness injected the loadout without movement ticks")
        assertTrue(sim.items.none { it.guaranteed }, "the collecting jump left the award behind")
        assertTrue(sim.player.onGround, "the harness resumed the route before finishing the jump")
        assertEquals(routeX, sim.player.x, 1.0, "the collecting detour did not rejoin the route")
        assertEquals(LootFloor.weaponAt(mapIndex).id, sim.run.loadout.weapon.id, "the award was not pinned before collection")
        assertEquals(LootFloor.slotsAt(mapIndex).held, sim.run.loadout.slots.held)
    }
}
