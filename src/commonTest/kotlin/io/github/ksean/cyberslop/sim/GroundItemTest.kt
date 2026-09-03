package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ENG-057: current ground-item payload and contact semantics before representation changes. */
class GroundItemTest {
    @Test
    fun `equipment variants and ramen retain their payload position and contact semantics`() {
        val at = Vec2(40.0, 30.0)
        val weapon = Weapons.of(WeaponId.ChromeFang)
        val powerup = Powerups.of(PowerupId.ForkBomb)
        val weaponOnly = GroundItem.equipment(at, weapon = weapon)
        val powerupOnly = GroundItem.equipment(at, powerup = powerup)
        val paired = GroundItem.equipment(at, weapon, powerup, guaranteed = true)
        val ramen = GroundItem.ramen(at)

        assertEquals(weapon, (weaponOnly.payload as GroundItem.Equipment).weapon)
        assertEquals(powerup, (powerupOnly.payload as GroundItem.Equipment).powerup)
        assertEquals(at + Vec2(GroundItem.PAIRED_OFFSET, 0.0), paired.powerupPosition)
        assertTrue((paired.payload as GroundItem.Equipment).guaranteed)
        assertEquals(GroundItem.Ramen, ramen.payload)
        assertFalse(weaponOnly.payload.guaranteed)

        assertTrue(paired.inReachOf(paired.powerupPosition, reach = 1.0))
        assertTrue(ramen.inReachOf(at, reach = 1.0))
        assertFalse(ramen.inReachOf(at + Vec2(2.0, 0.0), reach = 1.0))
        assertFailsWith<IllegalArgumentException> { GroundItem.Equipment() }
    }
}
