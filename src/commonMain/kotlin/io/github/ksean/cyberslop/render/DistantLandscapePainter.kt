package io.github.ksean.cyberslop.render

/** Converts the authored horizon model into the fixed primitive batches used by the world. */
internal object DistantLandscapePainter {
    fun paint(
        builder: SceneBuilder,
        landscape: DistantLandscape,
        accent: String,
        camera: Camera,
        horizon: Double,
        viewWidth: Double,
    ) {
        val brushes = Brushes(
            bodyRects = builder.batch(landscape.layer, landscape.tint, Primitive.Rect),
            bodyTriangles = builder.batch(landscape.layer, landscape.tint, Primitive.Triangle),
            bodySegments = builder.batch(
                landscape.layer,
                landscape.tint,
                Primitive.Segment,
                Scene.strokeWidth(DistantLandscapes.TEXTURE_STROKE_WORLD * Scene.ZOOM),
            ),
            bodyDots = builder.batch(landscape.layer, landscape.tint, Primitive.Dot),
            accentRects = builder.batch(landscape.layer, accent, Primitive.Rect),
            accentTriangles = builder.batch(landscape.layer, accent, Primitive.Triangle),
            accentSegments = builder.batch(
                landscape.layer,
                accent,
                Primitive.Segment,
                Scene.strokeWidth(DistantLandscapes.TEXTURE_STROKE_WORLD * Scene.ZOOM),
            ),
            accentDots = builder.batch(landscape.layer, accent, Primitive.Dot),
        )
        val offset = camera.x * landscape.parallax * Scene.ZOOM

        landscape.features.forEach { feature ->
            val left = feature.x * Scene.ZOOM - offset
            val width = feature.width * Scene.ZOOM
            if (left + width < 0.0 || left > viewWidth) return@forEach

            val height = feature.height * Scene.ZOOM
            val bottom = horizon + feature.baselineOffset * Scene.ZOOM
            paintFeature(feature, left, bottom - height, width, height, brushes)
        }
    }

    private fun paintFeature(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val centre = left + width / 2.0
        when (feature.motif) {
            DistantMotif.AshMesa -> {
                brushes.bodyRects.rect(
                    left + width * 0.22,
                    top + height * 0.22,
                    width * 0.56,
                    height * 0.78,
                )
                brushes.bodyTriangles.triangle(
                    left,
                    bottom,
                    left + width * 0.28,
                    top + height * 0.18,
                    centre,
                    bottom,
                )
                brushes.bodyTriangles.triangle(
                    centre,
                    bottom,
                    left + width * 0.72,
                    top + height * 0.18,
                    left + width,
                    bottom,
                )
            }

            DistantMotif.MegastructureRib -> repeat(4) { index ->
                val x = left + width * (0.12 + index * 0.25)
                brushes.bodySegments.segment(x, bottom, centre, top + height * (0.12 + index % 2 * 0.14))
            }

            DistantMotif.SandDune -> {
                brushes.bodyTriangles.triangle(left, bottom, left + width * 0.40, top, left + width * 0.72, bottom)
                brushes.bodyTriangles.triangle(
                    left + width * 0.38,
                    bottom,
                    left + width * 0.76,
                    top + height * 0.26,
                    left + width,
                    bottom,
                )
            }

            DistantMotif.SlagHeap -> jaggedRidge(left, top, width, height, brushes.bodyTriangles)
            DistantMotif.Floodplain -> brushes.bodyRects.rect(left, bottom - height * 0.16, width, height * 0.16)
            DistantMotif.StormWall -> {
                brushes.bodyRects.rect(left, top + height * 0.35, width, height * 0.65)
                repeat(3) { index ->
                    brushes.bodyDots.dot(
                        left + width * (0.2 + index * 0.3),
                        top + height * 0.35,
                        height * 0.28,
                    )
                }
            }

            DistantMotif.WildCanopy -> {
                brushes.bodyRects.rect(left, top + height * 0.46, width, height * 0.54)
                repeat(5) { index ->
                    brushes.bodyDots.dot(
                        left + width * (0.1 + index * 0.2),
                        top + height * (0.35 + index % 2 * 0.12),
                        height * 0.26,
                    )
                }
            }

            DistantMotif.DeadTrunk -> {
                brushes.bodyRects.rect(
                    centre - width * 0.08,
                    top + height * 0.16,
                    width * 0.16,
                    height * 0.84,
                )
                brushes.bodySegments.segment(centre, top + height * 0.38, left + width * 0.14, top + height * 0.10)
                brushes.bodySegments.segment(centre, top + height * 0.52, left + width * 0.86, top + height * 0.20)
            }

            DistantMotif.CrystalOutcrop -> crystalRange(left, top, width, height, brushes.bodyTriangles)
            DistantMotif.SmogBank, DistantMotif.CloudOcean -> repeat(4) { index ->
                brushes.bodyDots.dot(
                    left + width * (0.12 + index * 0.25),
                    top + height * (0.56 + index % 2 * 0.12),
                    height * (0.28 + (feature.variant + index) % 2 * 0.08),
                )
            }

            DistantMotif.Caldera -> {
                brushes.bodyTriangles.triangle(left, bottom, left + width * 0.30, top, centre, bottom)
                brushes.bodyTriangles.triangle(centre, bottom, left + width * 0.70, top, left + width, bottom)
                brushes.bodyRects.rect(left + width * 0.30, top + height * 0.86, width * 0.40, height * 0.14)
            }

            DistantMotif.VolcanicRidge, DistantMotif.FusedRidge ->
                jaggedRidge(left, top, width, height, brushes.bodyTriangles)

            DistantMotif.Snowfield, DistantMotif.AshTundra, DistantMotif.FrozenFlat -> {
                brushes.bodyRects.rect(left, top + height * 0.76, width, height * 0.24)
                brushes.bodyTriangles.triangle(
                    left,
                    top + height * 0.82,
                    centre,
                    top + height * 0.48,
                    left + width,
                    top + height * 0.82,
                )
                if (feature.motif == DistantMotif.Snowfield || feature.motif == DistantMotif.FrozenFlat) {
                    brushes.accentSegments.segment(
                        left,
                        top + height * 0.76,
                        left + width,
                        top + height * 0.82,
                    )
                }
            }

            DistantMotif.GlacialMountain, DistantMotif.DistantPeak -> {
                brushes.bodyTriangles.triangle(left, bottom, centre, top, left + width, bottom)
                brushes.accentTriangles.triangle(
                    centre - width * 0.09,
                    top + height * 0.18,
                    centre,
                    top,
                    centre + width * 0.07,
                    top + height * 0.14,
                )
            }

            DistantMotif.BrokenSkybridge -> {
                brushes.bodyRects.rect(left, top + height * 0.35, width * 0.42, height * 0.16)
                brushes.bodyRects.rect(left + width * 0.58, top + height * 0.47, width * 0.42, height * 0.16)
                brushes.bodySegments.segment(left + width * 0.42, top + height * 0.43, centre, top + height * 0.62)
                brushes.bodySegments.segment(centre, top + height * 0.62, left + width * 0.58, top + height * 0.55)
            }

            DistantMotif.BlastCrater -> {
                brushes.bodyTriangles.triangle(left, bottom, left + width * 0.22, top, centre, bottom)
                brushes.bodyTriangles.triangle(centre, bottom, left + width * 0.78, top, left + width, bottom)
                brushes.bodyRects.rect(left + width * 0.20, top + height * 0.82, width * 0.60, height * 0.18)
            }

            DistantMotif.Escarpment -> {
                brushes.bodyRects.rect(left + width * 0.18, top + height * 0.18, width * 0.72, height * 0.82)
                brushes.bodyTriangles.triangle(
                    left,
                    bottom,
                    left + width * 0.18,
                    top + height * 0.18,
                    left + width * 0.18,
                    bottom,
                )
            }

            DistantMotif.VaultMass -> {
                brushes.bodyRects.rect(left + width * 0.12, top + height * 0.36, width * 0.76, height * 0.64)
                brushes.bodyTriangles.triangle(
                    left + width * 0.12,
                    top + height * 0.36,
                    centre,
                    top,
                    left + width * 0.88,
                    top + height * 0.36,
                )
                brushes.accentDots.dot(centre, top + height * 0.48, DETAIL_DOT)
            }

            DistantMotif.DustMark,
            DistantMotif.TreeCrown,
            DistantMotif.VolcanicVent,
            -> brushes.accentDots.dot(centre, top + height / 2.0, minOf(width, height) / 2.0)

            DistantMotif.ScrapStake,
            DistantMotif.CableFragment,
            -> brushes.accentSegments.segment(left, bottom, left + width, top)

            DistantMotif.DrownedPylon,
            DistantMotif.RelayPost,
            DistantMotif.SurveillancePylon,
            -> {
                brushes.accentRects.rect(centre - width * 0.16, top, width * 0.32, height)
                brushes.accentDots.dot(centre, top, minOf(width, height) * 0.18)
            }

            DistantMotif.CrystalShard,
            DistantMotif.GlassSpire,
            -> brushes.accentTriangles.triangle(left, bottom, centre, top, left + width, bottom)

            DistantMotif.DustStreak,
            DistantMotif.DuneRipple,
            DistantMotif.WaterRipple,
            DistantMotif.HangingVine,
            DistantMotif.SmogStreak,
            DistantMotif.LavaChannel,
            DistantMotif.AvalancheScar,
            DistantMotif.CloudStreak,
            DistantMotif.FalloutStreak,
            DistantMotif.IceCrack,
            -> texture(feature, left, top, width, brushes)
        }
    }

    private fun jaggedRidge(
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        triangles: DrawBatch,
    ) {
        repeat(3) { index ->
            val section = width / 3.0
            val start = left + section * index
            val peak = start + section * (if (index % 2 == 0) 0.42 else 0.64)
            triangles.triangle(
                start,
                top + height,
                peak,
                top + height * (0.12 + index * 0.10),
                start + section,
                top + height,
            )
        }
    }

    private fun crystalRange(
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        triangles: DrawBatch,
    ) {
        repeat(4) { index ->
            val section = width / 4.0
            val start = left + section * index
            triangles.triangle(
                start,
                top + height,
                start + section * 0.55,
                top + height * (0.04 + index % 2 * 0.24),
                start + section,
                top + height,
            )
        }
    }

    private fun texture(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        brushes: Brushes,
    ) {
        val segments = if (feature.motif == DistantMotif.LavaChannel || feature.motif == DistantMotif.IceCrack) {
            brushes.accentSegments
        } else {
            brushes.bodySegments
        }
        val bend = (feature.variant - 1.5) * TEXTURE_BEND
        segments.segment(left, top, left + width * 0.48, top + bend)
        segments.segment(left + width * 0.48, top + bend, left + width, top - bend * 0.5)
    }

    private data class Brushes(
        val bodyRects: DrawBatch,
        val bodyTriangles: DrawBatch,
        val bodySegments: DrawBatch,
        val bodyDots: DrawBatch,
        val accentRects: DrawBatch,
        val accentTriangles: DrawBatch,
        val accentSegments: DrawBatch,
        val accentDots: DrawBatch,
    )

    private const val DETAIL_DOT = 1.5
    private const val TEXTURE_BEND = 0.8
}
