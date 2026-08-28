package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.BossAttack
import io.github.ksean.cyberslop.entity.Bosses
import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
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
    fun `an engaged boss follows the player out of its arena and stops at a ledge`() {
        val gap = 90..93
        val sim = TestLevels.simulation(TestLevels.flat(gapColumns = gap))
        val boss = sim.boss
        boss.fight.engage()

        // The player stands still far to the left; the boss walks out of its arena toward them.
        repeat(1800) { sim.tick(InputFrame()) }

        assertTrue(boss.position.x < TileMap.toWorld(sim.level.boss.leftTile), "the boss stayed in its arena")
        val leftFoot = TileMap.toTile(boss.position.x - boss.halfWidth)
        assertTrue(leftFoot > gap.last, "the boss walked over the gap: foot at column $leftFoot")
    }

    @Test
    fun `each attack is avoided by its listed dodge and lands on a player who stands still`() {
        Bosses.boss(1).phases.flatMap { it.attacks }.distinctBy { it.name }.forEach { attack ->
            assertTrue(damageWhen(attack, dodging = false) > 0.0, "${attack.name} missed a player who did nothing")
            assertEquals(0.0, damageWhen(attack, dodging = true), "${attack.name} hit a player who ${attack.dodge}")
        }
    }

    @Test
    fun `a volley cannot reach beyond eight tiles`() {
        val volley = Bosses.boss(5).phases.flatMap { it.attacks }.first { it.name == "Volley" }
        assertEquals(8.0 * 16, volley.reachPx)
    }

    /** Runs one attack through its whole window against a scripted target and sums the damage. */
    private fun damageWhen(attack: BossAttack, dodging: Boolean): Double {
        val sim = TestLevels.simulation()
        val boss = LiveBoss(
            io.github.ksean.cyberslop.entity.BossSpec(
                "fixture", 1000.0, 0.0,
                listOf(io.github.ksean.cyberslop.entity.BossPhase(1.0, listOf(attack))),
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
