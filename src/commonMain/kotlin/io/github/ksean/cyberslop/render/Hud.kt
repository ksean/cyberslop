package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.gen.DifficultyCurve
import io.github.ksean.cyberslop.loot.Powerup
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupTier
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.world.ThemeId

/** One held powerup, with how many times it is stacked (PROD-045). */
data class HudStack(
    /** So the display can resolve the same icon the ground draws (PROD-049, ENG-064). */
    val id: PowerupId,
    val name: String,
    val stacks: Int,
    val tier: PowerupTier,
) {
    val maxed: Boolean get() = stacks >= Powerup.MAX_STACKS
}

/**
 * Everything the heads-up display shows (PROD-045).
 *
 * A value rather than drawing code, so what the player is told can be asserted without a canvas
 * (ENG-060). The HUD drew a health bar and nothing else, which meant a run could not tell you what
 * weapon it had given you or what your build had become — in a game whose whole progression is loot.
 */
data class HudModel(
    val healthFraction: Double,
    val health: Int,
    val maxHealth: Int,
    val weaponId: WeaponId,
    val weaponName: String,
    val weaponTier: Tier,
    val mapIndex: Int,
    val mapCount: Int,
    val themeName: String,
    val scrap: Int,
    val powerups: List<HudStack>,
    val bossName: String?,
    val bossFraction: Double,
    /** Whether the main boss is dead, which is the one thing the bar cannot say. */
    val exitOpen: Boolean = false,
) {
    /**
     * The same state as prose, for the live region a canvas needs to not be opaque to assistive
     * technology (PROD-004).
     *
     * Here rather than in the browser layer because it must not be able to *disagree* with the bar
     * drawn beside it — and it did. A review round found the announcement hard-coding "of 10" and
     * looking only at the main boss, so during a committed mini-boss fight the screen showed a
     * health bar for a fight the live region said was not happening.
     */
    val announcement: String
        get() = buildString {
            append("Map $mapIndex of $mapCount, $themeName. ")
            append("Health $health. Weapon $weaponName.")
            when {
                exitOpen -> append(" Boss defeated; the way out is open.")
                bossName != null ->
                    append(" Fighting $bossName, ${(bossFraction * 100).toInt()} percent.")
            }
        }

    companion object {
        /**
         * Everything the display shows, read off one tick.
         *
         * **Which** boss the bar belongs to is decided here rather than in the browser layer: a
         * review round found that choice, and the map count, living in `CanvasRenderer`, where
         * ENG-060 forbids it and no test without a browser could reach it. The rule is the
         * engaged fight, main boss first — a player fighting the main boss is not being told
         * about the mini-boss they walked past.
         */
        fun of(sim: GameSimulation): HudModel {
            val live = listOf(sim.boss, sim.miniboss)
                .firstOrNull { it.fight.engaged && !it.fight.defeated }
            return of(
                run = sim.run,
                theme = sim.level.theme,
                mapCount = DifficultyCurve.MAPS,
                bossName = live?.spec?.name,
                bossFraction = live?.healthFraction ?: 0.0,
                exitOpen = sim.boss.fight.defeated,
            )
        }

        fun of(
            run: RunState,
            theme: ThemeId,
            mapCount: Int = DifficultyCurve.MAPS,
            bossName: String? = null,
            bossFraction: Double = 0.0,
            exitOpen: Boolean = false,
        ): HudModel = HudModel(
            healthFraction = (run.health / run.maxHealth).coerceIn(0.0, 1.0),
            health = run.health.toInt(),
            maxHealth = run.maxHealth.toInt(),
            weaponId = run.loadout.weapon.id,
            weaponName = run.loadout.weapon.name,
            weaponTier = run.loadout.weapon.tier,
            mapIndex = run.mapIndex,
            mapCount = mapCount,
            themeName = theme.displayName,
            scrap = run.scrap,
            // Ordered by strength so the build reads the same way twice running. A map's iteration
            // order is stable but says nothing a player would recognise.
            powerups = run.loadout.slots.held.entries
                .map { (id, stacks) -> Powerups.of(id).let { HudStack(id, it.name, stacks, it.tier) } }
                .sortedWith(compareByDescending<HudStack> { it.tier.ordinal }.thenBy { it.name }),
            bossName = bossName,
            bossFraction = bossFraction,
            exitOpen = exitOpen,
        )
    }
}

/**
 * How a pickup lying on the ground is drawn (PROD-044).
 *
 * Both a weapon and a powerup were an eight-pixel square in one of two blues, so neither what a
 * pickup was nor whether it was worth crossing a map for could be seen without walking into it.
 */
class PickupLook private constructor(
    val weapon: Boolean,
    val tierOrdinal: Int,
    val tierCount: Int,
) {
    init {
        require(tierOrdinal in 0 until tierCount) {
            "tier $tierOrdinal is not one of $tierCount"
        }
    }

    /**
     * Rarer is bigger, on one scale that weapons and powerups share.
     *
     * The tier count comes from the pickup's **own** registry. Deriving it from the weapon tiers
     * alone was wrong even though the two enums happen to be the same length today: a sixth powerup
     * tier would have pushed a powerup's scale to 2.125, past the stated maximum — the exact failure
     * the derivation was introduced to prevent.
     */
    val scale: Double
        get() = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * tierOrdinal / (tierCount - 1).toDouble()

    /**
     * The only way to build one, so a tier ordinal can never be paired with the wrong registry's
     * size. Not a `data class`: that generates a public `copy` whatever the constructor's
     * visibility, which R7 pointed out left the exact pairing this prevents still constructible.
     *
     * The two registries happen to hold five tiers each, which means no test can tell a wrong
     * denominator from a right one today — the guarantee has to be structural rather than asserted.
     * Each factory names its own registry and the constructor is private, so there is nowhere else
     * for the count to come from.
     */
    companion object {
        const val MIN_SCALE = 1.0
        const val MAX_SCALE = 1.9

        fun of(weapon: WeaponSpec) = PickupLook(true, weapon.tier.ordinal, Tier.entries.size)

        fun of(powerup: Powerup) =
            PickupLook(false, powerup.tier.ordinal, PowerupTier.entries.size)
    }
}
