package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.ThemeId
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PROD-040 and P-94: a fourth, extremely distant landscape authored for every map theme. */
class DistantLandscapeTest {
    @Test
    fun `every theme has its authored colour-independent landscape signature`() {
        val profiles = ThemeId.entries.map(DistantLandscapeProfiles::of)

        assertEquals(ThemeId.entries, profiles.map { it.theme })
        assertEquals(profiles.size, profiles.map { it.motifs.toSet() }.distinct().size)
        assertEquals(expectedMotifs, profiles.associate { it.theme to it.motifs.toSet() })
        profiles.forEach { profile ->
            assertTrue(profile.motifs.any { it.scale == DistantScale.Large })
            assertTrue(profile.motifs.any { it.scale == DistantScale.Detail })
            assertTrue(profile.motifs.any { it.scale == DistantScale.Texture })
        }
    }

    @Test
    fun `every theme declares the complete detailed composition vocabulary`() {
        ThemeId.entries.forEach { theme ->
            val profile = DistantLandscapeProfiles.of(theme)

            assertEquals(DistantRole.entries.toSet(), profile.motifs.map { it.role }.toSet(), "$theme roles")
            assertTrue(profile.landmarkVariants >= 3, "$theme lacks materially different landmark outlines")
            assertEquals(1, profile.motifs.count { it.role == DistantRole.Landmark })
            assertTrue(profile.motifs.count { it.role == DistantRole.SecondaryMass } >= 2)
            assertTrue(profile.motifs.count { it.role == DistantRole.StructuralDetail } >= 2)
        }
    }

    @Test
    fun `every composition cell has an anchored non-repeating visual hierarchy`() {
        ThemeId.entries.forEach { theme ->
            val landscape = Backdrops.of(SEED, level(theme)).distant
            val cells = landscape.features
                .groupBy { it.cellIndex }
                .toList()
                .sortedBy { it.first }

            assertTrue(cells.size >= 3, "$theme did not generate a panorama")
            cells.forEach { (cellIndex, features) ->
                val cellStart = cellIndex * DistantLandscapes.CELL_WIDTH
                val landmark = features.single { it.motif.role == DistantRole.Landmark }
                val landmarkCentre = landmark.x + landmark.width / 2.0
                val secondary = features.filter { it.motif.role == DistantRole.SecondaryMass }
                val details = features.filter { it.motif.role == DistantRole.StructuralDetail }
                val traces = features.filter { it.motif.role == DistantRole.SurfaceTrace }
                val atmosphere = features.filter { it.motif.role == DistantRole.Atmosphere }

                assertTrue(
                    landmarkCentre in
                        cellStart + DistantLandscapes.CELL_WIDTH / 3.0..
                        cellStart + DistantLandscapes.CELL_WIDTH * 2.0 / 3.0,
                    "$theme cell $cellIndex does not centre its landmark",
                )
                assertTrue(secondary.size in 2..4, "$theme cell $cellIndex secondary quota")
                assertTrue(details.size in 8..14, "$theme cell $cellIndex detail quota")
                assertTrue(traces.size in 6..12, "$theme cell $cellIndex trace quota")
                assertTrue(atmosphere.size in 2..4, "$theme cell $cellIndex atmosphere quota")
                assertTrue(
                    secondary.any { it.x < cellStart && it.x + it.width > cellStart },
                    "$theme cell $cellIndex has a left silhouette seam",
                )
                assertTrue(
                    secondary.any {
                        it.x < cellStart + DistantLandscapes.CELL_WIDTH &&
                            it.x + it.width > cellStart + DistantLandscapes.CELL_WIDTH
                    },
                    "$theme cell $cellIndex has a right silhouette seam",
                )
                assertTrue(
                    secondary.all { it.height <= landmark.height * LANDMARK_CLEAR_FRACTION },
                    "$theme cell $cellIndex obscures its landmark silhouette",
                )
                assertTrue(maximumDetailRun(details.sortedBy { it.x }) <= 3, "$theme repeats one detail stamp")
            }

            val landmarks = cells.map { (_, features) ->
                features.single { it.motif.role == DistantRole.Landmark }
            }
            landmarks.zipWithNext().forEach { (first, second) ->
                assertTrue(first.variant != second.variant, "$theme repeats adjacent landmark variants")
            }
        }
    }

    @Test
    fun `generated landscapes reproduce vary cover their scroll and retain every authored motif`() {
        ThemeId.entries.forEach { theme ->
            val level = level(theme)
            val once = Backdrops.of(SEED, level).distant
            val again = Backdrops.of(SEED, level).distant
            val changed = Backdrops.of(OTHER_SEED, level).distant

            assertEquals(once, again, "$theme did not reproduce its distant landscape")
            assertTrue(once != changed, "$theme ignored the distant-landscape seed")
            assertEquals(DISTANT_PARALLAX, once.parallax)
            assertEquals(Layer.BackdropDistant, once.layer)
            assertEquals(Palettes.of(theme).backdropDistant, once.tint)
            assertEquals(expectedMotifs.getValue(theme), once.features.map { it.motif }.toSet())
            assertTrue(once.features.first().x <= 0.0)
            assertTrue(
                once.features.maxOf { it.x + it.width } >= level.tiles.widthPx * once.parallax,
                "$theme runs out before its complete parallax scroll",
            )
        }
    }

    @Test
    fun `ordinary distant details are tiny while large landforms are explicitly exempt`() {
        ThemeId.entries.forEach { theme ->
            val features = Backdrops.of(SEED, level(theme)).distant.features
            val scales = features.map { it.motif.scale }.toSet()

            assertEquals(DistantScale.entries.toSet(), scales, "$theme omitted a distance scale")
            features.forEach { feature ->
                when (feature.motif.scale) {
                    DistantScale.Large -> Unit
                    DistantScale.Detail -> {
                        assertTrue(feature.width <= DETAIL_WORLD_LIMIT, "$theme detail is too wide: $feature")
                        assertTrue(feature.height <= DETAIL_WORLD_LIMIT, "$theme detail is too tall: $feature")
                    }

                    DistantScale.Texture -> assertTrue(
                        feature.strokeWidth * Scene.ZOOM <= TEXTURE_SCREEN_LIMIT,
                        "$theme texture is too thick: $feature",
                    )
                }
            }
        }
    }

    @Test
    fun `adding the isolated landscape stream preserves the seeded building skyline`() {
        val backdrop = Backdrops.of(SEED, level(ThemeId.NeonSlums))

        assertEquals(6245788328411421808uL, buildingSignature(backdrop))
    }

    private fun buildingSignature(backdrop: Backdrop): ULong {
        var hash = 0xCBF29CE484222325uL
        fun add(value: ULong) {
            hash = (hash xor value) * 0x100000001B3uL
        }
        fun add(text: String) = text.forEach { add(it.code.toULong()) }

        backdrop.layers.forEach { layer ->
            add(layer.parallax.toBits().toULong())
            add(layer.tint)
            layer.buildings.forEach { building ->
                add(building.x.toBits().toULong())
                add(building.width.toBits().toULong())
                add(building.height.toBits().toULong())
                add(building.windowColumns.toULong())
                add(building.windowRows.toULong())
                add(building.windows.toULong())
                add(building.roof.ordinal.toULong())
                add(building.windowLayout.ordinal.toULong())
                building.features.forEach { feature ->
                    add(feature.motif.ordinal.toULong())
                    add(feature.anchor.toBits().toULong())
                    add(feature.scale.toBits().toULong())
                    add(feature.variant.toULong())
                }
            }
        }
        return hash
    }

    private fun maximumDetailRun(features: List<DistantFeature>): Int {
        var maximum = 0
        var run = 0
        var previous: DistantMotif? = null
        features.forEach { feature ->
            run = if (feature.motif == previous) run + 1 else 1
            previous = feature.motif
            maximum = maxOf(maximum, run)
        }
        return maximum
    }

    private fun level(theme: ThemeId) = Level(
        mapIndex = theme.ordinal + 1,
        theme = theme,
        tiles = TileMap(80, 40),
        floorMask = Mask(80, 40),
        arcMask = Mask(80, 40),
        spawnColumn = 2,
        spawnRow = 30,
        miniboss = Arena(20, 30, 30),
        boss = Arena(40, 50, 30),
        jets = emptyList(),
    )

    private companion object {
        val SEED = 0xD157A17uL
        val OTHER_SEED = 0xD157A18uL
        const val DISTANT_PARALLAX = 0.024
        const val TEXTURE_SCREEN_LIMIT = 2.0
        const val DETAIL_WORLD_LIMIT = TILE_SIZE / 4.0
        const val LANDMARK_CLEAR_FRACTION = 0.62

        val expectedMotifs = mapOf(
            ThemeId.RuinedCitySprawl to setOf(
                DistantMotif.BuriedArcologyMesa,
                DistantMotif.AshMesa,
                DistantMotif.MegastructureRib,
                DistantMotif.DustMark,
                DistantMotif.AntennaFork,
                DistantMotif.DustStreak,
                DistantMotif.DustVeil,
            ),
            ThemeId.RustFlats to setOf(
                DistantMotif.BucketWheelCrawler,
                DistantMotif.SandDune,
                DistantMotif.SlagHeap,
                DistantMotif.ScrapStake,
                DistantMotif.DerrickPump,
                DistantMotif.DuneRipple,
                DistantMotif.HeatBand,
            ),
            ThemeId.FloodedUndercity to setOf(
                DistantMotif.BreachedFloodgate,
                DistantMotif.Floodplain,
                DistantMotif.StormWall,
                DistantMotif.DrownedPylon,
                DistantMotif.SnappedMast,
                DistantMotif.WaterRipple,
                DistantMotif.RainCurtain,
            ),
            ThemeId.ChemFoundry to setOf(
                DistantMotif.OvergrownCoolingTree,
                DistantMotif.WildCanopy,
                DistantMotif.DeadTrunk,
                DistantMotif.TreeCrown,
                DistantMotif.FungalSpire,
                DistantMotif.HangingVine,
                DistantMotif.SporeBand,
            ),
            ThemeId.NeonSlums to setOf(
                DistantMotif.CrystalTransmissionCrown,
                DistantMotif.CrystalOutcrop,
                DistantMotif.SmogBank,
                DistantMotif.CrystalShard,
                DistantMotif.BentCablePylon,
                DistantMotif.SmogStreak,
                DistantMotif.ToxicHaze,
            ),
            ThemeId.SableRefinery to setOf(
                DistantMotif.RefineryCaldera,
                DistantMotif.Caldera,
                DistantMotif.VolcanicRidge,
                DistantMotif.VolcanicVent,
                DistantMotif.FracturedTower,
                DistantMotif.LavaChannel,
                DistantMotif.AshPlume,
            ),
            ThemeId.ServerStacks to setOf(
                DistantMotif.GlacialDataMountain,
                DistantMotif.Snowfield,
                DistantMotif.GlacialMountain,
                DistantMotif.RelayPost,
                DistantMotif.AvalancheFence,
                DistantMotif.AvalancheScar,
                DistantMotif.SnowRibbon,
            ),
            ThemeId.SkybridgeRuin to setOf(
                DistantMotif.SeveredSkybridge,
                DistantMotif.CloudOcean,
                DistantMotif.DistantPeak,
                DistantMotif.BrokenSkybridge,
                DistantMotif.CableFragment,
                DistantMotif.MaintenancePod,
                DistantMotif.CloudStreak,
                DistantMotif.RainShaft,
            ),
            ThemeId.ReactorCore to setOf(
                DistantMotif.RupturedReactorCrater,
                DistantMotif.AshTundra,
                DistantMotif.BlastCrater,
                DistantMotif.FusedRidge,
                DistantMotif.GlassSpire,
                DistantMotif.WarningTower,
                DistantMotif.FalloutStreak,
                DistantMotif.FalloutPlume,
            ),
            ThemeId.ArcologyVault to setOf(
                DistantMotif.EscarpmentVault,
                DistantMotif.FrozenFlat,
                DistantMotif.Escarpment,
                DistantMotif.VaultMass,
                DistantMotif.SurveillancePylon,
                DistantMotif.CausewayMarker,
                DistantMotif.IceCrack,
                DistantMotif.LightPillar,
            ),
        )
    }
}
