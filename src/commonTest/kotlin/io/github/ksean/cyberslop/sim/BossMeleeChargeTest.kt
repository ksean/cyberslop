package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.BossAttack
import io.github.ksean.cyberslop.entity.BossAttackKind
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossPhase
import io.github.ksean.cyberslop.entity.BossProfile
import io.github.ksean.cyberslop.entity.BossSpec
import io.github.ksean.cyberslop.entity.Bosses
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PROD-104 / P-79: deterministic map-scaled boss melee charges with swept attack hits. */
class BossMeleeChargeTest {
    @Test
    fun `charge chance rises linearly from fifty to ninety percent`() {
        val chances = (1..10).map(LiveBoss::chargeChance)

        assertEquals(0.50, chances.first(), absoluteTolerance = EPSILON)
        assertEquals(0.90, chances.last(), absoluteTolerance = EPSILON)
        chances.zipWithNext().forEachIndexed { index, (before, after) ->
            assertEquals(0.40 / 9.0, after - before, absoluteTolerance = EPSILON, message = "map ${index + 2}")
        }
    }

    @Test
    fun `fixed seed cohort follows the map charge curve`() {
        val shares = (1..10).map { map ->
            val charged = (0 until COHORT_SIZE).count { seed ->
                LiveBoss.rollsCharge(map, Rng.derive(seed.toULong(), map, "boss-melee-charges"))
            }
            charged / COHORT_SIZE.toDouble()
        }

        shares.forEachIndexed { index, share ->
            val expected = LiveBoss.chargeChance(index + 1)
            assertTrue(abs(share - expected) <= 0.01, "map ${index + 1}: expected $expected, observed $share")
        }
        assertTrue(shares.zipWithNext().all { (before, after) -> before < after }, "$shares")
    }

    @Test
    fun `both boss ranks receive independent derived charge streams`() {
        val seed = 0xC4A6EuL
        val map = 6
        val sim = TestLevels.simulation(TestLevels.flat(mapIndex = map), seed)

        assertEquals(Rng.derive(seed, map, "miniboss-melee-charges").state, sim.miniboss.chargeRng.state)
        assertEquals(Rng.derive(seed, map, "boss-melee-charges").state, sim.boss.chargeRng.state)
        assertNotEquals(sim.miniboss.chargeRng.state, sim.boss.chargeRng.state)
    }

    @Test
    fun `every melee module rolls while a ranged attack leaves the charge stream untouched`() {
        listOf(
            BossModule.Slam to 1,
            BossModule.Sweep to 1,
            BossModule.Flurry to 4,
            BossModule.Rush to 10,
        ).forEach { (module, map) ->
            val fixture = fixture(module, map, charged = true)
            val before = fixture.boss.chargeRng.state

            beginAttack(fixture)

            assertNotEquals(before, fixture.boss.chargeRng.state, "$module did not roll")
            assertTrue(fixture.boss.meleeChargeSelected, "$module ignored a successful roll")
        }

        val ranged = fixture(BossModule.Bolt, map = 1, charged = true)
        val before = ranged.boss.chargeRng.state
        beginAttack(ranged)
        assertEquals(before, ranged.boss.chargeRng.state)
        assertFalse(ranged.boss.meleeChargeSelected)
    }

    @Test
    fun `a charged melee moves for its active window in the locked direction`() {
        val fixture = fixture(BossModule.Slam, map = 1, charged = true)
        val start = beginAttack(fixture)
        val crossedTarget = targetAt(fixture.boss, x = start.x + 200.0)

        finishAttack(fixture.boss, crossedTarget)
        val travelled = start.x - fixture.boss.position.x

        assertEquals(LiveBoss.LUNGE_SPEED * fixture.attack.activeSeconds, travelled, absoluteTolerance = 0.001)
    }

    @Test
    fun `an uncharged melee makes no attack-driven movement`() {
        val fixture = fixture(BossModule.Slam, map = 1, charged = false)
        val start = beginAttack(fixture)

        finishAttack(fixture.boss, targetAt(fixture.boss, x = start.x - 130.0))

        assertEquals(start.x, fixture.boss.position.x, absoluteTolerance = EPSILON)
    }

    @Test
    fun `a charged attack hits once anywhere along its swept reach while its dodge still works`() {
        fun resolve(charged: Boolean, dodging: Boolean): Pair<BossAttack, Double> {
            val fixture = fixture(BossModule.Slam, map = 1, charged = charged)
            val start = beginAttack(fixture)
            val standing = targetAt(fixture.boss, x = start.x - 130.0)
            val target = if (dodging) standing.copy(
                centre = standing.centre - Vec2(0.0, 40.0),
                onGround = false,
            ) else {
                standing
            }
            return fixture.attack to finishAttack(fixture.boss, target)
        }

        val (attack, chargedDamage) = resolve(charged = true, dodging = false)
        assertEquals(attack.damage, chargedDamage, absoluteTolerance = EPSILON)
        assertEquals(0.0, resolve(charged = false, dodging = false).second, absoluteTolerance = EPSILON)
        assertEquals(0.0, resolve(charged = true, dodging = true).second, absoluteTolerance = EPSILON)
    }

    @Test
    fun `a charge stops at a wall and cannot hit beyond its clipped path`() {
        val level = TestLevels.flat(
            wallColumn = 29,
            bossArena = Arena(20, 50, TestLevels.FLOOR_ROW + 1),
        )
        val fixture = fixture(BossModule.Slam, map = 1, charged = true, level = level)
        val start = beginAttack(fixture)
        val damage = finishAttack(fixture.boss, targetAt(fixture.boss, x = start.x - 150.0))
        val wallRight = TileMap.toWorld(30)

        assertTrue(
            fixture.boss.position.x - fixture.boss.halfWidth >= wallRight,
            "boss centre ${fixture.boss.position.x}, left edge ${fixture.boss.position.x - fixture.boss.halfWidth}",
        )
        assertTrue(start.x - fixture.boss.position.x < LiveBoss.LUNGE_SPEED * fixture.attack.activeSeconds)
        assertEquals(0.0, damage, absoluteTolerance = EPSILON)
    }

    @Test
    fun `committed span protection suppresses a swept charged hit`() {
        val sim = (0uL..1_000uL).firstNotNullOfOrNull { seed ->
            val level = TestLevels.flat(
                committedColumns = 1..5,
                bossArena = Arena(2, 12, TestLevels.FLOOR_ROW + 1),
            )
            val candidate = TestLevels.simulation(level, seed)
            candidate.boss.fight.engage()
            repeat(180) {
                if (candidate.boss.currentAttack == null) candidate.tick(InputFrame())
            }
            candidate.takeIf {
                it.boss.currentAttack?.kind == BossAttackKind.Melee && it.boss.meleeChargeSelected
            }
        } ?: error("fixture: no seed selected a charged opening melee")

        val start = sim.boss.position
        while (sim.boss.currentAttack != null) sim.tick(InputFrame())

        assertNotEquals(start, sim.boss.position, "fixture: selected charge did not move")
        assertEquals(sim.run.maxHealth, sim.run.health, absoluteTolerance = EPSILON)
    }

    private fun fixture(
        module: BossModule,
        map: Int,
        charged: Boolean,
        level: Level = TestLevels.flat(
            bossArena = Arena(20, 50, TestLevels.FLOOR_ROW + 1),
            mapIndex = map,
        ),
    ): Fixture {
        val attack = Bosses.attack(module, map, mainBoss = true)
        val profile = if (module.kind == BossAttackKind.Melee) {
            BossProfile(module, Bosses.rangedModulesFor(map).first())
        } else {
            BossProfile(Bosses.meleeModulesFor(map).first(), module)
        }
        val spec = BossSpec(
            name = "fixture",
            maxHealth = 1_000.0,
            contactDamage = 0.0,
            phases = listOf(BossPhase(1.0, listOf(attack))),
            profile = profile,
            mapIndex = map,
        )
        val boss = LiveBoss(
            spec = spec,
            arena = level.boss,
            tiles = level.tiles,
            rng = Rng(0uL),
            level = level,
            chargeRng = Rng(seedFor(map, charged)),
        )
        boss.placeAt(Vec2(TileMap.toWorld(35), TileMap.toWorld(TestLevels.FLOOR_ROW + 1)))
        boss.fight.engage()
        boss.restSecondsLeft = 0.0
        return Fixture(boss, attack)
    }

    private fun beginAttack(fixture: Fixture): Vec2 {
        val target = targetAt(fixture.boss, x = fixture.boss.position.x - 200.0)
        while (fixture.boss.currentAttack == null) fixture.boss.tick(TICK_SECONDS, target)
        assertEquals(fixture.attack.module, fixture.boss.currentAttack?.module)
        return fixture.boss.position
    }

    private fun finishAttack(boss: LiveBoss, target: BossTarget): Double {
        var damage = 0.0
        var ticks = 0
        while (boss.currentAttack != null && ticks++ < 240) damage += boss.tick(TICK_SECONDS, target)
        assertTrue(ticks < 240, "fixture attack did not finish")
        return damage
    }

    private fun targetAt(boss: LiveBoss, x: Double) =
        BossTarget(Vec2(x, boss.position.y - 13.0), onGround = true, crouched = false)

    private fun seedFor(map: Int, charged: Boolean): ULong =
        (0uL..10_000uL).first { seed -> LiveBoss.rollsCharge(map, Rng(seed)) == charged }

    private data class Fixture(val boss: LiveBoss, val attack: BossAttack)

    private companion object {
        const val COHORT_SIZE = 10_000
        const val EPSILON = 1e-9
    }
}
