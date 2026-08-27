package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.loot.DropTable
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.verify.Foothold
import io.github.ksean.cyberslop.verify.WitnessReplay
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PROD-047's placement invariants over every map of a seed cohort.
 *
 * The single-map version of this lives in `commonTest`, so the rule is exercised on both targets;
 * this is the sweep that would fail if one theme's geometry left no eligible ground. It is here
 * because generating hundreds of maps does not fit the browser runner's per-test timeout (ENG-031).
 */
class StaticDropCohortTest {
    @Test
    fun `every static pickup on every map stands somewhere legal`() {
        var checked = 0

        for (seed in 1uL..COHORT) {
            for (mapIndex in 1..10) {
                val generated = LevelGenerator.generate(seed * SPREAD, mapIndex)
                val level = generated.level
                val footholds = WitnessReplay.replay(level, generated.witness).footholds

                assertTrue(
                    level.pickups.isNotEmpty(),
                    "map $mapIndex of seed $seed placed no static pickup at all",
                )

                level.pickups.forEach { site ->
                    checked++
                    assertTrue(
                        !level.tiles.blocksMovement(site.column, site.row) &&
                            !level.tiles.isLethal(site.column, site.row) &&
                            level.tiles.blocksMovement(site.column, site.row + 1),
                        "map $mapIndex seed $seed: pickup at ${site.column},${site.row} " +
                            "is not standing on clear ground",
                    )
                    assertTrue(
                        Foothold(site.column, site.row) in footholds,
                        "map $mapIndex seed $seed: pickup at ${site.column},${site.row} " +
                            "is not on the route the witness walked",
                    )
                    assertTrue(
                        !level.miniboss.containsColumn(site.column) &&
                            !level.boss.containsColumn(site.column),
                        "map $mapIndex seed $seed: pickup at ${site.column} is inside an arena",
                    )
                    val span = site.column - Populator.COMMITTED_BUFFER..
                        site.column + Populator.COMMITTED_BUFFER
                    assertTrue(
                        span.none { Populator.isCommitted(level, it) },
                        "map $mapIndex seed $seed: pickup at ${site.column} is beside a " +
                            "committed span",
                    )
                }
            }
        }

        assertTrue(checked > MIN_SAMPLE, "only $checked pickups checked")
    }

    /**
     * PROD-047's spacing, which `StaticDrops` claimed from banding alone and did not have: two
     * candidates either side of a band boundary are neighbouring cells.
     */
    @Test
    fun `static pickups are never found on top of one another`() {
        for (seed in 1uL..COHORT) {
            for (mapIndex in 1..10) {
                val pickups = LevelGenerator.generate(seed * SPREAD, mapIndex).level.pickups
                pickups.forEachIndexed { index, one ->
                    pickups.drop(index + 1).forEach { other ->
                        val gap = kotlin.math.abs(one.column - other.column)
                        assertTrue(
                            gap >= StaticDrops.MIN_SEPARATION_TILES,
                            "map $mapIndex seed $seed: pickups at ${one.column} and " +
                                "${other.column} are $gap tiles apart",
                        )
                    }
                }
            }
        }
    }

    /**
     * PROD-047's contents. Round eight found the only content test asserting merely that each item
     * held *something*, so reverting the rarity shift or changing the split stayed green.
     */
    @Test
    fun `a static pickup is rarer on average than a corpse drop, and follows the same split`() {
        var weapons = 0
        var total = 0
        var cacheTier = 0
        var killTier = 0

        for (seed in 1uL..COHORT) {
            for (mapIndex in listOf(3, 7, 10)) {
                val level = LevelGenerator.generate(seed * SPREAD, mapIndex).level
                val sim = GameSimulation(
                    level, RunState.begin(seed).copy(mapIndex = mapIndex), seed * SPREAD,
                )
                level.pickups.forEach { site ->
                    val item = sim.items.first { it.position == site.centre }
                    total++
                    val weapon = item.weapon
                    if (weapon != null) {
                        weapons++
                        cacheTier += weapon.tier.ordinal
                    } else {
                        cacheTier += item.powerup!!.tier.ordinal
                    }
                }
                // The same map's unshifted draw, for comparison.
                val plain = Rng.derive(seed * SPREAD, mapIndex, "reference")
                repeat(level.pickups.size) {
                    killTier += DropTable.rollTier(plain, mapIndex).ordinal
                }
            }
        }

        assertTrue(total > MIN_CONTENT_SAMPLE, "only $total cache contents sampled")
        val share = weapons.toDouble() / total
        assertTrue(
            kotlin.math.abs(share - DropTable.weaponShare()) <= SHARE_TOLERANCE,
            "$weapons of $total caches were weapons, a share of $share against " +
                "${DropTable.weaponShare()}",
        )
        assertTrue(
            cacheTier > killTier,
            "caches averaged tier ${cacheTier.toDouble() / total} against an unshifted " +
                "${killTier.toDouble() / total} — the extra rarity draw is not happening",
        )
    }

    private companion object {
        const val COHORT = 20uL
        const val SPREAD = 0x9E3779B97F4A7C15uL
        const val MIN_SAMPLE = 200
        const val MIN_CONTENT_SAMPLE = 100
        const val SHARE_TOLERANCE = 0.10
    }
}
