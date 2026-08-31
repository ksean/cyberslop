package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.FireEffect
import io.github.ksean.cyberslop.combat.HitEffect
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val MAP_FOR_SCORING = 1

class LoadoutTest {
    @Test
    fun `a run begins with the broken bottle and no powerups`() {
        val loadout = Loadout.starting()

        assertEquals(WeaponId.BrokenBottle, loadout.weapon.id)
        assertEquals(0, loadout.slots.distinctCount)
    }

    @Test
    fun `walking over a better weapon equips it`() {
        val loadout = Loadout.starting()

        val (after, outcome) = loadout.collect(Weapons.of(WeaponId.VultureRailCarbine))

        assertIs<WeaponPickup.Equipped>(outcome)
        assertEquals(WeaponId.VultureRailCarbine, after.weapon.id)
    }

    /** PROD-070: there is no comparison — a lower-tier, lower-score weapon is taken all the same. */
    @Test
    fun `walking over a worse weapon equips it too and scraps the one held`() {
        val strong = Loadout.starting().collect(Weapons.of(WeaponId.SableCorpRailgun)).first

        val (after, outcome) = strong.collect(Weapons.of(WeaponId.BrokenBottle))

        assertIs<WeaponPickup.Equipped>(outcome)
        assertEquals(WeaponId.BrokenBottle, after.weapon.id)
        assertEquals(WeaponId.SableCorpRailgun, outcome.replaced.id)
        assertEquals(100, outcome.scrap, "a Blacksite weapon scraps for 100")
    }

    @Test
    fun `contact always resolves for every weapon in the registry`() {
        val loadout = Loadout.starting()

        Weapons.all.forEach { weapon ->
            val (after, outcome) = loadout.collect(weapon)
            if (weapon.id == loadout.weapon.id) {
                assertIs<WeaponPickup.Scrapped>(outcome, "${weapon.name} did not scrap on matching contact")
                assertEquals(loadout, after)
            } else {
                assertIs<WeaponPickup.Equipped>(outcome, "${weapon.name} did not equip on contact")
                assertEquals(weapon.id, after.weapon.id)
            }
        }
    }

    /** PROD-070: a build is made around one weapon and does not survive a different one. */
    @Test
    fun `a weapon pickup empties the build and pays Scrap for the weapon and each slot`() {
        var loadout = Loadout.starting()
        repeat(3) { loadout = loadout.collect(PowerupId.HollowpointFirmware, MAP_FOR_SCORING).first }
        repeat(2) { loadout = loadout.collect(PowerupId.OverclockCoil, MAP_FOR_SCORING).first }

        val (after, outcome) = loadout.collect(Weapons.of(WeaponId.SableCorpRailgun))

        assertIs<WeaponPickup.Equipped>(outcome)
        assertEquals(0, after.slots.distinctCount, "the build survived a weapon pickup")
        assertEquals(setOf(PowerupId.HollowpointFirmware, PowerupId.OverclockCoil), outcome.cleared.keys)
        // Bottle 8, plus one Scav slot (20) and one Chromed slot (45): per slot, not per stack.
        assertEquals(8 + 20 + 45, outcome.scrap)
    }

    /** PROD-070: another copy is sold; the build made around this weapon survives. */
    @Test
    fun `picking up a copy of the held weapon scraps only the pickup and preserves the build`() {
        var loadout = Loadout.starting().collect(Weapons.of(WeaponId.SableCorpRailgun)).first
        repeat(2) { loadout = loadout.collect(PowerupId.HollowpointFirmware, MAP_FOR_SCORING).first }
        loadout = loadout.collect(PowerupId.OverclockCoil, MAP_FOR_SCORING).first

        val (after, outcome) = loadout.collect(Weapons.of(WeaponId.SableCorpRailgun))

        assertIs<WeaponPickup.Scrapped>(outcome)
        assertEquals(loadout, after, "a same-weapon pickup changed the held build")
        assertEquals(WeaponId.SableCorpRailgun, outcome.pickup.id)
        assertEquals(100, outcome.scrap, "a Blacksite pickup scraps for 100")
    }

    @Test
    fun `a weapon pickup over an empty build pays only the weapon's Scrap`() {
        val (after, outcome) = Loadout.starting().collect(Weapons.of(WeaponId.SableCorpRailgun))

        assertIs<WeaponPickup.Equipped>(outcome)
        assertEquals(8, outcome.scrap)
        assertEquals(0, after.slots.distinctCount)
    }

    @Test
    fun `a powerup collected after a weapon lands in the emptied build`() {
        var loadout = Loadout.starting()
        repeat(3) { loadout = loadout.collect(PowerupId.HollowpointFirmware, MAP_FOR_SCORING).first }
        loadout = loadout.collect(Weapons.of(WeaponId.SableCorpRailgun)).first

        loadout = loadout.collect(PowerupId.OverclockCoil, MAP_FOR_SCORING).first

        assertEquals(mapOf(PowerupId.OverclockCoil to 1), loadout.slots.held)
    }

    @Test
    fun `the first weapon found on map one is an upgrade over the bottle`() {
        // Every other Street-tier weapon out-damages the starting one, so the opening progression
        // beat cannot be a pickup that does nothing.
        Weapons.all.filter { it.id != WeaponId.BrokenBottle }.forEach { weapon ->
            assertTrue(weapon.baseDps > Weapons.startingWeapon.baseDps, "${weapon.name} is no better than the bottle")
        }
    }

    @Test
    fun `no weapon effect can move the player`() {
        // ENG-051. If an effect could displace the player, the conservative reachability flood used
        // for anti-stranding would no longer cover everywhere they can end up.
        val movers = Weapons.all.filter { weapon ->
            weapon.onFire.any { it !is FireEffect.DashStrike && it !is FireEffect.FreeRecast } ||
                weapon.onHit.any { it is HitEffect.Slow && false }
        }

        assertTrue(movers.isEmpty(), "weapons with player-displacing effects: $movers")
    }

    @Test
    fun `the dash strike moves the reach of the attack, not the player`() {
        val katana = Weapons.of(WeaponId.KillSwitchKatana)
        val dash = katana.onFire.filterIsInstance<FireEffect.DashStrike>().single()

        assertTrue(dash.reachPx > 0.0, "a dash strike must extend the attack")
        assertTrue(dash.invulnerableSeconds > 0.0)
    }
}
