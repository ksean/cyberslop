package io.github.ksean.cyberslop.render

/** Converts authored panorama cells into four fixed, depth-ordered tonal brush sets. */
internal object DistantLandscapePainter {
    fun paint(
        builder: SceneBuilder,
        landscape: DistantLandscape,
        palette: Palette,
        camera: Camera,
        horizon: Double,
        viewWidth: Double,
    ) {
        val brushes = Brushes(
            shadow = brush(builder, landscape.layer, palette.backdropDistantShadow),
            body = brush(builder, landscape.layer, landscape.tint),
            facet = brush(builder, landscape.layer, palette.backdropDistantFacet),
            signal = brush(builder, landscape.layer, palette.theme),
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

    private fun brush(builder: SceneBuilder, layer: Layer, style: String) = Brush(
        rects = builder.batch(layer, style, Primitive.Rect),
        triangles = builder.batch(layer, style, Primitive.Triangle),
        segments = builder.batch(
            layer,
            style,
            Primitive.Segment,
            Scene.strokeWidth(DistantLandscapes.TEXTURE_STROKE_WORLD * Scene.ZOOM),
        ),
        dots = builder.batch(layer, style, Primitive.Dot),
    )

    private fun paintFeature(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        when (feature.motif.role) {
            DistantRole.Landmark -> paintLandmark(feature, left, top, width, height, brushes)
            DistantRole.SecondaryMass -> paintSecondary(feature, left, top, width, height, brushes)
            DistantRole.StructuralDetail -> paintDetail(feature, left, top, width, height, brushes)
            DistantRole.SurfaceTrace -> paintTrace(feature, left, top, width, brushes)
            DistantRole.Atmosphere -> paintAtmosphere(feature, left, top, width, height, brushes.shadow)
        }
    }

    private fun paintLandmark(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        when (feature.motif) {
            DistantMotif.BuriedArcologyMesa -> buriedArcologyMesa(feature, left, top, width, height, brushes)
            DistantMotif.BucketWheelCrawler -> bucketWheelCrawler(feature, left, top, width, height, brushes)
            DistantMotif.BreachedFloodgate -> breachedFloodgate(feature, left, top, width, height, brushes)
            DistantMotif.OvergrownCoolingTree -> overgrownCoolingTree(feature, left, top, width, height, brushes)
            DistantMotif.CrystalTransmissionCrown ->
                crystalTransmissionCrown(feature, left, top, width, height, brushes)
            DistantMotif.RefineryCaldera -> refineryCaldera(feature, left, top, width, height, brushes)
            DistantMotif.GlacialDataMountain -> glacialDataMountain(feature, left, top, width, height, brushes)
            DistantMotif.SeveredSkybridge -> severedSkybridge(feature, left, top, width, height, brushes)
            DistantMotif.RupturedReactorCrater ->
                rupturedReactorCrater(feature, left, top, width, height, brushes)
            DistantMotif.EscarpmentVault -> escarpmentVault(feature, left, top, width, height, brushes)
            else -> error("${feature.motif} is not a landmark")
        }
    }

    private fun buriedArcologyMesa(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val split = 0.43 + feature.variant * 0.07
        brushes.body.rects.rect(left + width * 0.10, top + height * 0.44, width * 0.80, height * 0.56)
        brushes.body.triangles.triangle(
            left,
            bottom,
            left + width * 0.24,
            top + height * 0.24,
            left + width * split,
            bottom,
        )
        brushes.body.triangles.triangle(
            left + width * (split + 0.04),
            bottom,
            left + width * (0.72 - feature.variant * 0.05),
            top + height * (0.10 + feature.variant * 0.06),
            left + width,
            bottom,
        )
        brushes.body.rects.rect(
            left + width * (0.25 + feature.variant * 0.03),
            top + height * 0.31,
            width * 0.28,
            height * 0.17,
        )
        repeat(5) { index ->
            val ribX = left + width * (0.22 + index * 0.13)
            brushes.facet.segments.segment(
                ribX,
                bottom,
                left + width * (0.48 + feature.variant * 0.03),
                top + height * (0.30 + index % 2 * 0.12),
            )
        }
        brushes.facet.segments.segment(
            left + width * 0.12,
            top + height * 0.66,
            left + width * 0.88,
            top + height * 0.57,
        )
        brushes.signal.dots.dot(left + width * 0.51, top + height * 0.48, DETAIL_DOT)
    }

    private fun bucketWheelCrawler(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val wheelX = left + width * (0.26 + feature.variant * 0.06)
        val wheelY = top + height * 0.66
        val radius = height * (0.23 + feature.variant * 0.025)
        brushes.body.rects.rect(left + width * 0.34, top + height * 0.54, width * 0.53, height * 0.30)
        brushes.body.dots.dot(wheelX, wheelY, radius)
        brushes.body.triangles.triangle(
            left + width * 0.46,
            top + height * 0.54,
            left + width * (0.72 + feature.variant * 0.04),
            top + height * 0.24,
            left + width * 0.82,
            top + height * 0.54,
        )
        brushes.body.rects.rect(left + width * 0.40, top + height * 0.84, width * 0.48, height * 0.12)
        repeat(6) { index ->
            val endX = wheelX + radius * WHEEL_X[index]
            val endY = wheelY + radius * WHEEL_Y[index]
            brushes.facet.segments.segment(wheelX, wheelY, endX, endY)
        }
        brushes.facet.segments.segment(
            left + width * 0.50,
            top + height * 0.54,
            left + width * (0.86 - feature.variant * 0.05),
            top + height * (0.16 + feature.variant * 0.07),
        )
        brushes.facet.rects.rect(left + width * 0.58, top + height * 0.61, width * 0.13, height * 0.10)
        brushes.signal.dots.dot(left + width * 0.66, top + height * 0.63, DETAIL_DOT)
        brushes.shadow.segments.segment(left + width * 0.18, bottom, left + width * 0.92, bottom)
    }

    private fun breachedFloodgate(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val gap = 0.13 + feature.variant * 0.045
        val leftTowerWidth = width * (0.28 + feature.variant * 0.03)
        val rightTowerX = left + width * (0.58 + feature.variant * 0.035)
        brushes.body.rects.rect(left + width * 0.05, top + height * 0.18, leftTowerWidth, height * 0.82)
        brushes.body.rects.rect(rightTowerX, top + height * 0.08, width * 0.31, height * 0.92)
        brushes.body.rects.rect(left, top + height * 0.67, width * (0.44 - gap), height * 0.18)
        brushes.body.rects.rect(rightTowerX + width * gap, top + height * 0.58, width * 0.36, height * 0.17)
        brushes.body.triangles.triangle(
            left + width * 0.05,
            top + height * 0.18,
            left + width * 0.21,
            top,
            left + width * 0.34,
            top + height * 0.18,
        )
        repeat(4) { index ->
            val y = top + height * (0.30 + index * 0.14)
            brushes.facet.segments.segment(left + width * 0.10, y, left + width * 0.30, y + height * 0.04)
        }
        brushes.facet.segments.segment(rightTowerX, top + height * 0.30, left + width * 0.88, bottom)
        brushes.facet.segments.segment(left + width * 0.38, bottom, rightTowerX, top + height * 0.62)
        brushes.signal.dots.dot(rightTowerX + width * 0.15, top + height * 0.24, DETAIL_DOT)
    }

    private fun overgrownCoolingTree(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val centre = left + width * (0.46 + feature.variant * 0.04)
        brushes.body.rects.rect(centre - width * 0.07, top + height * 0.26, width * 0.14, height * 0.74)
        brushes.body.triangles.triangle(
            left + width * 0.22,
            bottom,
            centre,
            top + height * 0.38,
            left + width * 0.76,
            bottom,
        )
        repeat(5 + feature.variant) { index ->
            val crownX = left + width * (0.12 + index * 0.14)
            val crownY = top + height * (0.16 + index % 3 * 0.09)
            brushes.body.dots.dot(crownX, crownY, height * (0.14 + index % 2 * 0.035))
        }
        repeat(5) { index ->
            val side = if (index % 2 == 0) -1.0 else 1.0
            brushes.facet.segments.segment(
                centre,
                top + height * (0.38 + index * 0.09),
                centre + side * width * (0.18 + index * 0.025),
                top + height * (0.14 + index * 0.05),
            )
        }
        brushes.facet.segments.segment(left + width * 0.30, bottom, centre, top + height * 0.46)
        brushes.facet.segments.segment(left + width * 0.72, bottom, centre, top + height * 0.46)
        brushes.signal.dots.dot(left + width * 0.68, top + height * 0.20, DETAIL_DOT)
    }

    private fun crystalTransmissionCrown(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val shardCount = 5 + feature.variant
        repeat(shardCount) { index ->
            val section = width / shardCount
            val start = left + section * index
            val peakY = top + height * (0.04 + (index * 3 + feature.variant) % 5 * 0.09)
            brushes.body.triangles.triangle(start, bottom, start + section * 0.55, peakY, start + section, bottom)
            brushes.facet.segments.segment(start + section * 0.55, peakY, start + section * 0.70, bottom)
        }
        val mastX = left + width * (0.40 + feature.variant * 0.09)
        brushes.body.rects.rect(mastX, top + height * 0.18, width * 0.035, height * 0.75)
        brushes.facet.segments.segment(mastX, top + height * 0.30, left + width * 0.18, top + height * 0.55)
        brushes.facet.segments.segment(mastX, top + height * 0.43, left + width * 0.83, top + height * 0.65)
        brushes.signal.dots.dot(mastX, top + height * 0.18, DETAIL_DOT)
    }

    private fun refineryCaldera(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val breach = 0.40 + feature.variant * 0.07
        brushes.body.triangles.triangle(
            left,
            bottom,
            left + width * 0.23,
            top + height * 0.18,
            left + width * breach,
            bottom,
        )
        brushes.body.triangles.triangle(
            left + width * (breach + 0.08),
            bottom,
            left + width * 0.78,
            top + height * (0.08 + feature.variant * 0.06),
            left + width,
            bottom,
        )
        brushes.body.rects.rect(left + width * 0.24, top + height * 0.78, width * 0.55, height * 0.18)
        repeat(1 + feature.variant) { index ->
            brushes.body.dots.dot(
                left + width * (0.36 + index * 0.13),
                top + height * 0.72,
                height * 0.10,
            )
        }
        val stackX = left + width * (0.64 - feature.variant * 0.08)
        brushes.body.rects.rect(stackX, top + height * 0.31, width * 0.045, height * 0.48)
        repeat(4) { index ->
            brushes.facet.segments.segment(
                left + width * 0.25,
                top + height * (0.77 + index * 0.045),
                left + width * 0.78,
                top + height * (0.72 + index * 0.055),
            )
        }
        brushes.facet.segments.segment(left + width * 0.18, bottom, stackX, top + height * 0.64)
        brushes.signal.dots.dot(stackX + width * 0.022, top + height * 0.28, DETAIL_DOT * 1.2)
    }

    private fun glacialDataMountain(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val peakCount = 2 + feature.variant
        repeat(peakCount) { index ->
            val section = width / peakCount
            val start = left + section * index
            val peakX = start + section * (0.38 + index % 2 * 0.20)
            val peakY = top + height * (index % 3 * 0.11)
            brushes.body.triangles.triangle(start, bottom, peakX, peakY, start + section, bottom)
            brushes.facet.triangles.triangle(
                peakX - section * 0.10,
                peakY + height * 0.18,
                peakX,
                peakY,
                peakX + section * 0.13,
                peakY + height * 0.22,
            )
        }
        repeat(3) { index ->
            val x = left + width * (0.34 + index * 0.14)
            brushes.body.rects.rect(x, top + height * (0.56 + index % 2 * 0.08), width * 0.08, height * 0.36)
            brushes.facet.segments.segment(x, top + height * 0.66, x + width * 0.08, top + height * 0.66)
        }
        brushes.facet.segments.segment(
            left + width * 0.12,
            top + height * 0.76,
            left + width * 0.88,
            top + height * 0.58,
        )
        brushes.signal.dots.dot(left + width * 0.49, top + height * 0.62, DETAIL_DOT)
    }

    private fun severedSkybridge(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val breakLeft = 0.39 + feature.variant * 0.045
        val breakRight = 0.61 + feature.variant * 0.025
        brushes.body.rects.rect(left + width * 0.09, top + height * 0.24, width * 0.10, height * 0.76)
        brushes.body.rects.rect(
            left + width * 0.81,
            top + height * (0.14 + feature.variant * 0.05),
            width * 0.10,
            height * (0.86 - feature.variant * 0.05),
        )
        brushes.body.rects.rect(left, top + height * 0.35, width * breakLeft, height * 0.12)
        brushes.body.rects.rect(
            left + width * breakRight,
            top + height * 0.43,
            width * (1.0 - breakRight),
            height * 0.12,
        )
        brushes.body.triangles.triangle(
            left + width * 0.10,
            bottom,
            left + width * 0.15,
            top + height * 0.18,
            left + width * 0.22,
            bottom,
        )
        repeat(5) { index ->
            val fromX = left + width * (0.14 + index * 0.17)
            val fromY = top + height * (0.36 + index % 2 * 0.07)
            brushes.facet.segments.segment(
                fromX,
                fromY,
                fromX + width * 0.08,
                top + height * (0.64 + index * 0.035),
            )
        }
        brushes.facet.segments.segment(
            left + width * breakLeft,
            top + height * 0.41,
            left + width * 0.50,
            top + height * (0.58 + feature.variant * 0.06),
        )
        brushes.facet.segments.segment(
            left + width * 0.50,
            top + height * (0.58 + feature.variant * 0.06),
            left + width * breakRight,
            top + height * 0.49,
        )
        brushes.signal.dots.dot(left + width * 0.14, top + height * 0.21, DETAIL_DOT)
        brushes.signal.dots.dot(left + width * 0.86, top + height * (0.11 + feature.variant * 0.05), DETAIL_DOT)
    }

    private fun rupturedReactorCrater(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val shellX = left + width * (0.44 + feature.variant * 0.05)
        brushes.body.triangles.triangle(
            left,
            bottom,
            left + width * 0.19,
            top + height * 0.34,
            left + width * 0.43,
            bottom,
        )
        brushes.body.triangles.triangle(
            left + width * 0.56,
            bottom,
            left + width * 0.82,
            top + height * 0.24,
            left + width,
            bottom,
        )
        brushes.body.rects.rect(left + width * 0.18, top + height * 0.82, width * 0.67, height * 0.16)
        brushes.body.dots.dot(shellX, top + height * 0.64, height * (0.19 + feature.variant * 0.025))
        brushes.body.rects.rect(
            left + width * (0.66 - feature.variant * 0.04),
            top + height * 0.42,
            width * 0.045,
            height * 0.42,
        )
        repeat(4) { index ->
            val angleX = if (index < 2) -1.0 else 1.0
            val angleY = if (index % 2 == 0) -1.0 else 1.0
            brushes.facet.segments.segment(
                shellX,
                top + height * 0.64,
                shellX + angleX * width * (0.12 + feature.variant * 0.02),
                top + height * 0.64 + angleY * height * 0.16,
            )
        }
        brushes.facet.segments.segment(left + width * 0.12, bottom, shellX, top + height * 0.70)
        brushes.facet.segments.segment(shellX, top + height * 0.70, left + width * 0.92, bottom)
        brushes.signal.dots.dot(shellX, top + height * 0.64, DETAIL_DOT * 1.2)
    }

    private fun escarpmentVault(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val vaultLeft = left + width * (0.24 + feature.variant * 0.035)
        brushes.body.rects.rect(left + width * 0.08, top + height * 0.42, width * 0.84, height * 0.58)
        brushes.body.triangles.triangle(
            left,
            bottom,
            left + width * 0.16,
            top + height * 0.20,
            left + width * 0.30,
            bottom,
        )
        brushes.body.rects.rect(vaultLeft, top + height * 0.31, width * 0.53, height * 0.60)
        brushes.body.triangles.triangle(
            vaultLeft,
            top + height * 0.31,
            vaultLeft + width * 0.26,
            top + height * (0.04 + feature.variant * 0.05),
            vaultLeft + width * 0.53,
            top + height * 0.31,
        )
        repeat(2 + feature.variant) { index ->
            val x = left + width * (0.14 + index * 0.17)
            brushes.body.rects.rect(x, top + height * 0.72, width * 0.055, height * 0.28)
        }
        brushes.shadow.rects.rect(
            vaultLeft + width * 0.20,
            top + height * 0.58,
            width * 0.13,
            height * 0.33,
        )
        repeat(4) { index ->
            val x = vaultLeft + width * (0.07 + index * 0.13)
            brushes.facet.segments.segment(x, top + height * 0.38, x, top + height * 0.85)
        }
        brushes.facet.segments.segment(
            left + width * 0.10,
            top + height * 0.68,
            left + width * 0.90,
            top + height * 0.63,
        )
        brushes.signal.dots.dot(vaultLeft + width * 0.265, top + height * 0.55, DETAIL_DOT)
    }

    private fun paintSecondary(
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
                brushes.body.rects.rect(
                    left + width * 0.22,
                    top + height * 0.22,
                    width * 0.56,
                    height * 0.78,
                )
                brushes.body.triangles.triangle(
                    left,
                    bottom,
                    left + width * 0.28,
                    top + height * 0.18,
                    centre,
                    bottom,
                )
                brushes.body.triangles.triangle(
                    centre,
                    bottom,
                    left + width * 0.72,
                    top + height * 0.18,
                    left + width,
                    bottom,
                )
                brushes.facet.segments.segment(
                    left + width * 0.20,
                    top + height * 0.64,
                    left + width * 0.80,
                    top + height * 0.57,
                )
            }

            DistantMotif.MegastructureRib -> {
                brushes.body.rects.rect(left, top + height * 0.88, width, height * 0.12)
                repeat(4) { index ->
                    val x = left + width * (0.12 + index * 0.25)
                    brushes.body.segments.segment(
                        x,
                        bottom,
                        centre,
                        top + height * (0.12 + index % 2 * 0.14),
                    )
                }
            }

            DistantMotif.SandDune -> {
                brushes.body.triangles.triangle(
                    left,
                    bottom,
                    left + width * 0.40,
                    top,
                    left + width * 0.72,
                    bottom,
                )
                brushes.body.triangles.triangle(
                    left + width * 0.38,
                    bottom,
                    left + width * 0.76,
                    top + height * 0.26,
                    left + width,
                    bottom,
                )
                brushes.facet.segments.segment(
                    left + width * 0.12,
                    top + height * 0.78,
                    left + width * 0.84,
                    top + height * 0.68,
                )
            }

            DistantMotif.SlagHeap,
            DistantMotif.VolcanicRidge,
            DistantMotif.FusedRidge,
            -> {
                jaggedRidge(left, top, width, height, brushes.body.triangles)
                brushes.facet.segments.segment(
                    left + width * 0.12,
                    top + height * 0.74,
                    left + width * 0.88,
                    top + height * 0.62,
                )
            }

            DistantMotif.Floodplain -> {
                brushes.shadow.rects.rect(left, bottom - height * 0.34, width, height * 0.34)
                brushes.body.rects.rect(left, bottom - height * 0.18, width, height * 0.18)
                brushes.facet.segments.segment(
                    left,
                    bottom - height * 0.19,
                    left + width * 0.44,
                    bottom - height * 0.23,
                )
                brushes.facet.segments.segment(
                    left + width * 0.58,
                    bottom - height * 0.16,
                    left + width,
                    bottom - height * 0.20,
                )
            }

            DistantMotif.StormWall -> {
                brushes.body.rects.rect(left, top + height * 0.35, width, height * 0.65)
                repeat(3) { index ->
                    brushes.body.dots.dot(left + width * (0.2 + index * 0.3), top + height * 0.35, height * 0.28)
                }
            }

            DistantMotif.WildCanopy -> {
                brushes.body.rects.rect(left, top + height * 0.46, width, height * 0.54)
                repeat(5) { index ->
                    brushes.body.dots.dot(
                        left + width * (0.1 + index * 0.2),
                        top + height * (0.35 + index % 2 * 0.12),
                        height * 0.26,
                    )
                }
                brushes.facet.segments.segment(
                    left + width * 0.08,
                    top + height * 0.65,
                    left + width * 0.92,
                    top + height * 0.60,
                )
            }

            DistantMotif.DeadTrunk -> {
                brushes.body.rects.rect(centre - width * 0.08, top + height * 0.16, width * 0.16, height * 0.84)
                brushes.body.segments.segment(centre, top + height * 0.38, left + width * 0.14, top + height * 0.10)
                brushes.body.segments.segment(centre, top + height * 0.52, left + width * 0.86, top + height * 0.20)
                brushes.facet.segments.segment(centre, top + height * 0.30, centre - width * 0.02, bottom)
            }

            DistantMotif.CrystalOutcrop -> {
                crystalRange(left, top, width, height, brushes.body.triangles)
                repeat(3) { index ->
                    val x = left + width * (0.18 + index * 0.28)
                    brushes.facet.segments.segment(x, top + height * 0.30, x + width * 0.08, bottom)
                }
            }

            DistantMotif.SmogBank,
            DistantMotif.CloudOcean,
            -> repeat(4) { index ->
                brushes.body.dots.dot(
                    left + width * (0.12 + index * 0.25),
                    top + height * (0.56 + index % 2 * 0.12),
                    height * (0.28 + (feature.variant + index) % 2 * 0.08),
                )
            }

            DistantMotif.Caldera -> {
                brushes.body.triangles.triangle(left, bottom, left + width * 0.30, top, centre, bottom)
                brushes.body.triangles.triangle(centre, bottom, left + width * 0.70, top, left + width, bottom)
                brushes.body.rects.rect(left + width * 0.30, top + height * 0.86, width * 0.40, height * 0.14)
                brushes.facet.segments.segment(
                    left + width * 0.28,
                    top + height * 0.84,
                    left + width * 0.72,
                    top + height * 0.84,
                )
            }

            DistantMotif.Snowfield,
            DistantMotif.AshTundra,
            DistantMotif.FrozenFlat,
            -> {
                brushes.body.rects.rect(left, top + height * 0.76, width, height * 0.24)
                brushes.body.triangles.triangle(
                    left,
                    top + height * 0.82,
                    centre,
                    top + height * 0.48,
                    left + width,
                    top + height * 0.82,
                )
                brushes.facet.segments.segment(left, top + height * 0.76, left + width, top + height * 0.82)
            }

            DistantMotif.GlacialMountain,
            DistantMotif.DistantPeak,
            -> {
                brushes.body.triangles.triangle(left, bottom, centre, top, left + width, bottom)
                brushes.facet.triangles.triangle(
                    centre - width * 0.09,
                    top + height * 0.18,
                    centre,
                    top,
                    centre + width * 0.07,
                    top + height * 0.14,
                )
                brushes.facet.segments.segment(
                    left + width * 0.18,
                    top + height * 0.76,
                    centre,
                    top + height * 0.28,
                )
            }

            DistantMotif.BrokenSkybridge -> {
                brushes.body.rects.rect(left, top + height * 0.35, width * 0.42, height * 0.16)
                brushes.body.rects.rect(left + width * 0.58, top + height * 0.47, width * 0.42, height * 0.16)
                brushes.facet.segments.segment(
                    left + width * 0.42,
                    top + height * 0.43,
                    centre,
                    top + height * 0.62,
                )
                brushes.facet.segments.segment(
                    centre,
                    top + height * 0.62,
                    left + width * 0.58,
                    top + height * 0.55,
                )
            }

            DistantMotif.BlastCrater -> {
                brushes.body.triangles.triangle(left, bottom, left + width * 0.22, top, centre, bottom)
                brushes.body.triangles.triangle(centre, bottom, left + width * 0.78, top, left + width, bottom)
                brushes.body.rects.rect(left + width * 0.20, top + height * 0.82, width * 0.60, height * 0.18)
                brushes.facet.segments.segment(
                    left + width * 0.18,
                    top + height * 0.80,
                    left + width * 0.82,
                    top + height * 0.80,
                )
            }

            DistantMotif.Escarpment -> {
                brushes.body.rects.rect(left + width * 0.18, top + height * 0.18, width * 0.72, height * 0.82)
                brushes.body.triangles.triangle(
                    left,
                    bottom,
                    left + width * 0.18,
                    top + height * 0.18,
                    left + width * 0.18,
                    bottom,
                )
                brushes.facet.segments.segment(
                    left + width * 0.24,
                    top + height * 0.34,
                    left + width * 0.82,
                    top + height * 0.34,
                )
            }

            DistantMotif.VaultMass -> {
                brushes.body.rects.rect(left + width * 0.12, top + height * 0.36, width * 0.76, height * 0.64)
                brushes.body.triangles.triangle(
                    left + width * 0.12,
                    top + height * 0.36,
                    centre,
                    top,
                    left + width * 0.88,
                    top + height * 0.36,
                )
                brushes.facet.rects.rect(centre - width * 0.07, top + height * 0.48, width * 0.14, height * 0.42)
                brushes.facet.dots.dot(centre, top + height * 0.48, DETAIL_DOT)
            }

            else -> error("${feature.motif} is not a secondary mass")
        }
    }

    private fun paintDetail(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        brushes: Brushes,
    ) {
        val bottom = top + height
        val centre = left + width / 2.0
        val family = feature.motif.ordinal % 4
        when (family) {
            0 -> {
                brushes.facet.segments.segment(centre, bottom, centre, top)
                brushes.facet.segments.segment(centre, top + height * 0.36, left + width, top)
            }

            1 -> {
                brushes.facet.rects.rect(centre - width * 0.14, top + height * 0.18, width * 0.28, height * 0.82)
                brushes.facet.segments.segment(left, top + height * 0.48, left + width, top + height * 0.48)
            }

            2 -> {
                brushes.facet.triangles.triangle(left, bottom, centre, top, left + width, bottom)
                brushes.facet.segments.segment(centre, top, centre, bottom)
            }

            else -> {
                brushes.facet.segments.segment(left, bottom, centre, top)
                brushes.facet.segments.segment(centre, top, left + width, bottom)
            }
        }
    }

    private fun paintTrace(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        brushes: Brushes,
    ) {
        val bend = (feature.variant - 1.5) * TEXTURE_BEND
        brushes.facet.segments.segment(left, top, left + width * 0.48, top + bend)
        brushes.facet.segments.segment(left + width * 0.48, top + bend, left + width, top - bend * 0.5)
    }

    private fun paintAtmosphere(
        feature: DistantFeature,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        shadow: Brush,
    ) {
        when (feature.motif) {
            DistantMotif.RainCurtain,
            DistantMotif.RainShaft,
            DistantMotif.LightPillar,
            -> repeat(4) { index ->
                val x = left + width * (0.12 + index * 0.23)
                shadow.segments.segment(x, top, x + width * 0.05, top + height)
            }

            DistantMotif.SporeBand,
            DistantMotif.AshPlume,
            DistantMotif.FalloutPlume,
            -> {
                repeat(3) { index ->
                    shadow.dots.dot(
                        left + width * (0.22 + index * 0.28),
                        top + height * (0.48 + index % 2 * 0.15),
                        height * (0.20 + index * 0.04),
                    )
                }
                shadow.segments.segment(left, top + height * 0.72, left + width, top + height * 0.58)
            }

            else -> repeat(3) { index ->
                val y = top + height * (0.30 + index * 0.24)
                val drift = (feature.variant + index) % 3 * width * 0.04
                shadow.segments.segment(
                    left + drift,
                    y,
                    left + width * (0.72 + index * 0.10),
                    y + height * 0.08,
                )
            }
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

    private data class Brushes(
        val shadow: Brush,
        val body: Brush,
        val facet: Brush,
        val signal: Brush,
    )

    private data class Brush(
        val rects: DrawBatch,
        val triangles: DrawBatch,
        val segments: DrawBatch,
        val dots: DrawBatch,
    )

    private const val DETAIL_DOT = 1.5
    private const val TEXTURE_BEND = 0.8
    private val WHEEL_X = doubleArrayOf(1.0, 0.5, -0.5, -1.0, -0.5, 0.5)
    private val WHEEL_Y = doubleArrayOf(0.0, 0.87, 0.87, 0.0, -0.87, -0.87)
}
