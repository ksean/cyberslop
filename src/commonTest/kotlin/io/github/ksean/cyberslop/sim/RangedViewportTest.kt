package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.CombatBodies
import io.github.ksean.cyberslop.combat.ResolvedWeapon
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-75: player ranged attacks end at the live view and cannot damage off-screen targets. */
class RangedViewportTest {
    @Test
    fun `a fast ranged projectile stops at the right edge before an off-screen target`() {
        val sim = TestLevels.simulation()
        sim.autoFire.remaining = 100.0
        sim.enemies.clear()
        val visible = TestLevels.enemyAt(
            sim,
            EnemyArchetype.Turret,
            column = 8,
            health = 1_000.0,
        ).also { it.stun(1.0) }
        val offscreen = TestLevels.enemyAt(
            sim,
            EnemyArchetype.Turret,
            column = 11,
            health = 1_000.0,
        ).also { it.stun(1.0) }
        val weapon = resolved(WeaponId.SableCorpRailgun)
        val shot = LiveProjectile(
            position = Vec2(100.0, offscreen.centre.y),
            velocity = Vec2(6_000.0, 0.0),
            damage = weapon.damagePerProjectile,
            pierceLeft = GameSimulation.MAX_PIERCE,
            secondsLeft = 1.0,
            passesTerrain = false,
            fromPlayer = true,
            weapon = weapon,
        )
        sim.projectiles += shot

        sim.tick(
            InputFrame(),
            GameplayViewport(left = 80.0, top = 200.0, right = 150.0, bottom = 280.0),
        )

        assertTrue(visible.health < 1_000.0, "the target before the edge was not hit")
        assertEquals(1_000.0, offscreen.health)
        assertTrue(shot !in sim.projectiles, "the projectile remained live beyond the view")
        val impact = sim.impacts.single().shape as HitShape.Impact
        assertEquals(150.0, impact.at.x, 1e-9)
    }

    @Test
    fun `a Kessler strike excludes edge-tangent targets but hits a partially visible body`() {
        fun healthAfterTick(right: Double): Pair<Double, Double> {
            val sim = simulation(WeaponId.KesslerOrbitalUplink, PowerupId.BurnRig)
            val target = TestLevels.enemyAt(
                sim,
                EnemyArchetype.Turret,
                column = 10,
                health = 1_000.0,
            ).also { it.stun(1.0) }

            sim.tick(InputFrame(), GameplayViewport(0.0, 180.0, right, 280.0))

            return target.health to target.burn.secondsLeft
        }

        val tangent = healthAfterTick(right = 143.0)
        assertEquals(1_000.0, tangent.first)
        assertEquals(0.0, tangent.second)

        val partiallyVisible = healthAfterTick(right = 144.0)
        assertTrue(partiallyVisible.first < 1_000.0)
        assertTrue(partiallyVisible.second > 0.0)
    }

    @Test
    fun `ranged secondary damage and status exclude a wholly off-screen target`() {
        val sim = simulation(
            WeaponId.KesslerOrbitalUplink,
            PowerupId.ThermitePayload,
            PowerupId.BurnRig,
        )
        val visible = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8, health = 1_000.0)
            .also {
                it.position = it.position.copy(x = 138.0)
                it.stun(1.0)
            }
        val offscreen = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 11, health = 1_000.0)
            .also {
                it.position = it.position.copy(x = 168.0)
                it.stun(1.0)
            }

        sim.tick(InputFrame(), GameplayViewport(0.0, 180.0, 150.0, 280.0))

        assertTrue(visible.health < 1_000.0, "fixture: the visible target was not struck")
        assertEquals(1_000.0, offscreen.health)
        assertEquals(0.0, offscreen.burn.secondsLeft)
    }

    @Test
    fun `every viewport edge consumes modified ranged projectiles without re-entry`() {
        data class Case(
            val name: String,
            val velocity: Vec2,
            val expected: Vec2,
            val gravity: Double = 0.0,
            val bounces: Int = 0,
            val homingTurn: Double = 0.0,
        )

        val viewport = GameplayViewport(80.0, 180.0, 160.0, 250.0)
        val start = Vec2(120.0, 215.0)
        val cases = listOf(
            Case("left homing", Vec2(-6_000.0, 0.0), Vec2(80.0, 215.0), homingTurn = 300.0),
            Case("right bouncing", Vec2(6_000.0, 0.0), Vec2(160.0, 215.0), bounces = 3),
            Case("top lobbed", Vec2(0.0, -6_000.0), Vec2(120.0, 180.0), gravity = 600.0),
            Case("bottom piercing", Vec2(0.0, 6_000.0), Vec2(120.0, 250.0)),
        )

        cases.forEach { case ->
            val sim = TestLevels.simulation().also {
                it.enemies.clear()
                it.autoFire.remaining = 100.0
            }
            val shot = projectile(
                position = start,
                velocity = case.velocity,
                weapon = resolved(WeaponId.SableCorpRailgun),
                bounces = case.bounces,
                gravity = case.gravity,
                homingTurn = case.homingTurn,
            )
            sim.projectiles += shot

            sim.tick(InputFrame(), viewport)

            assertTrue(shot !in sim.projectiles, "${case.name} remained live")
            val impact = sim.impacts.single().shape as HitShape.Impact
            assertEquals(case.expected.x, impact.at.x, 1e-9, case.name)
            assertEquals(case.expected.y, impact.at.y, 1e-9, case.name)
            assertEquals(case.bounces, shot.bouncesLeft, case.name)
        }
    }

    @Test
    fun `a projectile left behind by a moved viewport is spent before overlapping a target`() {
        val sim = TestLevels.simulation().also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
        }
        val weapon = resolved(WeaponId.SableCorpRailgun)
        val shot = projectile(Vec2(100.0, 220.0), Vec2(60.0, 0.0), weapon)
        sim.projectiles += shot
        sim.tick(InputFrame(), GameplayViewport(80.0, 180.0, 160.0, 250.0))
        assertTrue(shot in sim.projectiles, "fixture: the first viewport spent the shot")
        val target = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8, health = 1_000.0)
            .also {
                it.position = Vec2(shot.position.x - LiveEnemy.BODY_HALF, 213.0)
                it.stun(1.0)
            }

        sim.tick(InputFrame(), GameplayViewport(200.0, 180.0, 280.0, 250.0))

        assertEquals(1_000.0, target.health)
        assertTrue(shot !in sim.projectiles)
    }

    @Test
    fun `psychic projectiles and melee swings are not bounded by the ranged viewport`() {
        val psychic = TestLevels.simulation().also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
        }
        val psychicTarget = TestLevels.enemyAt(
            psychic,
            EnemyArchetype.Turret,
            column = 11,
            health = 1_000.0,
        ).also { it.stun(1.0) }
        val psychicWeapon = resolved(WeaponId.NeuralSpike)
        psychic.projectiles += projectile(
            Vec2(100.0, psychicTarget.centre.y),
            Vec2(6_000.0, 0.0),
            psychicWeapon,
            passesTerrain = true,
        )

        psychic.tick(InputFrame(), GameplayViewport(80.0, 200.0, 150.0, 280.0))
        assertTrue(psychicTarget.health < 1_000.0, "the psychic control was clipped")

        val melee = simulation(WeaponId.BrokenBottle)
        val meleeTarget = TestLevels.enemyAt(melee, EnemyArchetype.Turret, column = 5, health = 1_000.0)
            .also { it.stun(1.0) }
        repeat(10) {
            melee.tick(InputFrame(), GameplayViewport(0.0, 180.0, 60.0, 280.0))
        }
        assertTrue(meleeTarget.health < 1_000.0, "the melee control was view-bounded")
    }

    @Test
    fun `enemy and boss projectiles are not consumed by the player ranged viewport`() {
        val sim = TestLevels.simulation().also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
        }
        val enemyShot = LiveProjectile(
            Vec2(30.0, 100.0), Vec2(60.0, 0.0), 1.0, 0, 1.0,
            passesTerrain = false,
            fromPlayer = false,
        )
        val bossShot = LiveProjectile(
            Vec2(90.0, 100.0), Vec2(-60.0, 0.0), 1.0, 0, 1.0,
            passesTerrain = false,
            fromPlayer = false,
            bossOwned = true,
        )
        sim.projectiles += listOf(enemyShot, bossShot)

        sim.tick(InputFrame(), GameplayViewport(40.0, 80.0, 80.0, 120.0))

        assertTrue(enemyShot in sim.projectiles)
        assertTrue(bossShot in sim.projectiles)
    }

    @Test
    fun `a status applied on-screen keeps ticking after its target leaves the view`() {
        val sim = simulation(WeaponId.KesslerOrbitalUplink, PowerupId.BurnRig)
        val target = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8, health = 1_000.0)
            .also { it.stun(1.0) }
        val viewport = GameplayViewport(0.0, 180.0, 160.0, 280.0)
        sim.tick(InputFrame(), viewport)
        assertTrue(target.burn.secondsLeft > 0.0, "fixture: no burn was applied")
        target.position = target.position.copy(x = 220.0)
        sim.autoFire.remaining = 100.0
        val before = target.health

        sim.tick(InputFrame(), viewport)

        assertTrue(target.health < before, "the existing burn stopped off-screen")
    }

    @Test
    fun `an off-screen boss is protected from a ranged strike`() {
        val level = TestLevels.flat(
            committedColumns = 1..5,
            bossArena = Arena(2, 12, TestLevels.FLOOR_ROW + 1),
        )
        val run = RunState.begin(TestLevels.SEED).copy(
            loadout = Loadout(Weapons.of(WeaponId.KesslerOrbitalUplink), PowerupSlots.empty()),
        )
        val sim = GameSimulation(level, run, TestLevels.SEED).also {
            it.enemies.clear()
            it.autoFire.remaining = 0.0
            it.boss.fight.engage()
        }
        val before = sim.boss.fight.health
        val tangentEdge = sim.boss.centre.x - CombatBodies.BOSS_RADIUS

        sim.tick(InputFrame(), GameplayViewport(0.0, 180.0, tangentEdge, 280.0))

        assertEquals(before, sim.boss.fight.health)
    }

    @Test
    fun `terrain before the view may bounce but an exact edge tie spends the shot`() {
        val weapon = resolved(WeaponId.SableCorpRailgun)
        val y = TileMap.toWorld(TestLevels.FLOOR_ROW) + LiveEnemy.BODY_HALF

        val beforeEdge = TestLevels.simulation(TestLevels.flat(wallColumn = 8)).also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
        }
        val bouncing = projectile(Vec2(110.0, y), Vec2(1_200.0, 0.0), weapon, bounces = 1)
        beforeEdge.projectiles += bouncing
        beforeEdge.tick(InputFrame(), GameplayViewport(0.0, 180.0, 200.0, 280.0))
        assertTrue(bouncing in beforeEdge.projectiles)
        assertTrue(bouncing.velocity.x < 0.0)

        val tied = TestLevels.simulation(TestLevels.flat(wallColumn = 10)).also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
        }
        val spent = projectile(Vec2(140.0, y), Vec2(2_400.0, 0.0), weapon, bounces = 1)
        tied.projectiles += spent
        tied.tick(InputFrame(), GameplayViewport(0.0, 180.0, 160.0, 280.0))
        assertTrue(spent !in tied.projectiles)
        assertEquals(1, spent.bouncesLeft)
        assertEquals(160.0, (tied.impacts.single().shape as HitShape.Impact).at.x, 1e-9)
    }

    private fun simulation(weaponId: WeaponId, vararg powerups: PowerupId): GameSimulation {
        val slots = powerups.fold(PowerupSlots.empty()) { held, powerup ->
            held.collect(powerup).first
        }
        val run = RunState.begin(TestLevels.SEED).copy(
            loadout = Loadout(Weapons.of(weaponId), slots),
        )
        return GameSimulation(TestLevels.flat(committedColumns = 1..5), run, TestLevels.SEED).also {
            it.enemies.clear()
            it.autoFire.remaining = 0.0
        }
    }

    private fun resolved(weaponId: WeaponId): ResolvedWeapon = DamagePipeline.resolve(
        Weapons.of(weaponId),
        PowerupSlots.empty(),
    )

    private fun projectile(
        position: Vec2,
        velocity: Vec2,
        weapon: ResolvedWeapon,
        bounces: Int = 0,
        gravity: Double = 0.0,
        homingTurn: Double = 0.0,
        passesTerrain: Boolean = false,
    ) = LiveProjectile(
        position = position,
        velocity = velocity,
        damage = weapon.damagePerProjectile,
        pierceLeft = GameSimulation.MAX_PIERCE,
        secondsLeft = 1.0,
        passesTerrain = passesTerrain,
        fromPlayer = true,
        homingTurn = homingTurn,
        homingRadius = 1_000.0,
        weapon = weapon,
        bouncesLeft = bounces,
        gravity = gravity,
    )
}
