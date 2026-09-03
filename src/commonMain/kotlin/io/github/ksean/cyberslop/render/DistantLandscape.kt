package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.world.ThemeId

/** Apparent scale of a mark in the horizon landscape (PROD-040, P-94). */
enum class DistantScale { Large, Detail, Texture }

/** Authored landscape vocabulary. Its scale makes the far-distance size constraint explicit. */
enum class DistantMotif(val scale: DistantScale) {
    AshMesa(DistantScale.Large),
    MegastructureRib(DistantScale.Large),
    DustMark(DistantScale.Detail),
    DustStreak(DistantScale.Texture),

    SandDune(DistantScale.Large),
    SlagHeap(DistantScale.Large),
    ScrapStake(DistantScale.Detail),
    DuneRipple(DistantScale.Texture),

    Floodplain(DistantScale.Large),
    StormWall(DistantScale.Large),
    DrownedPylon(DistantScale.Detail),
    WaterRipple(DistantScale.Texture),

    WildCanopy(DistantScale.Large),
    DeadTrunk(DistantScale.Large),
    TreeCrown(DistantScale.Detail),
    HangingVine(DistantScale.Texture),

    CrystalOutcrop(DistantScale.Large),
    SmogBank(DistantScale.Large),
    CrystalShard(DistantScale.Detail),
    SmogStreak(DistantScale.Texture),

    Caldera(DistantScale.Large),
    VolcanicRidge(DistantScale.Large),
    VolcanicVent(DistantScale.Detail),
    LavaChannel(DistantScale.Texture),

    Snowfield(DistantScale.Large),
    GlacialMountain(DistantScale.Large),
    RelayPost(DistantScale.Detail),
    AvalancheScar(DistantScale.Texture),

    CloudOcean(DistantScale.Large),
    DistantPeak(DistantScale.Large),
    BrokenSkybridge(DistantScale.Large),
    CableFragment(DistantScale.Detail),
    CloudStreak(DistantScale.Texture),

    AshTundra(DistantScale.Large),
    BlastCrater(DistantScale.Large),
    FusedRidge(DistantScale.Large),
    GlassSpire(DistantScale.Detail),
    FalloutStreak(DistantScale.Texture),

    FrozenFlat(DistantScale.Large),
    Escarpment(DistantScale.Large),
    VaultMass(DistantScale.Large),
    SurveillancePylon(DistantScale.Detail),
    IceCrack(DistantScale.Texture),
}

data class DistantLandscapeProfile(
    val theme: ThemeId,
    val motifs: List<DistantMotif>,
)

/** One colour-independent landscape signature per map theme. */
object DistantLandscapeProfiles {
    private val profiles = listOf(
        profile(
            ThemeId.RuinedCitySprawl,
            DistantMotif.AshMesa,
            DistantMotif.MegastructureRib,
            DistantMotif.DustMark,
            DistantMotif.DustStreak,
        ),
        profile(
            ThemeId.RustFlats,
            DistantMotif.SandDune,
            DistantMotif.SlagHeap,
            DistantMotif.ScrapStake,
            DistantMotif.DuneRipple,
        ),
        profile(
            ThemeId.FloodedUndercity,
            DistantMotif.Floodplain,
            DistantMotif.StormWall,
            DistantMotif.DrownedPylon,
            DistantMotif.WaterRipple,
        ),
        profile(
            ThemeId.ChemFoundry,
            DistantMotif.WildCanopy,
            DistantMotif.DeadTrunk,
            DistantMotif.TreeCrown,
            DistantMotif.HangingVine,
        ),
        profile(
            ThemeId.NeonSlums,
            DistantMotif.CrystalOutcrop,
            DistantMotif.SmogBank,
            DistantMotif.CrystalShard,
            DistantMotif.SmogStreak,
        ),
        profile(
            ThemeId.SableRefinery,
            DistantMotif.Caldera,
            DistantMotif.VolcanicRidge,
            DistantMotif.VolcanicVent,
            DistantMotif.LavaChannel,
        ),
        profile(
            ThemeId.ServerStacks,
            DistantMotif.Snowfield,
            DistantMotif.GlacialMountain,
            DistantMotif.RelayPost,
            DistantMotif.AvalancheScar,
        ),
        profile(
            ThemeId.SkybridgeRuin,
            DistantMotif.CloudOcean,
            DistantMotif.DistantPeak,
            DistantMotif.BrokenSkybridge,
            DistantMotif.CableFragment,
            DistantMotif.CloudStreak,
        ),
        profile(
            ThemeId.ReactorCore,
            DistantMotif.AshTundra,
            DistantMotif.BlastCrater,
            DistantMotif.FusedRidge,
            DistantMotif.GlassSpire,
            DistantMotif.FalloutStreak,
        ),
        profile(
            ThemeId.ArcologyVault,
            DistantMotif.FrozenFlat,
            DistantMotif.Escarpment,
            DistantMotif.VaultMass,
            DistantMotif.SurveillancePylon,
            DistantMotif.IceCrack,
        ),
    ).associateBy { it.theme }

    fun of(theme: ThemeId): DistantLandscapeProfile = profiles.getValue(theme)

    private fun profile(
        theme: ThemeId,
        vararg motifs: DistantMotif,
    ) = DistantLandscapeProfile(theme, motifs.toList())
}

/** Seeded geometry. Coordinates are world pixels measured from the landscape horizon. */
data class DistantFeature(
    val motif: DistantMotif,
    val x: Double,
    val baselineOffset: Double,
    val width: Double,
    val height: Double,
    val strokeWidth: Double,
    val variant: Int,
)

data class DistantLandscape(
    val profile: DistantLandscapeProfile,
    val parallax: Double,
    val tint: String,
    val layer: Layer,
    val features: List<DistantFeature>,
)

/** Generates the horizon from an isolated stream, leaving the established skyline untouched. */
object DistantLandscapes {
    const val PARALLAX = 0.024
    const val TEXTURE_STROKE_WORLD = 0.5

    fun of(
        seed: ULong,
        mapIndex: Int,
        theme: ThemeId,
        tint: String,
        levelWidthPx: Double,
    ): DistantLandscape {
        val profile = DistantLandscapeProfiles.of(theme)
        val rng = Rng.derive(seed, mapIndex, "backdrop-distant")
        val features = mutableListOf<DistantFeature>()
        val startMotif = rng.nextInt(profile.motifs.size)
        val span = levelWidthPx * PARALLAX + MARGIN_WORLD
        var x = -MARGIN_WORLD
        var index = 0

        while (x < span) {
            val motif = profile.motifs[(startMotif + index) % profile.motifs.size]
            val feature = feature(motif, x, rng)
            features.add(feature)
            x += advance(feature, rng)
            index++
        }

        return DistantLandscape(
            profile = profile,
            parallax = PARALLAX,
            tint = tint,
            layer = Layer.BackdropDistant,
            features = features,
        )
    }

    private fun feature(motif: DistantMotif, x: Double, rng: Rng): DistantFeature {
        val width: Double
        val height: Double
        when (motif.scale) {
            DistantScale.Large -> {
                width = LARGE_MIN_WIDTH + rng.nextInt(LARGE_WIDTH_RANGE)
                height = if (motif in LOW_LANDFORMS) {
                    LOW_MIN_HEIGHT + rng.nextInt(LOW_HEIGHT_RANGE)
                } else {
                    TALL_MIN_HEIGHT + rng.nextInt(TALL_HEIGHT_RANGE)
                }
            }

            DistantScale.Detail -> {
                width = DETAIL_MIN_SIZE + rng.nextDouble() * DETAIL_SIZE_RANGE
                height = DETAIL_MIN_SIZE + rng.nextDouble() * DETAIL_SIZE_RANGE
            }

            DistantScale.Texture -> {
                width = TEXTURE_MIN_WIDTH + rng.nextInt(TEXTURE_WIDTH_RANGE)
                height = TEXTURE_STROKE_WORLD
            }
        }
        return DistantFeature(
            motif = motif,
            x = x,
            baselineOffset = rng.nextDouble() * BASELINE_RANGE,
            width = width,
            height = height,
            strokeWidth = TEXTURE_STROKE_WORLD,
            variant = rng.nextInt(VARIANTS),
        )
    }

    private fun advance(feature: DistantFeature, rng: Rng): Double = when (feature.motif.scale) {
        DistantScale.Large ->
            feature.width * LARGE_OVERLAP + LARGE_MIN_GAP + rng.nextInt(LARGE_GAP_RANGE)
        DistantScale.Detail -> DETAIL_MIN_GAP + rng.nextDouble() * DETAIL_GAP_RANGE
        DistantScale.Texture -> feature.width * TEXTURE_OVERLAP + TEXTURE_MIN_GAP
    }

    private const val MARGIN_WORLD = 2200.0
    private const val BASELINE_RANGE = 6.0
    private const val LARGE_MIN_WIDTH = 42.0
    private const val LARGE_WIDTH_RANGE = 62
    private const val LOW_MIN_HEIGHT = 14.0
    private const val LOW_HEIGHT_RANGE = 26
    private const val TALL_MIN_HEIGHT = 58.0
    private const val TALL_HEIGHT_RANGE = 54
    private const val LARGE_OVERLAP = 0.62
    private const val LARGE_MIN_GAP = 2.0
    private const val LARGE_GAP_RANGE = 8
    private const val DETAIL_MIN_SIZE = 1.0
    private const val DETAIL_SIZE_RANGE = 3.0
    private const val DETAIL_MIN_GAP = 5.0
    private const val DETAIL_GAP_RANGE = 7.0
    private const val TEXTURE_MIN_WIDTH = 8.0
    private const val TEXTURE_WIDTH_RANGE = 18
    private const val TEXTURE_OVERLAP = 0.72
    private const val TEXTURE_MIN_GAP = 2.0
    private const val VARIANTS = 4

    private val LOW_LANDFORMS = setOf(
        DistantMotif.SandDune,
        DistantMotif.Floodplain,
        DistantMotif.SmogBank,
        DistantMotif.Snowfield,
        DistantMotif.CloudOcean,
        DistantMotif.AshTundra,
        DistantMotif.FrozenFlat,
    )
}
