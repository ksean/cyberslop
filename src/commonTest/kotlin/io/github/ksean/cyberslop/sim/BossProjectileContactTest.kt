package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P-90: boss rounds use swept visible-body contact and committed-only protection. */
class BossProjectileContactTest {
    @Test
    fun `boss rounds sweep the standing and crouching player bodies between tick endpoints`() {
        listOf(BossModule.Bolt, BossModule.Burst, BossModule.Scatter).forEach { module ->
            listOf(false, true).forEach { crouched ->
                val sim = TestLevels.simulation()
                if (crouched) sim.tick(InputFrame(crouch = true))
                val radius = 6.0
                val start = Vec2(sim.player.x - radius - 4.0, sim.player.y - radius)
                val endX = sim.player.x + Physics.Default.width + radius + 4.0
                val round = bossRound(
                    position = start,
                    velocity = Vec2((endX - start.x) / TICK_SECONDS, 0.0),
                    module = module,
                )
                sim.projectiles += round

                val healthBefore = sim.run.health
                sim.tick(InputFrame(crouch = crouched))

                assertEquals(healthBefore - DAMAGE, sim.run.health, 1e-9, "$module crouched=$crouched")
                assertFalse(round in sim.projectiles, "the contacting $module round remained live")
                assertTrue(sim.playerHurtSecondsLeft > 0.0, "the $module contact did not start the red hurt flash")
                val impact = sim.impacts.single().shape as HitShape.Impact
                assertEquals(sim.player.x, impact.at.x, 1e-6, "the $module round did not stop at first contact")
                assertEquals(sim.player.y - radius, impact.at.y, 1e-6)
            }
        }
    }

    @Test
    fun `an ordinary jump outside a committed span does not suppress a boss round`() {
        val sim = TestLevels.simulation()
        sim.tick(InputFrame(jump = true, jumpStart = true))
        assertFalse(sim.player.onGround, "fixture: the player did not jump")
        val round = bossRound(position = sim.player.centre(Physics.Default), velocity = Vec2.Zero)
        sim.projectiles += round

        val healthBefore = sim.run.health
        sim.tick(InputFrame(jump = true))

        assertFalse(sim.player.onGround, "fixture: the player landed before contact")
        assertEquals(healthBefore - DAMAGE, sim.run.health, 1e-9)
        assertTrue(sim.playerHurtSecondsLeft > 0.0)
        assertFalse(round in sim.projectiles)
    }

    @Test
    fun `committed overlap and its landing grace still suppress boss rounds`() {
        val committed = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN
        val sim = TestLevels.simulation(TestLevels.flat(committedColumns = committed))

        hitWithStationaryRound(sim)
        assertEquals(sim.run.maxHealth, sim.run.health, "a boss round landed over a committed column")
        assertEquals(0.0, sim.playerHurtSecondsLeft, 1e-9)

        while (playerOverlaps(sim, committed)) sim.tick(InputFrame(right = true))
        hitWithStationaryRound(sim)
        assertEquals(sim.run.maxHealth, sim.run.health, "a boss round landed during landing grace")
        assertEquals(0.0, sim.playerHurtSecondsLeft, 1e-9)

        repeat(ceil(GameSimulation.LANDING_GRACE / TICK_SECONDS).toInt() + 1) {
            sim.tick(InputFrame())
        }
        hitWithStationaryRound(sim)
        assertEquals(sim.run.maxHealth - DAMAGE, sim.run.health, 1e-9, "a boss round stayed suppressed after grace")
        assertTrue(sim.playerHurtSecondsLeft > 0.0)
    }

    private fun hitWithStationaryRound(sim: GameSimulation) {
        val round = bossRound(position = sim.player.centre(Physics.Default), velocity = Vec2.Zero)
        sim.projectiles += round
        sim.tick(InputFrame())
        assertFalse(round in sim.projectiles, "a protected contact did not spend the round")
    }

    private fun playerOverlaps(sim: GameSimulation, columns: IntRange): Boolean {
        val left = TileMap.toTile(sim.player.x)
        val right = TileMap.toTile(sim.player.x + Physics.Default.width - 0.001)
        return (left..right).any { it in columns }
    }

    private fun bossRound(
        position: Vec2,
        velocity: Vec2,
        module: BossModule = BossModule.Scatter,
    ): LiveProjectile =
        LiveProjectile(
            position = position,
            velocity = velocity,
            damage = DAMAGE,
            pierceLeft = 0,
            secondsLeft = 1.0,
            passesTerrain = false,
            fromPlayer = false,
            bossOwned = true,
            bossModule = module,
        )

    private companion object {
        const val DAMAGE = 7.0
    }
}
