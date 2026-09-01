package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.BossAttack
import io.github.ksean.cyberslop.entity.Bosses
import io.github.ksean.cyberslop.entity.BossAttackKind
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossRoster
import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.FireJet
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Activation on awareness, pursuit under the ledge rule, and dodges as mechanics (P-35). */
class BossBehaviourTest {
    @Test
    fun `an unnoticed boss neither moves nor attacks nor takes damage`() {
        val sim = TestLevels.simulation()
        val boss = sim.boss
        val start = boss.position

        repeat(300) { sim.tick(InputFrame()) }
        boss.fight.damage(100.0)

        assertFalse(boss.fight.engaged)
        assertEquals(start, boss.position, "an unnoticed boss moved")
        assertEquals(boss.spec.maxHealth, boss.fight.health, "an unnoticed boss took damage")
        assertEquals(sim.run.maxHealth, sim.run.health)
    }

    @Test
    fun `a boss that notices the player fights before the player enters its arena`() {
        val sim = TestLevels.simulation()
        val boss = sim.boss
        val start = boss.position.x

        // Walk toward the arena only until the boss notices; the player must still be outside it.
        var walked = 0
        while (!boss.fight.engaged && walked < 600) {
            sim.tick(InputFrame(right = true))
            walked++
        }
        assertTrue(boss.fight.engaged, "the boss never noticed the player")
        assertTrue(
            TileMap.toTile(sim.player.x) < sim.level.boss.leftTile,
            "fixture: the player was already inside the arena when the boss noticed",
        )
        assertTrue(boss.fight.vulnerable, "an engaged boss could not be hurt")

        repeat(120) { sim.tick(InputFrame()) }
        assertTrue(boss.position.x < start, "an engaged boss did not close on the player")
    }

    @Test
    fun `an engaged boss follows the player out of its arena and leaps a pit`() {
        val gap = 90..93
        val sim = TestLevels.simulation(TestLevels.flat(gapColumns = gap))
        val boss = sim.boss
        boss.fight.engage()
        var airborne = false

        // The player stands still far to the left; the boss walks out of its arena toward them.
        repeat(2400) {
            sim.tick(InputFrame())
            airborne = airborne || boss.leap != null
        }

        assertTrue(boss.position.x < TileMap.toWorld(sim.level.boss.leftTile), "the boss stayed in its arena")
        assertTrue(airborne, "the boss never began a leap")
        val rightFoot = TileMap.toTile(boss.position.x + boss.halfWidth)
        assertTrue(rightFoot < gap.first, "the boss did not clear the gap: foot at column $rightFoot")
    }

    @Test
    fun `both boss ranks leap acid spikes and barrels while pursuing`() {
        val hazards = listOf(
            TestLevels.flat(acidColumns = 15..17),
            TestLevels.flat(spikeColumns = 15..17),
            TestLevels.flat(barrels = listOf(Barrel(16, TestLevels.FLOOR_ROW))),
        )
        hazards.forEach { level ->
            listOf(false, true).forEach { main ->
                val sim = TestLevels.simulation(level)
                val live = if (main) sim.boss else sim.miniboss
                live.placeAt(Vec2(TileMap.toWorld(24), TileMap.toWorld(TestLevels.FLOOR_ROW + 1)))
                live.fight.engage()
                var airborne = false
                repeat(1_800) {
                    sim.tick(InputFrame())
                    airborne = airborne || live.leap != null
                }
                assertTrue(airborne, "${if (main) "main" else "mini"} boss never leapt over the hazard")
                assertTrue(
                    TileMap.toTile(live.position.x + live.halfWidth) < 15,
                    "${if (main) "main" else "mini"} boss did not cross the hazard",
                )
            }
        }
    }

    @Test
    fun `both boss ranks wait out an active fire jet and cross its off window`() {
        val jet = FireJet(
            column = 16,
            topRow = TestLevels.FLOOR_ROW - 5,
            bottomRow = TestLevels.FLOOR_ROW,
            periodSeconds = 1.0,
            onSeconds = 0.35,
            phaseSeconds = 0.0,
        )
        listOf(false, true).forEach { main ->
            val sim = TestLevels.simulation(TestLevels.flat(jets = listOf(jet)))
            val live = if (main) sim.boss else sim.miniboss
            live.placeAt(Vec2(TileMap.toWorld(24), TileMap.toWorld(TestLevels.FLOOR_ROW + 1)))
            live.fight.engage()

            repeat(1_800) { sim.tick(InputFrame()) }

            assertTrue(
                TileMap.toTile(live.position.x + live.halfWidth) < jet.column,
                "${if (main) "main" else "mini"} boss never used the jet's safe window",
            )
        }
    }

    @Test
    fun `each attack is avoided by its listed dodge and lands on a player who stands still`() {
        (1..10).flatMap { Bosses.boss(it).phases.flatMap { phase -> phase.attacks } }
            .filter { it.kind == BossAttackKind.Melee }
            .distinctBy { it.module }
            .forEach { attack ->
            assertTrue(damageWhen(attack, dodging = false) > 0.0, "${attack.name} missed a player who did nothing")
            assertEquals(0.0, damageWhen(attack, dodging = true), "${attack.name} hit a player who ${attack.dodge}")
            }
    }

    /**
     * Gate-2 finding: the dodge test above drives a synthetic target. This one drives the real
     * player with the four inputs through the real movement model, against a phase-three boss so
     * every attack is in the cycle: dodging takes nothing; doing nothing takes each attack once.
     */
    @Test
    fun `every attack is dodged with real inputs and lands on a player who only stands there`() {
        fun fight(policy: (GameSimulation) -> InputFrame): Triple<Double, Map<String, Double>, Set<String>> {
            val level = TestLevels.flat(bossArena = Arena(6, 22, TestLevels.FLOOR_ROW + 1), mapIndex = 5)
            val run = io.github.ksean.cyberslop.run.RunState.begin(TestLevels.SEED)
                .copy(mapIndex = 5, health = 100_000.0)
            val sim = GameSimulation(level, run, TestLevels.SEED, optionalLoot = false)
            sim.boss.fight.engage()
            sim.boss.fight.damage(sim.boss.spec.maxHealth * 0.8)
            val expected = sim.boss.fight.currentPhase().attacks.map { it.name }.toSet()
            val landed = mutableMapOf<String, Double>()
            val seen = mutableSetOf<String>()
            var ticks = 0
            var activeName: String? = null
            while (ticks < 6000 && (seen != expected || expected.any { it !in landed }) && !sim.run.dead) {
                val before = sim.run.health
                activeName = sim.boss.currentAttack?.name ?: activeName
                sim.tick(policy(sim))
                ticks++
                val attack = sim.boss.currentAttack
                if (attack != null) {
                    activeName = attack.name
                    if (sim.boss.striking) seen.add(attack.name)
                }
                if (sim.run.health < before) {
                    val name = activeName ?: error("damage arrived before any attack")
                    landed[name] = (landed[name] ?: 0.0) + (before - sim.run.health)
                }
            }
            assertEquals(expected, seen, "fixture: not every attack resolved in $ticks ticks")
            return Triple(sim.grossDamageTaken, landed, expected)
        }

        val (dodged, _, _) = fight(TestLevels::dodge)
        assertEquals(0.0, dodged, "a player performing every listed dodge still took damage")

        val (_, landed, expected) = fight(TestLevels::standStill)
        expected.forEach { name ->
            assertTrue((landed[name] ?: 0.0) > 0.0, "$name missed a player who did nothing: $landed")
        }
    }

    /** Round-3 finding: an attack holds its aim; a boss does not turn to follow a player crossing it mid-telegraph. */
    @Test
    fun `a boss holds its facing through an attack`() {
        val sim = TestLevels.simulation()
        val boss = LiveBoss(Bosses.boss(1), sim.level.boss, sim.level.tiles)
        boss.fight.engage()
        val left = BossTarget(Vec2(boss.position.x - 40.0, boss.position.y - 13.0), onGround = true, crouched = false)
        val right = BossTarget(Vec2(boss.position.x + 40.0, boss.position.y - 13.0), onGround = true, crouched = false)
        while (boss.currentAttack == null) boss.tick(TICK_SECONDS, left)
        assertEquals(-1, boss.facing)

        repeat(5) { boss.tick(TICK_SECONDS, right) }

        assertTrue(boss.currentAttack != null, "fixture: the attack ended")
        assertEquals(-1, boss.facing, "the boss turned to follow the player during its telegraph")
    }

    /** `specs/enemies.md`: a Rush is a lunge — the boss carries forward through its active window. */
    @Test
    fun `a rush carries the boss forward`() {
        val map = 10
        val seed = (1uL..2_000uL).first { BossModule.Rush in BossRoster.forRun(it).boss(map).modules }
        val level = TestLevels.flat(bossArena = Arena(6, 30, TestLevels.FLOOR_ROW + 1), mapIndex = map)
        val run = io.github.ksean.cyberslop.run.RunState.begin(seed).copy(mapIndex = map, health = 100_000.0)
        val sim = GameSimulation(level, run, seed, optionalLoot = false)
        val boss = sim.boss
        boss.fight.engage()
        boss.fight.damage(boss.spec.maxHealth * 0.8)
        var startX: Double? = null
        var endX: Double? = null
        var ticks = 0
        while (endX == null && ticks < 6000 && !sim.run.dead) {
            sim.tick(TestLevels.standStill(sim))
            ticks++
            val attack = boss.currentAttack
            if (attack?.name == "Rush" && boss.striking) {
                if (startX == null) startX = boss.position.x
            } else if (startX != null) {
                endX = boss.position.x
            }
        }
        assertTrue(startX != null && endX != null, "fixture: no Rush resolved in $ticks ticks")
        val travelled = (endX!! - startX!!) * boss.facing
        assertTrue(travelled > 40.0, "the Rush moved the boss only $travelled px forward")
    }

    @Test
    fun `every ranged module has level spanning reach`() {
        (1..10).flatMap { Bosses.boss(it).phases.flatMap { phase -> phase.attacks } }
            .filter { it.kind == BossAttackKind.Ranged }
            .forEach {
                assertTrue(
                    it.reachPx >= TestLevels.WIDTH * 16.0,
                    "${it.name} still expires after only ${it.reachPx / 16.0} tiles",
                )
            }
    }

    /** Runs one attack through its whole window against a scripted target and sums the damage. */
    private fun damageWhen(attack: BossAttack, dodging: Boolean): Double {
        val sim = TestLevels.simulation()
        val boss = LiveBoss(
            io.github.ksean.cyberslop.entity.BossSpec(
                "fixture", 1000.0, 0.0,
                listOf(io.github.ksean.cyberslop.entity.BossPhase(1.0, listOf(attack))),
                io.github.ksean.cyberslop.entity.BossProfile(
                    io.github.ksean.cyberslop.entity.BossModule.Slam,
                    io.github.ksean.cyberslop.entity.BossModule.Bolt,
                ),
            ),
            sim.level.boss,
            sim.level.tiles,
        )
        boss.fight.engage()
        val standing = Vec2(boss.position.x - 40.0, boss.position.y - 13.0)
        var total = 0.0
        var elapsed = 0.0
        // Rest, telegraph, then the active window; the dodge is held for the whole attack.
        while (elapsed < 1.0 + attack.totalSeconds + 0.1) {
            val telegraphStarted = boss.currentAttack != null
            val target = when {
                !dodging || !telegraphStarted -> BossTarget(standing, onGround = true, crouched = false)
                attack.dodge == Dodge.Jump -> BossTarget(standing - Vec2(0.0, 40.0), onGround = false, crouched = false)
                attack.dodge == Dodge.Crouch -> BossTarget(standing, onGround = true, crouched = true)
                else -> BossTarget(standing - Vec2(60.0, 0.0), onGround = true, crouched = false)
            }
            total += boss.tick(TICK_SECONDS, target)
            elapsed += TICK_SECONDS
        }
        return total
    }
}
