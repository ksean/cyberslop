package io.github.ksean.cyberslop.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossTest {
    @Test
    fun `every attack on every map is behaviourally telegraphed`() {
        // Simulated, not read from a field: metadata saying "0.4" would pass for an attack whose
        // hitbox exists on the first frame.
        forEachAttack { attack, where ->
            var elapsed = 0.0
            while (elapsed < attack.telegraphSeconds - STEP) {
                assertEquals(0.0, attack.damageAt(elapsed), "$where damages during its telegraph")
                elapsed += STEP
            }
            assertTrue(
                attack.damageAt(attack.telegraphSeconds + STEP) > 0.0,
                "$where never becomes dangerous",
            )
        }
    }

    @Test
    fun `no telegraph falls below the fairness floor, even on the last map`() {
        forEachAttack { attack, where ->
            assertTrue(
                attack.telegraphSeconds >= BossAttack.MIN_TELEGRAPH_SECONDS,
                "$where telegraphs for only ${attack.telegraphSeconds} s",
            )
        }
    }

    @Test
    fun `every attack is avoidable with the four movement inputs`() {
        forEachAttack { attack, where ->
            assertTrue(attack.dodge in Dodge.entries, "$where has no way to be avoided")
        }
    }

    @Test
    fun `an unengaged boss cannot be damaged`() {
        val fight = BossFight(Bosses.boss(1))

        repeat(100) { fight.damage(50.0) }

        assertFalse(fight.engaged, "damage engaged the boss on its own")
        assertEquals(Bosses.boss(1).maxHealth, fight.health)
    }

    @Test
    fun `noticing the player makes the boss vulnerable and never wears off`() {
        val fight = BossFight(Bosses.boss(1))

        fight.engage()

        assertTrue(fight.engaged)
        assertTrue(fight.damage(10.0))
        assertTrue(fight.engaged)
    }

    @Test
    fun `the exit opens only when the boss dies`() {
        val spec = Bosses.boss(1)
        val fight = BossFight(spec)
        fight.engage()

        assertFalse(fight.exitOpen, "exit was open with the boss alive")
        fight.damage(spec.maxHealth)

        assertTrue(fight.defeated)
        assertTrue(fight.exitOpen)
    }

    @Test
    fun `the boss gains attacks as its health falls`() {
        val spec = Bosses.boss(5)
        val fight = BossFight(spec)
        fight.engage()

        val opening = fight.currentPhase().attacks.size
        fight.damage(spec.maxHealth * 0.5)
        val middle = fight.currentPhase().attacks.size
        fight.damage(spec.maxHealth * 0.3)
        val closing = fight.currentPhase().attacks.size

        assertTrue(middle > opening, "phase two added nothing")
        assertTrue(closing > middle, "phase three added nothing")
    }

    @Test
    fun `a mini-boss is weaker than the boss on the same map`() {
        (1..10).forEach { map ->
            assertTrue(Bosses.miniboss(map).maxHealth < Bosses.boss(map).maxHealth, "map $map")
        }
    }

    @Test
    fun `every map has a distinctly named boss and mini-boss`() {
        val bosses = (1..10).map { Bosses.boss(it).name }
        val minibosses = (1..10).map { Bosses.miniboss(it).name }

        assertEquals(10, bosses.toSet().size)
        assertEquals(10, minibosses.toSet().size)
        assertTrue((bosses.toSet() intersect minibosses.toSet()).isEmpty())
    }

    private fun forEachAttack(check: (BossAttack, String) -> Unit) {
        (1..10).forEach { map ->
            listOf(Bosses.boss(map), Bosses.miniboss(map)).forEach { spec ->
                spec.phases.flatMap { it.attacks }.forEach { attack ->
                    check(attack, "map $map ${spec.name} ${attack.name}")
                }
            }
        }
    }

    private companion object {
        const val STEP = 1.0 / 60.0
    }
}
