package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.WeaponScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The floor bounds what a player can be holding when a boss arena seals behind them.
 *
 * It deliberately does not claim the floor beats every map: the required rate grows about 81x across
 * a run and a worst-case loadout does not, so optional loot is required past the early game. What is
 * asserted instead is that the floor is real, rises, carries the opening maps, and that the ceiling
 * is high enough for the last one.
 */
class LootFloorTest {
    /** Gate-2 finding: the pressure harness must start a map with what a player *arrives* holding. */
    @Test
    fun `the slots arriving at a map hold only the awards of the maps before it`() {
        assertEquals(0, LootFloor.slotsArrivingAt(1).totalStacks, "map 1 is entered with a powerup nobody has awarded")
        (2..10).forEach { map ->
            assertEquals(LootFloor.slotsAt(map - 1).held, LootFloor.slotsArrivingAt(map).held, "arriving at map $map")
        }
    }

    /**
     * Gate-2 finding: the cache's roll could return the very weapon it exists to replace. Seed 17
     * is the one that did; the seed sweep lives in `jvmTest` (`LootFloorCohortTest`), because a
     * hundred generated maps block the browser runner past its ping timeout (ENG-031).
     */
    @Test
    fun `the starter cache never holds the starting weapon`() {
        val seed = 17uL
        val sim = GameSimulation(LevelGenerator.generate(seed, 1).level, RunState.begin(seed), seed)
        val cache = sim.items.first { it.guaranteed && it.weapon != null }.weapon!!
        assertTrue(cache.id != Weapons.startingWeapon.id, "seed $seed: the starter cache holds the ${cache.name}")
    }

    /** Round-3 finding: a boss is judged with what the player arrives holding, never with its own award. */
    @Test
    fun `the furthest clearable map is judged with the arriving loadout`() {
        val furthest = LootFloor.furthestClearableMap()
        assertTrue(furthest >= 1, "the floor clears nothing")
        (1..furthest).forEach { map ->
            val seconds = Balance.bossHealth(map) / LootFloor.damagePerSecondArrivingAt(map)
            assertTrue(seconds <= Balance.targetBossSeconds(map) * LootFloor.BAND_SLACK, "map $map is counted as covered but its boss takes $seconds s with the arriving loadout")
        }
        if (furthest < 10) {
            val next = furthest + 1
            val seconds = Balance.bossHealth(next) / LootFloor.damagePerSecondArrivingAt(next)
            assertTrue(seconds > Balance.targetBossSeconds(next) * LootFloor.BAND_SLACK, "map $next is clearable on arrival but not counted")
        }
    }

    /** Gate-2 finding: the floor is what the awards *guarantee*, and a main boss guarantees T3, never T4. */
    @Test
    fun `the floor's weapon is the weakest tier the awards guarantee`() {
        assertTrue(LootFloor.weaponAt(1).tier == Tier.Street, "map 1 opens above the starter cache")
        (2..10).forEach { map ->
            assertTrue(LootFloor.weaponAt(map).tier == Tier.Chromed, "map $map assumes a ${LootFloor.weaponAt(map).tier} weapon; a main boss guarantees only Chromed")
        }
    }

    @Test
    fun `map one never faces its mini-boss with the starting weapon`() {
        // The bottle's 4 DPS against a 108 HP mini-boss is 27 s, far outside an 18 s band. The
        // guaranteed starter cache is what stops that being the opening experience.
        assertTrue(
            LootFloor.damagePerSecondAt(1) > Weapons.startingWeapon.baseDps * 2.0,
            "the guaranteed floor on map 1 is no better than the bottle",
        )
    }

    @Test
    fun `the guaranteed floor never goes backwards`() {
        // Non-decreasing rather than strictly increasing: a worst-case award is sometimes a powerup
        // that does nothing for single-target damage — extra range, say — so the floor legitimately
        // plateaus for a map. What it must never do is fall.
        (1..9).forEach { map ->
            assertTrue(
                LootFloor.damagePerSecondAt(map + 1) >= LootFloor.damagePerSecondAt(map),
                "map ${map + 1} guarantees less than map $map",
            )
        }
    }

    @Test
    fun `the guaranteed floor rises substantially across a run`() {
        // Measured at 9.9 -> 48.7 (4.9x) once the weapon floor was corrected to what the awards
        // guarantee — Chromed from map 2, never above — so the rise is the powerups' doing.
        assertTrue(
            LootFloor.damagePerSecondAt(10) > LootFloor.damagePerSecondAt(1) * 4.0,
            "the floor barely moves across ten maps: ${LootFloor.damagePerSecondAt(1)} -> ${LootFloor.damagePerSecondAt(10)}",
        )
    }

    @Test
    fun `the floor carries the opening maps unaided`() {
        val furthest = LootFloor.furthestClearableMap()

        assertTrue(
            furthest >= GUARANTEED_MAPS,
            "guaranteed drops alone clear only map $furthest; the opening should not require luck",
        )
    }

    @Test
    fun `the floor clears trash and mini-bosses on the maps it carries`() {
        (1..LootFloor.furthestClearableMap()).forEach { map ->
            val dps = LootFloor.damagePerSecondAt(map)
            assertTrue(
                Balance.trashHealth(map) / dps <= Balance.targetTrashSeconds(map) * LootFloor.BAND_SLACK,
                "map $map: trash is out of band on the guaranteed floor",
            )
            assertTrue(
                Balance.minibossHealth(map) / dps <=
                    Balance.targetMinibossSeconds(map) * LootFloor.BAND_SLACK,
                "map $map: mini-boss is out of band on the guaranteed floor",
            )
        }
    }

    @Test
    fun `the ceiling reaches the final map, so a good run is winnable`() {
        assertTrue(
            Balance.peakAchievableDps() > Balance.requiredDps(10),
            "no build can meet the final map's requirement",
        )
    }

    @Test
    fun `beyond the floor, the run genuinely needs optional loot`() {
        // Stated as a property rather than left implicit: this is the intended difficulty, and the
        // commit line is what keeps it from becoming a trap.
        val furthest = LootFloor.furthestClearableMap()

        assertTrue(furthest < 10, "the guaranteed floor trivialises the whole run")
    }

    private companion object {
        const val GUARANTEED_MAPS = 3

        /** Floating-point slack only; the floor is compared in damage, not in units of it. */
        const val TOLERANCE = 1e-9
    }

    /**
     * The guarantee itself: **no optional route can leave a player below the floor.**
     *
     * The shape matters and three earlier versions of this test had it wrong. It is not enough to
     * walk one long route and check the end, nor to check monotonicity as you go: monotonicity says
     * a build never gets worse *than it was*, and the floor is a comparison with a **different**
     * route's build. Each case here therefore starts fresh, takes some optional powerups, and only
     * then takes the guaranteed awards — which is exactly the shape round eleven used to find a
     * map-four route ending at 30.27 against a 32.01 floor while every step obeyed the rules.
     *
     * Measured over every three-powerup optional route on all ten maps: the score-and-damage rule
     * left **10 of 8,160** below the floor, worst by 21.1 damage, and a Pareto rule failed on the
     * same ten. Guaranteed awards landing unconditionally leaves **0 of 8,160**.
     */
    @Test
    fun `no optional route can put a player below the floor`() {
        (1..10).forEach { map ->
            val floor = LootFloor.damagePerSecondAt(map)
            val weapon = LootFloor.weaponAt(map)
            val guaranteed = guaranteedRoute(map)

            optionalRoutes().forEach { optional ->
                var loadout = Loadout(weapon, PowerupSlots.empty())
                optional.forEach { loadout = loadout.collect(it, map).first }
                guaranteed.forEach {
                    loadout = loadout.collect(it, map, guaranteed = true).first
                }

                val dps = DamagePipeline.resolve(weapon, loadout.slots).expectedDps
                assertTrue(
                    dps >= floor - TOLERANCE,
                    "map $map: optional $optional then the guaranteed awards ends at $dps, " +
                        "below the $floor the floor promises",
                )
            }
        }
    }

    /**
     * Optional routes that fill the build before the guaranteed awards arrive, including the exact
     * three that round eleven found and the damage-over-time cluster the failures shared.
     */
    private fun optionalRoutes(): List<List<PowerupId>> {
        val ids = Powerups.all.map { it.id }
        val found = listOf(PowerupId.ArcCascade, PowerupId.BurnRig, PowerupId.ThermitePayload)
        val triples = mutableListOf(found, found.reversed())
        // Every three-subset is 816 per map and does not fit the browser runner; a stride across the
        // registry covers the same shapes without pinning the count to the registry's order.
        for (a in ids.indices step 3) {
            for (b in a + 1 until ids.size step 4) {
                for (c in b + 1 until ids.size step 5) {
                    triples.add(listOf(ids[a], ids[b], ids[c]))
                }
            }
        }
        triples.add(ids.take(PowerupSlots.MAX_SLOTS))
        triples.add(ids.takeLast(PowerupSlots.MAX_SLOTS))
        return triples
    }

    @Test
    fun `a build only ever does more as it collects, weapons and powerups alike`() {
        val map = 5
        var loadout = Loadout(Weapons.startingWeapon, PowerupSlots.empty())
        var best = WeaponScore.of(loadout.weapon, loadout.slots, map)

        val powerups = Powerups.all + Powerups.all.reversed()
        val weapons = Weapons.all + Weapons.all.reversed()
        powerups.forEachIndexed { index, powerup ->
            loadout = loadout.collect(powerup.id, map).first
            val afterPowerup = WeaponScore.of(loadout.weapon, loadout.slots, map)
            assertTrue(
                afterPowerup >= best,
                "collecting ${powerup.name} took the build from $best to $afterPowerup",
            )
            best = afterPowerup

            val found = weapons[index % weapons.size]
            loadout = loadout.collect(found, map).first
            val afterWeapon = WeaponScore.of(loadout.weapon, loadout.slots, map)
            assertTrue(
                afterWeapon >= best,
                "walking over ${found.name} took the build from $best to $afterWeapon",
            )
            best = afterWeapon
        }
    }

    /** The guaranteed awards, in the order and count the floor is computed from. */
    private fun guaranteedRoute(map: Int): List<PowerupId> {
        val pool = Powerups.ofTier(PowerupTier.Street) + Powerups.ofTier(PowerupTier.Scav)
        return (0 until LootFloor.guaranteedPowerups(map)).map { pool[it % pool.size].id }
    }

    private fun one(powerup: Powerup): PowerupSlots =
        PowerupSlots.empty().collect(powerup.id).first
}
