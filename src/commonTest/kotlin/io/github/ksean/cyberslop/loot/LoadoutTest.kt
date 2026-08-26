package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.FireEffect
import io.github.ksean.cyberslop.combat.HitEffect
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

        val (after, outcome) = loadout.collect(Weapons.of(WeaponId.VultureRailCarbine), mapIndex = 3)

        assertIs<WeaponPickup.Equipped>(outcome)
        assertEquals(WeaponId.VultureRailCarbine, after.weapon.id)
    }

    @Test
    fun `walking over a worse weapon leaves the loadout alone and yields scrap`() {
        val strong = Loadout.starting()
            .collect(Weapons.of(WeaponId.SableCorpRailgun), mapIndex = 8).first

        val (after, outcome) = strong.collect(Weapons.of(WeaponId.BrokenBottle), mapIndex = 8)

        assertIs<WeaponPickup.Scrapped>(outcome)
        assertEquals(WeaponId.SableCorpRailgun, after.weapon.id)
    }

    @Test
    fun `contact always resolves, for every weapon in the registry`() {
        val loadout = Loadout.starting()

        Weapons.all.forEach { weapon ->
            val (_, outcome) = loadout.collect(weapon, mapIndex = 5)
            assertTrue(
                outcome is WeaponPickup.Equipped || outcome is WeaponPickup.Scrapped,
                "${weapon.name} did not resolve on contact",
            )
        }
    }

    @Test
    fun `powerups survive a weapon swap`() {
        var loadout = Loadout.starting()
        repeat(3) { loadout = loadout.collect(PowerupId.HollowpointFirmware).first }
        repeat(2) { loadout = loadout.collect(PowerupId.OverclockCoil).first }

        loadout = loadout.collect(Weapons.of(WeaponId.SableCorpRailgun), mapIndex = 8).first

        assertEquals(3, loadout.slots.stacksOf(PowerupId.HollowpointFirmware))
        assertEquals(2, loadout.slots.stacksOf(PowerupId.OverclockCoil))
    }

    @Test
    fun `the first weapon found on map one is an upgrade over the bottle`() {
        // Every other Street-tier weapon out-damages the starting one, so the opening progression
        // beat cannot be a pickup that does nothing.
        val loadout = Loadout.starting()

        Weapons.all.filter { it.id != WeaponId.BrokenBottle }.forEach { weapon ->
            val (_, outcome) = loadout.collect(weapon, mapIndex = 1)
            assertIs<WeaponPickup.Equipped>(outcome, "${weapon.name} was refused over the bottle")
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
