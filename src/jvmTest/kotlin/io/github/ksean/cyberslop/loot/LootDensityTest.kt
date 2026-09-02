package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two loot-density numbers, measured over a cohort rather than asserted.
 *
 * PROD-046 and PROD-047 are statistical claims, so a single seed cannot discharge either. This lives
 * in `jvmTest` because a cohort of this size does not fit the browser runner's per-test timeout
 * (ENG-031).
 *
 * The kill-rate test does not fight the combat system to produce kills. It sets a fatal burn on every
 * enemy outside the player's reach and ticks once: the burn drains through `advanceStatuses`, which calls the production kill
 * path exactly once per enemy. That is the same `onKilled` a weapon reaches, so what is measured is
 * the shipping drop rule and not a copy of it.
 */
class LootDensityTest {
    @Test
    fun `static pickups average two per map across seeds`() {
        val counts = mutableListOf<Int>()
        for (seed in 1uL..COHORT) {
            for (mapIndex in 1..10) {
                counts += LevelGenerator.generate(seed * SPREAD, mapIndex).level.pickups.size
            }
        }

        val mean = counts.sum().toDouble() / counts.size
        assertTrue(
            abs(mean - 2.0) <= MEAN_TOLERANCE,
            "static pickups averaged $mean per map over ${counts.size} maps, wanted 2.0",
        )
        assertTrue(
            counts.all { it in 1..3 },
            "a map placed ${counts.filter { it !in 1..3 }.distinct()} static pickups",
        )
    }

    @Test
    fun `one kill in five drops something, three in ten of them a weapon`() {
        var kills = 0
        var drops = 0
        var weapons = 0

        for (seed in 1uL..COHORT) {
            for (mapIndex in listOf(1, 5, 10)) {
                val level = LevelGenerator.generate(seed * SPREAD, mapIndex).level
                val run = RunState.begin(seed).copy(mapIndex = mapIndex)
                val sim = GameSimulation(level, run, seed * SPREAD)

                // Enemies near the player are removed rather than killed. Two things happen within
                // reach that would each bias the count downward: the weapon's opening swing kills on
                // its own, and `collectItems` runs in the same tick, so a drop at the player's feet
                // is taken before it is ever counted. Measured: leaving them in reported 0.1875.
                val centre = centreOf(sim)
                sim.enemies.removeAll { (it.position - centre).length <= CLEAR_RADIUS_PX }

                // And the same for pickups already lying there. A static drop (PROD-047) can be
                // placed in the spawn's own band, and `collectItems` takes it on the first tick —
                // which subtracts from the very counter this test reads. That alone accounted for
                // the whole gap between a measured 0.1875 and the required 0.20.
                sim.items.removeAll { (it.position - centre).length <= CLEAR_RADIUS_PX }

                val living = sim.enemies.size
                repeat(ROUNDS) {
                    val before = sim.items.size
                    sim.enemies.forEach { enemy ->
                        enemy.health = REVIVE_HEALTH
                        enemy.burn.apply(1.0, REVIVE_HEALTH * 120.0)
                    }
                    sim.tick(InputFrame())

                    assertTrue(
                        sim.enemies.none { it.alive },
                        "the fatal burn left ${sim.enemies.count { it.alive }} enemies alive",
                    )
                    kills += living
                    // PROD-110's independent food drop is not PROD-046 equipment loot. Count only
                    // the weapon/powerup items this round appended, just as the asserted 30/70
                    // split does.
                    val equipment = sim.items.subList(before, sim.items.size)
                        .filter { it.weapon != null || it.powerup != null }
                    drops += equipment.size
                    weapons += equipment.count { it.weapon != null }
                }
            }
        }

        val rate = drops.toDouble() / kills
        assertTrue(kills > MIN_SAMPLE, "only $kills kills sampled; too few to measure a rate")
        assertTrue(
            abs(rate - 0.20) <= RATE_TOLERANCE,
            "$drops drops from $kills kills is a rate of $rate, wanted 0.20",
        )

        // The split, measured rather than read off the constant. A review round found every loot
        // test counting totals only, so changing the share left them all green.
        val weaponShare = weapons.toDouble() / drops
        assertTrue(
            abs(weaponShare - DropTable.weaponShare()) <= SHARE_TOLERANCE,
            "$weapons of $drops drops were weapons, a share of $weaponShare against the " +
                "${DropTable.weaponShare()} PROD-046 requires",
        )
    }

    private fun centreOf(sim: GameSimulation) = Vec2(
        sim.player.x + Physics.Default.width / 2.0,
        sim.player.y + sim.player.height(Physics.Default) / 2.0,
    )

    private companion object {
        const val COHORT = 40uL

        /**
         * Comfortably past both the starting weapon's 25.6 px reach and the one-tile pickup radius,
         * so nothing an excluded enemy does can touch the sample.
         */
        const val CLEAR_RADIUS_PX = 64.0

        /**
         * Each level's enemies are killed, revived and killed again. Regenerating maps is what
         * costs; the drop rule is what is being sampled, so sampling it many times per map buys a
         * sample large enough to tell 0.20 from 0.1875 instead of leaving both inside 2 sigma.
         */
        const val ROUNDS = 10
        const val REVIVE_HEALTH = 10.0

        /** Seeds 1..40 are adjacent SplitMix64 states; spreading them samples the stream properly. */
        const val SPREAD = 0x9E3779B97F4A7C15uL

        const val MEAN_TOLERANCE = 0.15
        const val RATE_TOLERANCE = 0.01
        const val SHARE_TOLERANCE = 0.02

        /**
         * Two seed sets of this size measured 0.1957 and 0.2009 — a spread of half a percentage
         * point that is seed luck, not bias: the generator's own threshold measures 0.1999 over a
         * million draws, and scrap accounting proves no drop is lost after the fact. The tolerance
         * is set to survive that spread while still being nowhere near the 3-6% rate this replaced.
         */
        const val MIN_SAMPLE = 500
    }
}
