package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.world.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-95: authored panoramas stay rich, distinct, restrained and fixed-cost. */
class DistantLandscapePainterTest {
    @Test
    fun `every landmark has three detailed outlines and every theme has its own silhouette`() {
        val themeSignatures = mutableSetOf<String>()

        ThemeId.entries.forEach { theme ->
            val profile = DistantLandscapeProfiles.of(theme)
            val landmark = profile.motifs.single { it.role == DistantRole.Landmark }
            val palette = Palettes.of(theme)
            val variants = (0 until profile.landmarkVariants).map { variant ->
                paint(profile, palette, listOf(feature(landmark, variant)))
            }

            assertEquals(profile.landmarkVariants, variants.map(::geometrySignature).distinct().size, "$theme variants")
            variants.forEachIndexed { variant, frame ->
                assertTrue(frame.batches.sumOf { it.size } >= LANDMARK_PRIMITIVES, "$theme variant $variant is sparse")
                assertTrue(
                    frame.batches.filter { it.style == palette.backdropDistantFacet }.sumOf { it.size } >=
                        LANDMARK_INTERNAL_MARKS,
                    "$theme variant $variant lacks internal design",
                )
            }
            assertTrue(themeSignatures.add(geometrySignature(variants.first())), "$theme repeats another landmark")
        }
    }

    @Test
    fun `secondary masses are composed shapes rather than single stamps`() {
        ThemeId.entries.forEach { theme ->
            val profile = DistantLandscapeProfiles.of(theme)
            val palette = Palettes.of(theme)
            profile.motifs.filter { it.role == DistantRole.SecondaryMass }.forEach { motif ->
                val frame = paint(profile, palette, listOf(feature(motif, variant = 1)))

                assertTrue(frame.batches.sumOf { it.size } >= SECONDARY_PRIMITIVES, "$motif is under-designed")
            }
        }
    }

    @Test
    fun `panoramas paint restrained fixed-cost tones in depth order`() {
        ThemeId.entries.forEachIndexed { index, theme ->
            val palette = Palettes.of(theme)
            val landscape = DistantLandscapes.of(
                seed = SEED,
                mapIndex = index + 1,
                theme = theme,
                tint = palette.backdropDistant,
                levelWidthPx = LEVEL_WIDTH,
            )
            val oneCell = landscape.copy(features = landscape.features.filter { it.cellIndex == 0 })
            val cellFrame = paint(landscape.profile, palette, oneCell.features)
            val fullFrame = paint(landscape.profile, palette, landscape.features)
            val tones = fullFrame.batches.map { it.style }.distinct()
            val keys: (DrawList) -> Set<Triple<String, Primitive, Double>> = { frame ->
                frame.batches.map { Triple(it.style, it.primitive, it.width) }.toSet()
            }
            val signalCount = fullFrame.batches.filter { it.style == palette.theme }.sumOf { it.size }
            val primitiveCount = fullFrame.batches.sumOf { it.size }

            assertEquals(
                listOf(
                    palette.backdropDistantShadow,
                    palette.backdropDistant,
                    palette.backdropDistantFacet,
                    palette.theme,
                ),
                tones,
                "$theme paints distant tones out of order",
            )
            assertEquals(keys(cellFrame), keys(fullFrame), "$theme batch vocabulary grows with cell count")
            assertTrue(fullFrame.batches.size <= MAX_BATCHES, "$theme exceeds its distant batch ceiling")
            assertTrue(signalCount * SIGNAL_DENOMINATOR <= primitiveCount, "$theme has too much signal light")
        }
    }

    private fun paint(
        profile: DistantLandscapeProfile,
        palette: Palette,
        features: List<DistantFeature>,
    ): DrawList {
        val builder = SceneBuilder()
        builder.begin()
        DistantLandscapePainter.paint(
            builder = builder,
            landscape = DistantLandscape(
                profile = profile,
                parallax = DistantLandscapes.PARALLAX,
                tint = palette.backdropDistant,
                layer = Layer.BackdropDistant,
                features = features,
            ),
            palette = palette,
            camera = Camera(0.0, 0.0, VIEW_WIDTH, VIEW_HEIGHT),
            horizon = HORIZON,
            viewWidth = VIEW_WIDTH,
        )
        return builder.build()
    }

    private fun feature(motif: DistantMotif, variant: Int) = DistantFeature(
        motif = motif,
        cellIndex = 0,
        x = FEATURE_X,
        baselineOffset = 0.0,
        width = if (motif.role == DistantRole.Landmark) LANDMARK_WIDTH else SECONDARY_WIDTH,
        height = if (motif.role == DistantRole.Landmark) LANDMARK_HEIGHT else SECONDARY_HEIGHT,
        strokeWidth = DistantLandscapes.TEXTURE_STROKE_WORLD,
        variant = variant,
    )

    private fun geometrySignature(frame: DrawList): String = frame.batches.joinToString("|") { batch ->
        buildString {
            append(batch.primitive)
            append(':')
            repeat(batch.size * batch.primitive.stride) { index ->
                append(batch[index].toBits())
                append(',')
            }
        }
    }

    private companion object {
        val SEED = 0xD37A11uL
        const val LEVEL_WIDTH = 18_000.0
        const val VIEW_WIDTH = 1_200.0
        const val VIEW_HEIGHT = 320.0
        const val HORIZON = 250.0
        const val FEATURE_X = 80.0
        const val LANDMARK_WIDTH = 142.0
        const val LANDMARK_HEIGHT = 104.0
        const val SECONDARY_WIDTH = 104.0
        const val SECONDARY_HEIGHT = 48.0
        const val LANDMARK_PRIMITIVES = 8
        const val LANDMARK_INTERNAL_MARKS = 2
        const val SECONDARY_PRIMITIVES = 3
        const val MAX_BATCHES = 16
        const val SIGNAL_DENOMINATOR = 8
    }
}
