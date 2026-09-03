package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.ResolvedWeapon
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P-72: a fast player projectile tests the path between fixed-step endpoints. */
class ProjectileSweepTest {
    @Test
    fun `railgun hits an enemy crossed between tick endpoints at base and upgraded speed`() {
        listOf(0, 3).forEach { opticsStacks ->
            val sim = TestLevels.simulation()
            sim.autoFire.remaining = 100.0
            val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8)
            enemy.stun(1.0)
            val weapon = railgun(opticsStacks)
            val speed = weapon.spec.projectileSpeed * weapon.reachScale
            val tickTravel = speed * TICK_SECONDS
            val centre = enemy.centre
            val start = centre - Vec2(tickTravel / 2.0, 0.0)
            assertTrue((start - centre).length > GameSimulation.PROJECTILE_RADIUS, "fixture: start overlaps")
            assertTrue(
                (start + Vec2(tickTravel, 0.0) - centre).length > GameSimulation.PROJECTILE_RADIUS,
                "fixture: end overlaps",
            )

            val before = enemy.health
            sim.projectiles += LiveProjectile(
                position = start,
                velocity = Vec2(speed, 0.0),
                damage = weapon.damagePerProjectile,
                pierceLeft = GameSimulation.MAX_PIERCE,
                secondsLeft = 1.0,
                passesTerrain = false,
                fromPlayer = true,
                radius = GameSimulation.PROJECTILE_RADIUS,
                weapon = weapon,
            )

            sim.tick(InputFrame())

            assertEquals(
                weapon.damagePerProjectile,
                before - enemy.health,
                "Ranger Optics stacks=$opticsStacks, speed=$speed",
            )
        }
    }

    @Test
    fun `swept disc includes tangency and excludes clearance`() {
        fun damageAt(offset: Double): Double {
            val sim = TestLevels.simulation()
            sim.autoFire.remaining = 100.0
            val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8, health = 1_000.0)
            enemy.stun(1.0)
            val weapon = railgun(0)
            val centre = enemy.centre
            val start = centre + Vec2(-20.0, offset)
            val before = enemy.health
            sim.projectiles += projectile(start, Vec2(2_400.0, 0.0), weapon, pierceLeft = 0)

            sim.tick(InputFrame())

            return before - enemy.health
        }

        assertEquals(railgun(0).damagePerProjectile, damageAt(GameSimulation.PROJECTILE_RADIUS))
        assertEquals(0.0, damageAt(GameSimulation.PROJECTILE_RADIUS + 1e-6))
    }

    @Test
    fun `piercing contacts resolve in travel order rather than enemy insertion order`() {
        fun damaged(insertionOrder: List<Int>): Set<Int> {
            val sim = TestLevels.simulation()
            sim.autoFire.remaining = 100.0
            val enemies = insertionOrder.associateWith { column ->
                TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = column, health = 1_000.0)
                    .also { it.stun(1.0) }
            }
            val weapon = railgun(0)
            val start = enemies.getValue(8).centre - Vec2(15.0, 0.0)
            sim.projectiles += projectile(start, Vec2(3_600.0, 0.0), weapon, pierceLeft = 1)

            sim.tick(InputFrame())

            return enemies.filterValues { it.health < 1_000.0 }.keys
        }

        assertEquals(setOf(8, 9), damaged(listOf(8, 9, 10)))
        assertEquals(setOf(8, 9), damaged(listOf(10, 9, 8)))
    }

    @Test
    fun `one projectile damages an overlapped enemy only once across pieces and ticks`() {
        val sim = TestLevels.simulation()
        sim.autoFire.remaining = 100.0
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8, health = 1_000.0)
        enemy.stun(1.0)
        val weapon = railgun(0)
        val shot = projectile(enemy.centre - Vec2(1.0, 0.0), Vec2(60.0, 0.0), weapon)
        sim.projectiles += shot

        repeat(2) { sim.tick(InputFrame()) }

        assertEquals(weapon.damagePerProjectile, 1_000.0 - enemy.health)
        assertEquals(1, shot.hitTargets.size)
    }

    @Test
    fun `a swept railgun path damages a boss crossed between endpoints`() {
        val sim = TestLevels.simulation()
        sim.autoFire.remaining = 100.0
        sim.boss.fight.engage()
        val weapon = railgun(0)
        val before = sim.boss.fight.health
        val start = sim.boss.centre - Vec2(80.0, 0.0)
        sim.projectiles += projectile(start, Vec2(9_600.0, 0.0), weapon, pierceLeft = 0)

        sim.tick(InputFrame())

        assertEquals(weapon.damagePerProjectile, before - sim.boss.fight.health)
    }

    @Test
    fun `terrain blocks later contacts but preserves earlier ones`() {
        val sim = TestLevels.simulation(TestLevels.flat(wallColumn = 9))
        sim.autoFire.remaining = 100.0
        val beforeWall = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8, health = 1_000.0)
            .also { it.stun(1.0) }
        val behindWall = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 10, health = 1_000.0)
            .also { it.stun(1.0) }
        val weapon = railgun(0)
        val start = beforeWall.centre - Vec2(15.0, 0.0)
        sim.projectiles += projectile(start, Vec2(3_600.0, 0.0), weapon)

        sim.tick(InputFrame())

        assertEquals(weapon.damagePerProjectile, 1_000.0 - beforeWall.health)
        assertEquals(1_000.0, behindWall.health)
    }

    @Test
    fun `a reflected projectile does not damage the same enemy again`() {
        val sim = TestLevels.simulation(TestLevels.flat(wallColumn = 9))
        sim.autoFire.remaining = 100.0
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8, health = 1_000.0)
            .also { it.stun(1.0) }
        val weapon = railgun(0)
        val shot = projectile(
            enemy.centre - Vec2(15.0, 0.0),
            Vec2(3_600.0, 0.0),
            weapon,
            bouncesLeft = 1,
        )
        sim.projectiles += shot

        sim.tick(InputFrame())

        assertEquals(weapon.damagePerProjectile, 1_000.0 - enemy.health)
        assertEquals(1, shot.hitTargets.size)
        assertTrue(shot.velocity.x < 0.0, "fixture: the shot did not reflect")
    }

    @Test
    fun `projectile hit identities participate in the digest`() {
        val sim = TestLevels.simulation()
        val shot = projectile(Vec2.Zero, Vec2.Right, railgun(0))
        sim.projectiles += shot
        val before = sim.digest()

        shot.hitTargets = setOf(CombatTargetId(CombatTargetKind.Enemy, 3))

        assertNotEquals(before, sim.digest())
    }

    private fun railgun(opticsStacks: Int) = DamagePipeline.resolve(
        Weapons.of(WeaponId.SableCorpRailgun),
        (0 until opticsStacks).fold(PowerupSlots.empty()) { slots, _ ->
            slots.collect(PowerupId.RangerOptics).first
        },
    )

    private fun projectile(
        position: Vec2,
        velocity: Vec2,
        weapon: ResolvedWeapon,
        pierceLeft: Int = GameSimulation.MAX_PIERCE,
        bouncesLeft: Int = 0,
    ) = LiveProjectile(
        position = position,
        velocity = velocity,
        damage = weapon.damagePerProjectile,
        pierceLeft = pierceLeft,
        secondsLeft = 1.0,
        passesTerrain = false,
        fromPlayer = true,
        radius = GameSimulation.PROJECTILE_RADIUS,
        weapon = weapon,
        bouncesLeft = bouncesLeft,
    )

}
