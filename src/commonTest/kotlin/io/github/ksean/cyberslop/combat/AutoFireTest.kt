package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The weapon fires on its own, always toward the cursor, with no attack input (PROD-021).
 */
class AutoFireTest {
    @Test
    fun `the broken bottle swings every two seconds without any input`() {
        val system = AutoFire(Weapons.startingWeapon, PowerupSlots.empty())
        var swings = 0

        repeat(ticksFor(seconds = 10.0)) {
            swings += system.tick(TICK_SECONDS, MUZZLE, CURSOR).size
        }

        assertEquals(5, swings, "expected one swing every 2 s over 10 s")
    }

    @Test
    fun `shots are aimed at the cursor, not at the facing direction`() {
        val system = AutoFire(Weapons.startingWeapon, PowerupSlots.empty())

        val shots = fireOnce(system, cursor = Vec2(MUZZLE.x, MUZZLE.y - 100.0))

        assertTrue(shots.isNotEmpty())
        assertTrue(shots.first().direction.y < -0.9, "did not aim upward at the cursor")
    }

    @Test
    fun `cooldowns do not drift when they are not a whole number of ticks`() {
        // 0.12 s is 7.2 ticks. Discarding the remainder each activation would make the weapon fire
        // at 7.5/s instead of 8.33/s, and every published damage figure would be wrong in play.
        val minigun = Weapons.of(WeaponId.DebtCollectorMinigun)
        val system = AutoFire(minigun, PowerupSlots.empty())
        var shots = 0

        repeat(ticksFor(seconds = 60.0)) {
            shots += system.tick(TICK_SECONDS, MUZZLE, CURSOR).size
        }

        val expected = 60.0 / minigun.cooldown
        assertTrue(
            abs(shots - expected) <= 1.0,
            "fired $shots times in 60 s, expected about $expected",
        )
    }

    @Test
    fun `every registry weapon keeps its published rate over a minute`() {
        Weapons.all.forEach { weapon ->
            val system = AutoFire(weapon, PowerupSlots.empty())
            var shots = 0
            repeat(ticksFor(seconds = 60.0)) {
                shots += system.tick(TICK_SECONDS, MUZZLE, CURSOR).size
            }
            val expected = 60.0 / weapon.cooldown
            assertTrue(
                abs(shots - expected) <= 1.0,
                "${weapon.name}: fired $shots in 60 s, expected about $expected",
            )
        }
    }

    @Test
    fun `attack speed cannot drive a weapon below the cooldown floor`() {
        val minigun = Weapons.of(WeaponId.DebtCollectorMinigun)
        var slots = PowerupSlots.empty()
        repeat(3) { slots = slots.collect(PowerupId.OverclockCoil).first }

        val resolved = DamagePipeline.resolve(minigun, slots)

        assertTrue(resolved.cooldown >= 0.08, "cooldown ${resolved.cooldown} is below the floor")
    }

    @Test
    fun `a shot carries the resolved weapon, so the build is applied`() {
        var slots = PowerupSlots.empty()
        repeat(3) { slots = slots.collect(PowerupId.HollowpointFirmware).first }
        val system = AutoFire(Weapons.startingWeapon, slots)

        val shot = fireOnce(system, CURSOR).first()

        assertTrue(
            shot.weapon.damagePerProjectile > Weapons.startingWeapon.damage,
            "damage powerup was not applied",
        )
    }

    private fun fireOnce(system: AutoFire, cursor: Vec2): List<Shot> {
        repeat(ticksFor(seconds = 5.0)) {
            val shots = system.tick(TICK_SECONDS, MUZZLE, cursor)
            if (shots.isNotEmpty()) return shots
        }
        return emptyList()
    }

    private fun ticksFor(seconds: Double): Int = (seconds / TICK_SECONDS).toInt()

    private companion object {
        val MUZZLE = Vec2(100.0, 100.0)
        val CURSOR = Vec2(400.0, 100.0)
    }
}
