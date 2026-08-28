package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.combat.DamagePipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The floor bounds what a player can be holding when they reach a boss that engages on awareness
 * and gates the exit until it falls.
 *
 * It deliberately does not claim the floor beats every map: the required rate grows about 81x across
 * a run and a worst-case loadout does not, so optional loot is required past the early game. What is
 * asserted instead is that the floor is real, rises, carries the opening maps, and that the ceiling
 * is high enough for the last one.
 */
class LootFloorTest {
    /**
     * PROD-070 makes every weapon pickup a reset, so the loadout at any point is the last
     * guaranteed weapon plus only the powerups awarded after it — never an accumulation.
     */
    @Test
    fun `the slots arriving at a map are exactly the previous boss's powerup`() {
        assertEquals(0, LootFloor.slotsArrivingAt(1).totalStacks, "map 1 is entered with a powerup nobody has awarded")
        (2..10).forEach { map ->
            val arriving = LootFloor.slotsArrivingAt(map)
            assertEquals(1, arriving.distinctCount, "arriving at map $map with ${arriving.held}")
            assertEquals(1, arriving.totalStacks, "arriving at map $map with ${arriving.held}")
            val powerup = Powerups.of(arriving.held.keys.single())
            assertTrue(powerup.tier.ordinal >= PowerupTier.Scav.ordinal, "a main boss guarantees a Scav powerup; the floor assumed ${powerup.tier}")
        }
    }

    @Test
    fun `the slots at a map's boss are the mini-boss's powerup from map four and nothing before`() {
        (1..3).forEach { map -> assertEquals(0, LootFloor.slotsAt(map).totalStacks, "map $map's mini-boss awards no powerup") }
        (4..10).forEach { map ->
            assertEquals(1, LootFloor.slotsAt(map).totalStacks, "map $map: the mini-boss award replaced the boss weapon and emptied the build")
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

    /**
     * A boss is judged with what the player holds when they fight it: under PROD-070 that is the
     * mini-boss award — the arriving weapon is gone by then — and never the boss's own award.
     */
    @Test
    fun `the furthest clearable map is judged with the loadout held at the boss`() {
        val furthest = LootFloor.furthestClearableMap()
        assertTrue(furthest >= 1, "the floor clears nothing")
        (1..furthest).forEach { map ->
            val seconds = Balance.bossHealth(map) / LootFloor.damagePerSecondAt(map)
            assertTrue(seconds <= Balance.targetBossSeconds(map) * LootFloor.BAND_SLACK, "map $map is counted as covered but its boss takes $seconds s with the held loadout")
        }
        if (furthest < 10) {
            val next = furthest + 1
            val seconds = Balance.bossHealth(next) / LootFloor.damagePerSecondAt(next)
            assertTrue(seconds > Balance.targetBossSeconds(next) * LootFloor.BAND_SLACK, "map $next is clearable but not counted")
        }
    }

    /** The floor is what the awards *guarantee*, and a forced pickup can be a downgrade (PROD-070). */
    @Test
    fun `the floor's weapons are the weakest tiers the awards guarantee`() {
        assertEquals(Tier.Street, LootFloor.weaponArrivingAt(1).tier, "map 1 opens above the starter cache")
        (2..10).forEach { map ->
            assertEquals(Tier.Chromed, LootFloor.weaponArrivingAt(map).tier, "map $map is entered with a ${LootFloor.weaponArrivingAt(map).tier} weapon; a main boss guarantees only Chromed")
            assertEquals(Tier.Scav, LootFloor.weaponAt(map).tier, "map $map's boss is fought with a ${LootFloor.weaponAt(map).tier} weapon; the mini-boss award guarantees only Scav and is always taken")
        }
        assertEquals(Tier.Scav, LootFloor.weaponAt(1).tier)
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
    fun `the arriving floor steps up once, from the starter cache to a Chromed weapon`() {
        // Under PROD-070 nothing accumulates: the rise the floor can promise is the weapon tier of
        // the previous boss's award plus its one powerup, and it is the same on every later map.
        assertTrue(
            LootFloor.damagePerSecondArrivingAt(2) > LootFloor.damagePerSecondArrivingAt(1),
            "arriving at map 2 is no better than arriving at map 1",
        )
        (3..10).forEach { map ->
            assertEquals(LootFloor.damagePerSecondArrivingAt(2), LootFloor.damagePerSecondArrivingAt(map), 1e-9, "map $map arrives with something map 2 did not")
        }
    }

    @Test
    fun `the floor carries the opening maps unaided`() {
        val furthest = LootFloor.furthestClearableMap()

        assertTrue(
            furthest >= GUARANTEED_MAPS,
            "guaranteed drops alone clear only map $furthest; the opening should not require luck",
        )
    }

    /** Round-1 finding: the mini-boss is fought with what the player arrives holding, not with its own award. */
    @Test
    fun `the floor clears trash and mini-bosses on the maps it carries`() {
        (1..LootFloor.furthestClearableMap()).forEach { map ->
            val dps = LootFloor.damagePerSecondArrivingAt(map)
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
        // Stated as a property rather than left implicit: this is the intended difficulty. There is
        // no commit line; the exit gate opens on the boss's death and nothing seals a player in.
        val furthest = LootFloor.furthestClearableMap()

        assertTrue(furthest < 10, "the guaranteed floor trivialises the whole run")
    }

    private companion object {
        const val GUARANTEED_MAPS = 3

        /** Floating-point slack only; the floor is compared in damage, not in units of it. */
        const val TOLERANCE = 1e-9
    }

    /**
     * The guarantee itself: **no optional route can leave a player below the floor.** Under
     * PROD-070 the guaranteed weapon pickup resets whatever the route collected, so the end of
     * every route is the weapon plus the powerups awarded after it — the floor exactly.
     */
    @Test
    fun `no optional route can put a player below the floor`() {
        (1..10).forEach { map ->
            val floor = LootFloor.damagePerSecondAt(map)
            val weapon = LootFloor.weaponAt(map)
            val guaranteed = guaranteedRoute(map)

            optionalRoutes().forEach { optional ->
                var loadout = Loadout(LootFloor.weaponArrivingAt(map), PowerupSlots.empty())
                optional.forEach { loadout = loadout.collect(it, map).first }
                loadout = loadout.collect(weapon).first
                guaranteed.forEach {
                    loadout = loadout.collect(it, map, guaranteed = true).first
                }

                val dps = DamagePipeline.resolve(loadout.weapon, loadout.slots).expectedDps
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

    /** The guaranteed powerups awarded after the map's mini-boss weapon: its own, from map 4. */
    private fun guaranteedRoute(map: Int): List<PowerupId> =
        LootFloor.slotsAt(map).held.keys.toList()

    private fun one(powerup: Powerup): PowerupSlots =
        PowerupSlots.empty().collect(powerup.id).first
}
