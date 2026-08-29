package io.github.ksean.cyberslop.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-60: the random roster covers its legal space and its undodged pressure rises by map band. */
class BossDifficultyTest {
    @Test
    fun `the seed cohort covers every legal primary pair and both signature kinds`() {
        for (map in 1..10) {
            val legalPairs = Bosses.meleeModulesFor(map).flatMap { melee ->
                Bosses.rangedModulesFor(map).map { ranged -> listOf(melee, ranged) }
            }.toSet()
            val seenPairs = mutableSetOf<List<BossModule>>()
            val signatureKinds = mutableSetOf<BossAttackKind>()
            for (seed in 1uL..COHORT) {
                val roster = BossRoster.forRun(seed)
                seenPairs += roster.miniboss(map).primaryPair
                seenPairs += roster.boss(map).primaryPair
                signatureKinds += requireNotNull(roster.boss(map).signature).kind
            }

            assertEquals(legalPairs, seenPairs, "map $map did not cover every legal pair")
            assertEquals(
                BossAttackKind.entries.toSet(),
                signatureKinds,
                "map $map signatures covered only $signatureKinds"
            )
        }
    }

    @Test
    fun `mean undodged main-boss damage per second rises from early to middle to late maps`() {
        val early = meanPressure(1..3)
        val middle = meanPressure(4..6)
        val late = meanPressure(7..10)

        assertTrue(early < middle, "middle pressure $middle did not exceed early pressure $early")
        assertTrue(middle < late, "late pressure $late did not exceed middle pressure $middle")
    }

    private fun meanPressure(maps: IntRange): Double {
        var total = 0.0
        var encounters = 0
        for (seed in 1uL..COHORT) for (map in maps) {
            val spec = Bosses.boss(map, BossRoster.forRun(seed).boss(map))
            val attacks = spec.phaseAt(CLOSING_HEALTH).attacks
            val damage = attacks.sumOf { it.damage * it.eventOffsets.size }
            val seconds = attacks.sumOf { it.totalSeconds + CLOSING_REST_SECONDS }
            total += damage / seconds
            encounters++
        }
        return total / encounters
    }

    private companion object {
        const val COHORT = 128uL
        const val CLOSING_HEALTH = 0.20
        const val CLOSING_REST_SECONDS = 0.65
    }
}
