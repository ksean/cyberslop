package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.combat.FirePattern
import io.github.ksean.cyberslop.combat.ProjectileBallistics
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
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-71: Ashfall's launch and gravity are simulation-owned fixed-step state. */
class LobbedProjectileTest {
    @Test
    fun `Ashfall leads with the target's most recently completed movement tick`() {
        val sim = simulation()
        sim.autoFire.remaining = 1.0
        val target = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = 14)
        sim.tick(InputFrame())
        assertTrue(target.aimingVelocity.lengthSquared > 0.0, "fixture target did not move")

        sim.autoFire.remaining = 0.0
        val origin = Vec2(
            sim.player.x + Physics.Default.width / 2.0,
            sim.player.y + sim.player.height(Physics.Default) / 2.0,
        )
        val targetAtTrigger = enemyCentre(target)
        val pattern = sim.autoFire.weapon.spec.pattern as FirePattern.Projectile
        val expected = ProjectileBallistics.solve(
            origin = origin,
            target = targetAtTrigger,
            targetVelocity = target.aimingVelocity,
            nominalSpeed = sim.autoFire.weapon.spec.projectileSpeed,
            gravity = pattern.gravity,
            lifetimeSeconds = pattern.lifetimeSeconds,
            tickSeconds = TICK_SECONDS,
        )

        sim.tick(InputFrame())

        val grenade = sim.projectiles.single { it.fromPlayer }
        val initialVelocity = Vec2(grenade.velocity.x, grenade.velocity.y - grenade.gravity * TICK_SECONDS)
        assertVector(expected.velocity, initialVelocity)
    }

    @Test
    fun `Ashfall applies the same completed-tick lead to a moving boss`() {
        val sim = simulation()
        sim.autoFire.remaining = 1.0
        sim.boss.placeAt(Vec2(14 * 16.0, (TestLevels.FLOOR_ROW + 1) * 16.0))
        sim.boss.fight.engage()
        sim.tick(InputFrame())
        assertTrue(sim.boss.aimingVelocity.lengthSquared > 0.0, "fixture boss did not move")

        sim.autoFire.remaining = 0.0
        val origin = Vec2(
            sim.player.x + Physics.Default.width / 2.0,
            sim.player.y + sim.player.height(Physics.Default) / 2.0,
        )
        val pattern = sim.autoFire.weapon.spec.pattern as FirePattern.Projectile
        val expected = ProjectileBallistics.solve(
            origin = origin,
            target = sim.boss.centre,
            targetVelocity = sim.boss.aimingVelocity,
            nominalSpeed = sim.autoFire.weapon.spec.projectileSpeed,
            gravity = pattern.gravity,
            lifetimeSeconds = pattern.lifetimeSeconds,
            tickSeconds = TICK_SECONDS,
        )

        sim.tick(InputFrame())

        val grenade = sim.projectiles.single { it.fromPlayer }
        val initialVelocity = Vec2(grenade.velocity.x, grenade.velocity.y - grenade.gravity * TICK_SECONDS)
        assertVector(expected.velocity, initialVelocity)
    }

    @Test
    fun `Ashfall launches upward and gains its declared downward velocity every tick`() {
        val sim = simulation()
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 14).stun(2.0)

        sim.tick(InputFrame())
        val grenade = sim.projectiles.single { it.fromPlayer }
        val firstVelocityY = grenade.velocity.y

        assertEquals(600.0, grenade.gravity)
        assertTrue(firstVelocityY < 0.0, "grenade launched downward at $firstVelocityY px/s")

        sim.tick(InputFrame())

        assertClose(firstVelocityY + grenade.gravity * TICK_SECONDS, grenade.velocity.y)
    }

    @Test
    fun `moving the target after launch does not bend an unmodified grenade`() {
        val sim = simulation()
        val target = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 14).also { it.stun(2.0) }
        sim.tick(InputFrame())
        val grenade = sim.projectiles.single { it.fromPlayer }
        val before = grenade.velocity
        target.position += Vec2(0.0, -120.0)

        sim.tick(InputFrame())

        assertClose(before.x, grenade.velocity.x)
        assertClose(before.y + grenade.gravity * TICK_SECONDS, grenade.velocity.y)
    }

    @Test
    fun `ordinary projectile velocity remains constant at zero gravity`() {
        val sim = simulation(weapon = WeaponId.ScraplineZipPistol)
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 14).stun(2.0)
        sim.tick(InputFrame())
        val shot = sim.projectiles.single { it.fromPlayer }
        val before = shot.velocity

        sim.tick(InputFrame())

        assertEquals(0.0, shot.gravity)
        assertEquals(before, shot.velocity)
    }

    @Test
    fun `Fork Bomb grenades share the trigger-time ballistic velocity`() {
        val sim = simulation(build = listOf(PowerupId.ForkBomb))
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 14).stun(2.0)

        sim.tick(InputFrame())

        val grenades = sim.projectiles.filter { it.fromPlayer }
        assertEquals(2, grenades.size)
        assertEquals(1, grenades.map { it.velocity }.toSet().size)
        assertTrue(grenades.all { it.gravity == 600.0 })
    }

    @Test
    fun `Seeker turns before the same tick gravity increment`() {
        val sim = simulation(build = List(3) { PowerupId.SeekerDaemon })
        val target = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8).also { it.stun(2.0) }
        sim.tick(InputFrame())
        val grenade = sim.projectiles.single { it.fromPlayer }
        val before = grenade.velocity
        val desired = (enemyCentre(target) - grenade.position).normalisedOr(before)
        val turned = TrigTable.turnToward(
            before,
            desired,
            grenade.homingTurn * TICK_SECONDS,
        ) * before.length

        sim.tick(InputFrame())

        assertClose(turned.x, grenade.velocity.x)
        assertClose(turned.y + grenade.gravity * TICK_SECONDS, grenade.velocity.y)
    }

    @Test
    fun `gravity continues on the tick after a terrain reflection`() {
        val sim = TestLevels.simulation()
        sim.autoFire.remaining = 100.0
        val floorTop = io.github.ksean.cyberslop.world.TileMap.toWorld(TestLevels.FLOOR_ROW + 1)
        val shot = LiveProjectile(
            position = Vec2(120.0, floorTop - 0.5),
            velocity = Vec2(0.0, 60.0),
            damage = 1.0,
            pierceLeft = 0,
            secondsLeft = 1.0,
            passesTerrain = false,
            fromPlayer = true,
            bouncesLeft = 1,
            gravity = 600.0,
        )
        sim.projectiles += shot

        sim.tick(InputFrame())
        val reflected = shot.velocity.y
        assertTrue(reflected < 0.0, "fixture: projectile did not reflect")
        sim.tick(InputFrame())

        assertClose(reflected + shot.gravity * TICK_SECONDS, shot.velocity.y)
    }

    @Test
    fun `a low obstruction below the arc is cleared but a higher one blocks it`() {
        fun targetWasHit(high: Boolean): Boolean {
            val level = TestLevels.flat()
            level.tiles[9, TestLevels.FLOOR_ROW] = TileKind.Solid
            if (high) level.tiles[9, TestLevels.FLOOR_ROW - 1] = TileKind.Solid
            val sim = simulation(level = level)
            val target = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 14).also { it.stun(2.0) }
            val before = target.health
            repeat(110) {
                sim.tick(InputFrame())
                if (target.health < before) return true
            }
            return false
        }

        assertTrue(targetWasHit(high = false), "grenade did not clear the low obstruction")
        assertTrue(!targetWasHit(high = true), "grenade passed through the high obstruction")
    }

    @Test
    fun `flash follows the solved initial launch tangent`() {
        val sim = simulation()
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 14).stun(2.0)
        sim.tick(InputFrame())
        val grenade = sim.projectiles.single { it.fromPlayer }
        val initialVelocity = Vec2(
            grenade.velocity.x,
            grenade.velocity.y - grenade.gravity * TICK_SECONDS,
        )

        assertVector(initialVelocity.normalisedOr(Vec2.Right), sim.lastShot!!.direction)
        assertTrue(sim.lastShot!!.direction.y < 0.0)
    }

    @Test
    fun `gravity and a pending lob aim point participate in the digest`() {
        val sim = simulation()
        sim.autoFire.remaining = 100.0
        val shot = LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, false, true)
        sim.projectiles += shot
        val withoutGravity = sim.digest()
        sim.projectiles.clear()
        sim.projectiles += LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, false, true, gravity = 600.0)
        assertTrue(withoutGravity != sim.digest(), "gravity is absent from the digest")

        sim.projectiles.clear()
        sim.pendingBurst = PendingBurst(1, 0.05, Vec2.Right, sim.autoFire.weapon, Vec2(100.0, 80.0))
        val firstAim = sim.digest()
        sim.pendingBurst = sim.pendingBurst!!.copy(aimPoint = Vec2(101.0, 80.0))
        assertTrue(firstAim != sim.digest(), "pending lob aim is absent from the digest")
    }

    private fun simulation(
        weapon: WeaponId = WeaponId.AshfallGrenadeLobber,
        build: List<PowerupId> = emptyList(),
        level: Level = TestLevels.flat(),
    ): GameSimulation {
        val slots = build.fold(PowerupSlots.empty()) { held, powerup -> held.collect(powerup).first }
        val run = RunState.begin(TestLevels.SEED).let {
            it.copy(loadout = Loadout(Weapons.of(weapon), slots))
        }
        return GameSimulation(level, run, TestLevels.SEED)
    }

    private fun enemyCentre(enemy: LiveEnemy) =
        enemy.position + Vec2(GameSimulation.ENEMY_HALF, GameSimulation.ENEMY_HALF)

    private fun assertVector(expected: Vec2, actual: Vec2) {
        assertClose(expected.x, actual.x)
        assertClose(expected.y, actual.y)
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")
}
