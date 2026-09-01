package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.render.Camera
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.LiveProjectile
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Browser wiring for P-75: the live camera rectangle reaches the common simulation tick. */
class GameplayViewportWiringTest {
    @Test
    fun `camera bounds terminate a ranged projectile in the browser adapter`() {
        val sim = TestLevels.simulation().also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
        }
        val weapon = DamagePipeline.resolve(
            Weapons.of(WeaponId.SableCorpRailgun),
            PowerupSlots.empty(),
        )
        val shot = LiveProjectile(
            position = Vec2(100.0, 220.0),
            velocity = Vec2(6_000.0, 0.0),
            damage = weapon.damagePerProjectile,
            pierceLeft = GameSimulation.MAX_PIERCE,
            secondsLeft = 1.0,
            passesTerrain = false,
            fromPlayer = true,
            weapon = weapon,
        )
        sim.projectiles += shot

        sim.tick(InputFrame(), Camera(x = 80.0, y = 180.0, viewWidth = 70.0, viewHeight = 100.0))

        assertTrue(shot !in sim.projectiles)
        assertEquals(150.0, shot.position.x, 1e-9)
    }
}
