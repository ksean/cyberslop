package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.Weapons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerupRegistryTest {
    @Test
    fun `the registry meets the required breadth`() {
        assertTrue(Powerups.all.size >= 15, "only ${Powerups.all.size} powerups")
        assertEquals(Powerups.all.size, Powerups.all.map { it.id }.toSet().size)
    }

    @Test
    fun `the four named effects are present`() {
        listOf(
            PowerupId.OverclockCoil,
            PowerupId.HollowpointFirmware,
            PowerupId.SeekerDaemon,
            PowerupId.ChillProtocol,
        ).forEach { required ->
            assertTrue(Powerups.all.any { it.id == required }, "missing $required")
        }
    }

    @Test
    fun `stacking always increases strength`() {
        Powerups.all.forEach { powerup ->
            val magnitudes = (1..MAX_STACKS).map { powerup.magnitude(it) }
            magnitudes.zipWithNext { lower, higher ->
                assertTrue(higher > lower, "${powerup.id} does not grow: $magnitudes")
            }
        }
    }

    @Test
    fun `stacking is never super-linear`() {
        Powerups.all.forEach { powerup ->
            val first = powerup.magnitude(1)
            (2..MAX_STACKS).forEach { stacks ->
                assertTrue(
                    powerup.magnitude(stacks) <= first * stacks + TOLERANCE,
                    "${powerup.id} at $stacks is ${powerup.magnitude(stacks)}, " +
                        "above $stacks x $first",
                )
            }
        }
    }

    @Test
    fun `every powerup has a rarity tier`() {
        PowerupTier.entries.forEach { tier ->
            assertTrue(Powerups.all.any { it.tier == tier }, "no powerup at $tier")
        }
    }

    /** P-15: every build resolves to something that can actually fire. */
    @Test
    fun `every weapon with every powerup at every stack count resolves finite and positive`() {
        Weapons.all.forEach { weapon ->
            Powerups.all.forEach { powerup ->
                (1..MAX_STACKS).forEach { stacks ->
                    var slots = PowerupSlots.empty()
                    repeat(stacks) { slots = slots.collect(powerup.id).first }
                    val resolved = DamagePipeline.resolve(weapon, slots)
                    val label = "${weapon.id} with $stacks x ${powerup.id}"
                    assertTrue(resolved.damagePerProjectile.isFinite() && resolved.damagePerProjectile > 0.0, "$label: damage ${resolved.damagePerProjectile}")
                    assertTrue(resolved.cooldown.isFinite() && resolved.cooldown > 0.0, "$label: cooldown ${resolved.cooldown}")
                }
            }
        }
    }

    private companion object {
        const val MAX_STACKS = 3
        const val TOLERANCE = 1e-9
    }
}
