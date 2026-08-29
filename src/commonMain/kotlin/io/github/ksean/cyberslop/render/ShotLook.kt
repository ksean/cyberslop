package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponClass
import io.github.ksean.cyberslop.sim.HitShape
import io.github.ksean.cyberslop.sim.LiveProjectile

/**
 * The three colours a projectile is drawn in (PROD-080): a dim [glow] behind it, its [body], and
 * a bright [core] on top; the tracer is a bloom in the glow colour under a line in the core colour.
 */
data class ShotLook(val glow: String, val body: String, val core: String) {
    val colours: List<String> get() = listOf(glow, body, core)
}

/**
 * Which look a shot takes, from what fired it (`specs/presentation.md`, Weapon effects).
 *
 * The player's looks are fixed across themes so that PROD-051 — no item colour is a projectile
 * colour — is a property of two constant sets, checked once, rather than of ten palettes.
 */
object ShotLooks {
    val RANGED = ShotLook(glow = "#7a3a10", body = "#ff9a3c", core = "#fff1c4")
    val PSYCHIC = ShotLook(glow = "#3a1a6e", body = "#b06cff", core = "#efe2ff")

    /** An enemy's or a boss's shot: the map's hazard colours, with a white core. */
    fun enemy(palette: Palette) = ShotLook(glow = palette.hazardGlow, body = palette.hazard, core = ENEMY_CORE)

    fun of(shot: LiveProjectile, palette: Palette): ShotLook = when {
        !shot.fromPlayer -> enemy(palette)
        shot.weapon?.spec?.cls == WeaponClass.Psychic -> PSYCHIC
        else -> RANGED
    }

    /** The look the live shot had, so an impact is drawn as the shot it was. */
    fun of(impact: HitShape.Impact, palette: Palette): ShotLook = when {
        !impact.fromPlayer -> enemy(palette)
        impact.psychic -> PSYCHIC
        else -> RANGED
    }

    /** Every fixed colour a player's shot may use, for the disjointness check against item colours. */
    val fixedColours: List<String> = RANGED.colours + PSYCHIC.colours + ENEMY_CORE

    const val ENEMY_CORE = "#ffffff"
}
