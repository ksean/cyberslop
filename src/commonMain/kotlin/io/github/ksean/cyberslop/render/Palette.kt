package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.world.ThemeId

/**
 * A sub-theme's colours (PROD-040).
 *
 * Colours are plain strings here rather than browser values, because `commonMain` may not depend on
 * a browser API (ENG-010) and because a palette is data a test can compare. The renderer converts
 * each to a `JsString` **once per distinct colour** and caches it, which is affordable exactly
 * because the count is bounded — the same reason [glow] is a fixed three tones rather than a
 * continuous function of how dangerous an enemy is (ENG-061).
 *
 * The run's colour temperature is meant to drift across it: cold slate and sodium orange in the
 * sprawl, magenta and cyan in the slums, sodium red in the reactor, sterile white-gold in the vault.
 */
data class Palette(
    val sky: String,
    val skyLow: String,
    val backdropDistantShadow: String,
    val backdropDistant: String,
    val backdropDistantFacet: String,
    val backdropFar: String,
    val backdropMid: String,
    val backdropNear: String,
    val window: String,
    val tileBody: String,
    val tileEdge: String,
    val tileDeep: String,
    val hazard: String,
    val hazardGlow: String,
    val accent: String,
    val haze: String,
    /** Dim to hot. An enemy picks a tone by menace band, never by a continuous value. */
    val glow: List<String>,
) {
    init {
        require(glow.size == GLOW_TONES) { "a palette needs exactly $GLOW_TONES glow tones" }
        // A tone index means nothing unless the tones it indexes actually get brighter. Checked
        // here rather than only in a test, so a palette that breaks it cannot be added at all.
        for (index in 1 until glow.size) {
            require(luminanceOf(glow[index]) > luminanceOf(glow[index - 1])) {
                "glow tone $index (${glow[index]}) is no brighter than ${glow[index - 1]}"
            }
        }
    }

    val colours: List<String>
        get() = listOf(
            sky, skyLow, backdropDistantShadow, backdropDistant, backdropDistantFacet,
            backdropFar, backdropMid, backdropNear, window,
            tileBody, tileEdge, tileDeep, hazard, hazardGlow, accent, haze,
        ) + glow

    /** The map's canonical theme colour is its building light, with no duplicate value to drift. */
    val theme: String get() = window

    companion object {
        const val GLOW_TONES = 3

        /**
         * Rec. 709 relative luminance of an opaque `#rrggbb`.
         *
         * PROD-042 is a claim about how bright an enemy *looks*, and an index into a list is not
         * that. A review round found the gap: the index was monotone while the colours it resolved
         * to, drawn from whichever map's palette was current, were not.
         */
        fun luminanceOf(hex: String): Double {
            require(hex.length == 7 && hex[0] == '#') { "not an opaque hex triplet: $hex" }
            val red = hex.substring(1, 3).toInt(16)
            val green = hex.substring(3, 5).toInt(16)
            val blue = hex.substring(5, 7).toInt(16)
            return 0.2126 * red + 0.7152 * green + 0.0722 * blue
        }
    }
}

object Palettes {
    /**
     * Enemies keep one faction identity across the whole run, so their body and plating do not move
     * with the theme. What ties them to a place is [Palette.glow], which does.
     */
    const val ENEMY_BODY = "#3b3f4d"
    const val ENEMY_PLATE = "#585e70"
    const val ENEMY_DARK = "#23262f"

    /** What an enemy or boss is drawn in for the hurt flash (PROD-076); nothing else uses it. */
    const val HURT = "#ff3b30"

    /** Player feedback for collecting a healing bowl of ramen (PROD-110). */
    const val HEAL = "#39d353"

    fun of(theme: ThemeId): Palette = palettes.getValue(theme)

    /**
     * Tuned against rendered frames, not by eye in a table. The first pass had `backdropNear` within
     * a shade of `tileBody`, so the skyline and the ground the player stands on read as one surface
     * and the level had no depth at all. Backdrops now sit close to the sky and the terrain is
     * lifted well clear of them.
     */
    private val palettes = mapOf(
        // Cold slate under sodium light.
        ThemeId.RuinedCitySprawl to Palette(
            sky = "#070912", skyLow = "#0e1220",
            backdropDistantShadow = "#181419", backdropDistant = "#241917",
            backdropDistantFacet = "#30201c",
            backdropFar = "#12172a", backdropMid = "#191f38", backdropNear = "#222a4a",
            window = "#ffb069",
            tileBody = "#39415f", tileEdge = "#6a7699", tileDeep = "#1b2033",
            hazard = "#6bd66b", hazardGlow = "#b6f7b6",
            accent = "#ff8a3d", haze = "#1d2540",
            glow = listOf("#8a5a2a", "#d2803a", "#ffb066"),
        ),
        // Oxide and amber.
        ThemeId.RustFlats to Palette(
            sky = "#0d0b08", skyLow = "#191410",
            backdropDistantShadow = "#21180e", backdropDistant = "#2c1f10",
            backdropDistantFacet = "#3b2914",
            backdropFar = "#1e1811", backdropMid = "#2a2117", backdropNear = "#382b1e",
            window = "#ffc46b",
            tileBody = "#5c4229", tileEdge = "#96693c", tileDeep = "#2a1e12",
            hazard = "#7cd94f", hazardGlow = "#b6f58a",
            accent = "#ffb020", haze = "#2c2217",
            glow = listOf("#8a6420", "#d29a2c", "#ffcf5c"),
        ),
        // Standing water and sick green light.
        ThemeId.FloodedUndercity to Palette(
            sky = "#030b0b", skyLow = "#071615",
            backdropDistantShadow = "#0a201f", backdropDistant = "#0d2927",
            backdropDistantFacet = "#123432",
            backdropFar = "#0a1e1d", backdropMid = "#0f2b2b", backdropNear = "#16393a",
            window = "#58e0d0",
            tileBody = "#24494b", tileEdge = "#3d7d81", tileDeep = "#102526",
            hazard = "#7cff5a", hazardGlow = "#c2ff9e",
            accent = "#22d3ee", haze = "#0e2c2d",
            glow = listOf("#1c7a72", "#2bb3a6", "#5ef0dd"),
        ),
        // Violet plant under acid-yellow vapour.
        ThemeId.ChemFoundry to Palette(
            sky = "#09070f", skyLow = "#120d1c",
            backdropDistantShadow = "#131a15", backdropDistant = "#19231a",
            backdropDistantFacet = "#202b1f",
            backdropFar = "#181026", backdropMid = "#221733", backdropNear = "#2e1f45",
            window = "#d8ff5a",
            tileBody = "#40325c", tileEdge = "#715a97", tileDeep = "#1e1730",
            hazard = "#b6f000", hazardGlow = "#e4ff7a",
            accent = "#a855f7", haze = "#1f1730",
            glow = listOf("#6b3f9e", "#9a5ee0", "#c893ff"),
        ),
        // The one everybody pictures: magenta and cyan.
        ThemeId.NeonSlums to Palette(
            sky = "#08050f", skyLow = "#120a1f",
            backdropDistantShadow = "#1b0d25", backdropDistant = "#281231",
            backdropDistantFacet = "#351841",
            backdropFar = "#19102c", backdropMid = "#241340", backdropNear = "#331a58",
            window = "#ff5ac8",
            tileBody = "#43285e", tileEdge = "#7a479b", tileDeep = "#21132f",
            hazard = "#4ade80", hazardGlow = "#9df5bd",
            accent = "#ff2fb3", haze = "#22133a",
            glow = listOf("#a02476", "#e03aa8", "#ff77d2"),
        ),
        // Near-black, lit only by molten copper.
        ThemeId.SableRefinery to Palette(
            sky = "#06070a", skyLow = "#0d0f13",
            backdropDistantShadow = "#18110f", backdropDistant = "#261611",
            backdropDistantFacet = "#321c16",
            backdropFar = "#111319", backdropMid = "#181b22", backdropNear = "#22262f",
            window = "#ff9455",
            tileBody = "#333947", tileEdge = "#5c687c", tileDeep = "#191c23",
            hazard = "#63e07a", hazardGlow = "#a5f2b3",
            accent = "#e2683a", haze = "#171a21",
            glow = listOf("#8a3f22", "#c85f33", "#ff8f5c"),
        ),
        // Cold blue and white LED.
        ThemeId.ServerStacks to Palette(
            sky = "#04070d", skyLow = "#091018",
            backdropDistantShadow = "#0f1926", backdropDistant = "#172536",
            backdropDistantFacet = "#203247",
            backdropFar = "#0c1728", backdropMid = "#12203a", backdropNear = "#1a2d51",
            window = "#d7ecff",
            tileBody = "#27395a", tileEdge = "#486a96", tileDeep = "#131d2e",
            hazard = "#52e3a0", hazardGlow = "#9df5cd",
            accent = "#3b82f6", haze = "#101d31",
            glow = listOf("#24518f", "#3a7ad2", "#74aaff"),
        ),
        // Grey dusk above the cloud layer, hazard orange below.
        ThemeId.SkybridgeRuin to Palette(
            sky = "#0b1018", skyLow = "#151c28",
            backdropDistantShadow = "#211f1c", backdropDistant = "#302a20",
            backdropDistantFacet = "#3e3628",
            backdropFar = "#1b2432", backdropMid = "#26313f", backdropNear = "#35455f",
            window = "#ffd9a0",
            tileBody = "#47566f", tileEdge = "#7a8aa6", tileDeep = "#262f3d",
            hazard = "#6ee06e", hazardGlow = "#aaf2aa",
            accent = "#ff7a1a", haze = "#232d3d",
            glow = listOf("#8a5018", "#cc7726", "#ffa457"),
        ),
        // Sodium red, and everything too hot to touch.
        ThemeId.ReactorCore to Palette(
            sky = "#0d0407", skyLow = "#17060b",
            backdropDistantShadow = "#1d1012", backdropDistant = "#2b1716",
            backdropDistantFacet = "#39201c",
            backdropFar = "#200810", backdropMid = "#2e0b16", backdropNear = "#40111e",
            window = "#ffe3b0",
            tileBody = "#5e2029", tileEdge = "#9c3e46", tileDeep = "#2c0e14",
            hazard = "#b7f000", hazardGlow = "#e6ff80",
            accent = "#ff3b30", haze = "#250912",
            glow = listOf("#992018", "#e03a2c", "#ff7a63"),
        ),
        // Sterile white-gold. The only clean place in the run, and the last.
        ThemeId.ArcologyVault to Palette(
            sky = "#0f0e0b", skyLow = "#1a1914",
            backdropDistantShadow = "#22221b", backdropDistant = "#302e22",
            backdropDistantFacet = "#403c2b",
            backdropFar = "#221f16", backdropMid = "#302d1e", backdropNear = "#423e2a",
            window = "#fff3c4",
            tileBody = "#5f5a41", tileEdge = "#a09873", tileDeep = "#34311f",
            hazard = "#8ee84a", hazardGlow = "#cbf79c",
            accent = "#ffd24a", haze = "#2b2819",
            glow = listOf("#8a7420", "#d2b02c", "#ffe071"),
        ),
    )
}
