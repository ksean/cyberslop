package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.LootFloor
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-3 finding: an award created and collected inside one tick escaped a post-tick pin. */
class PressureHarnessTest {
    @Test
    fun `a mini-boss award taken on the tick it drops is the floor's weakest`() {
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
        assertTrue(sim.items.none { it.guaranteed }, "fixture: the award was not collected on the tick it dropped")
        assertEquals(LootFloor.weaponAt(mapIndex).id, sim.run.loadout.weapon.id, "the award was not pinned before collection")
        assertEquals(LootFloor.slotsAt(mapIndex).held, sim.run.loadout.slots.held)
    }
}
