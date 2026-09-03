package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.world.TILE_SIZE
import kotlin.test.Test
import kotlin.test.assertEquals

/** ENG-024: actor-owned body geometry used by simulation, presentation and fixtures. */
class BodyGeometryTest {
    @Test
    fun `player centre follows the active physics width and stance height`() {
        val physics = Physics.Default.copy(width = 18.0, standingHeight = 30.0, crouchingHeight = 10.0)
        val standing = PlayerState(x = 20.0, y = 40.0)
        val crouching = standing.copy(stance = Stance.Crouch)

        assertEquals(Vec2(29.0, 55.0), standing.centre(physics))
        assertEquals(Vec2(29.0, 45.0), crouching.centre(physics))
    }

    @Test
    fun `normal enemy owns its square body centre and tile-aligned feet offset`() {
        val enemy = LiveEnemy(
            archetype = EnemyArchetype.Swarm,
            position = Vec2(30.0, 50.0),
            health = 1.0,
            homeX = 30.0,
            patrolPx = 0.0,
        )

        assertEquals(14.0, LiveEnemy.BODY_SIZE)
        assertEquals(Vec2(37.0, 57.0), enemy.centre)
        assertEquals(TILE_SIZE.toDouble(), LiveEnemy.FEET_OFFSET)
    }
}
