package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Weapon pickup in the simulation: the reset and the paired award (PROD-070, P-42). */
class WeaponPickupTest {
    private fun atPlayer(sim: GameSimulation) = Vec2(sim.player.x + 6.0, sim.player.y + 13.0)

    /** A simulation whose player already holds a three-slot build on the bottle. */
    private fun withBuild(): GameSimulation {
        var loadout = Loadout.starting()
        repeat(3) { loadout = loadout.collect(PowerupId.HollowpointFirmware, 1).first }
        loadout = loadout.collect(PowerupId.FractureLens, 1).first
        loadout = loadout.collect(PowerupId.OverclockCoil, 1).first
        val run = RunState.begin(TestLevels.SEED).copy(loadout = loadout)
        return GameSimulation(TestLevels.flat(), run, TestLevels.SEED)
    }

    @Test
    fun `a kill-drop weapon wipes a three-slot build and pays its Scrap`() {
        val sim = withBuild()
        val scrapBefore = sim.run.scrap
        sim.items.add(GroundItem(atPlayer(sim), Weapons.of(WeaponId.RustlineMachete), null))

        sim.tick(InputFrame())

        assertEquals(WeaponId.RustlineMachete, sim.run.loadout.weapon.id)
        assertEquals(0, sim.run.loadout.slots.distinctCount, "the build survived the pickup")
        // Bottle 8 + Scav 20 + Street 8 + Chromed 45.
        assertEquals(scrapBefore + 8 + 20 + 8 + 45, sim.run.scrap)
    }

    @Test
    fun `a paired award leaves the player with its weapon and its powerup on it`() {
        val sim = withBuild()
        sim.items.add(
            GroundItem(atPlayer(sim), Weapons.of(WeaponId.VultureRailCarbine), Powerups.of(PowerupId.SpikeDriver), guaranteed = true),
        )

        sim.tick(InputFrame())

        assertEquals(WeaponId.VultureRailCarbine, sim.run.loadout.weapon.id)
        assertEquals(mapOf(PowerupId.SpikeDriver to 1), sim.run.loadout.slots.held, "the award's powerup did not survive its own weapon")
    }

    /** Round-2 finding: the powerup is drawn a tile from the weapon; standing on it must collect the pair. */
    @Test
    fun `a paired award is collected from its powerup's side too`() {
        val sim = withBuild()
        // The weapon icon a tile to the left, out of reach; the powerup icon under the player.
        val at = atPlayer(sim) - Vec2(GroundItem.PAIRED_OFFSET, 0.0)
        val item = GroundItem(at, Weapons.of(WeaponId.VultureRailCarbine), Powerups.of(PowerupId.SpikeDriver), guaranteed = true)
        sim.items.add(item)
        assertTrue((item.position - atPlayer(sim)).length >= 16.0, "fixture: the weapon icon is within reach")

        sim.tick(InputFrame())

        assertEquals(WeaponId.VultureRailCarbine, sim.run.loadout.weapon.id, "the pair was not collected from the powerup's side")
        assertEquals(mapOf(PowerupId.SpikeDriver to 1), sim.run.loadout.slots.held)
    }
}
