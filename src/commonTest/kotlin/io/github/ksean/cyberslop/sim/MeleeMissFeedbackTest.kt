package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeleeMissFeedbackTest {
    @Test
    fun `a missed Static Lash reports its completed base and Ranger-scaled reach`() {
        listOf(
            PowerupSlots.empty() to 4.0 * METRE,
            rangerOptics() to 6.0 * METRE,
        ).forEach { (slots, expectedReach) ->
            val sim = simulation(WeaponId.StaticLash, slots)
            val completed = completeMissedSwing(sim, InputFrame(right = true))
            val miss = assertNotNull(sim.lastHit).shape as HitShape.MeleeMiss

            assertEquals(completed.origin, miss.origin)
            assertEquals(completed.direction, miss.direction)
            assertEquals(expectedReach, miss.reachPx, TOLERANCE)
        }
    }

    @Test
    fun `a Static Lash that damages a target reports no miss`() {
        val sim = simulation(WeaponId.StaticLash)
        val origin = sim.player.centre(Physics.Default)
        val target = enemyAt(
            sim,
            origin + TrigTable.rotate(Vec2.Right, -30.0) * (2.0 * METRE),
        )
        val health = target.health

        sim.tick(InputFrame())
        assertTrue(target.health < health, "fixture: Static Lash did not damage the target")
        while (sim.activeSwing != null) sim.tick(InputFrame())

        assertNull(sim.lastHit, "a successful Static Lash reported ${sim.lastHit}")
    }

    @Test
    fun `a missed non ArcSwing melee reports its first-contact range`() {
        val sim = simulation(WeaponId.MeatgrinderHalo)

        sim.tick(InputFrame())

        val miss = assertNotNull(sim.lastHit).shape as HitShape.MeleeMiss
        assertEquals(sim.player.centre(Physics.Default), miss.origin)
        assertEquals(sim.aimDirection, miss.direction)
        assertEquals(Weapons.of(WeaponId.MeatgrinderHalo).rangePx, miss.reachPx, TOLERANCE)
    }

    @Test
    fun `ranged and psychic misses do not report a melee range`() {
        listOf(WeaponId.ScraplineZipPistol, WeaponId.GhostwireTether).forEach { weaponId ->
            val sim = simulation(weaponId)

            sim.tick(InputFrame())

            assertFalse(sim.lastHit?.shape is HitShape.MeleeMiss, "$weaponId reported melee feedback")
        }
    }

    @Test
    fun `melee miss feedback is excluded from the digest`() {
        val sim = simulation(WeaponId.StaticLash)
        val before = sim.digest()
        sim.lastHit = HitIndicator(
            shape = HitShape.MeleeMiss(Vec2(10.0, 20.0), Vec2.Right, 64.0),
            secondsLeft = GameSimulation.FLASH_VISIBLE_SECONDS,
            totalSeconds = GameSimulation.FLASH_VISIBLE_SECONDS,
        )

        assertEquals(before, sim.digest())
    }

    @Test
    fun `a completed swing receives the complete feedback window`() {
        val sim = simulation(WeaponId.StaticLash)
        completeMissedSwing(sim, InputFrame())
        sim.autoFire.remaining = 100.0
        assertEquals(1.0, sim.lastHit!!.strength, TOLERANCE, "the completion tick consumed feedback time")

        repeat(ceil(GameSimulation.FLASH_VISIBLE_SECONDS / TICK_SECONDS).toInt() - 1) {
            sim.tick(InputFrame())
            assertTrue(sim.lastHit?.shape is HitShape.MeleeMiss, "the miss trace expired one tick early")
        }
        sim.tick(InputFrame())

        assertNull(sim.lastHit, "the miss trace outlived its flash window")
    }

    private fun completeMissedSwing(sim: GameSimulation, input: InputFrame): ActiveMeleeSwing {
        sim.tick(input)
        assertNotNull(sim.activeSwing, "fixture: the weapon did not start an ArcSwing")
        var completed = sim.activeSwing!!
        while (sim.activeSwing != null) {
            completed = sim.activeSwing!!
            assertFalse(
                sim.lastHit?.shape is HitShape.MeleeMiss,
                "the attack was called a miss while its sector could still acquire a target",
            )
            sim.tick(input)
        }
        return completed
    }

    private fun simulation(
        weaponId: WeaponId,
        slots: PowerupSlots = PowerupSlots.empty(),
    ): GameSimulation {
        val run = RunState.begin(SEED).copy(loadout = Loadout(Weapons.of(weaponId), slots))
        return GameSimulation(TestLevels.flat(), run, SEED)
    }

    private fun rangerOptics(): PowerupSlots {
        var slots = PowerupSlots.empty()
        repeat(3) { slots = slots.collect(PowerupId.RangerOptics).first }
        return slots
    }

    private fun enemyAt(sim: GameSimulation, centre: Vec2): LiveEnemy = LiveEnemy(
        archetype = EnemyArchetype.Turret,
        position = centre - Vec2(LiveEnemy.BODY_HALF, LiveEnemy.BODY_HALF),
        health = 1_000.0,
        homeX = centre.x - LiveEnemy.BODY_HALF,
        patrolPx = 0.0,
    ).also { enemy ->
        enemy.stun(10.0)
        sim.enemies += enemy
    }

    private companion object {
        val SEED = 0xA115uL
        const val METRE = 16.0
        const val TOLERANCE = 1e-9
    }
}
