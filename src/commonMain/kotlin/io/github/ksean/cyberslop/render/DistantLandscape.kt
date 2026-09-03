package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.world.ThemeId

/** Apparent scale of a mark in the horizon landscape (PROD-040, P-94). */
enum class DistantScale { Large, Detail, Texture }

/** The visual job a feature performs inside one authored panorama cell (P-95). */
enum class DistantRole { Landmark, SecondaryMass, StructuralDetail, SurfaceTrace, Atmosphere }

/** Authored landscape vocabulary. Roles give random placement an explicit composition grammar. */
enum class DistantMotif(
    val role: DistantRole,
    val scale: DistantScale,
) {
    BuriedArcologyMesa(DistantRole.Landmark, DistantScale.Large),
    AshMesa(DistantRole.SecondaryMass, DistantScale.Large),
    MegastructureRib(DistantRole.SecondaryMass, DistantScale.Large),
    DustMark(DistantRole.StructuralDetail, DistantScale.Detail),
    AntennaFork(DistantRole.StructuralDetail, DistantScale.Detail),
    DustStreak(DistantRole.SurfaceTrace, DistantScale.Texture),
    DustVeil(DistantRole.Atmosphere, DistantScale.Large),

    BucketWheelCrawler(DistantRole.Landmark, DistantScale.Large),
    SandDune(DistantRole.SecondaryMass, DistantScale.Large),
    SlagHeap(DistantRole.SecondaryMass, DistantScale.Large),
    ScrapStake(DistantRole.StructuralDetail, DistantScale.Detail),
    DerrickPump(DistantRole.StructuralDetail, DistantScale.Detail),
    DuneRipple(DistantRole.SurfaceTrace, DistantScale.Texture),
    HeatBand(DistantRole.Atmosphere, DistantScale.Large),

    BreachedFloodgate(DistantRole.Landmark, DistantScale.Large),
    Floodplain(DistantRole.SecondaryMass, DistantScale.Large),
    StormWall(DistantRole.SecondaryMass, DistantScale.Large),
    DrownedPylon(DistantRole.StructuralDetail, DistantScale.Detail),
    SnappedMast(DistantRole.StructuralDetail, DistantScale.Detail),
    WaterRipple(DistantRole.SurfaceTrace, DistantScale.Texture),
    RainCurtain(DistantRole.Atmosphere, DistantScale.Large),

    OvergrownCoolingTree(DistantRole.Landmark, DistantScale.Large),
    WildCanopy(DistantRole.SecondaryMass, DistantScale.Large),
    DeadTrunk(DistantRole.SecondaryMass, DistantScale.Large),
    TreeCrown(DistantRole.StructuralDetail, DistantScale.Detail),
    FungalSpire(DistantRole.StructuralDetail, DistantScale.Detail),
    HangingVine(DistantRole.SurfaceTrace, DistantScale.Texture),
    SporeBand(DistantRole.Atmosphere, DistantScale.Large),

    CrystalTransmissionCrown(DistantRole.Landmark, DistantScale.Large),
    CrystalOutcrop(DistantRole.SecondaryMass, DistantScale.Large),
    SmogBank(DistantRole.SecondaryMass, DistantScale.Large),
    CrystalShard(DistantRole.StructuralDetail, DistantScale.Detail),
    BentCablePylon(DistantRole.StructuralDetail, DistantScale.Detail),
    SmogStreak(DistantRole.SurfaceTrace, DistantScale.Texture),
    ToxicHaze(DistantRole.Atmosphere, DistantScale.Large),

    RefineryCaldera(DistantRole.Landmark, DistantScale.Large),
    Caldera(DistantRole.SecondaryMass, DistantScale.Large),
    VolcanicRidge(DistantRole.SecondaryMass, DistantScale.Large),
    VolcanicVent(DistantRole.StructuralDetail, DistantScale.Detail),
    FracturedTower(DistantRole.StructuralDetail, DistantScale.Detail),
    LavaChannel(DistantRole.SurfaceTrace, DistantScale.Texture),
    AshPlume(DistantRole.Atmosphere, DistantScale.Large),

    GlacialDataMountain(DistantRole.Landmark, DistantScale.Large),
    Snowfield(DistantRole.SecondaryMass, DistantScale.Large),
    GlacialMountain(DistantRole.SecondaryMass, DistantScale.Large),
    RelayPost(DistantRole.StructuralDetail, DistantScale.Detail),
    AvalancheFence(DistantRole.StructuralDetail, DistantScale.Detail),
    AvalancheScar(DistantRole.SurfaceTrace, DistantScale.Texture),
    SnowRibbon(DistantRole.Atmosphere, DistantScale.Large),

    SeveredSkybridge(DistantRole.Landmark, DistantScale.Large),
    CloudOcean(DistantRole.SecondaryMass, DistantScale.Large),
    DistantPeak(DistantRole.SecondaryMass, DistantScale.Large),
    BrokenSkybridge(DistantRole.SecondaryMass, DistantScale.Large),
    CableFragment(DistantRole.StructuralDetail, DistantScale.Detail),
    MaintenancePod(DistantRole.StructuralDetail, DistantScale.Detail),
    CloudStreak(DistantRole.SurfaceTrace, DistantScale.Texture),
    RainShaft(DistantRole.Atmosphere, DistantScale.Large),

    RupturedReactorCrater(DistantRole.Landmark, DistantScale.Large),
    AshTundra(DistantRole.SecondaryMass, DistantScale.Large),
    BlastCrater(DistantRole.SecondaryMass, DistantScale.Large),
    FusedRidge(DistantRole.SecondaryMass, DistantScale.Large),
    GlassSpire(DistantRole.StructuralDetail, DistantScale.Detail),
    WarningTower(DistantRole.StructuralDetail, DistantScale.Detail),
    FalloutStreak(DistantRole.SurfaceTrace, DistantScale.Texture),
    FalloutPlume(DistantRole.Atmosphere, DistantScale.Large),

    EscarpmentVault(DistantRole.Landmark, DistantScale.Large),
    FrozenFlat(DistantRole.SecondaryMass, DistantScale.Large),
    Escarpment(DistantRole.SecondaryMass, DistantScale.Large),
    VaultMass(DistantRole.SecondaryMass, DistantScale.Large),
    SurveillancePylon(DistantRole.StructuralDetail, DistantScale.Detail),
    CausewayMarker(DistantRole.StructuralDetail, DistantScale.Detail),
    IceCrack(DistantRole.SurfaceTrace, DistantScale.Texture),
    LightPillar(DistantRole.Atmosphere, DistantScale.Large),
}

data class DistantLandscapeProfile(
    val theme: ThemeId,
    val motifs: List<DistantMotif>,
    val landmarkVariants: Int = DistantLandscapes.LANDMARK_VARIANTS,
)

/** One colour-independent landscape signature per map theme. */
object DistantLandscapeProfiles {
    private val profiles = listOf(
        profile(
            ThemeId.RuinedCitySprawl,
            DistantMotif.BuriedArcologyMesa, DistantMotif.AshMesa,
            DistantMotif.MegastructureRib, DistantMotif.DustMark,
            DistantMotif.AntennaFork, DistantMotif.DustStreak, DistantMotif.DustVeil,
        ),
        profile(
            ThemeId.RustFlats,
            DistantMotif.BucketWheelCrawler, DistantMotif.SandDune,
            DistantMotif.SlagHeap, DistantMotif.ScrapStake,
            DistantMotif.DerrickPump, DistantMotif.DuneRipple, DistantMotif.HeatBand,
        ),
        profile(
            ThemeId.FloodedUndercity,
            DistantMotif.BreachedFloodgate, DistantMotif.Floodplain,
            DistantMotif.StormWall, DistantMotif.DrownedPylon,
            DistantMotif.SnappedMast, DistantMotif.WaterRipple, DistantMotif.RainCurtain,
        ),
        profile(
            ThemeId.ChemFoundry,
            DistantMotif.OvergrownCoolingTree, DistantMotif.WildCanopy,
            DistantMotif.DeadTrunk, DistantMotif.TreeCrown,
            DistantMotif.FungalSpire, DistantMotif.HangingVine, DistantMotif.SporeBand,
        ),
        profile(
            ThemeId.NeonSlums,
            DistantMotif.CrystalTransmissionCrown, DistantMotif.CrystalOutcrop,
            DistantMotif.SmogBank, DistantMotif.CrystalShard,
            DistantMotif.BentCablePylon, DistantMotif.SmogStreak, DistantMotif.ToxicHaze,
        ),
        profile(
            ThemeId.SableRefinery,
            DistantMotif.RefineryCaldera, DistantMotif.Caldera,
            DistantMotif.VolcanicRidge, DistantMotif.VolcanicVent,
            DistantMotif.FracturedTower, DistantMotif.LavaChannel, DistantMotif.AshPlume,
        ),
        profile(
            ThemeId.ServerStacks,
            DistantMotif.GlacialDataMountain, DistantMotif.Snowfield,
            DistantMotif.GlacialMountain, DistantMotif.RelayPost,
            DistantMotif.AvalancheFence, DistantMotif.AvalancheScar, DistantMotif.SnowRibbon,
        ),
        profile(
            ThemeId.SkybridgeRuin,
            DistantMotif.SeveredSkybridge, DistantMotif.CloudOcean,
            DistantMotif.DistantPeak, DistantMotif.BrokenSkybridge,
            DistantMotif.CableFragment, DistantMotif.MaintenancePod,
            DistantMotif.CloudStreak, DistantMotif.RainShaft,
        ),
        profile(
            ThemeId.ReactorCore,
            DistantMotif.RupturedReactorCrater, DistantMotif.AshTundra,
            DistantMotif.BlastCrater, DistantMotif.FusedRidge,
            DistantMotif.GlassSpire, DistantMotif.WarningTower,
            DistantMotif.FalloutStreak, DistantMotif.FalloutPlume,
        ),
        profile(
            ThemeId.ArcologyVault,
            DistantMotif.EscarpmentVault, DistantMotif.FrozenFlat,
            DistantMotif.Escarpment, DistantMotif.VaultMass,
            DistantMotif.SurveillancePylon, DistantMotif.CausewayMarker,
            DistantMotif.IceCrack, DistantMotif.LightPillar,
        ),
    ).associateBy { it.theme }

    fun of(theme: ThemeId): DistantLandscapeProfile = profiles.getValue(theme)

    private fun profile(
        theme: ThemeId,
        vararg motifs: DistantMotif,
    ) = DistantLandscapeProfile(theme, motifs.toList())
}

/** One piece of seeded geometry, measured in distant-layer world pixels from its horizon. */
data class DistantFeature(
    val motif: DistantMotif,
    val cellIndex: Int,
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

/** Generates composed cells from an isolated stream, leaving the established skyline untouched. */
object DistantLandscapes {
    const val PARALLAX = 0.024
    const val CELL_WIDTH = 320.0
    const val TEXTURE_STROKE_WORLD = 0.5
    const val LANDMARK_VARIANTS = 3

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
        val lastX = levelWidthPx * PARALLAX + MARGIN_WORLD
        var cellIndex = FIRST_CELL
        var previousLandmarkVariant = -1

        while (cellIndex * CELL_WIDTH < lastX) {
            var landmarkVariant = rng.nextInt(profile.landmarkVariants)
            if (landmarkVariant == previousLandmarkVariant) {
                landmarkVariant = (landmarkVariant + 1) % profile.landmarkVariants
            }
            features += compositionCell(profile, cellIndex, landmarkVariant, rng)
            previousLandmarkVariant = landmarkVariant
            cellIndex++
        }

        return DistantLandscape(
            profile = profile,
            parallax = PARALLAX,
            tint = tint,
            layer = Layer.BackdropDistant,
            features = features,
        )
    }

    private fun compositionCell(
        profile: DistantLandscapeProfile,
        cellIndex: Int,
        landmarkVariant: Int,
        rng: Rng,
    ): List<DistantFeature> = buildList {
        val start = cellIndex * CELL_WIDTH
        val landmarkMotif = profile.motifs.single { it.role == DistantRole.Landmark }
        val secondaryMotifs = profile.motifs.filter { it.role == DistantRole.SecondaryMass }
        val detailMotifs = profile.motifs.filter { it.role == DistantRole.StructuralDetail }
        val traceMotifs = profile.motifs.filter { it.role == DistantRole.SurfaceTrace }
        val atmosphereMotifs = profile.motifs.filter { it.role == DistantRole.Atmosphere }
        val landmarkWidth = LANDMARK_MIN_WIDTH + rng.nextInt(LANDMARK_WIDTH_RANGE)
        val landmarkHeight = LANDMARK_MIN_HEIGHT + rng.nextInt(LANDMARK_HEIGHT_RANGE)
        val landmarkCentre =
            start + CELL_WIDTH / 2.0 + rng.nextDouble() * LANDMARK_JITTER * 2.0 - LANDMARK_JITTER

        add(
            feature(
                landmarkMotif,
                cellIndex,
                landmarkCentre - landmarkWidth / 2.0,
                landmarkWidth,
                landmarkHeight,
                landmarkVariant,
                rng,
            ),
        )

        val secondaryHeight = landmarkHeight * (SECONDARY_HEIGHT_MIN + rng.nextDouble() * SECONDARY_HEIGHT_RANGE)
        add(feature(secondaryMotifs[0], cellIndex, start - EDGE_OVERLAP, EDGE_MASS_WIDTH, secondaryHeight, 0, rng))
        add(
            feature(
                secondaryMotifs[1 % secondaryMotifs.size],
                cellIndex,
                start + CELL_WIDTH - EDGE_MASS_WIDTH + EDGE_OVERLAP,
                EDGE_MASS_WIDTH,
                secondaryHeight * 0.86,
                1,
                rng,
            ),
        )
        if (secondaryMotifs.size > 2 || rng.nextInt(2) == 0) {
            add(
                feature(
                    secondaryMotifs[2 % secondaryMotifs.size],
                    cellIndex,
                    start + SECONDARY_INSET + rng.nextDouble() * SECONDARY_JITTER,
                    INNER_MASS_WIDTH,
                    secondaryHeight * 0.72,
                    2,
                    rng,
                ),
            )
        }

        val detailCount = DETAIL_MIN_COUNT + rng.nextInt(DETAIL_COUNT_RANGE)
        repeat(detailCount) { index ->
            val motif = detailMotifs[(index + cellIndex.absoluteMod(detailMotifs.size)) % detailMotifs.size]
            val x = distributedX(start, index, detailCount, DETAIL_JITTER, rng)
            add(detailFeature(motif, cellIndex, x, index, rng))
        }

        val traceCount = TRACE_MIN_COUNT + rng.nextInt(TRACE_COUNT_RANGE)
        repeat(traceCount) { index ->
            val motif = traceMotifs[index % traceMotifs.size]
            val width = TRACE_MIN_WIDTH + rng.nextInt(TRACE_WIDTH_RANGE)
            add(
                feature(
                    motif,
                    cellIndex,
                    distributedX(start, index, traceCount, TRACE_JITTER, rng),
                    width,
                    TEXTURE_STROKE_WORLD,
                    rng.nextInt(VARIANTS),
                    rng,
                ),
            )
        }

        repeat(ATMOSPHERE_COUNT) { index ->
            val motif = atmosphereMotifs[index % atmosphereMotifs.size]
            add(
                feature(
                    motif,
                    cellIndex,
                    start + ATMOSPHERE_INSET + index * ATMOSPHERE_PITCH +
                        rng.nextDouble() * ATMOSPHERE_JITTER,
                    ATMOSPHERE_MIN_WIDTH + rng.nextInt(ATMOSPHERE_WIDTH_RANGE),
                    ATMOSPHERE_MIN_HEIGHT + rng.nextInt(ATMOSPHERE_HEIGHT_RANGE),
                    index,
                    rng,
                ),
            )
        }
    }

    private fun detailFeature(
        motif: DistantMotif,
        cellIndex: Int,
        x: Double,
        variant: Int,
        rng: Rng,
    ): DistantFeature = feature(
        motif = motif,
        cellIndex = cellIndex,
        x = x,
        width = DETAIL_MIN_SIZE + rng.nextDouble() * DETAIL_SIZE_RANGE,
        height = DETAIL_MIN_SIZE + rng.nextDouble() * DETAIL_SIZE_RANGE,
        variant = variant % VARIANTS,
        rng = rng,
    )

    private fun feature(
        motif: DistantMotif,
        cellIndex: Int,
        x: Double,
        width: Double,
        height: Double,
        variant: Int,
        rng: Rng,
    ) = DistantFeature(
        motif = motif,
        cellIndex = cellIndex,
        x = x,
        baselineOffset = rng.nextDouble() * BASELINE_RANGE,
        width = width,
        height = height,
        strokeWidth = TEXTURE_STROKE_WORLD,
        variant = variant,
    )

    private fun distributedX(
        start: Double,
        index: Int,
        count: Int,
        jitter: Double,
        rng: Rng,
    ): Double = start + (index + 0.5) * CELL_WIDTH / count + rng.nextDouble() * jitter - jitter / 2.0

    private fun Int.absoluteMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    private const val MARGIN_WORLD = 2200.0
    private const val FIRST_CELL = -7
    private const val BASELINE_RANGE = 5.0
    private const val LANDMARK_MIN_WIDTH = 112.0
    private const val LANDMARK_WIDTH_RANGE = 45
    private const val LANDMARK_MIN_HEIGHT = 82.0
    private const val LANDMARK_HEIGHT_RANGE = 35
    private const val LANDMARK_JITTER = 28.0
    private const val EDGE_OVERLAP = 18.0
    private const val EDGE_MASS_WIDTH = 104.0
    private const val SECONDARY_HEIGHT_MIN = 0.35
    private const val SECONDARY_HEIGHT_RANGE = 0.18
    private const val SECONDARY_INSET = 24.0
    private const val SECONDARY_JITTER = 28.0
    private const val INNER_MASS_WIDTH = 88.0
    private const val DETAIL_MIN_COUNT = 10
    private const val DETAIL_COUNT_RANGE = 5
    private const val DETAIL_MIN_SIZE = 1.0
    private const val DETAIL_SIZE_RANGE = 3.0
    private const val DETAIL_JITTER = 7.0
    private const val TRACE_MIN_COUNT = 8
    private const val TRACE_COUNT_RANGE = 5
    private const val TRACE_MIN_WIDTH = 10.0
    private const val TRACE_WIDTH_RANGE = 19
    private const val TRACE_JITTER = 11.0
    private const val ATMOSPHERE_COUNT = 3
    private const val ATMOSPHERE_INSET = 18.0
    private const val ATMOSPHERE_PITCH = 98.0
    private const val ATMOSPHERE_JITTER = 24.0
    private const val ATMOSPHERE_MIN_WIDTH = 72.0
    private const val ATMOSPHERE_WIDTH_RANGE = 45
    private const val ATMOSPHERE_MIN_HEIGHT = 15.0
    private const val ATMOSPHERE_HEIGHT_RANGE = 22
    private const val VARIANTS = 4
}
