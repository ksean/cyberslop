package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.world.ThemeId

/** Which moves a sub-theme may use, and how its geometry reads. */
data class ThemeProfile(
    val id: ThemeId,
    val allowsAcid: Boolean,
    val allowsJets: Boolean,
    val allowsCrouchDuct: Boolean,
    val allowsStepUp: Boolean,
    val allowsDrop: Boolean,
    val arenaWidthTiles: Int,
    val restPlatformTiles: Int,
)

/**
 * Sub-theme definitions.
 *
 * Hazard *availability* is monotone: once acid or jets enter the run they never leave it. Themes
 * differ by geometry, rest-platform width and arena size, not by taking a hazard away — a theme that
 * removed one made the measured difficulty of the generated maps dip in the middle of the run, which
 * is the opposite of what a difficulty curve is for.
 */
object Themes {
    private val profiles = mapOf(
        ThemeId.RuinedCitySprawl to ThemeProfile(
            ThemeId.RuinedCitySprawl,
            allowsAcid = false, allowsJets = false, allowsCrouchDuct = false,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 16, restPlatformTiles = 8,
        ),
        ThemeId.RustFlats to ThemeProfile(
            ThemeId.RustFlats,
            allowsAcid = true, allowsJets = false, allowsCrouchDuct = false,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 16, restPlatformTiles = 8,
        ),
        ThemeId.FloodedUndercity to ThemeProfile(
            ThemeId.FloodedUndercity,
            allowsAcid = true, allowsJets = false, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 16, restPlatformTiles = 7,
        ),
        ThemeId.ChemFoundry to ThemeProfile(
            ThemeId.ChemFoundry,
            allowsAcid = true, allowsJets = true, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 16, restPlatformTiles = 7,
        ),
        ThemeId.NeonSlums to ThemeProfile(
            ThemeId.NeonSlums,
            allowsAcid = true, allowsJets = true, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 18, restPlatformTiles = 6,
        ),
        ThemeId.SableRefinery to ThemeProfile(
            ThemeId.SableRefinery,
            allowsAcid = true, allowsJets = true, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 18, restPlatformTiles = 6,
        ),
        ThemeId.ServerStacks to ThemeProfile(
            ThemeId.ServerStacks,
            allowsAcid = true, allowsJets = true, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 18, restPlatformTiles = 5,
        ),
        ThemeId.SkybridgeRuin to ThemeProfile(
            ThemeId.SkybridgeRuin,
            allowsAcid = true, allowsJets = true, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 20, restPlatformTiles = 5,
        ),
        ThemeId.ReactorCore to ThemeProfile(
            ThemeId.ReactorCore,
            allowsAcid = true, allowsJets = true, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 20, restPlatformTiles = 5,
        ),
        ThemeId.ArcologyVault to ThemeProfile(
            ThemeId.ArcologyVault,
            allowsAcid = true, allowsJets = true, allowsCrouchDuct = true,
            allowsStepUp = true, allowsDrop = true, arenaWidthTiles = 22, restPlatformTiles = 5,
        ),
    )

    fun of(id: ThemeId): ThemeProfile = profiles.getValue(id)

    fun forMap(mapIndex: Int): ThemeId {
        require(mapIndex in 1..ThemeId.entries.size) { "no theme for map $mapIndex" }
        return ThemeId.entries[mapIndex - 1]
    }
}

/**
 * Generation parameters as a monotone function of map index. Every field moves in one direction, so
 * the difficulty of the generated artifact rises with the map number rather than merely the
 * parameters claiming to.
 */
data class DifficultyCurve(
    val widthTiles: Int,
    val gapFrequency: Double,
    val hazardFrequency: Double,
    val maxGapTiles: Int,
    val verticalBandTiles: Int,
    val jetDuty: Double,
    val jetPeriodSeconds: Double,
    /**
     * How often a jet corridor is proposed. It rises faster than difficulty alone would suggest,
     * because harder maps have shorter off-windows and therefore *reject* more jet proposals — a
     * fixed proposal rate made the later maps end up with fewer jets than the earlier ones.
     */
    val jetFrequency: Double,
    val enemiesPerHundredTiles: Double,
    /** Spike strips and barrels per hundred tiles of width, rounded down (`specs/hazards.md`). */
    val damagingHazardsPerHundredTiles: Double,
) {
    companion object {
        const val MAPS = 10

        fun at(mapIndex: Int): DifficultyCurve {
            require(mapIndex in 1..MAPS) { "map index $mapIndex out of range" }
            val d = (mapIndex - 1) / (MAPS - 1).toDouble()
            return DifficultyCurve(
                widthTiles = lerpInt(320, 720, d),
                gapFrequency = lerp(0.12, 0.42, d),
                hazardFrequency = lerp(0.0, 0.55, d),
                maxGapTiles = lerpInt(2, 3, d),
                verticalBandTiles = lerpInt(8, 26, d),
                // Bounded so the off-window always comfortably fits a crossing. Pushed further, a
                // jet is not harder — it is uncrossable, and the generator refuses it, which made
                // the hardest maps end up with the *fewest* jets.
                jetDuty = lerp(0.25, 0.40, d),
                jetPeriodSeconds = lerp(2.4, 1.4, d),
                jetFrequency = lerp(0.10, 0.34, d),
                enemiesPerHundredTiles = lerp(4.0, 9.0, d),
                damagingHazardsPerHundredTiles = lerp(0.0, 5.0, d),
            )
        }

        private fun lerp(from: Double, to: Double, t: Double): Double = from + (to - from) * t

        private fun lerpInt(from: Int, to: Int, t: Double): Int =
            (from + (to - from) * t).toInt()
    }
}
