package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Bosses
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
        val names = attackNames(offsetX = 40.0, count = 60)
        val melee = names.filter { it != "Volley" }
        val expected = listOf("Slam", "Sweep", "Rush")
        melee.forEachIndexed { index, name ->
            assertEquals(expected[index % 3], name, "melee cycle broke at $index: $melee")
        }
    }

    @Test
    fun `a phase holding one kind never chooses the other`() {
        val sim = TestLevels.simulation()
        val boss = LiveBoss(Bosses.boss(5), sim.level.boss, sim.level.tiles, Rng(3uL))
        boss.fight.engage()
        assertTrue(attackNames(boss, offsetX = 200.0, count = 40).none { it == "Volley" }, "phase one volleyed")
        val mini = LiveBoss(Bosses.miniboss(5), sim.level.boss, sim.level.tiles, Rng(3uL))
        mini.fight.engage()
        assertTrue(attackNames(mini, offsetX = 200.0, count = 40).all { it == "Slam" }, "the mini-boss did not slam")
    }

    /** Pinned, so the JVM and the browser runner are held to one sequence rather than each to its own. */
    @Test
    fun `the same seed chooses the same sequence on every target`() {
        assertEquals(PINNED, attackNames(offsetX = 100.0, count = 12, seed = 9uL).joinToString(" "))
    }

    private companion object {
        const val PINNED = "Slam Sweep Volley Rush Volley Volley Slam Sweep Volley Rush Slam Volley"
    }

    private fun rangedShare(offsetX: Double): Double {
        val names = attackNames(offsetX, count = 400)
        return names.count { it == "Volley" } / names.size.toDouble()
    }

    private fun attackNames(offsetX: Double, count: Int, seed: ULong = 1uL): List<String> {
        val sim = TestLevels.simulation()
        val boss = LiveBoss(Bosses.boss(5), sim.level.boss, sim.level.tiles, Rng(seed))
        boss.fight.engage()
        boss.fight.damage(boss.spec.maxHealth * 0.8)
        return attackNames(boss, offsetX, count)
    }

    /** Ticks the boss, holding the target [offsetX] to its left, until [count] attacks have begun. */
    private fun attackNames(boss: LiveBoss, offsetX: Double, count: Int): List<String> {
        val names = mutableListOf<String>()
        var last: Any? = null
        var ticks = 0
        while (names.size < count && ticks < 400_000) {
            val target = BossTarget(Vec2(boss.position.x - offsetX, boss.position.y - 13.0), onGround = true, crouched = false)
            boss.tick(TICK_SECONDS, target)
            val attack = boss.currentAttack
            if (attack != null && attack !== last) names.add(attack.name)
            last = attack ?: last.takeIf { attack != null }
            if (attack == null) last = null
            ticks++
        }
        assertEquals(count, names.size, "fixture: only ${names.size} attacks in $ticks ticks")
        return names
    }
}
