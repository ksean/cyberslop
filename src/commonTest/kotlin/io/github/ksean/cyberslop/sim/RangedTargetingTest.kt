package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.CombatBodies
import io.github.ksean.cyberslop.combat.Targeting
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-97: player ranged weapons aim at the closest visible target without a distance cap. */
class RangedTargetingTest {
    @Test
    fun `ranged aim ignores its legacy range and a closer off-screen enemy`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)
        val muzzle = sim.player.centre(Physics.Default)
        val viewport = GameplayViewport(
            left = muzzle.x - 1.0,
            top = muzzle.y - 100.0,
            right = muzzle.x + Targeting.AUTO_RANGE_PX + 100.0,
            bottom = muzzle.y + 100.0,
        )
        enemyAt(
            sim,
            Vec2(viewport.left - CombatBodies.ENEMY_RADIUS, muzzle.y),
        )
        val visible = enemyAt(
            sim,
            Vec2(muzzle.x + Targeting.AUTO_RANGE_PX + 32.0, muzzle.y - 40.0),
        )
        val expected = (visible.centre - muzzle).normalisedOr(Vec2.Right)

        sim.tick(InputFrame(), viewport)

        assertVector(expected, sim.aimDirection)
        assertTrue(sim.aimDirection.x > 0.0, "the closer off-screen enemy was selected")
    }

    @Test
    fun `partial body overlap is visible but edge tangency is not`() {
        fun aimWithTarget(offsetInside: Double): Pair<Vec2, Vec2> {
            val sim = simulation(WeaponId.ScraplineZipPistol)
            val muzzle = sim.player.centre(Physics.Default)
            val right = muzzle.x + 80.0
            val target = enemyAt(
                sim,
                Vec2(right + CombatBodies.ENEMY_RADIUS - offsetInside, muzzle.y - 40.0),
            )
            val targetDirection = (target.centre - muzzle).normalisedOr(Vec2.Right)

            sim.tick(
                InputFrame(),
                GameplayViewport(muzzle.x - 40.0, muzzle.y - 100.0, right, muzzle.y + 100.0),
            )
            return targetDirection to sim.aimDirection
        }

        val partial = aimWithTarget(offsetInside = 1.0)
        assertVector(partial.first, partial.second)

        val tangent = aimWithTarget(offsetInside = 0.0)
        assertVector(Vec2.Right, tangent.second)
    }

    @Test
    fun `nearest eligible target includes vulnerable bosses`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)
        val muzzle = sim.player.centre(Physics.Default)
        enemyAt(sim, muzzle + Vec2(180.0, 0.0))
        val minibossCentre = muzzle + Vec2(70.0, -30.0)
        sim.miniboss.placeAt(
            Vec2(minibossCentre.x, minibossCentre.y + sim.miniboss.height / 2.0),
        )
        sim.miniboss.fight.engage()
        val ineligibleBossCentre = muzzle + Vec2(20.0, 0.0)
        sim.boss.placeAt(
            Vec2(ineligibleBossCentre.x, ineligibleBossCentre.y + sim.boss.height / 2.0),
        )

        sim.tick(InputFrame(), wideViewport(muzzle))

        assertVector((minibossCentre - muzzle).normalisedOr(Vec2.Right), sim.aimDirection)
    }

    @Test
    fun `exact distance ties use stable combat identity`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)
        val muzzle = sim.player.centre(Physics.Default)
        val first = enemyAt(sim, muzzle + Vec2(80.0, -40.0))
        enemyAt(sim, muzzle + Vec2(-80.0, -40.0))
        val firstCentre = first.centre

        sim.tick(InputFrame(), wideViewport(muzzle))

        assertVector((firstCentre - muzzle).normalisedOr(Vec2.Right), sim.aimDirection)
    }

    @Test
    fun `cursor anchored strike uses the visible uncapped target`() {
        val strike = simulation(WeaponId.KesslerOrbitalUplink)
        val strikeMuzzle = strike.player.centre(Physics.Default)
        val strikeTarget = enemyAt(
            strike,
            strikeMuzzle + Vec2(Targeting.AUTO_RANGE_PX + 32.0, -24.0),
        )
        val strikeTargetCentre = strikeTarget.centre

        strike.tick(InputFrame(), wideViewport(strikeMuzzle))

        val beam = strike.lastHit?.shape as HitShape.Beam
        assertVector(strikeTargetCentre, beam.foot)
    }

    @Test
    fun `lobbed projectile keeps a visible aim beyond its live lifetime`() {
        val lob = simulation(WeaponId.AshfallGrenadeLobber)
        val lobMuzzle = lob.player.centre(Physics.Default)
        val lobTarget = enemyAt(
            lob,
            lobMuzzle + Vec2(1_000.0, -48.0),
        )
        val lobTargetCentre = lobTarget.centre

        lob.tick(
            InputFrame(),
            GameplayViewport(
                lobMuzzle.x - 100.0,
                lobMuzzle.y - 1_000.0,
                lobMuzzle.x + 1_100.0,
                lobMuzzle.y + 1_000.0,
            ),
        )

        val grenade = lob.projectiles.single { it.fromPlayer }
        assertVector((lobTargetCentre - lobMuzzle).normalisedOr(Vec2.Right), lob.aimDirection)
        assertTrue(grenade.velocity.x > 0.0, "the lob did not travel toward the target")
        assertTrue(grenade.velocity.y < 0.0, "the lob did not launch upward")
    }

    @Test
    fun `a timed burst keeps its trigger aim after the visible target moves`() {
        val sim = simulation(WeaponId.GanglordSmg)
        val muzzle = sim.player.centre(Physics.Default)
        val target = enemyAt(sim, muzzle + Vec2(120.0, -40.0))
        val initialViewport = wideViewport(muzzle)

        sim.tick(InputFrame(), initialViewport)
        val triggerDirection = requireNotNull(sim.lastShot).direction
        target.position = muzzle + Vec2(-120.0, -40.0) -
            Vec2(LiveEnemy.BODY_HALF, LiveEnemy.BODY_HALF)
        val laterViewport = GameplayViewport(
            left = muzzle.x - 180.0,
            top = muzzle.y - 120.0,
            right = muzzle.x + 40.0,
            bottom = muzzle.y + 120.0,
        )

        repeat(2) { sim.tick(InputFrame(), laterViewport) }
        val latestTargetCentre = target.centre
        sim.tick(InputFrame(), laterViewport)

        assertVector((latestTargetCentre - muzzle).normalisedOr(Vec2.Right), sim.aimDirection)
        assertVector(triggerDirection, requireNotNull(sim.lastShot).direction)
    }

    @Test
    fun `melee and psychic targeting retain the legacy range cap`() {
        listOf(WeaponId.BrokenBottle, WeaponId.NeuralSpike).forEach { weaponId ->
            val sim = simulation(weaponId)
            val muzzle = sim.player.centre(Physics.Default)
            enemyAt(
                sim,
                muzzle + Vec2(Targeting.AUTO_RANGE_PX + 32.0, -48.0),
            )

            sim.tick(InputFrame(), wideViewport(muzzle))

            assertEquals(Vec2.Right, sim.aimDirection, weaponId.name)
        }
    }

    private fun simulation(weaponId: WeaponId): GameSimulation {
        val run = RunState.begin(TestLevels.SEED).copy(
            loadout = Loadout(Weapons.of(weaponId), PowerupSlots.empty()),
        )
        return GameSimulation(TestLevels.flat(committedColumns = 1..5), run, TestLevels.SEED).also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
            it.tick(InputFrame())
            it.autoFire.remaining = 0.0
        }
    }

    private fun enemyAt(sim: GameSimulation, centre: Vec2): LiveEnemy = LiveEnemy(
        archetype = EnemyArchetype.Turret,
        position = centre - Vec2(LiveEnemy.BODY_HALF, LiveEnemy.BODY_HALF),
        health = 1_000.0,
        homeX = centre.x - LiveEnemy.BODY_HALF,
        patrolPx = 0.0,
    ).also {
        it.stun(1.0)
        sim.enemies += it
    }

    private fun wideViewport(muzzle: Vec2) = GameplayViewport(
        left = muzzle.x - 600.0,
        top = muzzle.y - 600.0,
        right = muzzle.x + 600.0,
        bottom = muzzle.y + 600.0,
    )

    private fun assertVector(expected: Vec2, actual: Vec2) {
        assertTrue(abs(expected.x - actual.x) < TOLERANCE, "expected x=${expected.x}, was ${actual.x}")
        assertTrue(abs(expected.y - actual.y) < TOLERANCE, "expected y=${expected.y}, was ${actual.y}")
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
