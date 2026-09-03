package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.progression.DiscoveryRecorder
import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Weapon pickup in the simulation: replacement, same-weapon Scrap and paired awards (PROD-070, P-42). */
class WeaponPickupTest {
    private fun atPlayer(sim: GameSimulation) = sim.player.centre(Physics.Default)

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
        sim.items.add(GroundItem.equipment(atPlayer(sim), weapon = Weapons.of(WeaponId.RustlineMachete)))

        val report = sim.tick(InputFrame())

        assertEquals(WeaponId.RustlineMachete, sim.run.loadout.weapon.id)
        assertEquals(
            listOf(DiscoveryId.Weapon(WeaponId.RustlineMachete)),
            report.collectedDiscoveries,
        )
        assertEquals(0, sim.run.loadout.slots.distinctCount, "the build survived the pickup")
        // Bottle 8 + Scav 20 + Street 8 + Chromed 45.
        assertEquals(scrapBefore + 8 + 20 + 8 + 45, sim.run.scrap)
    }

    @Test
    fun `a matching weapon pickup is removed for tier Scrap while the complete build survives`() {
        var loadout = Loadout.starting().collect(Weapons.of(WeaponId.SableCorpRailgun)).first
        repeat(2) { loadout = loadout.collect(PowerupId.HollowpointFirmware, 1).first }
        loadout = loadout.collect(PowerupId.OverclockCoil, 1).first
        val sim = GameSimulation(
            TestLevels.flat(),
            RunState.begin(TestLevels.SEED).copy(loadout = loadout),
            TestLevels.SEED,
        )
        val scrapBefore = sim.run.scrap
        val pickup = GroundItem.equipment(atPlayer(sim), weapon = Weapons.of(WeaponId.SableCorpRailgun))
        sim.items += pickup

        val report = sim.tick(InputFrame())

        assertEquals(loadout, sim.run.loadout)
        assertTrue(pickup !in sim.items, "the matching pickup remained on the ground")
        assertEquals(scrapBefore + 100, sim.run.scrap)
        assertEquals(listOf(100), sim.scrapGains.map { it.amount })
        assertEquals(
            listOf(DiscoveryId.Weapon(WeaponId.SableCorpRailgun)),
            report.collectedDiscoveries,
        )
    }

    @Test
    fun `a paired matching weapon scraps before its powerup joins the preserved build`() {
        var loadout = Loadout.starting().collect(Weapons.of(WeaponId.SableCorpRailgun)).first
        repeat(2) { loadout = loadout.collect(PowerupId.HollowpointFirmware, 1).first }
        loadout = loadout.collect(PowerupId.OverclockCoil, 1).first
        val sim = GameSimulation(
            TestLevels.flat(),
            RunState.begin(TestLevels.SEED).copy(loadout = loadout),
            TestLevels.SEED,
        )
        sim.items += GroundItem.equipment(
            position = atPlayer(sim),
            weapon = Weapons.of(WeaponId.SableCorpRailgun),
            powerup = Powerups.of(PowerupId.HollowpointFirmware),
            guaranteed = true,
        )

        val report = sim.tick(InputFrame())

        assertEquals(WeaponId.SableCorpRailgun, sim.run.loadout.weapon.id)
        assertEquals(3, sim.run.loadout.slots.stacksOf(PowerupId.HollowpointFirmware))
        assertEquals(1, sim.run.loadout.slots.stacksOf(PowerupId.OverclockCoil))
        assertEquals(100, sim.run.scrap)
        assertEquals(
            listOf(
                DiscoveryId.Weapon(WeaponId.SableCorpRailgun),
                DiscoveryId.Powerup(PowerupId.HollowpointFirmware),
            ),
            report.collectedDiscoveries,
        )
    }

    @Test
    fun `a paired award leaves the player with its weapon and its powerup on it`() {
        val sim = withBuild()
        sim.items.add(
            GroundItem.equipment(
                atPlayer(sim),
                weapon = Weapons.of(WeaponId.VultureRailCarbine),
                powerup = Powerups.of(PowerupId.SpikeDriver),
                guaranteed = true,
            ),
        )

        val report = sim.tick(InputFrame())

        assertEquals(WeaponId.VultureRailCarbine, sim.run.loadout.weapon.id)
        assertEquals(mapOf(PowerupId.SpikeDriver to 1), sim.run.loadout.slots.held, "the award's powerup did not survive its own weapon")
        assertEquals(
            listOf(
                DiscoveryId.Weapon(WeaponId.VultureRailCarbine),
                DiscoveryId.Powerup(PowerupId.SpikeDriver),
            ),
            report.collectedDiscoveries,
        )
    }

    /** Round-2 finding: the powerup is drawn a tile from the weapon; standing on it must collect the pair. */
    @Test
    fun `a paired award is collected from its powerup's side too`() {
        val sim = withBuild()
        // The weapon icon a tile to the left, out of reach; the powerup icon under the player.
        val at = atPlayer(sim) - Vec2(GroundItem.PAIRED_OFFSET, 0.0)
        val item = GroundItem.equipment(
            at,
            weapon = Weapons.of(WeaponId.VultureRailCarbine),
            powerup = Powerups.of(PowerupId.SpikeDriver),
            guaranteed = true,
        )
        sim.items.add(item)
        assertTrue((item.position - atPlayer(sim)).length >= 16.0, "fixture: the weapon icon is within reach")

        sim.tick(InputFrame())

        assertEquals(WeaponId.VultureRailCarbine, sim.run.loadout.weapon.id, "the pair was not collected from the powerup's side")
        assertEquals(mapOf(PowerupId.SpikeDriver to 1), sim.run.loadout.slots.held)
    }

    @Test
    fun `a repeated pickup in a fresh run is reported but is not a new profile discovery`() {
        fun collect(): List<DiscoveryId> {
            val sim = withBuild()
            sim.items.add(GroundItem.equipment(atPlayer(sim), weapon = Weapons.of(WeaponId.RustlineMachete)))
            return sim.tick(InputFrame()).collectedDiscoveries
        }

        val first = DiscoveryRecorder.record(PlayerProfile(), collect())
        val laterRun = DiscoveryRecorder.record(first.profile, collect())

        assertEquals(listOf(DiscoveryId.Weapon(WeaponId.RustlineMachete)), first.entries.map { it.id })
        assertTrue(laterRun.entries.isEmpty())
    }

    @Test
    fun `applied displaced and scrapped powerups are all reported as collected`() {
        fun simulationWith(loadout: Loadout): GameSimulation {
            val run = RunState.begin(TestLevels.SEED).copy(loadout = loadout)
            return GameSimulation(TestLevels.flat(), run, TestLevels.SEED)
        }

        val applied = simulationWith(Loadout.starting())
        applied.items.add(GroundItem.equipment(atPlayer(applied), powerup = Powerups.of(PowerupId.HollowpointFirmware)))
        assertEquals(
            listOf(DiscoveryId.Powerup(PowerupId.HollowpointFirmware)),
            applied.tick(InputFrame()).collectedDiscoveries,
        )
        assertEquals(1, applied.run.loadout.slots.stacksOf(PowerupId.HollowpointFirmware))

        var maxed = Loadout.starting()
        repeat(3) { maxed = maxed.collect(PowerupId.HollowpointFirmware, 1).first }
        val scrapped = simulationWith(maxed)
        val scrapBefore = scrapped.run.scrap
        scrapped.items.add(GroundItem.equipment(atPlayer(scrapped), powerup = Powerups.of(PowerupId.HollowpointFirmware)))
        assertEquals(
            listOf(DiscoveryId.Powerup(PowerupId.HollowpointFirmware)),
            scrapped.tick(InputFrame()).collectedDiscoveries,
        )
        assertTrue(scrapped.run.scrap > scrapBefore, "the fixture did not scrap the fourth stack")

        var full = Loadout.starting()
        Powerups.all.take(5).forEach { full = full.collect(it.id, 1).first }
        val displacedId = Powerups.all[5].id
        val displaced = simulationWith(full)
        displaced.items.add(GroundItem.equipment(atPlayer(displaced), powerup = Powerups.of(displacedId), guaranteed = true))
        assertEquals(
            listOf(DiscoveryId.Powerup(displacedId)),
            displaced.tick(InputFrame()).collectedDiscoveries,
        )
        assertEquals(1, displaced.run.loadout.slots.stacksOf(displacedId), "the fixture did not displace a slot")
    }
}
