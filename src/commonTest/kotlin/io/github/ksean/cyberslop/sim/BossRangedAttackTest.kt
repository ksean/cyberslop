package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossRoster
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Arena
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-35/P-43: ranged boss modules create their real terrain-bounded attack geometry. */
class BossRangedAttackTest {
    @Test
    fun `bolt burst scatter and laser materialise one three five and one live attacks`() {
        val cases = listOf(
            BossModule.Bolt to 1,
            BossModule.Burst to 3,
            BossModule.Scatter to 5,
            BossModule.Laser to 1,
        )
        cases.forEach { (module, expected) ->
            val map = when (module) {
                BossModule.Bolt -> 1
                BossModule.Burst -> 4
                BossModule.Scatter -> 5
                BossModule.Laser -> 10
                else -> error("not ranged")
            }
            val seed = seedWhosePrimaryRangeIs(map, module)
            val level = TestLevels.flat(
                bossArena = Arena(6, 30, TestLevels.FLOOR_ROW + 1),
                mapIndex = map,
            )
            val run = RunState.begin(seed).copy(mapIndex = map, health = 1_000_000.0)
            val sim = GameSimulation(level, run, seed, optionalLoot = false)
            var largest = 0
            var velocities = emptyList<io.github.ksean.cyberslop.core.Vec2>()
            repeat(4_000) {
                sim.tick(InputFrame())
                val shots = sim.projectiles.filter { it.bossModule == module }
                if (shots.size > largest) {
                    largest = shots.size
                    velocities = shots.map { it.velocity }
                }
                if (module == BossModule.Laser && sim.bossBeams.isNotEmpty()) largest = 1
                if (largest == expected) return@repeat
            }
            assertEquals(expected, largest, "$module produced the wrong live geometry")
            if (module == BossModule.Scatter) {
                assertEquals(5, velocities.distinct().size, "the spread collapsed onto one line")
            }
        }
    }

    @Test
    fun `boss projectiles and beams are not capped at eight tiles`() {
        BossModule.entries.filter { it.kind == io.github.ksean.cyberslop.entity.BossAttackKind.Ranged }
            .forEach { module ->
                val map = (1..10).first { module in io.github.ksean.cyberslop.entity.Bosses.modulesFor(it) }
                val attack = io.github.ksean.cyberslop.entity.Bosses.attack(module, map, mainBoss = true)
                assertTrue(
                    attack.reachPx >= TestLevels.WIDTH * 16.0,
                    "$module still expires after ${attack.reachPx / 16.0} tiles",
                )
            }
    }

    private fun seedWhosePrimaryRangeIs(map: Int, module: BossModule): ULong =
        (1uL..2_000uL).first { BossRoster.forRun(it).boss(map).primaryRanged == module }
}
