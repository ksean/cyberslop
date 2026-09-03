package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertTrue

/** Round-3 finding: a shot in flight carries the build that fired it, not whatever is held when it lands. */
class ProjectileOwnershipTest {
    @Test
    fun `a projectile keeps the effects of the weapon and build that fired it`() {
        var loadout = Loadout(Weapons.of(WeaponId.ScraplineZipPistol), io.github.ksean.cyberslop.loot.PowerupSlots.empty())
        loadout = loadout.collect(PowerupId.ChillProtocol, 1).first
        val sim = GameSimulation(TestLevels.flat(), RunState.begin(TestLevels.SEED).copy(loadout = loadout), TestLevels.SEED)
        val turret = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 8, health = 1e9)

        while (sim.projectiles.isEmpty()) sim.tick(InputFrame())
        // The build is wiped by a weapon pickup while the slug is still in the air.
        sim.items.add(
            GroundItem.equipment(
                sim.player.centre(Physics.Default),
                weapon = Weapons.of(WeaponId.RustlineMachete),
            ),
        )
        sim.tick(InputFrame())
        assertTrue(sim.run.loadout.slots.distinctCount == 0, "fixture: the pickup did not clear the build")
        assertTrue(sim.projectiles.isNotEmpty(), "fixture: the slug already landed")

        var ticks = 0
        while (sim.projectiles.isNotEmpty() && ticks < 120) { sim.tick(InputFrame()); ticks++ }

        assertTrue(turret.slowSecondsLeft > 0.0, "the slug lost the Chill Protocol slow of the build that fired it")
    }
}
