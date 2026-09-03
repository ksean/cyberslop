package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.FirePattern
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-1 finding: an orbit resolved at the weapon's range, twice its declared orbit radius. */
class OrbitWeaponTest {
    private fun simulation(): GameSimulation {
        val run = RunState.begin(TestLevels.SEED).copy(loadout = RunState.begin(TestLevels.SEED).loadout.copy(weapon = Weapons.of(WeaponId.NullEgoSingularity)))
        return GameSimulation(TestLevels.flat(), run, TestLevels.SEED)
    }

    private fun enemyAt(sim: GameSimulation, offsetX: Double): LiveEnemy {
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = TestLevels.SPAWN_COLUMN)
        enemy.stun(seconds = 60.0)
        val centre = sim.player.centre(Physics.Default)
        enemy.position = centre + Vec2(offsetX - LiveEnemy.BODY_HALF, -LiveEnemy.BODY_HALF)
        return enemy
    }

    /** The orbit is cursor-anchored: it resolves around the aimed target, at the pattern's own radius. */
    @Test
    fun `an orbit hits inside its declared radius around its target and not beyond it`() {
        val radius = (Weapons.of(WeaponId.NullEgoSingularity).pattern as FirePattern.Orbit).radius
        val inside = simulation()
        val aimedNear = enemyAt(inside, 20.0)
        val near = enemyAt(inside, 20.0 + radius - 4.0)
        val outside = simulation()
        val aimedFar = enemyAt(outside, 20.0)
        val far = enemyAt(outside, 20.0 + radius + 4.0)
        val health = near.health

        var ring: HitShape.Ring? = null
        repeat(90) {
            inside.tick(InputFrame()); outside.tick(InputFrame())
            (inside.lastHit?.shape as? HitShape.Ring)?.let { ring = it }
        }

        assertTrue(aimedNear.health < health && aimedFar.health < health, "fixture: the aimed target was not hit")
        assertTrue(near.health < health, "an enemy inside the orbit radius of the target was not hit")
        assertEquals(health, far.health, 1e-9, "an enemy beyond the orbit radius of the target was hit")
        assertEquals(radius, ring?.radius ?: error("no ring recorded"), 1e-9, "the indicator is not the orbit radius")
    }
}
