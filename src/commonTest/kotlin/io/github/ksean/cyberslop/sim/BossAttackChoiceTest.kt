package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Bosses
import io.github.ksean.cyberslop.entity.BossAttackKind
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-44: a boss opens ranged more often on a far player and melee more often on a near one (PROD-072). */
class BossAttackChoiceTest {
    @Test
    fun `a near player is answered with melee about four times in five`() {
        val share = rangedShare(offsetX = 40.0)
        assertTrue(share in 0.15..0.25, "ranged share inside melee reach was $share")
    }

    @Test
    fun `a far player is answered with ranged about four times in five`() {
        val share = rangedShare(offsetX = 200.0)
        assertTrue(share in 0.75..0.85, "ranged share beyond the volley reach was $share")
    }

    @Test
    fun `within a kind the attacks still cycle in registry order`() {
        val modules = attackModules(offsetX = 40.0, count = 60)
        val melee = modules.filter { it.kind == BossAttackKind.Melee }
        val expected = Bosses.boss(5).phaseAt(0.2).attacks
            .filter { it.kind == BossAttackKind.Melee }.map { it.module }
        melee.forEachIndexed { index, module ->
            assertEquals(expected[index % expected.size], module, "melee cycle broke at $index: $melee")
        }
    }

    @Test
    fun `every mini and main phase can choose both kinds`() {
        (1..10).forEach { map ->
            listOf(Bosses.miniboss(map), Bosses.boss(map)).forEach { spec ->
                spec.phases.forEach { phase ->
                    assertEquals(setOf(BossAttackKind.Melee, BossAttackKind.Ranged), phase.attacks.map { it.kind }.toSet())
                }
            }
        }
    }

    /** Pinned, so the JVM and the browser runner are held to one sequence rather than each to its own. */
    @Test
    fun `the same seed chooses the same sequence on every target`() {
        assertEquals(PINNED, attackModules(offsetX = 100.0, count = 12, seed = 9uL).joinToString(" "))
    }

    private companion object {
        const val PINNED = "Slam Flurry Scatter Slam Scatter Scatter Flurry Slam Scatter Flurry Slam Scatter"
    }

    private fun rangedShare(offsetX: Double): Double {
        val modules = attackModules(offsetX, count = 400)
        return modules.count { it.kind == BossAttackKind.Ranged } / modules.size.toDouble()
    }

    private fun attackModules(offsetX: Double, count: Int, seed: ULong = 1uL): List<BossModule> {
        val sim = TestLevels.simulation()
        val boss = LiveBoss(Bosses.boss(5), sim.level.boss, sim.level.tiles, Rng(seed))
        boss.fight.engage()
        boss.fight.damage(boss.spec.maxHealth * 0.8)
        return attackModules(boss, offsetX, count)
    }

    /** Ticks the boss, holding the target [offsetX] to its left, until [count] attacks have begun. */
    private fun attackModules(boss: LiveBoss, offsetX: Double, count: Int): List<BossModule> {
        val modules = mutableListOf<BossModule>()
        var last: Any? = null
        var ticks = 0
        while (modules.size < count && ticks < 400_000) {
            val target = BossTarget(Vec2(boss.position.x - offsetX, boss.position.y - 13.0), onGround = true, crouched = false)
            boss.tick(TICK_SECONDS, target)
            val attack = boss.currentAttack
            if (attack != null && attack !== last) modules.add(attack.module)
            last = attack
            ticks++
        }
        assertEquals(count, modules.size, "fixture: only ${modules.size} attacks in $ticks ticks")
        return modules
    }
}
