package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.loot.Powerup
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.loot.PowerupTier
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROD-044 and PROD-045, over a build the player might actually be holding.
 *
 * A review round found the only HUD test running against `RunState.begin`, whose loadout has no
 * powerups at all — so an assertion that the display "carries the build" passed against a display
 * that could not show one. And nothing referenced [PickupLook] anywhere, leaving PROD-044 with no
 * coverage at all.
 */
class HudModelTest {
    @Test
    fun `the display carries every held powerup and its stack count`() {
        val model = HudModel.of(runWithBuild(), ThemeId.NeonSlums, MAPS)

        assertEquals(
            HELD.size,
            model.powerups.size,
            "the display shows ${model.powerups.size} powerups against ${HELD.size} held",
        )
        HELD.forEach { (id, stacks) ->
            val shown = model.powerups.first { it.name == Powerups.of(id).name }
            assertEquals(stacks, shown.stacks, "${shown.name} is shown at the wrong stack count")
            assertEquals(Powerups.of(id).tier, shown.tier)
        }
        assertTrue(
            model.powerups.any { it.maxed },
            "a powerup at its stack cap is not reported as maxed, so the display cannot say so",
        )
    }

    @Test
    fun `the build is ordered so it reads the same way twice`() {
        val first = HudModel.of(runWithBuild(), ThemeId.NeonSlums, MAPS).powerups.map { it.name }
        val again = HudModel.of(runWithBuild(), ThemeId.NeonSlums, MAPS).powerups.map { it.name }

        assertEquals(first, again)
        assertEquals(
            first,
            first.sortedWith(
                compareByDescending<String> { name ->
                    Powerups.all.first { it.name == name }.tier.ordinal
                }.thenBy { it },
            ),
            "the build is not ordered by strength, so it reshuffles as the player picks things up",
        )
    }

    /**
     * The live region and the drawn bar come from one model, so they cannot disagree — which they
     * did: the announcement hard-coded "of 10" and looked only at the main boss, so a committed
     * mini-boss fight was drawn on screen and denied in text.
     */
    @Test
    fun `the announcement says what the display shows`() {
        val fighting = HudModel.of(
            runWithBuild(), ThemeId.NeonSlums, MAPS,
            bossName = "Neon Tyrant", bossFraction = 0.5,
        )

        assertTrue(fighting.announcement.contains("Map 3 of $MAPS"), fighting.announcement)
        assertTrue(fighting.announcement.contains("Neon Slums"), fighting.announcement)
        assertTrue(fighting.announcement.contains("Neon Tyrant"), fighting.announcement)
        assertTrue(fighting.announcement.contains("50 percent"), fighting.announcement)

        val cleared = HudModel.of(runWithBuild(), ThemeId.NeonSlums, MAPS, exitOpen = true)
        assertTrue(cleared.announcement.contains("way out is open"), cleared.announcement)
        assertTrue(
            !cleared.announcement.contains("Fighting"),
            "a defeated boss is still announced as a fight in progress",
        )
    }

    @Test
    fun `a pickup shows what it is and how rare it is`() {
        val weapon = PickupLook.of(Weapons.startingWeapon)
        val powerup = PickupLook.of(Powerups.of(PowerupId.HollowpointFirmware))

        assertTrue(weapon.weapon && !powerup.weapon, "kind is not carried")

        val byTier = Tier.entries.map { tier ->
            PickupLook.of(Weapons.all.first { it.tier == tier }).scale
        }
        byTier.zipWithNext { commoner, rarer ->
            assertTrue(rarer > commoner, "a rarer pickup is drawn no larger: $byTier")
        }
    }

    /**
     * Both scales have to span the same range, or size stops reading as rarity the moment the two
     * registries differ in length. They are the same length today; the point is that nothing
     * depends on their staying so.
     */
    /**
     * Everything in either registry lands inside the one declared range.
     *
     * Through the factories, because the constructor is private and they are the only place a tier
     * ordinal is paired with a registry size. The two registries hold five tiers each today, so no
     * assertion here could distinguish a wrong denominator from a right one — that guarantee is
     * structural, and this pins the range it protects.
     */
    @Test
    fun `every weapon and every powerup is scaled inside one declared range`() {
        val weapons = Weapons.all.map { PickupLook.of(it) }
        val powerups = Powerups.all.map { PickupLook.of(it) }

        (weapons + powerups).forEach { look ->
            assertTrue(
                look.scale >= PickupLook.MIN_SCALE && look.scale <= PickupLook.MAX_SCALE,
                "a pickup is drawn at ${look.scale}, outside " +
                    "${PickupLook.MIN_SCALE}..${PickupLook.MAX_SCALE}",
            )
            assertEquals(
                if (look.weapon) Tier.entries.size else PowerupTier.entries.size,
                look.tierCount,
                "a pickup's rarity is scaled against the wrong registry",
            )
        }

        assertEquals(PickupLook.MIN_SCALE, weapons.minOf { it.scale })
        assertEquals(PickupLook.MAX_SCALE, weapons.maxOf { it.scale })
        assertEquals(PickupLook.MIN_SCALE, powerups.minOf { it.scale })
        assertEquals(
            PickupLook.MAX_SCALE,
            powerups.maxOf { it.scale },
            absoluteTolerance = 1e-12,
            message = "the rarest powerup is not drawn at the same size as the rarest weapon",
        )
    }

    private fun runWithBuild(): RunState {
        var slots = PowerupSlots.empty()
        HELD.forEach { (id, stacks) -> repeat(stacks) { slots = slots.collect(id).first } }
        val run = RunState.begin(SEED).copy(mapIndex = 3)
        return run.copy(loadout = run.loadout.copy(slots = slots))
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val MAPS = 10

        /** One at the cap, so `maxed` is exercised rather than merely present. */
        val HELD = listOf(
            PowerupId.HollowpointFirmware to Powerup.MAX_STACKS,
            PowerupId.OverclockCoil to 2,
            PowerupId.ChillProtocol to 1,
        )
    }
}
