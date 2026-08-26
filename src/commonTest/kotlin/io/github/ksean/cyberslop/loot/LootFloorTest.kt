package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.entity.Balance
import kotlin.test.Test
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
    @Test
    fun `map one never faces its mini-boss with the starting weapon`() {
        // The bottle's 3 DPS against a 108 HP mini-boss is 36 s, far outside an 18 s band. The
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
        assertTrue(
            LootFloor.damagePerSecondAt(10) > LootFloor.damagePerSecondAt(1) * 5.0,
            "the floor barely moves across ten maps",
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
    }
}
