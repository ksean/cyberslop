package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.FirePattern
import io.github.ksean.cyberslop.combat.DamagePipeline
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
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MeleeSwingTest {
    @Test
    fun `active swing locks aim while its origin follows player movement and progress is monotone`() {
        val sim = simulation()
        sim.tick(InputFrame())
        val opening = sim.activeSwing!!

        val locked = opening.direction
        val openingOrigin = opening.origin
        assertEquals(0.0, opening.progress)

        repeat(3) {
            sim.tick(InputFrame(right = true))
            assertEquals(locked, sim.activeSwing!!.direction, "aim drifted during the activation")
        }

        val moved = sim.activeSwing!!
        assertNotEquals(openingOrigin, moved.origin)
        assertEquals(sim.player.centre(Physics.Default), moved.origin)
        assertTrue(moved.progress > opening.progress)
    }

    @Test
    fun `resolved build scales arc and reach on the active swing but Spike Driver changes neither`() {
        val weapon = Weapons.of(WeaponId.RustlineMachete)
        val pattern = weapon.pattern as FirePattern.ArcSwing
        val scaled =
            simulation(slots(PowerupId.RangerOptics, PowerupId.MassDriver), weapon.id).also { it.tick(InputFrame()) }
                .activeSwing!!
        val spiked = simulation(slots(PowerupId.SpikeDriver), weapon.id).also { it.tick(InputFrame()) }
            .activeSwing!!

        assertEquals(weapon.rangePx * 1.2, scaled.reachPx, 1e-9)
        assertEquals(pattern.arcDegrees * 1.25, scaled.arcDegrees, 1e-9)
        assertEquals(weapon.rangePx, spiked.reachPx, 1e-9)
        assertEquals(pattern.arcDegrees, spiked.arcDegrees, 1e-9)
        assertEquals(pattern.lingerSeconds, scaled.totalSeconds, 1e-9)
    }

    @Test
    fun `every registered ArcSwing snapshots its zero and three stack geometry below cooldown`() {
        Weapons.all.filter { it.pattern is FirePattern.ArcSwing }.forEach { weapon ->
            for (stacks in listOf(0, 3)) {
                var slots = PowerupSlots.empty()
                repeat(stacks) {
                    slots = slots.collect(PowerupId.RangerOptics).first
                    slots = slots.collect(PowerupId.MassDriver).first
                }
                val resolved = DamagePipeline.resolve(weapon, slots)
                val active = simulation(slots, weapon.id).also { it.tick(InputFrame()) }.activeSwing!!
                val pattern = weapon.pattern as FirePattern.ArcSwing

                assertEquals(weapon.rangePx * resolved.reachScale, active.reachPx, 1e-9, weapon.name)
                assertEquals(
                    (pattern.arcDegrees * resolved.hitboxScale).coerceAtMost(360.0),
                    active.arcDegrees,
                    1e-9,
                    weapon.name,
                )
                assertEquals(pattern.lingerSeconds, active.totalSeconds, 1e-9, weapon.name)
                assertTrue(active.totalSeconds < resolved.cooldown, "${weapon.name} can overlap its own swing")
            }
        }
    }

    @Test
    fun `player movement is resolved before the opening sector tests a body`() {
        val sim = simulation()
        val oldOrigin = sim.player.centre(Physics.Default)
        val reach = Weapons.startingWeapon.rangePx
        enemyAt(sim, oldOrigin + Vec2(25.0, 0.0))
        val target = enemyAt(
            sim,
            oldOrigin + TrigTable.rotate(Vec2.Right, -35.0) *
                (reach + io.github.ksean.cyberslop.combat.CombatBodies.ENEMY_RADIUS + 0.2),
        )
        val before = target.health

        sim.tick(InputFrame(right = true))

        assertTrue(target.health < before, "the opening hit used the player's pre-movement origin")
    }

    @Test
    fun `the Halo keeps its non ArcSwing path`() {
        val sim = simulation(weaponId = WeaponId.MeatgrinderHalo)
        sim.tick(InputFrame())

        assertEquals(null, sim.activeSwing)
        assertTrue(sim.lastSwing != null, "the Halo lost its existing activation presentation")
    }

    @Test
    fun `targets entering the cumulative sector are hit once and targets leaving early are not`() {
        val sim = simulation()
        val origin = sim.player.centre(Physics.Default)
        val entering = enemyAt(sim, origin + TrigTable.rotate(Vec2.Right, 25.0) * 50.0)
        val leaving = enemyAt(sim, origin + TrigTable.rotate(Vec2.Right, 30.0) * 50.0)
        val enteringHealth = entering.health
        val leavingHealth = leaving.health

        sim.tick(InputFrame())
        assertEquals(enteringHealth, entering.health, "the late-entry target was hit by the opening edge")
        leaving.position = origin + Vec2(-100.0, 0.0)

        repeat(8) { sim.tick(InputFrame()) }
        val afterHit = entering.health
        repeat(3) { sim.tick(InputFrame()) }

        assertTrue(afterHit < enteringHealth, "a target entering a visible portion was never hit")
        assertEquals(afterHit, entering.health, "one activation hit the same target more than once")
        assertEquals(leavingHealth, leaving.health, "a target that left before the sweep arrived was hit")
    }

    @Test
    fun `one activation hits every overlapping target regardless of projectile pierce`() {
        fun damaged(spikeDriver: Boolean): Int {
            val sim = simulation(if (spikeDriver) slots(PowerupId.SpikeDriver) else PowerupSlots.empty())
            val origin = sim.player.centre(Physics.Default)
            val targets = listOf(-30.0, 0.0, 30.0).map { angle ->
                enemyAt(sim, origin + TrigTable.rotate(Vec2.Right, angle) * 48.0)
            }
            val before = targets.map { it.health }
            sim.tick(InputFrame())
            repeat(8) { sim.tick(InputFrame()) }
            return targets.indices.count { targets[it].health < before[it] }
        }

        assertEquals(3, damaged(spikeDriver = false))
        assertEquals(3, damaged(spikeDriver = true))
    }

    private fun simulation(
        slots: PowerupSlots = PowerupSlots.empty(),
        weaponId: WeaponId = WeaponId.BrokenBottle,
    ): GameSimulation {
        val run = RunState.begin(SEED).copy(
            loadout = Loadout(Weapons.of(weaponId), slots),
        )
        return GameSimulation(TestLevels.flat(), run, SEED)
    }

    private fun slots(vararg ids: PowerupId): PowerupSlots {
        var slots = PowerupSlots.empty()
        ids.forEach { slots = slots.collect(it).first }
        return slots
    }

    private fun enemyAt(sim: GameSimulation, centre: Vec2): LiveEnemy {
        val enemy = LiveEnemy(
            archetype = EnemyArchetype.Turret,
            position = centre - Vec2(LiveEnemy.BODY_HALF, LiveEnemy.BODY_HALF),
            health = 1_000.0,
            homeX = centre.x - LiveEnemy.BODY_HALF,
            patrolPx = 0.0,
        )
        enemy.stun(10.0)
        sim.enemies += enemy
        return enemy
    }

    private companion object {
        val SEED = 0x5A1A6uL
    }
}
