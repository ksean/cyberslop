package io.github.ksean.cyberslop.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P-60: the seeded roster is varied, reproducible, complete and difficulty-banded. */
class BossProfileTest {
    @Test
    fun `every seeded encounter has one melee and one ranged primary without adjacent repeats`() {
        val roster = BossRoster.forRun(0xC0FFEEuL)
        val profiles = (1..10).flatMap { map ->
            listOf(roster.miniboss(map), roster.boss(map))
        }

        assertEquals(20, profiles.size)
        profiles.forEach { profile ->
            assertEquals(BossAttackKind.Melee, profile.primaryMelee.kind)
            assertEquals(BossAttackKind.Ranged, profile.primaryRanged.kind)
        }
        profiles.zipWithNext().forEachIndexed { index, (left, right) ->
            assertNotEquals(left.primaryPair, right.primaryPair, "encounters $index and ${index + 1} repeat")
        }
    }

    @Test
    fun `profiles use only their map band and main signatures are visible extra modules`() {
        val roster = BossRoster.forRun(7uL)
        (1..10).forEach { map ->
            val legal = Bosses.modulesFor(map).toSet()
            val mini = roster.miniboss(map)
            val main = roster.boss(map)
            assertTrue(mini.modules.all { it in legal }, "map $map mini used a module outside its band")
            assertTrue(main.modules.all { it in legal }, "map $map boss used a module outside its band")
            assertTrue(main.signature != null && main.signature !in main.primaryPair)
        }
    }

    @Test
    fun `the same seed reconstructs the same roster while another seed varies it`() {
        val first = BossRoster.forRun(123uL)
        assertEquals(first, BossRoster.forRun(123uL))
        assertNotEquals(first, BossRoster.forRun(124uL))
    }

    @Test
    fun `every produced phase has both attack kinds and damage rises with map and rank`() {
        val roster = BossRoster.forRun(99uL)
        (1..10).forEach { map ->
            listOf(
                Bosses.miniboss(map, roster.miniboss(map)),
                Bosses.boss(map, roster.boss(map)),
            ).forEach { spec ->
                spec.phases.forEach { phase ->
                    assertTrue(phase.attacks.any { it.kind == BossAttackKind.Melee })
                    assertTrue(phase.attacks.any { it.kind == BossAttackKind.Ranged })
                }
            }
        }
        BossModule.entries.forEach { module ->
            val legalMaps = (1..10).filter { module in Bosses.modulesFor(it) }
            legalMaps.zipWithNext().forEach { (earlier, later) ->
                assertTrue(
                    Bosses.attack(module, later, mainBoss = true).damage >
                        Bosses.attack(module, earlier, mainBoss = true).damage,
                    "$module did not grow from map $earlier to $later",
                )
            }
            legalMaps.forEach { map ->
                assertTrue(
                    Bosses.attack(module, map, mainBoss = true).damage >
                        Bosses.attack(module, map, mainBoss = false).damage,
                )
            }
        }
    }
}
