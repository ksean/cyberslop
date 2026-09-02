package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.Anchor
import io.github.ksean.cyberslop.combat.MeleeSector
import io.github.ksean.cyberslop.combat.WeaponClass
import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.AttackVisual
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.progression.DiscoveryEntry
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.HitShape
import io.github.ksean.cyberslop.sim.EnemyLeap
import io.github.ksean.cyberslop.sim.LiveBoss
import io.github.ksean.cyberslop.sim.LiveEnemy
import io.github.ksean.cyberslop.sim.MuzzleFlash
import io.github.ksean.cyberslop.sim.PlayerDeathEffect
import io.github.ksean.cyberslop.sim.SwingVisual
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap

/**
 * Turns one tick of the game into one frame of drawing (ENG-060).
 *
 * Everything about what a frame looks like is decided here, in `commonMain`, where a test can read
 * it without a browser. The browser layer receives a [DrawList] and issues primitives; it makes no
 * decision and holds no rule.
 *
 * The shape of the output is what ENG-061 rests on. Nothing is drawn entity by entity: a caller
 * takes a batch handle for a style once and pushes into it, so a frame costs one style assignment
 * per batch however many things are in it. Limbs are stroked segments rather than rotated
 * rectangles, so the per-sprite transform `specs/presentation.md` measured at 7.61x a bare draw does not
 * exist to be slow.
 */
object Scene {
    /**
     * World pixels to screen pixels.
     *
     * The game drew 1:1, which put a 26 px character on a 900 px screen — at that size an animated
     * figure is a moving dot and none of this work would be visible. The camera's view is measured
     * in world units, so zooming is this factor and a smaller view rectangle, and nothing about
     * following or clamping changes.
     */
    const val ZOOM = 3.5

    /**
     * Half the width of a Street-tier drop, in **screen** pixels.
     *
     * Screen rather than world: the pickup draw multiplies the position by [ZOOM] and then uses this
     * directly, so the 5.0 it held while a drop was a rectangle produced a 10 px wide bar — a fifth
     * of a tile. An icon has to be read, not merely noticed, so it is sized against the tile it lies
     * on: a tile is 16 world pixels and therefore 56 on screen, and a drop now spans 28 px at Street
     * to 53 px at Ascended.
     */
    const val PICKUP_PX = 14.0

    /** How far a drop hovers above and below its resting position, in screen px (PROD-079). */
    const val HOVER_PX = 4.0

    /** One rise and fall of a hovering drop, in seconds. */
    const val HOVER_PERIOD = 1.8

    /**
     * Snaps a stroke width onto a bounded ladder.
     *
     * A batch is one `beginPath`/`stroke` pair, so its width is part of its identity — and a
     * continuous width would give almost every limb its own batch, which is the same trap a
     * continuous enemy luminance would be. Fourteen steps cover a 26 px player's forearm to a boss's
     * torso; the largest snap is under a fifth of the width, which is nothing at a limb's size.
     */
    val strokeLadderSize: Int get() = STROKE_LADDER.size

    fun strokeWidth(raw: Double): Double {
        var best = STROKE_LADDER[0]
        var bestGap = if (raw > best) raw - best else best - raw
        for (index in 1 until STROKE_LADDER.size) {
            val candidate = STROKE_LADDER[index]
            val gap = if (raw > candidate) raw - candidate else candidate - raw
            if (gap < bestGap) { best = candidate; bestGap = gap }
        }
        return best
    }

    fun compose(
        sim: GameSimulation,
        camera: Camera,
        backdrop: Backdrop,
        hud: HudModel,
        timeSeconds: Double,
        builder: SceneBuilder,
        /**
         * How far this frame sits between the last tick and the current one.
         *
         * The loop interpolates the camera's target but the figure was drawn at the raw tick
         * position, so the player slid a few pixels against a camera that had already moved —
         * visible as jitter at every frame that was not exactly on a tick boundary. Both now read
         * the same position.
         */
        alpha: Double = 1.0,
        /** Draws the corridor mask over the world. Development only. */
        debugMasks: Boolean = false,
        /** First-pickup overlay; when present it replaces the ordinary HUD for this frame. */
        discovery: DiscoveryEntry? = null,
    ): DrawList {
        builder.begin()
        val palette = Palettes.of(sim.level.theme)
        val width = camera.viewWidth * ZOOM
        val height = camera.viewHeight * ZOOM

        sky(builder, palette, width, height)
        skyline(builder, palette, backdrop, camera, width, height)
        val presentationTime = presentationTime(timeSeconds, alpha)
        tiles(builder, palette, sim.level, camera, presentationTime)
        arenas(builder, palette, sim.level, camera)
        jets(builder, palette, sim.level, camera, timeSeconds, presentationTime)
        pickups(builder, sim, camera, presentationTime)
        enemies(builder, palette, sim, camera, presentationTime)
        bosses(builder, palette, sim, camera)
        projectiles(builder, palette, sim, camera)
        hitIndicator(builder, palette, sim, camera)
        // Both the arc and the figure hang off one interpolated position, or the swing sits ahead
        // of the hand that threw it by a tick of travel.
        val muzzle = drawnMuzzle(sim, alpha)
        if (sim.deathSequence == null) swing(builder, palette, sim, camera, muzzle)
        player(builder, palette, sim, camera, muzzle)
        scrapGains(builder, sim, camera, alpha)
        if (debugMasks) masks(builder, sim.level, camera)
        if (discovery == null) {
            hud(builder, palette, hud, width, height)
        } else {
            discoveryCard(builder, palette, discovery, width, height)
        }

        return builder.build()
    }

    /** Floating world-space feedback for every positive active-gameplay Scrap award (PROD-086). */
    private fun scrapGains(
        builder: SceneBuilder,
        sim: GameSimulation,
        camera: Camera,
        alpha: Double,
    ) {
        sim.scrapGains.forEach { gain ->
            val secondsLeft = gain.previousSecondsLeft +
                (gain.secondsLeft - gain.previousSecondsLeft) * alpha.coerceIn(0.0, 1.0)
            val opacity = (secondsLeft / GameSimulation.SCRAP_GAIN_SECONDS).coerceIn(0.0, 1.0)
            val progress = 1.0 - opacity
            builder.text(
                TextItem(
                    text = "+${gain.amount}",
                    x = (gain.origin.x - camera.x) * ZOOM,
                    y = (gain.origin.y - camera.y) * ZOOM - GameSimulation.SCRAP_GAIN_RISE_PX * progress,
                    sizePx = SCRAP_GAIN_SIZE,
                    style = SCRAP_GAIN_GOLD,
                    align = TextAlign.Centre,
                    bold = true,
                    opacity = opacity,
                ),
            )
        }
    }

    // ---- world ----------------------------------------------------------------------------

    private fun sky(builder: SceneBuilder, palette: Palette, width: Double, height: Double) {
        builder.batch(Layer.Sky, palette.sky, Primitive.Rect).rect(0.0, 0.0, width, height)
        // A band rather than a gradient: a gradient is a browser object, and a horizon is what the
        // eye actually reads here.
        val horizon = height * BACKDROP_HORIZON
        builder.batch(Layer.Sky, palette.skyLow, Primitive.Rect)
            .rect(0.0, horizon, width, height - horizon)
    }

    private fun skyline(
        builder: SceneBuilder,
        palette: Palette,
        backdrop: Backdrop,
        camera: Camera,
        width: Double,
        height: Double,
    ) {
        backdrop.layers.forEach { layer ->
            val bodyRects = builder.batch(layer.layer, layer.tint, Primitive.Rect)
            val bodySegments = builder.batch(
                layer.layer,
                layer.tint,
                Primitive.Segment,
                BACKDROP_STRUCTURE_STROKE,
            )
            val bodyDots = builder.batch(layer.layer, layer.tint, Primitive.Dot)
            // All light batches open after all body batches, so fixtures remain visible over their
            // own structure and a near tower still occludes a far fixture.
            val lightRects = builder.batch(layer.layer, palette.window, Primitive.Rect)
            val lightSegments = builder.batch(
                layer.layer,
                palette.window,
                Primitive.Segment,
                BACKDROP_LIGHT_STROKE,
            )
            val lightDots = builder.batch(layer.layer, palette.window, Primitive.Dot)
            val offset = camera.x * layer.parallax * ZOOM
            // Vertical parallax, anchored to the height the horizon fraction was calibrated at.
            val horizon = height * backdrop.horizonFraction +
                verticalDrift(backdrop, camera, layer.parallax, height)
            layer.buildings.forEach { building ->
                val x = building.x * ZOOM - offset
                val drawWidth = building.width * ZOOM
                if (x + drawWidth < 0.0 || x > width) return@forEach

                val drawHeight = building.height * ZOOM
                val top = horizon - drawHeight
                bodyRects.rect(x, top, drawWidth, drawHeight)
                backdropRoof(building.roof, x, top, drawWidth, drawHeight, bodyRects, bodySegments)

                val cellWidth = drawWidth / building.windowColumns
                val cellHeight = drawHeight / building.windowRows
                for (column in 0 until building.windowColumns) {
                    for (row in 0 until building.windowRows) {
                        if (!building.hasWindow(column, row)) continue
                        backdropWindow(
                            building.windowLayout,
                            x + cellWidth * column,
                            top + cellHeight * row,
                            cellWidth,
                            cellHeight,
                            lightRects,
                        )
                    }
                }
                building.features.forEach { feature ->
                    backdropFeature(
                        feature = feature,
                        x = x,
                        top = top,
                        width = drawWidth,
                        height = drawHeight,
                        bodyRects = bodyRects,
                        bodySegments = bodySegments,
                        bodyDots = bodyDots,
                        lightRects = lightRects,
                        lightSegments = lightSegments,
                        lightDots = lightDots,
                    )
                }
            }
        }
        val haze = height * backdrop.horizonFraction +
            verticalDrift(backdrop, camera, backdrop.layers.last().parallax, height)
        builder.batch(Layer.Haze, palette.haze, Primitive.Rect)
            .rect(0.0, haze - HAZE_PX, width, HAZE_PX * 2.0)
    }

    private fun backdropWindow(
        layout: BackdropWindows,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        lights: DrawBatch,
    ) {
        val insetX: Double
        val insetY: Double
        val fillWidth: Double
        val fillHeight: Double
        when (layout) {
            BackdropWindows.Grid -> {
                insetX = WINDOW_INSET
                insetY = WINDOW_INSET
                fillWidth = WINDOW_FILL
                fillHeight = WINDOW_FILL
            }

            BackdropWindows.Bands -> {
                insetX = 0.14
                insetY = 0.40
                fillWidth = 0.72
                fillHeight = 0.18
            }

            BackdropWindows.Columns -> {
                insetX = 0.40
                insetY = 0.12
                fillWidth = 0.18
                fillHeight = 0.72
            }

            BackdropWindows.Sparse -> {
                insetX = 0.38
                insetY = 0.38
                fillWidth = 0.24
                fillHeight = 0.24
            }
        }
        lights.rect(
            x + width * insetX,
            y + height * insetY,
            width * fillWidth,
            height * fillHeight,
        )
    }

    private fun backdropRoof(
        roof: BackdropRoof,
        x: Double,
        top: Double,
        width: Double,
        height: Double,
        rects: DrawBatch,
        segments: DrawBatch,
    ) {
        val rise = minOf(width, height) * BACKDROP_ROOF_SCALE
        when (roof) {
            BackdropRoof.Flat -> Unit
            BackdropRoof.Broken -> {
                rects.rect(x, top - rise * 0.42, width * 0.38, rise * 0.42)
                rects.rect(x + width * 0.70, top - rise * 0.18, width * 0.30, rise * 0.18)
                segments.segment(x + width * 0.38, top - rise * 0.42, x + width * 0.48, top)
            }

            BackdropRoof.Stepped -> {
                rects.rect(x + width * 0.12, top - rise * 0.55, width * 0.76, rise * 0.55)
                rects.rect(x + width * 0.32, top - rise, width * 0.36, rise * 0.45)
            }

            BackdropRoof.Sawtooth -> repeat(3) { index ->
                val left = x + width * index / 3.0
                val right = x + width * (index + 1) / 3.0
                segments.segment(left, top, (left + right) / 2.0, top - rise * 0.55)
                segments.segment((left + right) / 2.0, top - rise * 0.55, right, top)
            }

            BackdropRoof.Crowned -> {
                rects.rect(x + width * 0.16, top - rise * 0.45, width * 0.68, rise * 0.45)
                rects.rect(x + width * 0.36, top - rise, width * 0.28, rise * 0.55)
            }

            BackdropRoof.Ribbed -> repeat(4) { index ->
                val ribWidth = width * 0.09
                val ribX = x + width * (0.18 + index * 0.18)
                rects.rect(ribX, top - rise * (0.45 + index % 2 * 0.25), ribWidth, rise)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun backdropFeature(
        feature: BackdropFeature,
        x: Double,
        top: Double,
        width: Double,
        height: Double,
        bodyRects: DrawBatch,
        bodySegments: DrawBatch,
        bodyDots: DrawBatch,
        lightRects: DrawBatch,
        lightSegments: DrawBatch,
        lightDots: DrawBatch,
    ) {
        val anchor = x + width * feature.anchor
        val unit = (minOf(width, height) * BACKDROP_FEATURE_SCALE * feature.scale)
            .coerceAtLeast(BACKDROP_FEATURE_MIN)
        val side = if (feature.variant % 2 == 0) 1.0 else -1.0
        when (feature.motif) {
            BackdropMotif.RoofDamage -> {
                bodySegments.segment(anchor - unit, top, anchor - unit * 0.35, top - unit * 1.4)
                bodySegments.segment(anchor - unit * 0.35, top - unit * 1.4, anchor, top - unit * 0.6)
                bodySegments.segment(anchor, top - unit * 0.6, anchor + unit, top - unit * 1.1)
            }

            BackdropMotif.Stack -> {
                bodyRects.rect(anchor - unit * 0.35, top - unit * 2.6, unit * 0.70, unit * 2.6)
                lightDots.dot(anchor, top - unit * 2.72, unit * 0.18)
            }

            BackdropMotif.Tank -> {
                bodyDots.dot(anchor, top - unit * 0.62, unit * 0.78)
                bodyRects.rect(anchor - unit * 0.78, top - unit * 0.62, unit * 1.56, unit * 0.62)
                lightSegments.segment(anchor - unit * 0.42, top - unit * 0.55, anchor + unit * 0.42, top - unit * 0.55)
            }

            BackdropMotif.Pipe -> {
                bodySegments.segment(anchor, top - unit * 1.8, anchor, top + height * 0.56)
                bodySegments.segment(anchor, top + height * 0.56, anchor + side * unit * 1.8, top + height * 0.56)
                lightDots.dot(anchor, top - unit * 1.82, unit * 0.15)
            }

            BackdropMotif.Gantry -> {
                bodySegments.segment(anchor - unit, top, anchor - unit, top - unit * 1.6)
                bodySegments.segment(anchor + unit, top, anchor + unit, top - unit * 1.6)
                bodySegments.segment(anchor - unit, top - unit * 1.6, anchor + unit, top - unit * 1.6)
                lightSegments.segment(anchor - unit * 0.65, top - unit * 1.43, anchor + unit * 0.65, top - unit * 1.43)
            }

            BackdropMotif.Cable -> {
                bodySegments.segment(anchor - unit * 2.0, top - unit, anchor, top - unit * 0.25)
                bodySegments.segment(anchor, top - unit * 0.25, anchor + unit * 2.0, top - unit * 1.2)
            }

            BackdropMotif.Antenna -> {
                bodySegments.segment(anchor, top, anchor, top - unit * 2.8)
                bodySegments.segment(anchor, top - unit * 2.1, anchor + side * unit * 0.8, top - unit * 2.45)
                lightDots.dot(anchor, top - unit * 2.9, unit * 0.16)
            }

            BackdropMotif.SignFrame -> {
                val left = anchor - unit
                val right = anchor + unit
                val signTop = top - unit * 1.8
                bodySegments.segment(left, top, left, signTop)
                bodySegments.segment(right, top, right, signTop)
                bodySegments.segment(left, signTop, right, signTop)
                bodySegments.segment(left, top - unit * 0.55, right, top - unit * 0.55)
                lightSegments.segment(
                    left + unit * 0.25,
                    signTop + unit * 0.35,
                    right - unit * 0.25,
                    signTop + unit * 0.35
                )
            }

            BackdropMotif.Buttress -> {
                bodySegments.segment(anchor, top - unit * 1.2, anchor - unit * 1.4, top + height)
                bodySegments.segment(anchor, top - unit * 1.2, anchor + unit * 1.4, top + height)
            }

            BackdropMotif.Vent -> repeat(3) { index ->
                val ventX = anchor + (index - 1) * unit * 0.65
                val ventHeight = unit * (0.55 + (feature.variant + index) % 3 * 0.22)
                bodyRects.rect(ventX - unit * 0.18, top - ventHeight, unit * 0.36, ventHeight)
                lightDots.dot(ventX, top - ventHeight, unit * 0.10)
            }

            BackdropMotif.BridgeFragment -> {
                val end = anchor + side * unit * 3.2
                bodySegments.segment(anchor, top - unit * 0.8, end, top - unit * 0.8)
                bodySegments.segment(anchor + side * unit * 0.8, top - unit * 0.8, end, top + unit * 0.2)
                lightDots.dot(end, top - unit * 0.8, unit * 0.14)
            }

            BackdropMotif.LightStrip -> {
                lightRects.rect(anchor - unit * 0.22, top + height * 0.18, unit * 0.44, height * 0.62)
                repeat(3) { index ->
                    lightDots.dot(anchor, top + height * (0.30 + index * 0.18), unit * 0.10)
                }
            }
        }
    }

    /**
     * How far the horizon slides when the camera changes height, damped and then bounded.
     *
     * Anchored to the height the horizon fraction was calibrated at — the player's spawn — so the
     * skyline sits where it was designed to sit at the start of a map and drifts from there.
     */
    private fun verticalDrift(
        backdrop: Backdrop,
        camera: Camera,
        parallax: Double,
        height: Double,
    ): Double {
        val drift = (backdrop.referenceY - camera.y) * parallax * VERTICAL_PARALLAX * ZOOM
        val limit = height * VERTICAL_LIMIT
        return drift.coerceIn(-limit, limit)
    }

    /**
     * Only the tiles inside the view, because a 720-tile map is 46,000 cells and all but a few
     * hundred are off screen — the rule the placeholder renderer measured its way into, kept.
     *
     * Solid tiles get a lit top edge wherever nothing sits above them. That single extra rectangle
     * is what turns a grid of squares into surfaces with a light direction.
     */
    private fun tiles(
        builder: SceneBuilder,
        palette: Palette,
        level: Level,
        camera: Camera,
        timeSeconds: Double,
    ) {
        val first = (TileMap.toTile(camera.x) - 1).coerceAtLeast(0)
        val last = (TileMap.toTile(camera.x + camera.viewWidth) + 1)
            .coerceAtMost(level.widthTiles - 1)
        val top = (TileMap.toTile(camera.y) - 1).coerceAtLeast(0)
        val bottom = (TileMap.toTile(camera.y + camera.viewHeight) + 1)
            .coerceAtMost(level.tiles.height - 1)

        val body = builder.batch(Layer.Terrain, palette.tileBody, Primitive.Rect)
        val deep = builder.batch(Layer.Terrain, palette.tileDeep, Primitive.Rect)
        val edge = builder.batch(Layer.Terrain, palette.tileEdge, Primitive.Rect)
        val exitEdge = builder.batch(Layer.Terrain, EXIT_SURFACE, Primitive.Rect)
        val exitSparkDots = builder.batch(Layer.HazardSurface, EXIT_SPARK, Primitive.Dot)
        val hazard = builder.batch(Layer.Hazard, palette.hazard, Primitive.Rect)
        val hazardGlow = builder.batch(Layer.Hazard, palette.hazardGlow, Primitive.Rect)
        val bubbleGlow = builder.batch(Layer.Hazard, palette.hazardGlow, Primitive.Dot)
        val bubbleBody = builder.batch(Layer.HazardSurface, palette.hazard, Primitive.Dot)
        val spikes = builder.batch(Layer.Hazard, palette.hazardGlow, Primitive.Segment, strokeWidth(STRIP_WIDTH))
        val glassShards = builder.batch(
            Layer.HazardSurface, GLASS_RUST, Primitive.Segment, strokeWidth(GLASS_WIDTH),
        )
        val glassCrumbs = builder.batch(Layer.HazardSurface, GLASS_EDGE, Primitive.Dot)
        val size = TILE_SIZE * ZOOM

        for (x in first..last) {
            for (y in top..bottom) {
                val screenX = (TileMap.toWorld(x) - camera.x) * ZOOM
                val screenY = (TileMap.toWorld(y) - camera.y) * ZOOM
                when (level.tiles[x, y]) {
                    TileKind.Solid -> {
                        body.rect(screenX, screenY, size, size)
                        if (!level.tiles.blocksMovement(x, y - 1)) {
                            if (x > level.gateColumn) {
                                exitEdge.rect(screenX, screenY, size, EDGE_PX)
                                exitSparks(exitSparkDots, screenX, screenY, size, x, y, timeSeconds)
                            } else {
                                edge.rect(screenX, screenY, size, EDGE_PX)
                            }
                        }
                        deep.rect(screenX, screenY + size - SEAM_PX, size, SEAM_PX)
                    }

                    TileKind.Acid -> {
                        hazard.rect(screenX, screenY, size, size)
                        if (level.tiles[x, y - 1] != TileKind.Acid) {
                            hazardGlow.rect(screenX, screenY, size, EDGE_PX)
                            acidBubbles(bubbleGlow, bubbleBody, screenX, screenY, size, x, y, timeSeconds)
                        }
                    }

                    // A row of points standing on a dark base: the spikes are the strokes, so the
                    // strip reads as something that cuts rather than as a coloured floor.
                    TileKind.Spikes -> {
                        val base = screenY + size
                        hazard.rect(screenX, base - STRIP_BASE_PX, size, STRIP_BASE_PX)
                        val pitch = size / STRIP_POINTS
                        for (n in 0 until STRIP_POINTS) {
                            val left = screenX + n * pitch
                            val tip = left + pitch / 2.0
                            spikes.segment(left, base, tip, screenY + size * STRIP_TOP)
                            spikes.segment(tip, screenY + size * STRIP_TOP, left + pitch, base)
                        }
                    }

                    TileKind.BrokenGlass -> brokenGlass(
                        glassShards, glassCrumbs, screenX, screenY, size, x, y,
                    )

                    else -> Unit
                }
            }
        }
        barrels(builder, palette, level, camera, first..last, timeSeconds)
    }

    /** Five low disconnected slashes and three crumbs, varied by coordinate but never by time. */
    private fun brokenGlass(
        shards: DrawBatch,
        crumbs: DrawBatch,
        screenX: Double,
        screenY: Double,
        size: Double,
        tileX: Int,
        tileY: Int,
    ) {
        val base = screenY + size
        for (index in GLASS_X_START.indices) {
            val phase = positiveMod(tileX * 7 + tileY * 11 + index * 5, 17) / 16.0 - 0.5
            val x1 = screenX + size * (GLASS_X_START[index] + phase * 0.025)
            val y1 = base - size * (GLASS_Y_START[index] + phase * 0.018)
            val x2 = screenX + size * (GLASS_X_END[index] - phase * 0.018)
            val y2 = base - size * (GLASS_Y_END[index] - phase * 0.015)
            shards.segment(x1, y1, x2, y2)
        }
        for (index in GLASS_CRUMB_X.indices) {
            val phase = positiveMod(tileX * 13 + tileY * 3 + index * 7, 19) / 18.0 - 0.5
            crumbs.dot(
                screenX + size * (GLASS_CRUMB_X[index] + phase * 0.02),
                base - size * (GLASS_CRUMB_Y[index] - phase * 0.015),
                GLASS_CRUMB_RADIUS,
            )
        }
    }

    private fun exitSparks(
        sparks: DrawBatch,
        screenX: Double,
        screenY: Double,
        size: Double,
        tileX: Int,
        tileY: Int,
        timeSeconds: Double,
    ) {
        repeat(EXIT_SPARK_COUNT) { index ->
            val stagger = positiveMod(
                tileX * EXIT_PHASE_X + tileY * EXIT_PHASE_Y + index * EXIT_PHASE_INDEX,
                EXIT_PHASE_STEPS,
            )
            val progress = (
                (timeSeconds % EXIT_SPARK_PERIOD) / EXIT_SPARK_PERIOD +
                    stagger.toDouble() / EXIT_PHASE_STEPS
                ) % 1.0
            val x = screenX + size * EXIT_SPARK_X[index]
            val y = screenY - EXIT_SPARK_RISE * progress
            sparks.dot(x, y, EXIT_SPARK_RADIUS * (1.0 - progress))
        }
    }

    /** Three deterministic rings rising through an exposed acid tile (P-58). */
    private fun acidBubbles(
        glow: DrawBatch,
        body: DrawBatch,
        screenX: Double,
        screenY: Double,
        size: Double,
        tileX: Int,
        tileY: Int,
        timeSeconds: Double,
    ) {
        for (index in BUBBLE_X.indices) {
            val phase = positiveRemainder(
                timeSeconds / BUBBLE_CYCLE +
                    (tileX * BUBBLE_PHASE_X + tileY * BUBBLE_PHASE_Y + index * BUBBLE_PHASE_INDEX)
                        .toDouble() / BUBBLE_PHASE_STEPS,
                1.0,
            )
            val x = screenX + size * BUBBLE_X[index]
            val y = screenY + size * BUBBLE_RISE * (1.0 - phase)
            val radius = BUBBLE_MIN_RADIUS + (BUBBLE_MAX_RADIUS - BUBBLE_MIN_RADIUS) * phase
            glow.dot(x, y, radius)
            body.dot(x, y, (radius - BUBBLE_RING).coerceAtLeast(BUBBLE_INNER_MIN))
        }
    }

    private fun positiveRemainder(value: Double, modulus: Double): Double {
        val remainder = value % modulus
        return if (remainder < 0.0) remainder + modulus else remainder
    }

    /** A burning barrel: a body in its floor cell and three wavy tongues in the cell above (P-73). */
    private fun barrels(
        builder: SceneBuilder,
        palette: Palette,
        level: Level,
        camera: Camera,
        visible: IntRange,
        timeSeconds: Double,
    ) {
        if (level.barrels.isEmpty()) return
        val size = TILE_SIZE * ZOOM
        val body = builder.batch(Layer.Hazard, palette.hazard, Primitive.Rect)
        val bands = builder.batch(Layer.Hazard, palette.tileEdge, Primitive.Rect)
        val outer = BARREL_OUTER_WIDTHS.map { width ->
            builder.batch(Layer.Hazard, FIRE_OUTER, Primitive.Segment, size * width)
        }
        val core = BARREL_CORE_WIDTHS.map { width ->
            builder.batch(Layer.Hazard, FIRE_CORE, Primitive.Segment, size * width)
        }
        level.barrels.forEach { barrel ->
            if (barrel.column !in visible) return@forEach
            val x = (TileMap.toWorld(barrel.column) - camera.x) * ZOOM
            val floor = (TileMap.toWorld(barrel.row) - camera.y) * ZOOM + size
            val width = size * DRUM_WIDTH
            val left = x + (size - width) / 2.0
            val top = floor - size * DRUM_HEIGHT
            body.rect(left, top, width, size * DRUM_HEIGHT)
            bands.rect(left, top + size * DRUM_HEIGHT * 0.3, width, BAND_PX)
            bands.rect(left, top + size * DRUM_HEIGHT * 0.7, width, BAND_PX)
            barrelFlame(outer, core, x + size / 2.0, top, size, timeSeconds, barrel)
        }
    }

    /** Three independently phased, open paths: no two sides meet to make the old spike. */
    private fun barrelFlame(
        outer: List<DrawBatch>,
        core: List<DrawBatch>,
        centreX: Double,
        lidY: Double,
        size: Double,
        timeSeconds: Double,
        barrel: Barrel,
    ) {
        for (tongue in BARREL_FLAME_LENGTHS.indices) {
            val points = barrelFlamePoints(outer, centreX, lidY, size, timeSeconds, barrel, tongue)
            for (segment in outer.indices) {
                val from = points[segment]
                val to = points[segment + 1]
                outer[segment].segment(from.x, from.y, to.x, to.y)
                core[segment].segment(from.x, from.y, to.x, to.y)
            }
        }
    }

    private fun barrelFlamePoints(
        outer: List<DrawBatch>,
        centreX: Double,
        lidY: Double,
        size: Double,
        timeSeconds: Double,
        barrel: Barrel,
        tongue: Int,
    ): List<Vec2> {
        val startY = lidY - outer.first().width / 2.0
        val tipY = lidY - size * BARREL_FLAME_LENGTHS[tongue] + outer.last().width / 2.0
        val coordinatePhase = positiveMod(
            barrel.column * BARREL_PHASE_X + barrel.row * BARREL_PHASE_Y,
            BARREL_PHASE_STEPS,
        ).toDouble() / BARREL_PHASE_STEPS
        val cycle = positiveRemainder(timeSeconds / BARREL_WAVE_PERIOD + coordinatePhase, 1.0)
        val anchorX = centreX + size * BARREL_FLAME_ANCHORS[tongue]
        return (0..outer.size).map { point ->
            val progress = point.toDouble() / outer.size
            val envelope = TrigTable.sinDegrees(180.0 * progress)
            val zig = if ((point + tongue) % 2 == 0) -1.0 else 1.0
            val staticTurn = zig * BARREL_ZIGZAG * envelope
            val wave = BARREL_WAVE * envelope * TrigTable.sinDegrees(
                360.0 * (cycle + BARREL_FLAME_PHASES[tongue] + progress * BARREL_WAVE_TURNS),
            )
            Vec2(
                anchorX + size * (staticTurn + wave),
                startY + (tipY - startY) * progress,
            )
        }
    }

    private fun arenas(builder: SceneBuilder, palette: Palette, level: Level, camera: Camera) {
        val batch = builder.batch(Layer.Terrain, palette.accent, Primitive.Rect)
        listOf(level.miniboss, level.boss).forEach { arena ->
            batch.rect(
                (TileMap.toWorld(arena.leftTile) - camera.x) * ZOOM,
                (TileMap.toWorld(arena.floorRow) - camera.y) * ZOOM - ARENA_PX,
                arena.widthTiles * TILE_SIZE * ZOOM,
                ARENA_PX,
            )
        }
    }

    private fun jets(
        builder: SceneBuilder,
        palette: Palette,
        level: Level,
        camera: Camera,
        timeSeconds: Double,
        presentationTime: Double,
    ) {
        val size = TILE_SIZE * ZOOM
        val pipeNeck = builder.batch(Layer.Terrain, palette.tileEdge, Primitive.Rect)
        val pipeMouth = builder.batch(Layer.Terrain, palette.tileDeep, Primitive.Dot)
        val pipeRim = builder.batch(
            Layer.Terrain, palette.tileEdge, Primitive.Segment, strokeWidth(PIPE_RIM_WIDTH),
        )
        val pipeCrack = builder.batch(
            Layer.Terrain, palette.tileDeep, Primitive.Segment, strokeWidth(PIPE_CRACK_WIDTH),
        )
        val outer = JET_OUTER_WIDTHS.map { width ->
            builder.batch(Layer.Hazard, FIRE_OUTER, Primitive.Segment, strokeWidth(size * width))
        }
        val core = JET_CORE_WIDTHS.map { width ->
            builder.batch(Layer.Hazard, FIRE_CORE, Primitive.Segment, strokeWidth(size * width))
        }

        level.jets.forEach { jet ->
            val x = (TileMap.toWorld(jet.column) - camera.x) * ZOOM + size / 2.0
            val top = (TileMap.toWorld(jet.topRow) - camera.y) * ZOOM
            val bottom = (TileMap.toWorld(jet.bottomRow + 1) - camera.y) * ZOOM
            brokenPipe(pipeNeck, pipeMouth, pipeRim, pipeCrack, x, bottom)
            if (!jet.isOnAt(timeSeconds)) return@forEach
            flame(outer, core, x, top, bottom, size, presentationTime)
        }
    }

    /** Three tapering, independently phased tongues; the broad outer strokes always paint first. */
    private fun flame(
        outer: List<DrawBatch>,
        core: List<DrawBatch>,
        centreX: Double,
        top: Double,
        bottom: Double,
        size: Double,
        timeSeconds: Double,
    ) {
        for (tongue in JET_LENGTHS.indices) {
            val points = flamePoints(outer, centreX, top, bottom, size, timeSeconds, tongue)
            for (segment in outer.indices) {
                val from = points[segment]
                val to = points[segment + 1]
                outer[segment].segment(from.x, from.y, to.x, to.y)
                core[segment].segment(from.x, from.y, to.x, to.y)
            }
        }
    }

    private fun flamePoints(
        outer: List<DrawBatch>,
        centreX: Double,
        top: Double,
        bottom: Double,
        size: Double,
        timeSeconds: Double,
        tongue: Int,
    ): List<Vec2> {
        val startY = bottom - outer.first().width / 2.0
        val tongueTop = bottom - (bottom - top) * JET_LENGTHS[tongue]
        val tipY = tongueTop + outer.last().width / 2.0
        val cycle = positiveRemainder(timeSeconds / JET_WAVE_PERIOD + JET_PHASE_EPSILON, 1.0)
        return (0..outer.size).map { point ->
            val progress = point.toDouble() / outer.size
            val envelope = TrigTable.sinDegrees(180.0 * progress)
            val zig = if ((point + tongue) % 2 == 0) -1.0 else 1.0
            val staticTurn = zig * JET_ZIGZAG * envelope
            val wave = JET_WAVE * envelope * TrigTable.sinDegrees(
                360.0 * (cycle + JET_PHASES[tongue] + progress * JET_WAVE_TURNS),
            )
            val branch = JET_DIRECTIONS[tongue] * JET_BRANCH * progress
            Vec2(
                centreX + size * (branch + staticTurn + wave),
                startY + (tipY - startY) * progress,
            )
        }
    }

    /** The jet's source remains visible during its safe window (PROD-096). */
    private fun brokenPipe(
        neck: DrawBatch,
        mouth: DrawBatch,
        rim: DrawBatch,
        crack: DrawBatch,
        centreX: Double,
        surfaceY: Double,
    ) {
        val size = TILE_SIZE * ZOOM
        val halfNeck = size * PIPE_NECK_WIDTH / 2.0
        neck.rect(centreX - halfNeck, surfaceY, halfNeck * 2.0, size * PIPE_NECK_HEIGHT)
        mouth.dot(centreX, surfaceY + size * PIPE_MOUTH_DROP, size * PIPE_MOUTH_RADIUS)

        val rimY = surfaceY + size * PIPE_RIM_DROP
        rim.segment(
            centreX - size * PIPE_RIM_RADIUS,
            rimY + size * PIPE_RIM_LEFT_DROP,
            centreX - size * PIPE_RIM_GAP,
            rimY - size * PIPE_RIM_LIFT,
        )
        rim.segment(
            centreX + size * PIPE_RIM_GAP,
            rimY - size * PIPE_RIM_LIFT,
            centreX + size * PIPE_RIM_RADIUS,
            rimY + size * PIPE_RIM_RIGHT_DROP,
        )

        val crackStartX = centreX + size * PIPE_CRACK_START_X
        val crackStartY = surfaceY + size * PIPE_CRACK_START_Y
        val crackJointX = centreX + size * PIPE_CRACK_JOINT_X
        val crackJointY = surfaceY + size * PIPE_CRACK_JOINT_Y
        crack.segment(crackStartX, crackStartY, crackJointX, crackJointY)
        crack.segment(
            crackJointX,
            crackJointY,
            centreX + size * PIPE_CRACK_END_X,
            surfaceY + size * PIPE_CRACK_END_Y,
        )
    }

    // ---- things in the world ----------------------------------------------------------------

    /**
     * A drop is drawn as the thing it is (PROD-044, PROD-049).
     *
     * It was a bar in the theme's `accent` and a block in `hazardGlow` — **the acid colour**, so
     * every powerup in the game was drawn the same colour as the thing that kills you — with size
     * carrying rarity and nothing carrying identity. A player crossing a map to reach a drop could
     * not tell a railgun from a machete until they were standing on it, and PROD-030 makes contact
     * irrevocable.
     *
     * The rarity glow that used to sit behind a pickup is gone with them. It was
     * `palette.glow[last]`, which is also an enemy's eye and a mine's core, and PROD-051 does not
     * allow a drop to be drawn in a colour the same frame gives an effect. The halo under the icon
     * does the separating now, and does it against terrain the glow never helped with.
     */
    private fun pickups(
        builder: SceneBuilder,
        sim: GameSimulation,
        camera: Camera,
        timeSeconds: Double,
    ) {
        sim.items.forEach { item ->
            // A paired award (weapon and powerup on one item) draws both, the powerup a tile to
            // the right, so it looks like the two drops it resolves as.
            item.weapon?.let {
                pickup(builder, camera, item.position, PickupLook.of(it), WeaponIcons.of(it.id), timeSeconds)
            }
            item.powerup?.let {
                pickup(builder, camera, item.powerupPosition, PickupLook.of(it), PowerupIcons.of(it.id), timeSeconds)
            }
            if (item.ramen) ramen(builder, camera, item.position)
        }
    }

    /** A fixed ground-aligned bowl, separate from hovering weapon and powerup icon grammar. */
    private fun ramen(builder: SceneBuilder, camera: Camera, at: Vec2) {
        val x = (at.x - camera.x) * ZOOM
        if (x < -OFF_SCREEN || x > camera.viewWidth * ZOOM + OFF_SCREEN) return
        val groundY = (at.y + TILE_SIZE / 2.0 - camera.y) * ZOOM
        val rimY = groundY - RAMEN_RIM_RISE * RAMEN_VISUAL_SCALE
        val baseY = groundY - RAMEN_OUTLINE_WIDTH * RAMEN_VISUAL_SCALE / 2.0

        fun scaled(value: Double): Double = value * RAMEN_VISUAL_SCALE

        fun bowl(batch: DrawBatch) {
            batch.segment(x - scaled(8.0), rimY, x + scaled(8.0), rimY)
            batch.segment(x - scaled(7.5), rimY + scaled(0.5), x - scaled(4.0), baseY)
            batch.segment(x + scaled(7.5), rimY + scaled(0.5), x + scaled(4.0), baseY)
            batch.segment(x - scaled(4.0), baseY, x + scaled(4.0), baseY)
        }

        bowl(builder.batch(
            Layer.ItemHalo,
            RAMEN_OUTLINE,
            Primitive.Segment,
            scaled(RAMEN_OUTLINE_WIDTH),
        ))
        bowl(builder.batch(
            Layer.Items,
            RAMEN_BOWL,
            Primitive.Segment,
            scaled(RAMEN_BODY_WIDTH),
        ))
        builder.batch(
            Layer.ItemWear,
            RAMEN_WEAR,
            Primitive.Segment,
            scaled(RAMEN_DETAIL_WIDTH),
        ).segment(
            x + scaled(3.5),
            baseY - scaled(0.8),
            x + scaled(6.4),
            rimY + scaled(1.2),
        )

        val noodles = builder.batch(
            Layer.Items,
            RAMEN_NOODLE,
            Primitive.Segment,
            scaled(RAMEN_DETAIL_WIDTH),
        )
        noodles.segment(x - scaled(5.0), rimY, x - scaled(6.5), rimY - scaled(2.0))
        noodles.segment(x - scaled(6.5), rimY - scaled(2.0), x - scaled(4.5), rimY - scaled(4.0))
        noodles.segment(x - scaled(4.5), rimY - scaled(4.0), x - scaled(6.0), rimY - scaled(6.0))
        noodles.segment(x - scaled(1.0), rimY, x + scaled(0.5), rimY - scaled(2.0))
        noodles.segment(x + scaled(0.5), rimY - scaled(2.0), x - scaled(1.5), rimY - scaled(4.0))
        noodles.segment(x - scaled(1.5), rimY - scaled(4.0), x, rimY - scaled(6.0))

        val chopsticks = builder.batch(
            Layer.Items,
            RAMEN_CHOPSTICK,
            Primitive.Segment,
            scaled(RAMEN_DETAIL_WIDTH),
        )
        chopsticks.segment(
            x + scaled(2.0),
            rimY + scaled(0.5),
            x + scaled(7.0),
            groundY - scaled(15.5),
        )
        chopsticks.segment(
            x + scaled(4.0),
            rimY + scaled(0.5),
            x + scaled(9.0),
            groundY - scaled(15.5),
        )
    }

    /**
     * A drop: its icon in its materials, ringed in its kind's colour, hovering (PROD-050, PROD-078,
     * PROD-079). The ring is drawn here and nowhere else, which is what keeps it off the hand and
     * out of the HUD; the hover is an offset on the drawn origin only, so where the player must
     * stand to collect the item is the simulation's business alone.
     */
    private fun pickup(
        builder: SceneBuilder,
        camera: Camera,
        at: Vec2,
        look: PickupLook,
        icon: Icon,
        timeSeconds: Double,
    ) {
        val x = (at.x - camera.x) * ZOOM
        val y = (at.y - camera.y) * ZOOM - hoverOffset(timeSeconds, at.x)
        if (x < -OFF_SCREEN || x > camera.viewWidth * ZOOM + OFF_SCREEN) return

        val scale = PICKUP_PX * look.scale
        kindRing(builder, look, x, y, scale)
        IconPainter.paint(builder, icon, x, y, scale, Layer.ItemHalo, Layer.Items, Layer.ItemWear)
        tierPips(builder, look, x, y + scale * IconStyles.KIND_RING + PIP_DROP)
    }

    /**
     * The time a frame shows: the tick's time less the fraction of a tick the frame has not yet
     * reached. The player and camera are interpolated by [alpha] (ENG-062), so a drop that hovered
     * at the tick's time alone would step while they slide on a display faster than the tick rate.
     */
    fun presentationTime(timeSeconds: Double, alpha: Double): Double =
        (timeSeconds - (1.0 - alpha) * TICK_SECONDS).coerceAtLeast(0.0)

    /**
     * How far above its rest a drop at world [x] is drawn at [timeSeconds] (`specs/presentation.md`,
     * Hover): a sine of the period, phased by position so neighbouring drops are out of step.
     */
    fun hoverOffset(timeSeconds: Double, x: Double): Double =
        HOVER_PX * TrigTable.sinDegrees(360.0 * timeSeconds / HOVER_PERIOD + x * HOVER_PHASE_DEGREES_PER_PX)

    private fun kindRing(builder: SceneBuilder, look: PickupLook, x: Double, y: Double, scale: Double) {
        val radius = IconStyles.KIND_RING * scale
        val at = Vec2(x, y)
        val colour = IconStyles.ringOf(look)
        IconStyles.bloomWidthOf(look, scale)?.let { width ->
            ring(builder, colour, at, radius, Layer.ItemHalo, width, KIND_RING_SEGMENTS)
        }
        ring(builder, IconStyles.HALO, at, radius, Layer.ItemHalo, IconStyles.haloWidthOf(StrokeWeight.Hair, scale), KIND_RING_SEGMENTS)
        ring(builder, colour, at, radius, Layer.Items, IconStyles.widthOf(StrokeWeight.Hair, scale), KIND_RING_SEGMENTS)
    }

    /**
     * Rarity, now that shape is spent on identity.
     *
     * Counted rather than compared: telling a 1.0-scale drop from a 1.3-scale one needs both on
     * screen at once, where four pips against two needs neither a second drop nor colour.
     */
    private fun tierPips(builder: SceneBuilder, look: PickupLook, centreX: Double, y: Double) {
        val count = look.tierOrdinal + 1
        val halo = builder.batch(Layer.ItemHalo, IconStyles.HALO, Primitive.Dot)
        val pips = builder.batch(
            Layer.Items,
            IconStyles.ringOf(look),
            Primitive.Dot,
        )
        val first = centreX - (count - 1) * PIP_PITCH / 2.0
        for (index in 0 until count) {
            val x = first + index * PIP_PITCH
            halo.dot(x, y, PIP_PX + PIP_HALO)
            pips.dot(x, y, PIP_PX)
        }
    }

    private fun enemies(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
        timeSeconds: Double,
    ) {
        val player = centreOfPlayer(sim)

        sim.enemies.forEach { enemy ->
            if (!enemy.alive) return@forEach
            // The simulation anchors an enemy at the top-left of a 14 px box, in a 16 px cell whose
            // floor is one tile below. Drawing from that anchor put a Brute's feet eight pixels
            // under the floor and its whole figure seven pixels left of what a shot has to hit.
            val x = (enemy.position.x + ENEMY_HALF - camera.x) * ZOOM
            val ground = (enemy.position.y + TILE_SIZE - camera.y) * ZOOM
            if (x < -OFF_SCREEN || x > camera.viewWidth * ZOOM + OFF_SCREEN) return@forEach

            val look = EnemyLooks.of(enemy.archetype, sim.level.mapIndex)
            when (look.form) {
                EnemyForm.Biped -> biped(builder, palette, look, sim, enemy, x, ground)
                EnemyForm.Hover -> hover(builder, palette, look, enemy, x, ground, timeSeconds)
                EnemyForm.Crawler -> crawler(builder, palette, look, enemy, engagement(enemy, look, player), x, ground)
            }
            enemyStatuses(builder, enemy, look, x, ground, timeSeconds)
            // The boss's bar, for anyone who has been hurt (PROD-077); full health shows none.
            if (enemy.health < enemy.maxHealth) {
                healthBar(builder, palette, x, ground - look.height * ZOOM - BAR_GAP, GameSimulation.ENEMY_SIZE * ZOOM, enemy.healthFraction)
            }
        }
    }

    private fun enemyStatuses(
        builder: SceneBuilder,
        enemy: LiveEnemy,
        look: EnemyLook,
        x: Double,
        ground: Double,
        timeSeconds: Double,
    ) {
        if (enemy.burn.secondsLeft > 0.0) burnIndicators(builder, enemy, look, x, ground, timeSeconds)
        if (enemy.bleed.secondsLeft > 0.0) bleedIndicators(builder, enemy, look, x, ground, timeSeconds)
    }

    private fun burnIndicators(
        builder: SceneBuilder,
        enemy: LiveEnemy,
        look: EnemyLook,
        x: Double,
        ground: Double,
        timeSeconds: Double,
    ) {
        val outer = builder.batch(Layer.ActorStatus, BURN_OUTER, Primitive.Segment, BURN_OUTER_WIDTH)
        val core = builder.batch(Layer.ActorStatus, BURN_CORE, Primitive.Segment, BURN_CORE_WIDTH)
        val outerEmbers = builder.batch(Layer.ActorStatus, BURN_OUTER, Primitive.Dot)
        val coreEmbers = builder.batch(Layer.ActorStatus, BURN_CORE, Primitive.Dot)
        val span = GameSimulation.ENEMY_SIZE * ZOOM
        val height = look.height * ZOOM
        repeat(STATUS_COUNT) { index ->
            val progress = statusProgress(timeSeconds, enemy, index, BURN_PERIOD)
            val atX = x + (index - 1) * span * STATUS_SPACING
            val atY = ground - height * (STATUS_START + STATUS_TRAVEL * progress)
            val size = BURN_SIZE * (1.0 - STATUS_SHRINK * progress)
            outer.segment(atX - size, atY + size, atX, atY - size)
            outer.segment(atX, atY - size, atX + size, atY + size)
            core.segment(atX - size * 0.55, atY + size * 0.55, atX, atY - size * 0.55)
            core.segment(atX, atY - size * 0.55, atX + size * 0.55, atY + size * 0.55)
            outerEmbers.dot(atX + size * 0.7, atY - size * 1.45, BURN_EMBER)
            coreEmbers.dot(atX + size * 0.7, atY - size * 1.45, BURN_EMBER * 0.45)
        }
    }

    private fun bleedIndicators(
        builder: SceneBuilder,
        enemy: LiveEnemy,
        look: EnemyLook,
        x: Double,
        ground: Double,
        timeSeconds: Double,
    ) {
        val drops = builder.batch(Layer.ActorStatus, BLEED, Primitive.Segment, BLEED_WIDTH)
        val bulbs = builder.batch(Layer.ActorStatus, BLEED, Primitive.Dot)
        val span = GameSimulation.ENEMY_SIZE * ZOOM
        val top = ground - look.height * ZOOM
        val height = look.height * ZOOM
        repeat(STATUS_COUNT) { index ->
            val progress = statusProgress(timeSeconds, enemy, index, BLEED_PERIOD)
            val atX = x + (index - 1) * span * STATUS_SPACING
            val atY = top + height * (STATUS_START + STATUS_TRAVEL * progress)
            val size = BLEED_SIZE * (1.0 - STATUS_SHRINK * progress)
            bulbs.dot(atX, atY - size * 0.35, size * 0.58)
            drops.segment(atX - size * 0.62, atY - size * 0.25, atX, atY + size)
            drops.segment(atX + size * 0.62, atY - size * 0.25, atX, atY + size)
        }
    }

    private fun statusProgress(
        timeSeconds: Double,
        enemy: LiveEnemy,
        index: Int,
        period: Double,
    ): Double {
        val column = TileMap.toTile(enemy.position.x)
        val row = TileMap.toTile(enemy.position.y)
        val stagger = positiveMod(column * 7 + row * 11 + index * 5, STATUS_PHASE_STEPS)
        return ((timeSeconds % period) / period + stagger.toDouble() / STATUS_PHASE_STEPS) % 1.0
    }

    private fun positiveMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus

    private fun centreOfPlayer(sim: GameSimulation) = Vec2(
        sim.player.x + Physics.Default.width / 2.0,
        sim.player.y + sim.player.height(Physics.Default) / 2.0,
    )

    /**
     * Where an armed enemy is looking, as a unit vector — the player when it is close enough to
     * fire on, and the way it is patrolling otherwise.
     *
     * Purely presentational: it reads the simulation and writes nothing back (ENG-062). An armed
     * enemy that stared down its patrol line while shooting the player behind it was a promise
     * `specs/presentation.md` made and the first implementation did not keep. The range is the
     * simulation's own, so what a figure looks like it is doing is what it is doing.
     */
    private fun engagement(enemy: LiveEnemy, look: EnemyLook, player: Vec2): Vec2 {
        val patrol = Vec2(enemy.facing.toDouble(), 0.0)
        if (!look.armed) return patrol
        // A shot resolves on the aim taken when its wind-up began, so that is what the telegraph
        // shows — a barrel that kept tracking a dodging player would lie about where the shot goes.
        if (enemy.windingUp) return enemy.attackDirection
        val offset = player - Vec2(enemy.position.x + ENEMY_HALF, enemy.position.y + ENEMY_HALF)
        val range = GameSimulation.SHOOTER_RANGE
        if (offset.lengthSquared > range * range) return patrol
        return offset.normalisedOr(patrol)
    }

    /**
     * What an enemy's rig needs, read off the simulation and nothing else (ENG-062): its own
     * wind-up, swing and shot windows, so a Brute winding up reads as a Brute winding up
     * (PROD-063).
     */
    fun enemyMotion(sim: GameSimulation, enemy: LiveEnemy): Motion {
        val look = EnemyLooks.of(enemy.archetype, sim.level.mapIndex)
        val aim = engagement(enemy, look, centreOfPlayer(sim))
        // A shooter turns to face what it is shooting at; everything else walks its patrol. The
        // full direction goes to the pose, not just its sign — a shooter firing upward has to look
        // like it, since its projectile leaves on that diagonal.
        return Motion(
            speedX = enemy.leap?.let { it.direction * EnemyLeap.VX }
                ?: (enemy.facing * look.strideRate * REFERENCE_SPEED),
            verticalSpeed = enemy.vy,
            onGround = enemy.leap == null && enemy.vy == 0.0,
            facing = if (aim.x < 0.0) -1 else 1,
            stridePx = enemy.stridePx * look.strideRate,
            secondsSinceShot = enemy.lastShot?.let { it.totalSeconds - it.secondsLeft } ?: Double.MAX_VALUE,
            secondsSinceSwing = enemy.lastSwing?.let { it.totalSeconds - it.secondsLeft } ?: Double.MAX_VALUE,
            windingUp = enemy.windingUp,
            shotSeconds = enemy.lastShot?.totalSeconds ?: Actor.FIRE_SECONDS,
            swingSeconds = enemy.lastSwing?.totalSeconds ?: Actor.SWING_SECONDS,
            swingDirection = enemy.lastSwing?.direction ?: enemy.attackDirection,
            weaponAim = if (look.armed) aim else null,
            scale = look.height / Physics.Default.standingHeight,
        )
    }

    private fun biped(
        builder: SceneBuilder,
        palette: Palette,
        look: EnemyLook,
        sim: GameSimulation,
        enemy: LiveEnemy,
        x: Double,
        ground: Double,
    ) {
        val pose = Actor.pose(enemyMotion(sim, enemy))
        val hurt = enemy.hurtSecondsLeft > 0.0
        figure(
            builder, palette, pose, x, ground, look,
            bodyStyle = hurtOr(hurt, Palettes.ENEMY_BODY),
            limbStyle = hurtOr(hurt, Palettes.ENEMY_DARK),
            trimStyle = hurtOr(hurt, Palettes.ENEMY_PLATE),
            armStyle = hurtOr(hurt, Palettes.ENEMY_PLATE),
        )

        val glow = palette.glow[look.glowTone]
        val feet = Vec2(x, ground)
        enemy.lastSwing?.let { swoosh(builder, glow, feet + pose.leadHand * ZOOM, it) }
        enemy.lastShot?.let { muzzleFlash(builder, glow, glow, feet + barrelTip(pose) * ZOOM, it) }
    }

    /** Where the plain barrel a figure holds ends, in actor-local pixels. */
    fun barrelTip(pose: Pose, weaponReach: Double = BARREL_REACH): Vec2 =
        pose.leadHand + pose.weaponAim * (pose.height * weaponReach)

    /**
     * The muzzle of a registry weapon drawn in the lead hand, in actor-local pixels: the far end of
     * the held icon, which spans [HELD_ICON] of the plain barrel's reach.
     */
    fun muzzleOf(pose: Pose, weapon: io.github.ksean.cyberslop.combat.WeaponSpec): Vec2 =
        pose.leadHand + pose.weaponAim * (pose.height * weaponReach(weapon) * HELD_ICON)

    /** A legless pod. Nothing else in the game leaves the ground, so nothing else reads like it. */
    private fun hover(
        builder: SceneBuilder,
        palette: Palette,
        look: EnemyLook,
        enemy: LiveEnemy,
        x: Double,
        ground: Double,
        timeSeconds: Double,
    ) {
        val size = look.height * ZOOM
        val bob = TrigTable.sinDegrees(timeSeconds * HOVER_DEGREES_PER_SECOND) * size * HOVER_BOB
        // Held clear of the floor rather than resting on it: nothing else in the game does that,
        // and it is the whole reason the pod reads as airborne.
        val centreY = ground - size * (POD_HEIGHT + HOVER_CLEARANCE) + bob
        // Banking: the thrust trails the direction of travel, which is what a hovering thing does
        // to move sideways and the only cue that distinguishes drifting left from drifting right.
        val bank = -enemy.facing * size * HOVER_BANK

        val hurt = enemy.hurtSecondsLeft > 0.0
        builder.batch(Layer.Actors, hurtOr(hurt, Palettes.ENEMY_BODY), Primitive.Rect)
            .rect(x - size * look.bulk / 2.0, centreY, size * look.bulk, size * POD_HEIGHT)
        plating(builder, look, x, centreY, size, hurtOr(hurt, Palettes.ENEMY_PLATE))
        builder.batch(Layer.ActorGlow, palette.glow[look.glowTone], Primitive.Dot)
            .dot(x + enemy.facing * size * EYE_OFFSET, centreY + size * POD_HEIGHT / 2.0, size * EYE)
        // Thruster plumes, which is the whole reason it reads as airborne rather than as a floating
        // block: two short segments under it, angled outward.
        val plume = builder.batch(
            Layer.ActorFront, palette.accent, Primitive.Segment, strokeWidth(size * PLUME_WIDTH),
        )
        plume.segment(
            x - size * PLUME_SPREAD, centreY + size * POD_HEIGHT,
            x - size * PLUME_SPREAD * 1.6 + bank, centreY + size * (POD_HEIGHT + PLUME_LENGTH),
        )
        plume.segment(
            x + size * PLUME_SPREAD, centreY + size * POD_HEIGHT,
            x + size * PLUME_SPREAD * 1.6 + bank, centreY + size * (POD_HEIGHT + PLUME_LENGTH),
        )
        // A pod has no hand, so its strike comes from its body — and its wind-up is a charge
        // gathering at its front, growing through the telegraph (PROD-063).
        if (enemy.windingUp) {
            val progress = 1.0 - (enemy.windUpLeft / enemy.windUpTotal).coerceIn(0.0, 1.0)
            builder.batch(Layer.Effects, palette.hazardGlow, Primitive.Dot).dot(
                x + enemy.facing * size * (look.bulk / 2.0 + CHARGE_OFFSET),
                centreY + size * POD_HEIGHT / 2.0,
                size * CHARGE * (0.4 + 0.6 * progress),
            )
        }
        enemy.lastSwing?.let {
            swoosh(builder, palette.glow[look.glowTone], Vec2(x, centreY + size * POD_HEIGHT / 2.0), it)
        }
    }

    /** A cannon pod which unfolds onto four articulated legs once it notices the player. */
    private fun crawler(
        builder: SceneBuilder,
        palette: Palette,
        look: EnemyLook,
        enemy: LiveEnemy,
        aim: Vec2,
        x: Double,
        ground: Double,
    ) {
        val size = look.height * ZOOM
        val folded = !enemy.engaged
        val airborne = enemy.leap != null
        val legHeight = size * when {
            airborne -> CRAWLER_TUCK_HEIGHT
            folded -> CRAWLER_FOLDED_HEIGHT
            else -> CRAWLER_LEG_HEIGHT
        }
        val baseHeight = size * BASE_HEIGHT
        val feet = ground

        val hurt = enemy.hurtSecondsLeft > 0.0
        val legs = builder.batch(
            Layer.ActorBehind,
            hurtOr(hurt, Palettes.ENEMY_DARK),
            Primitive.Segment,
            strokeWidth(size * CRAWLER_LEG_WIDTH),
        )
        for (side in listOf(-1.0, 1.0)) {
            val hipX = x + side * size * CRAWLER_HIP_SPREAD
            val kneeX = x + side * size * if (folded || airborne) CRAWLER_TUCK_SPREAD else CRAWLER_KNEE_SPREAD
            val footX = x + side * size * if (folded || airborne) CRAWLER_FOOT_FOLDED else CRAWLER_FOOT_SPREAD
            val hipY = feet - legHeight
            val kneeY = feet - legHeight * CRAWLER_KNEE_HEIGHT
            val footY = if (airborne) feet - legHeight * CRAWLER_TUCK_FOOT else feet
            legs.segment(hipX, hipY, kneeX, kneeY)
            legs.segment(kneeX, kneeY, footX, footY)
        }
        val baseBottom = feet - legHeight
        builder.batch(Layer.Actors, hurtOr(hurt, Palettes.ENEMY_DARK), Primitive.Rect)
            .rect(
                x - size * look.bulk * BASE_WIDTH, baseBottom - baseHeight,
                size * look.bulk * BASE_WIDTH * 2.0, baseHeight,
            )
        // Behind the head, so it reads as emerging from the crawler housing.
        val head = baseBottom - baseHeight - size * TURRET_HEAD / 2.0
        // The head sweeps: the barrel follows whatever the mobile crawler is tracking.
        builder.batch(
            Layer.ActorBehind, hurtOr(hurt, Palettes.ENEMY_PLATE), Primitive.Segment,
            strokeWidth(size * BARREL_WIDTH),
        ).segment(x, head, x + aim.x * size * BARREL, head + aim.y * size * BARREL)
        builder.batch(Layer.ActorHead, hurtOr(hurt, Palettes.ENEMY_BODY), Primitive.Rect)
            .rect(
                x - size * TURRET_HEAD / 2.0, head - size * TURRET_HEAD / 2.0,
                size * TURRET_HEAD, size * TURRET_HEAD,
            )
        plating(builder, look, x, head - size * TURRET_HEAD / 2.0, size, hurtOr(hurt, Palettes.ENEMY_PLATE))
        builder.batch(Layer.ActorGlow, palette.glow[look.glowTone], Primitive.Dot)
            .dot(x + aim.x * size * EYE_OFFSET, head + aim.y * size * EYE_OFFSET, size * EYE)
        // The wind-up is a charge at the barrel's mouth, growing through the telegraph (PROD-063).
        if (enemy.windingUp) {
            val progress = 1.0 - (enemy.windUpLeft / enemy.windUpTotal).coerceIn(0.0, 1.0)
            builder.batch(Layer.Effects, palette.hazardGlow, Primitive.Dot).dot(
                x + aim.x * size * BARREL, head + aim.y * size * BARREL, size * CHARGE * (0.4 + 0.6 * progress),
            )
        }
        enemy.lastShot?.let {
            val glow = palette.glow[look.glowTone]
            muzzleFlash(builder, glow, glow, Vec2(x + aim.x * size * BARREL, head + aim.y * size * BARREL), it)
        }
    }

    /**
     * Draws a posed figure.
     *
     * Every limb is one stroked segment, so a whole figure is six segments, two rectangles and two
     * dots — and all of them land in batches shared with every other figure on screen.
     */
    private fun figure(
        builder: SceneBuilder,
        palette: Palette,
        pose: Pose,
        originX: Double,
        originY: Double,
        look: EnemyLook?,
        bodyStyle: String,
        limbStyle: String = Palettes.ENEMY_DARK,
        trimStyle: String = Palettes.ENEMY_PLATE,
        armStyle: String = Palettes.ENEMY_PLATE,
        eyeStyle: String? = null,
        /** Whether to draw the weapon in the lead hand. Enemies inherit it from their archetype. */
        armed: Boolean = look?.armed == true,
        /** How far the weapon reaches, as a fraction of the figure's height. */
        weaponReach: Double = BARREL_REACH,
        /**
         * The icon to draw in the lead hand, for a figure holding a weapon from the registry.
         *
         * Null draws the plain barrel an enemy gets. An enemy's weapon is not a `WeaponId` — it is
         * an archetype trait — so there is no icon to resolve for one, and inventing a mapping so
         * that every figure could take the same path would be an abstraction with one caller
         * (ENG-022).
         */
        heldIcon: Icon? = null,
        /** Actor-side fallback when a held icon points exactly vertically. */
        heldFacing: Int = 1,
    ) {
        val bulk = look?.bulk ?: 1.0
        val thickness = pose.height * ZOOM * LIMB * bulk

        // Three widths per figure, snapped, so a crowd of actors shares three batches rather than
        // breaking the stroke path once per limb.
        val legWidth = strokeWidth(thickness)
        val armWidth = strokeWidth(thickness * ARM)
        val torsoWidth = strokeWidth(thickness * TORSO * bulk)

        val legs = builder.batch(Layer.ActorBehind, limbStyle, Primitive.Segment, legWidth)
        val rearArms = builder.batch(Layer.ActorBehind, limbStyle, Primitive.Segment, armWidth)
        val body = builder.batch(Layer.Actors, bodyStyle, Primitive.Segment, torsoWidth)
        // Arms get their own tone. Drawn in the body's colour they simply disappeared into the
        // torso, so the weapon read as floating unattached in front of the figure.
        val arms = builder.batch(Layer.ActorFront, armStyle, Primitive.Segment, armWidth)

        fun link(from: Vec2, to: Vec2, batch: DrawBatch) {
            batch.segment(
                originX + from.x * ZOOM, originY + from.y * ZOOM,
                originX + to.x * ZOOM, originY + to.y * ZOOM,
            )
        }

        // Legs first, so the torso overlaps them rather than the other way round.
        link(pose.hip, pose.leadKnee, legs)
        link(pose.leadKnee, pose.leadFoot, legs)
        link(pose.hip, pose.rearKnee, legs)
        link(pose.rearKnee, pose.rearFoot, legs)
        link(pose.hip, pose.neck, body)
        link(pose.rearShoulder, pose.rearElbow, rearArms)
        link(pose.rearElbow, pose.rearHand, rearArms)
        link(pose.leadShoulder, pose.leadElbow, arms)
        link(pose.leadElbow, pose.leadHand, arms)

        val headX = originX + pose.head.x * ZOOM
        val headY = originY + pose.head.y * ZOOM
        val headRadius = pose.headRadius * ZOOM * (look?.headScale ?: 1.0)
        builder.batch(Layer.ActorHead, bodyStyle, Primitive.Dot).dot(headX, headY, headRadius)

        val glow = eyeStyle ?: palette.glow[look?.glowTone ?: palette.glow.size - 1]
        builder.batch(Layer.ActorGlow, glow, Primitive.Dot).dot(
            headX + headRadius * EYE_LEAD * (if (pose.leadHand.x >= pose.hip.x) 1.0 else -1.0),
            headY,
            headRadius * EYE_SIZE,
        )

        if (look != null) {
            // Centred on the torso, not on the head: the head carries the figure's forward lean,
            // so plating hung off it slid ahead of the body it is supposed to be armouring.
            val torsoX = originX + (pose.hip.x + pose.neck.x) / 2.0 * ZOOM
            plating(builder, look, torsoX, originY - pose.height * ZOOM, pose.height * ZOOM, trimStyle)
        }

        if (armed) {
            val handX = originX + pose.leadHand.x * ZOOM
            val handY = originY + pose.leadHand.y * ZOOM
            val reach = pose.height * weaponReach * ZOOM
            if (heldIcon == null) {
                val tip = barrelTip(pose, weaponReach)
                builder.batch(Layer.ActorFront, trimStyle, Primitive.Segment, armWidth)
                    .segment(handX, handY, originX + tip.x * ZOOM, originY + tip.y * ZOOM)
            } else {
                // The icon's own `+x` runs along the aim and its box is `[-1, 1]`, so an origin half
                // a reach along the aim at half a reach of scale puts the grip in the hand and the
                // muzzle exactly where the plain barrel ended. `weaponReach` still comes from the
                // weapon's declared range, so a railgun is still longer than a bottle — it now
                // scales a shape rather than a line.
                val scale = reach / 2.0 * HELD_ICON
                IconPainter.paint(
                    builder,
                    heldIcon,
                    originX = handX + pose.weaponAim.x * scale,
                    originY = handY + pose.weaponAim.y * scale,
                    scale = scale,
                    // Over the arm that holds it, under nothing else the actor draws.
                    haloLayer = Layer.ActorFront,
                    outlineLayer = Layer.ActorTrim,
                    wearLayer = Layer.ActorWear,
                    aim = pose.weaponAim,
                    handedness = IconHandedness.forHeldAim(pose.weaponAim, heldFacing),
                )
            }
        }
    }

    /**
     * Armour and protrusions, which is where an enemy's toughness is actually visible (PROD-042).
     *
     * Both counts come from [EnemyLook], which derives them from the health the enemy carries, so a
     * tougher enemy is plated and spiked without this code knowing anything about difficulty.
     */
    private fun plating(
        builder: SceneBuilder,
        look: EnemyLook,
        centreX: Double,
        topY: Double,
        size: Double,
        style: String = Palettes.ENEMY_PLATE,
    ) {
        if (look.plates > 0) {
            val plates = builder.batch(Layer.ActorTrim, style, Primitive.Rect)
            repeat(look.plates) { index ->
                val width = size * PLATE_WIDTH * look.bulk
                plates.rect(
                    centreX - width / 2.0,
                    topY + size * PLATE_TOP + index * size * PLATE_PITCH,
                    width,
                    size * PLATE_HEIGHT,
                )
            }
        }
        if (look.spikes > 0) {
            val spikes = builder.batch(
                Layer.ActorTrim, style, Primitive.Segment,
                strokeWidth(size * SPIKE_WIDTH),
            )
            repeat(look.spikes) { index ->
                val side = if (index % 2 == 0) -1.0 else 1.0
                val y = topY + size * (PLATE_TOP + index * SPIKE_PITCH)
                spikes.segment(
                    centreX + side * size * SPIKE_BASE,
                    y,
                    centreX + side * size * SPIKE_TIP,
                    y - size * SPIKE_RISE,
                )
            }
        }
    }

    private fun bosses(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
    ) {
        listOf(sim.miniboss to false, sim.boss to true).forEach { (live, isMain) ->
            if (live.fight.defeated) return@forEach
            val bossLook = BossLooks.of(live.spec.profile, sim.level.mapIndex, isMain)
            val look = bossLook.body
            val x = (live.position.x - camera.x) * ZOOM
            val feet = (live.position.y - camera.y) * ZOOM

            val pose = Actor.pose(bossMotion(sim, live))
            // The telegraph colour is a fairness signal, so the hurt flash never covers it.
            val hurt = live.hurtSecondsLeft > 0.0 && !live.telegraphing
            val body = when {
                live.telegraphing -> palette.hazardGlow
                hurt -> Palettes.HURT
                !live.fight.vulnerable -> Palettes.ENEMY_DARK
                else -> palette.accent
            }
            figure(
                builder, palette, pose, x, feet, look, body,
                limbStyle = hurtOr(hurt, Palettes.ENEMY_DARK),
                trimStyle = hurtOr(hurt, Palettes.ENEMY_PLATE),
                armStyle = hurtOr(hurt, Palettes.ENEMY_PLATE),
                armed = false,
            )
            bossHardware(builder, palette, live, bossLook, pose, Vec2(x, feet), hurt)
            crown(builder, palette, look, x, feet - look.height * ZOOM, hurtOr(hurt, palette.glow[palette.glow.size - 1]))
            healthBar(builder, palette, x, feet - look.height * ZOOM - BAR_GAP, look.height * ZOOM, live.healthFraction)
            bossStrike(builder, palette, live, pose, Vec2(x, feet), look)
        }
    }

    /**
     * What a boss's rig needs (ENG-062): its telegraph is a wind-up, its active window a swing or
     * a shot over exactly that window, and its walk toward the player a gait (PROD-063).
     */
    fun bossMotion(sim: GameSimulation, live: LiveBoss): Motion {
        val look = EnemyLooks.boss(sim.level.mapIndex, live === sim.boss)
        val attack = live.currentAttack
        val active = if (attack != null && live.striking) live.attackElapsed - attack.telegraphSeconds else null
        val eventAge = if (attack != null && active != null) mostRecentEventAge(attack.eventOffsets, active) else null
        val forward = Vec2(live.facing.toDouble(), 0.0)
        return Motion(
            speedX = if (live.moving) live.facing * LiveBoss.SPEED else 0.0,
            verticalSpeed = live.vy,
            onGround = live.leap == null && live.vy == 0.0,
            facing = live.facing,
            stridePx = live.stridePx,
            secondsSinceShot = if (attack != null && attack.visual.ranged) eventAge ?: Double.MAX_VALUE else Double.MAX_VALUE,
            secondsSinceSwing = if (attack != null && !attack.visual.ranged) eventAge ?: Double.MAX_VALUE else Double.MAX_VALUE,
            windingUp = live.telegraphing,
            shotSeconds = BOSS_EVENT_FLASH_SECONDS,
            swingSeconds = BOSS_EVENT_SWING_SECONDS,
            swingDirection = attack?.let { strikeDirection(it.visual, forward) } ?: forward,
            weaponAim = forward,
            scale = look.height / Physics.Default.standingHeight,
        )
    }

    private fun mostRecentEventAge(offsets: List<Double>, activeSeconds: Double): Double? = offsets
        .filter { it <= activeSeconds }
        .maxOrNull()
        ?.let { activeSeconds - it }

    /** A slam comes down at the ground ahead; a lunge trails its swoosh behind; the rest go level. */
    private fun strikeDirection(visual: AttackVisual, forward: Vec2): Vec2 = when (visual) {
        AttackVisual.GroundSlam -> Vec2(forward.x * SLAM_FORWARD, SLAM_DOWN)
        AttackVisual.Lunge -> forward * -1.0
        AttackVisual.LevelSweep, AttackVisual.RapidSweep,
        AttackVisual.MuzzleBolt, AttackVisual.MuzzleBurst, AttackVisual.MuzzleFan,
        AttackVisual.LaserBeam -> forward
    }

    /** Every separately timed attack event gets its own swing or muzzle flash. */
    private fun bossStrike(
        builder: SceneBuilder,
        palette: Palette,
        live: LiveBoss,
        pose: Pose,
        feet: Vec2,
        look: EnemyLook,
    ) {
        val attack = live.currentAttack ?: return
        if (!live.striking) return
        val style = palette.hazardGlow
        val active = live.attackElapsed - attack.telegraphSeconds
        attack.eventOffsets.forEachIndexed { eventIndex, offset ->
            val age = active - offset
            if (age < 0.0) return@forEachIndexed
            if (attack.visual.ranged) {
                if (age > BOSS_EVENT_FLASH_SECONDS) return@forEachIndexed
                val flash = MuzzleFlash(live.aimDirection, BOSS_EVENT_FLASH_SECONDS - age, BOSS_EVENT_FLASH_SECONDS)
                val muzzle = bossMuzzle(feet, pose, look, live.aimDirection)
                when (attack.visual) {
                    AttackVisual.MuzzleFan -> BOSS_SCATTER_ANGLES.forEach { angle ->
                        muzzleFlash(builder, style, style, muzzle, flash.copy(direction = TrigTable.rotate(live.aimDirection, angle)))
                    }
                    AttackVisual.LaserBeam -> muzzleFlash(builder, ShotLooks.ENEMY_CORE, style, muzzle, flash)
                    else -> muzzleFlash(builder, style, style, muzzle, flash)
                }
            } else {
                if (age > BOSS_EVENT_SWING_SECONDS) return@forEachIndexed
                val direction = strikeDirection(attack.visual, Vec2(live.facing.toDouble(), 0.0))
                val swing = SwingVisual(
                    origin = Vec2.Zero,
                    direction = direction,
                    arcDegrees = BOSS_SWING_ARC,
                    reachPx = attack.reachPx,
                    secondsLeft = BOSS_EVENT_SWING_SECONDS - age,
                    totalSeconds = BOSS_EVENT_SWING_SECONDS,
                )
                val hand = if (attack.visual == AttackVisual.RapidSweep && eventIndex % 2 == 1) pose.rearHand else pose.leadHand
                swoosh(builder, style, feet + hand * ZOOM, swing)
            }
        }
    }

    private fun bossMuzzle(feet: Vec2, pose: Pose, look: EnemyLook, aim: Vec2): Vec2 =
        feet + pose.rearShoulder * ZOOM + aim.normalisedOr(Vec2.Right) * (look.height * BOSS_BARREL_REACH * ZOOM)

    /**
     * Draws every profile module as hardware, so a silhouette predicts the attacks before combat.
     * The signature stays folded high on the back above 60 % health, then pivots into a forward
     * mount; its shape never disappears while locked.
     */
    private fun bossHardware(
        builder: SceneBuilder,
        palette: Palette,
        live: LiveBoss,
        look: BossLook,
        pose: Pose,
        feet: Vec2,
        hurt: Boolean,
    ) {
        val body = look.body
        val size = body.height * ZOOM
        val forward = Vec2(live.facing.toDouble(), 0.0)
        val activeAim = live.currentAttack?.let { live.aimDirection } ?: forward
        val hardwareStyle = when {
            live.telegraphing -> palette.hazardGlow
            hurt -> Palettes.HURT
            else -> Palettes.ENEMY_PLATE
        }
        val glowStyle = when {
            live.telegraphing -> palette.hazardGlow
            hurt -> Palettes.HURT
            else -> palette.glow[palette.glow.size - 1]
        }

        fun screen(local: Vec2): Vec2 = feet + local * ZOOM
        look.hardware.forEach { hardware ->
            val locked = hardware.folded && live.healthFraction > SIGNATURE_HEALTH
            val origin = when (hardware.mount) {
                BossMount.LeadArm -> screen(pose.leadHand)
                BossMount.RearShoulder -> screen(pose.rearShoulder)
                BossMount.HighBack -> screen(
                    pose.neck + if (locked) {
                        Vec2(-live.facing * body.height * SIGNATURE_BACK, -body.height * SIGNATURE_HIGH)
                    } else {
                        Vec2(live.facing * body.height * SIGNATURE_DEPLOY_FORWARD, -body.height * SIGNATURE_DEPLOY_HIGH)
                    },
                )
            }
            val direction = when {
                locked -> Vec2(0.0, -1.0)
                live.currentAttack?.module == hardware.module -> activeAim
                else -> forward
            }.normalisedOr(forward)
            drawBossMarker(
                builder = builder,
                marker = hardware.marker,
                origin = origin,
                direction = direction,
                size = size,
                solidStyle = hardwareStyle,
                glowStyle = glowStyle,
                alternateOrigin = screen(pose.rearHand),
            )

            if (hardware.module == io.github.ksean.cyberslop.entity.BossModule.Laser &&
                live.currentAttack?.module == hardware.module && live.telegraphing
            ) {
                val attack = live.currentAttack ?: return@forEach
                val charge = (live.attackElapsed / attack.telegraphSeconds).coerceIn(0.0, 1.0)
                val lens = origin + direction * (size * LASER_REACH)
                builder.batch(Layer.Effects, palette.hazardGlow, Primitive.Dot)
                    .dot(lens.x, lens.y, size * LASER_LENS * (LASER_CHARGE_BASE + charge * LASER_CHARGE_RANGE))
            }
        }
    }

    /** Each marker has different colour-stripped geometry; styles only communicate state. */
    private fun drawBossMarker(
        builder: SceneBuilder,
        marker: BossMarker,
        origin: Vec2,
        direction: Vec2,
        size: Double,
        solidStyle: String,
        glowStyle: String,
        alternateOrigin: Vec2,
    ) {
        val normal = Vec2(-direction.y, direction.x)
        val thin = builder.batch(Layer.ActorTrim, solidStyle, Primitive.Segment, strokeWidth(size * HARDWARE_THIN))
        val thick = builder.batch(Layer.ActorFront, solidStyle, Primitive.Segment, strokeWidth(size * HARDWARE_THICK))
        val plates = builder.batch(Layer.ActorTrim, solidStyle, Primitive.Rect)
        val solidDots = builder.batch(Layer.ActorTrim, solidStyle, Primitive.Dot)
        val glowDots = builder.batch(Layer.ActorGlow, glowStyle, Primitive.Dot)
        fun point(along: Double, across: Double = 0.0): Vec2 = origin + direction * (size * along) + normal * (size * across)

        when (marker) {
            BossMarker.WeightedForearm -> {
                val tip = point(WEIGHT_REACH)
                thick.segment(origin.x, origin.y, tip.x, tip.y)
                solidDots.dot(tip.x, tip.y, size * WEIGHT_RADIUS)
            }
            BossMarker.LongBlade -> {
                val guardA = point(BLADE_GUARD_ALONG, -BLADE_GUARD)
                val guardB = point(BLADE_GUARD_ALONG, BLADE_GUARD)
                val tip = point(BLADE_REACH)
                thin.segment(origin.x, origin.y, tip.x, tip.y)
                thick.segment(guardA.x, guardA.y, guardB.x, guardB.y)
            }
            BossMarker.PairedBlades -> {
                val first = point(PAIRED_REACH)
                thin.segment(origin.x, origin.y, first.x, first.y)
                val second = alternateOrigin + direction * (size * PAIRED_REACH)
                thin.segment(alternateOrigin.x, alternateOrigin.y, second.x, second.y)
            }
            BossMarker.RamPlate -> {
                val rootA = point(RAM_ROOT, -RAM_HALF_HEIGHT)
                val rootB = point(RAM_ROOT, RAM_HALF_HEIGHT)
                val nose = point(RAM_REACH)
                thick.segment(rootA.x, rootA.y, nose.x, nose.y)
                thick.segment(rootB.x, rootB.y, nose.x, nose.y)
                thick.segment(rootA.x, rootA.y, rootB.x, rootB.y)
            }
            BossMarker.NarrowBarrel -> {
                val tip = point(BARREL_MARKER_REACH)
                thin.segment(origin.x, origin.y, tip.x, tip.y)
                glowDots.dot(tip.x, tip.y, size * BARREL_BORE)
            }
            BossMarker.BurstMagazine -> {
                val tip = point(BARREL_MARKER_REACH)
                thin.segment(origin.x, origin.y, tip.x, tip.y)
                val magazine = point(MAGAZINE_ALONG, MAGAZINE_DROP)
                plates.rect(
                    magazine.x - size * MAGAZINE_WIDTH / 2.0,
                    magazine.y - size * MAGAZINE_HEIGHT / 2.0,
                    size * MAGAZINE_WIDTH,
                    size * MAGAZINE_HEIGHT,
                )
                glowDots.dot(tip.x, tip.y, size * BARREL_BORE)
            }
            BossMarker.ScatterPorts -> {
                val muzzle = point(SCATTER_REACH)
                val a = muzzle - normal * (size * SCATTER_HALF_WIDTH)
                val b = muzzle + normal * (size * SCATTER_HALF_WIDTH)
                thick.segment(origin.x, origin.y, muzzle.x, muzzle.y)
                thick.segment(a.x, a.y, b.x, b.y)
                repeat(SCATTER_PORTS) { index ->
                    val across = -SCATTER_HALF_WIDTH + SCATTER_HALF_WIDTH * 2.0 * index / (SCATTER_PORTS - 1)
                    val port = point(SCATTER_REACH, across)
                    glowDots.dot(port.x, port.y, size * SCATTER_PORT_RADIUS)
                }
            }
            BossMarker.LaserLens -> {
                val lens = point(LASER_REACH)
                val railA = origin - normal * (size * LASER_RAIL_GAP)
                val railB = origin + normal * (size * LASER_RAIL_GAP)
                val tipA = lens - normal * (size * LASER_RAIL_GAP)
                val tipB = lens + normal * (size * LASER_RAIL_GAP)
                thin.segment(railA.x, railA.y, tipA.x, tipA.y)
                thin.segment(railB.x, railB.y, tipB.x, tipB.y)
                solidDots.dot(lens.x, lens.y, size * LASER_LENS)
                glowDots.dot(lens.x, lens.y, size * LASER_CORE)
            }
        }
    }

    /** What makes a boss unmistakable at a glance. Nothing else in the game wears one. */
    private fun crown(
        builder: SceneBuilder,
        palette: Palette,
        look: EnemyLook,
        centreX: Double,
        topY: Double,
        style: String = palette.glow[palette.glow.size - 1],
    ) {
        val size = look.height * ZOOM
        val batch = builder.batch(
            Layer.ActorTrim, style, Primitive.Segment,
            strokeWidth(size * CROWN_WIDTH),
        )
        repeat(look.crown) { index ->
            val offset = (index - (look.crown - 1) / 2.0) * size * CROWN_PITCH
            batch.segment(
                centreX + offset, topY,
                centreX + offset, topY - size * CROWN_HEIGHT,
            )
        }
    }

    /** A boss's bar (PROD-043), and any hurt enemy's (PROD-077): the same two batches for everyone. */
    private fun healthBar(
        builder: SceneBuilder,
        palette: Palette,
        centreX: Double,
        y: Double,
        width: Double,
        fraction: Double,
    ) {
        builder.batch(Layer.Effects, Palettes.ENEMY_DARK, Primitive.Rect)
            .rect(centreX - width / 2.0, y, width, BAR_HEIGHT)
        builder.batch(Layer.Effects, palette.hazardGlow, Primitive.Rect)
            .rect(centreX - width / 2.0, y, width * fraction, BAR_HEIGHT)
    }

    /** The hurt flash is a style swap (PROD-076), so it costs no batch a frame did not already open. */
    private fun hurtOr(hurt: Boolean, own: String): String = if (hurt) Palettes.HURT else own

    private fun playerFeedback(hurt: Boolean, healing: Boolean, own: String): String = when {
        hurt -> Palettes.HURT
        healing -> Palettes.HEAL
        else -> own
    }

    private fun projectiles(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
    ) {
        // Four marks in the shooter's look (PROD-071, PROD-080): glow, body and core dots at the
        // shot, and a two-tone tracer back along TRACER_SECONDS of its travel, so a shot reads as a
        // lit line of flight rather than a floating point. Five batches per look, never one per shot.
        sim.projectiles.forEach { shot ->
            val head = Vec2((shot.position.x - camera.x) * ZOOM, (shot.position.y - camera.y) * ZOOM)
            shotMarks(builder, ShotLooks.of(shot, palette), head, head - shot.velocity * (TRACER_SECONDS * ZOOM), shot.radius * ZOOM)
        }
        sim.bossBeams.forEach { beam ->
            val start = Vec2((beam.start.x - camera.x) * ZOOM, (beam.start.y - camera.y) * ZOOM)
            val end = Vec2((beam.end.x - camera.x) * ZOOM, (beam.end.y - camera.y) * ZOOM)
            builder.batch(
                Layer.ShotGlow,
                palette.hazardGlow,
                Primitive.Segment,
                strokeWidth(BOSS_BEAM_BLOOM_WIDTH * beam.strength),
            ).segment(start.x, start.y, end.x, end.y)
            builder.batch(
                Layer.ShotCore,
                ShotLooks.ENEMY_CORE,
                Primitive.Segment,
                strokeWidth(BOSS_BEAM_CORE_WIDTH * beam.strength),
            ).segment(start.x, start.y, end.x, end.y)
        }
        // A shot spent inside the tick it was fired was never in the list above; its last line of
        // flight is kept for the flash window so a point-blank hit is still seen to go somewhere.
        sim.impacts.forEach { hit ->
            val impact = hit.shape as? HitShape.Impact ?: return@forEach
            val head = Vec2((impact.at.x - camera.x) * ZOOM, (impact.at.y - camera.y) * ZOOM)
            // Thins with the window like every indicator; the ladder bounds how many batches open.
            shotMarks(
                builder, ShotLooks.of(impact, palette), head,
                head - impact.velocity * (TRACER_SECONDS * ZOOM), IMPACT_PX * hit.strength, hit.strength,
            )
        }
    }

    /**
     * One shot's marks (`specs/presentation.md`, Weapon effects): a glow dot at [SHOT_GLOW] × the
     * radius, the body at the radius, the core at [SHOT_CORE] ×, and the tracer as a bloom in the
     * glow colour under a line in the core colour. On three layers, glow under body under core,
     * because an impact's widths thin with its window and a fresher impact would otherwise open
     * its wider tracer batch after an older one's dots (review round 1).
     */
    private fun shotMarks(builder: SceneBuilder, look: ShotLook, head: Vec2, tail: Vec2, radius: Double, strength: Double = 1.0) {
        builder.batch(Layer.ShotGlow, look.glow, Primitive.Segment, strokeWidth(TRACER_BLOOM_WIDTH * strength)).segment(head.x, head.y, tail.x, tail.y)
        builder.batch(Layer.ShotGlow, look.glow, Primitive.Dot).dot(head.x, head.y, radius * SHOT_GLOW)
        builder.batch(Layer.ShotBody, look.body, Primitive.Dot).dot(head.x, head.y, radius)
        builder.batch(Layer.ShotCore, look.core, Primitive.Segment, strokeWidth(TRACER_WIDTH * strength)).segment(head.x, head.y, tail.x, tail.y)
        builder.batch(Layer.ShotCore, look.core, Primitive.Dot).dot(head.x, head.y, radius * SHOT_CORE)
    }

    /**
     * Where an instant attack went (PROD-071), at the geometry its hit test used: a beam from the
     * top of the view onto a strike point with a ring at its radius, a chain through the targets
     * struck, or a ring at a blast's radius. Fades over the flash window.
     */
    private fun hitIndicator(builder: SceneBuilder, palette: Palette, sim: GameSimulation, camera: Camera) {
        val hit = sim.lastHit ?: return
        val core = palette.glow[palette.glow.size - 1]
        val bloom = palette.hazardGlow
        fun screen(at: Vec2) = Vec2((at.x - camera.x) * ZOOM, (at.y - camera.y) * ZOOM)
        when (val shape = hit.shape) {
            // Every stroke thins with the window (`specs/presentation.md`: each fades), so the
            // indicator is seen to be an event, not a fixture.
            is HitShape.Beam -> {
                val foot = screen(shape.foot)
                builder.batch(Layer.Effects, bloom, Primitive.Segment, strokeWidth(BEAM_BLOOM_WIDTH * hit.strength))
                    .segment(foot.x, 0.0, foot.x, foot.y)
                builder.batch(Layer.Effects, core, Primitive.Segment, strokeWidth(BEAM_CORE_WIDTH * hit.strength))
                    .segment(foot.x, 0.0, foot.x, foot.y)
                ring(builder, bloom, foot, shape.radius * ZOOM, FLASH_WIDTH * hit.strength)
            }
            is HitShape.Chain -> {
                val links = builder.batch(Layer.Effects, core, Primitive.Segment, strokeWidth(CHAIN_WIDTH * hit.strength))
                val sparks = builder.batch(Layer.Effects, core, Primitive.Dot)
                shape.points.map(::screen).zipWithNext().forEach { (a, b) ->
                    links.segment(a.x, a.y, b.x, b.y)
                    sparks.dot(b.x, b.y, CHAIN_SPARK_PX * hit.strength)
                }
            }
            is HitShape.Ring -> ring(builder, bloom, screen(shape.centre), shape.radius * ZOOM, FLASH_WIDTH * hit.strength)
            is HitShape.Impact -> Unit // drawn with the projectiles, in the shooter's colour
        }
    }

    /** A ring as a closed polygon of segments, in one batch. */
    private fun ring(builder: SceneBuilder, style: String, at: Vec2, radius: Double, width: Double = FLASH_WIDTH) =
        ring(builder, style, at, radius, Layer.Effects, strokeWidth(width), PULSE_SEGMENTS)

    /** A stroked circle as [chords] chords, on [layer] at an already-snapped [width]. */
    private fun ring(builder: SceneBuilder, style: String, at: Vec2, radius: Double, layer: Layer, width: Double, chords: Int) {
        val batch = builder.batch(layer, style, Primitive.Segment, width)
        var previous = Vec2(at.x + radius, at.y)
        for (step in 1..chords) {
            val direction = TrigTable.rotate(Vec2.Right, 360.0 * step / chords)
            val point = Vec2(at.x + direction.x * radius, at.y + direction.y * radius)
            batch.segment(previous.x, previous.y, point.x, point.y)
            previous = point
        }
    }

    private fun swing(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
        muzzle: Vec2,
    ) {
        sim.activeSwing?.let { active ->
            // Use the same interpolated centre as the figure so sub-tick presentation never pulls
            // the fan away from the player; at a tick boundary this is the state's exact origin.
            val origin = Vec2((muzzle.x - camera.x) * ZOOM, (muzzle.y - camera.y) * ZOOM)
            playerSwoosh(builder, palette.hazardGlow, origin, active.sector)
            return
        }
        sim.lastSwing?.let { legacy ->
            swoosh(
                builder,
                palette.hazardGlow,
                Vec2((muzzle.x - camera.x) * ZOOM, (muzzle.y - camera.y) * ZOOM),
                legacy,
            )
        }
    }

    /** The player's closed fan, drawn directly from the sector its hit test consumed. */
    private fun playerSwoosh(builder: SceneBuilder, style: String, origin: Vec2, sector: MeleeSector) {
        val trailing = sector.trailingDirection
        SWOOSH_RINGS.forEachIndexed { ring, fraction ->
            val batch = builder.batch(
                Layer.Effects,
                style,
                Primitive.Segment,
                strokeWidth(SWING_WIDTH * SWOOSH_WIDTHS[ring]),
            )
            val reach = sector.reachPx * fraction * ZOOM
            var previous = origin + trailing * reach
            for (step in 1..SWING_SEGMENTS) {
                val direction = TrigTable.rotate(trailing, sector.sweptDegrees * step / SWING_SEGMENTS)
                val point = origin + direction * reach
                batch.segment(previous.x, previous.y, point.x, point.y)
                previous = point
            }
        }

        val boundary = builder.batch(
            Layer.Effects,
            style,
            Primitive.Segment,
            strokeWidth(SWING_WIDTH * SWOOSH_WIDTHS[0]),
        )
        val reach = sector.reachPx * ZOOM
        val trailingTip = origin + trailing * reach
        val leadingTip = origin + sector.leadingDirection * reach
        boundary.segment(origin.x, origin.y, trailingTip.x, trailingTip.y)
        boundary.segment(origin.x, origin.y, leadingTip.x, leadingTip.y)

        val ribs = builder.batch(
            Layer.Effects,
            style,
            Primitive.Segment,
            strokeWidth(SWING_WIDTH * SWOOSH_WIDTHS.last()),
        )
        for (rib in 1 until SWOOSH_RIBS) {
            val direction = TrigTable.rotate(trailing, sector.sweptDegrees * rib / SWOOSH_RIBS)
            val inner = origin + direction * (sector.reachPx * SWOOSH_RINGS.last() * ZOOM)
            val outer = origin + direction * reach
            ribs.segment(inner.x, inner.y, outer.x, outer.y)
        }
    }

    /**
     * A melee swing as a **swoosh** (PROD-033, PROD-066): three nested arcs along the swing at
     * decreasing radius and width, with spark dots at the leading edge, drawn as bars — no path
     * API and no transform. The outer arc is the reach the hit test used; the arcs fade by
     * retreating from the trailing edge over the swing's window. Shared by the player and every
     * enemy, in whatever colour the caller draws in.
     */
    private fun swoosh(builder: SceneBuilder, style: String, origin: Vec2, swing: SwingVisual) {
        val half = swing.arcDegrees / 2.0
        val span = swing.arcDegrees * swing.strength
        val leading = TrigTable.rotate(swing.direction, half)

        SWOOSH_RINGS.forEachIndexed { ring, fraction ->
            val batch = builder.batch(
                Layer.Effects, style, Primitive.Segment, strokeWidth(SWING_WIDTH * SWOOSH_WIDTHS[ring]),
            )
            val reach = swing.reachPx * fraction * ZOOM
            var previous: Vec2? = null
            for (step in 0..SWING_SEGMENTS) {
                val offset = half - span * step / SWING_SEGMENTS
                val direction = TrigTable.rotate(swing.direction, offset)
                val point = Vec2(origin.x + direction.x * reach, origin.y + direction.y * reach)
                previous?.let { batch.segment(it.x, it.y, point.x, point.y) }
                previous = point
            }
        }

        val sparks = builder.batch(Layer.Effects, style, Primitive.Dot)
        val tip = swing.reachPx * ZOOM
        sparks.dot(origin.x + leading.x * tip, origin.y + leading.y * tip, SPARK_PX * swing.strength)
        sparks.dot(
            origin.x + leading.x * tip * SWOOSH_RINGS[1], origin.y + leading.y * tip * SWOOSH_RINGS[1],
            SPARK_PX * 0.6 * swing.strength,
        )
    }

    /** An activation pulse: a ring around the weapon that grows as it fades (PROD-066). */
    private fun pulse(builder: SceneBuilder, style: String, at: Vec2, flash: MuzzleFlash) =
        ring(builder, style, at, PULSE_PX * (1.0 + PULSE_GROWTH * (1.0 - flash.strength)))

    /**
     * A shot leaving the barrel (PROD-066): a bright core dot, a longer bloom segment along the
     * aim and two short spikes at ±35°, fading over the flash window. Shared by the player and
     * every enemy.
     */
    private fun muzzleFlash(
        builder: SceneBuilder,
        coreStyle: String,
        bloomStyle: String,
        at: Vec2,
        flash: MuzzleFlash,
    ) {
        builder.batch(Layer.Effects, coreStyle, Primitive.Dot).dot(at.x, at.y, FLASH_PX * flash.strength)
        val bloom = builder.batch(Layer.Effects, bloomStyle, Primitive.Segment, strokeWidth(FLASH_WIDTH))
        val reach = FLASH_REACH * flash.strength
        bloom.segment(at.x, at.y, at.x + flash.direction.x * reach, at.y + flash.direction.y * reach)
        for (side in listOf(-FLASH_SPIKE_DEGREES, FLASH_SPIKE_DEGREES)) {
            val spike = TrigTable.rotate(flash.direction, side)
            bloom.segment(
                at.x, at.y,
                at.x + spike.x * reach * FLASH_SPIKE, at.y + spike.y * reach * FLASH_SPIKE,
            )
        }
    }

    /**
     * Where the player is drawn this frame, in world coordinates: the middle of the body.
     *
     * The **feet** are interpolated, not the top-left corner. Crouching re-anchors `y` by the
     * twelve-pixel difference between the two stance heights, so interpolating the corner and then
     * adding the new height threw the figure a whole stance-height off the floor for the frames
     * either side of a crouch — 42 screen pixels at this zoom. The feet are continuous across a
     * stance change by construction, because that is the point the movement model anchors.
     *
     * Exposed as [drawnCentre] because the camera has to follow the same point. It did not: it
     * followed the stance-dependent corner, so at the vertical dead-zone edge a crouch moved the
     * whole world by that same 42 pixels while the player stood still. Fixing the figure and
     * leaving the camera alone fixed half a defect.
     */
    /**
     * The point the camera follows: the standing head height above the interpolated feet.
     *
     * **Not the body's centre**, which is what it followed after the round-six correction — that
     * subtracts the *current* stance height, so crouching moved it six world pixels (21 on screen)
     * while the feet stayed put, and at the vertical dead-zone edge the whole view lurched. This
     * uses the standing height always, so a stance change cannot move it at all, and it reproduces
     * the framing the camera had before any of this: the top of a standing player's head.
     */
    fun drawnFollow(sim: GameSimulation, alpha: Double): Vec2 =
        Vec2(drawnMuzzle(sim, alpha).x, drawnFeet(sim, alpha) - Physics.Default.standingHeight)

    private fun drawnFeet(sim: GameSimulation, alpha: Double): Double {
        val physics = Physics.Default
        val previousFeet = sim.previousPlayer.y + sim.previousPlayer.height(physics)
        val feet = sim.player.y + sim.player.height(physics)
        return previousFeet + (feet - previousFeet) * alpha
    }

    private fun drawnMuzzle(sim: GameSimulation, alpha: Double): Vec2 {
        val state = sim.player
        val previous = sim.previousPlayer
        val physics = Physics.Default
        val previousFeet = previous.y + previous.height(physics)
        val feet = previousFeet + (state.y + state.height(physics) - previousFeet) * alpha
        return Vec2(
            previous.x + (state.x - previous.x) * alpha + physics.width / 2.0,
            feet - state.height(physics) / 2.0,
        )
    }

    private fun player(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
        muzzle: Vec2,
    ) {
        val state = sim.player
        val pose = playerPose(sim)
        val x = (muzzle.x - camera.x) * ZOOM
        val feet = (muzzle.y + state.height(Physics.Default) / 2.0 - camera.y) * ZOOM
        val hurt = sim.playerHurtSecondsLeft > 0.0
        val healing = sim.playerHealSecondsLeft > 0.0

        figure(
            builder, palette, pose, x, feet,
            look = null,
            bodyStyle = playerFeedback(hurt, healing, PLAYER_BODY),
            limbStyle = playerFeedback(hurt, healing, PLAYER_LIMB),
            trimStyle = playerFeedback(hurt, healing, palette.accent),
            armStyle = playerFeedback(hurt, healing, PLAYER_ARM),
            // The player carries a weapon at all times (PROD-023), and `specs/presentation.md` says it
            // attaches to the lead hand. It did not: the geometry was gated on an enemy archetype
            // being armed, and the player has no archetype, so the one figure that always holds
            // something was the only one drawn empty-handed.
            armed = true,
            weaponReach = weaponReach(sim.run.loadout.weapon),
            // The same geometry the drop was drawn with, so picking a weapon up teaches the player
            // what that shape means on the floor of the next map (PROD-049).
            heldIcon = WeaponIcons.of(sim.run.loadout.weapon.id),
            heldFacing = sim.facing,
            // Fixed rather than themed. The player has to be the one figure on screen that is never
            // in doubt, and against a dark map full of enemies in the same faction colours a themed
            // eye put them in the same read as everything trying to kill them.
            eyeStyle = PLAYER_EYE,
        )

        sim.deathSequence?.let { terminal ->
            playerDeathEffect(builder, terminal.effect, pose, x, feet, terminal.ageSeconds)
        }

        // The cue sits at the weapon, not the hand (PROD-066): the flash at the muzzle of a weapon
        // that has one, a pulse around the dish of one that does not.
        sim.lastShot?.takeIf { sim.deathSequence == null }?.let { flash ->
            val weapon = sim.run.loadout.weapon
            val muzzle = Vec2(x, feet) + muzzleOf(pose, weapon) * ZOOM
            if (weapon.anchor == Anchor.Cursor || weapon.cls == WeaponClass.Psychic) {
                pulse(builder, palette.hazardGlow, muzzle, flash)
            } else {
                muzzleFlash(builder, palette.glow[palette.glow.size - 1], palette.hazardGlow, muzzle, flash)
            }
        }
    }

    /** The normal lethal-frame rig at age zero, then the pure two-second terminal collapse. */
    fun playerPose(sim: GameSimulation): Pose {
        val ordinary = Actor.pose(motionOf(sim))
        val terminal = sim.deathSequence ?: return ordinary
        return Actor.deathPose(ordinary, terminal.collapseProgress, sim.facing)
    }

    private fun playerDeathEffect(
        builder: SceneBuilder,
        effect: PlayerDeathEffect,
        pose: Pose,
        x: Double,
        feet: Double,
        ageSeconds: Double,
    ) {
        when (effect) {
            PlayerDeathEffect.None -> Unit
            PlayerDeathEffect.Poison -> poisonBubbles(builder, pose, x, feet, ageSeconds)
            PlayerDeathEffect.Flame -> playerFlames(builder, pose, x, feet, ageSeconds)
            PlayerDeathEffect.Bleed -> playerBleed(builder, pose, x, feet, ageSeconds)
        }
    }

    private fun poisonBubbles(
        builder: SceneBuilder,
        pose: Pose,
        x: Double,
        feet: Double,
        timeSeconds: Double,
    ) {
        val outer = builder.batch(Layer.ActorStatus, POISON_OUTER, Primitive.Segment, POISON_OUTER_WIDTH)
        val core = builder.batch(Layer.ActorStatus, POISON_CORE, Primitive.Segment, POISON_CORE_WIDTH)
        repeat(STATUS_COUNT) { index ->
            val progress = positiveRemainder(timeSeconds / POISON_PERIOD + index.toDouble() / STATUS_COUNT, 1.0)
            val anchor = bodyPoint(pose, (index + 1.0) / (STATUS_COUNT + 1.0), x, feet)
            val centre = Vec2(
                anchor.x + (index - 1) * POISON_SPACING,
                anchor.y - POISON_RISE * progress,
            )
            val radius = POISON_MIN_RADIUS + POISON_GROWTH * progress
            statusRing(outer, centre, radius)
            statusRing(core, centre, radius * POISON_CORE_SCALE)
        }
    }

    private fun playerFlames(
        builder: SceneBuilder,
        pose: Pose,
        x: Double,
        feet: Double,
        timeSeconds: Double,
    ) {
        val outer = builder.batch(Layer.ActorStatus, BURN_OUTER, Primitive.Segment, BURN_OUTER_WIDTH)
        val core = builder.batch(Layer.ActorStatus, BURN_CORE, Primitive.Segment, BURN_CORE_WIDTH)
        val outerEmbers = builder.batch(Layer.ActorStatus, BURN_OUTER, Primitive.Dot)
        val coreEmbers = builder.batch(Layer.ActorStatus, BURN_CORE, Primitive.Dot)
        repeat(STATUS_COUNT) { index ->
            val progress = positiveRemainder(timeSeconds / BURN_PERIOD + index.toDouble() / STATUS_COUNT, 1.0)
            val anchor = bodyPoint(pose, (index + 1.0) / (STATUS_COUNT + 1.0), x, feet)
            val atX = anchor.x + (index - 1) * PLAYER_STATUS_SPACING
            val atY = anchor.y - PLAYER_STATUS_TRAVEL * progress
            val size = BURN_SIZE * (1.0 - STATUS_SHRINK * progress)
            outer.segment(atX - size, atY + size, atX, atY - size)
            outer.segment(atX, atY - size, atX + size, atY + size)
            core.segment(atX - size * 0.55, atY + size * 0.55, atX, atY - size * 0.55)
            core.segment(atX, atY - size * 0.55, atX + size * 0.55, atY + size * 0.55)
            outerEmbers.dot(atX + size * 0.7, atY - size * 1.45, BURN_EMBER)
            coreEmbers.dot(atX + size * 0.7, atY - size * 1.45, BURN_EMBER * 0.45)
        }
    }

    private fun playerBleed(
        builder: SceneBuilder,
        pose: Pose,
        x: Double,
        feet: Double,
        timeSeconds: Double,
    ) {
        val drops = builder.batch(Layer.ActorStatus, BLEED, Primitive.Segment, BLEED_WIDTH)
        val bulbs = builder.batch(Layer.ActorStatus, BLEED, Primitive.Dot)
        repeat(STATUS_COUNT) { index ->
            val progress = positiveRemainder(timeSeconds / BLEED_PERIOD + index.toDouble() / STATUS_COUNT, 1.0)
            val anchor = bodyPoint(pose, (index + 1.0) / (STATUS_COUNT + 1.0), x, feet)
            val atX = anchor.x + (index - 1) * PLAYER_STATUS_SPACING
            val atY = anchor.y + PLAYER_STATUS_TRAVEL * progress
            val size = BLEED_SIZE * (1.0 - STATUS_SHRINK * progress)
            bulbs.dot(atX, atY - size * 0.35, size * 0.58)
            drops.segment(atX - size * 0.62, atY - size * 0.25, atX, atY + size)
            drops.segment(atX + size * 0.62, atY - size * 0.25, atX, atY + size)
        }
    }

    private fun bodyPoint(pose: Pose, progress: Double, x: Double, feet: Double): Vec2 {
        val point = pose.hip + (pose.neck - pose.hip) * progress
        return Vec2(x + point.x * ZOOM, feet + point.y * ZOOM)
    }

    private fun statusRing(batch: DrawBatch, centre: Vec2, radius: Double) {
        var previous = centre + Vec2(radius, 0.0)
        repeat(POISON_CHORDS) { index ->
            val next = centre +
                TrigTable.rotate(Vec2.Right, (index + 1.0) * STATUS_CIRCLE / POISON_CHORDS) * radius
            batch.segment(previous.x, previous.y, next.x, next.y)
            previous = next
        }
    }

    /**
     * How far the held weapon reaches, from its own range.
     *
     * A broken bottle and a railgun should not be the same line. Scaled from the weapon's declared
     * range and clamped, so the registry drives it and a long-range weapon does not draw a barrel
     * across the map.
     */
    fun weaponReach(weapon: io.github.ksean.cyberslop.combat.WeaponSpec): Double =
        (weapon.rangePx / (TILE_SIZE * WEAPON_REACH_TILES)).coerceIn(WEAPON_REACH_MIN, WEAPON_REACH_MAX)

    /** What the player's rig needs, read off the simulation and nothing else (ENG-062). */
    fun motionOf(sim: GameSimulation): Motion {
        val state = sim.player
        val active = sim.activeSwing
        return Motion(
            speedX = state.vx,
            verticalSpeed = state.vy,
            onGround = state.onGround,
            crouched = state.stance == io.github.ksean.cyberslop.physics.Stance.Crouch,
            facing = sim.facing,
            stridePx = sim.playerStridePx,
            secondsSinceShot = sim.lastShot
                ?.let { it.totalSeconds - it.secondsLeft } ?: Double.MAX_VALUE,
            secondsSinceSwing = active?.elapsedSeconds ?: sim.lastSwing
                ?.let { it.totalSeconds - it.secondsLeft } ?: Double.MAX_VALUE,
            // The simulation's own windows, so the arm finishes its sweep on the tick the swing
            // stops being drawn rather than snapping back partway through it.
            shotSeconds = sim.lastShot?.totalSeconds ?: Actor.FIRE_SECONDS,
            swingSeconds = active?.totalSeconds ?: sim.lastSwing?.totalSeconds ?: Actor.SWING_SECONDS,
            swingDirection = active?.direction ?: sim.lastSwing?.direction ?: Vec2.Right,
            swingArcDegrees = active?.arcDegrees,
            swingProgress = active?.progress,
            // The weapon points where the game is aiming it. The player never chooses a direction,
            // so the held weapon is the only thing that tells them what has been locked onto.
            weaponAim = sim.aimDirection,
        )
    }

    /**
     * The corridor the spine swept, for development.
     *
     * Here rather than in the browser layer for the same reason as everything else: it is geometry
     * and a colour, and the renderer is not allowed to choose either (ENG-060).
     */
    private fun masks(builder: SceneBuilder, level: Level, camera: Camera) {
        val first = (TileMap.toTile(camera.x) - 1).coerceAtLeast(0)
        val last = (TileMap.toTile(camera.x + camera.viewWidth) + 1)
            .coerceAtMost(level.widthTiles - 1)
        val top = (TileMap.toTile(camera.y) - 1).coerceAtLeast(0)
        val bottom = (TileMap.toTile(camera.y + camera.viewHeight) + 1)
            .coerceAtMost(level.tiles.height - 1)

        val batch = builder.batch(Layer.Debug, ARC_MASK, Primitive.Rect)
        val size = TILE_SIZE * ZOOM
        for (x in first..last) {
            for (y in top..bottom) {
                if (!level.arcMask[x, y]) continue
                batch.rect(
                    (TileMap.toWorld(x) - camera.x) * ZOOM,
                    (TileMap.toWorld(y) - camera.y) * ZOOM,
                    size,
                    size,
                )
            }
        }
    }

    // ---- heads-up display --------------------------------------------------------------------

    /**
     * One icon in the display's left column, sitting on the same baseline as the name beside it.
     *
     * Halo and outline on two layers for the reason `IconPainter` states: on one, the order is
     * whichever batch was opened first, and a display listing items of several rarities opens them
     * in the wrong one.
     */
    private fun hudIcon(builder: SceneBuilder, icon: Icon, line: Double) {
        IconPainter.paint(
            builder,
            icon,
            HUD_MARGIN + HUD_ICON,
            line - HUD_ICON_RISE,
            HUD_ICON,
            haloLayer = Layer.Hud,
            outlineLayer = Layer.HudOverlay,
            wearLayer = Layer.HudWear,
        )
    }

    private fun hud(
        builder: SceneBuilder,
        palette: Palette,
        model: HudModel,
        width: Double,
        height: Double,
    ) {
        val panel = builder.batch(Layer.Hud, HUD_BACK, Primitive.Rect)
        val barWidth = width * HUD_BAR_FRACTION

        panel.rect(HUD_MARGIN, HUD_MARGIN, barWidth, HUD_BAR_HEIGHT)
        builder.batch(Layer.HudOverlay, HUD_HEALTH, Primitive.Rect)
            .rect(HUD_MARGIN, HUD_MARGIN, barWidth * model.healthFraction, HUD_BAR_HEIGHT)

        builder.text(
            TextItem(
                "${model.health}/${model.maxHealth}",
                HUD_MARGIN + HUD_TEXT_INSET,
                HUD_MARGIN + HUD_BAR_HEIGHT - HUD_TEXT_INSET,
                HUD_SMALL, HUD_TEXT, bold = true,
            ),
        )
        // The weapon the player is carrying, drawn as the shape it was when it was on the floor —
        // which is what makes the next one of those on the floor mean something (PROD-049).
        val weaponLine = HUD_MARGIN + HUD_BAR_HEIGHT + HUD_LINE
        hudIcon(builder, WeaponIcons.of(model.weaponId), line = weaponLine)
        builder.text(
            TextItem(
                model.weaponName,
                HUD_MARGIN + HUD_ICON_COLUMN,
                weaponLine,
                HUD_BODY, palette.accent, bold = true,
            ),
        )
        builder.text(
            TextItem(
                "Map ${model.mapIndex}/${model.mapCount} — ${model.themeName}",
                width - HUD_MARGIN,
                HUD_MARGIN + HUD_LINE,
                HUD_BODY, HUD_TEXT, TextAlign.Right,
            ),
        )
        builder.text(
            TextItem(
                "Scrap ${model.scrap}",
                width - HUD_MARGIN,
                HUD_MARGIN + HUD_LINE * 2,
                HUD_SMALL, palette.glow[palette.glow.size - 1], TextAlign.Right,
            ),
        )

        // The build, which the HUD never showed at all — in a game whose progression is loot.
        val empty = builder.batch(Layer.Hud, HUD_BACK, Primitive.Rect)
        val pips = builder.batch(Layer.HudOverlay, palette.accent, Primitive.Rect)
        model.powerups.forEachIndexed { index, stack ->
            val y = HUD_MARGIN + HUD_BAR_HEIGHT + HUD_LINE * (index + 2)
            hudIcon(builder, PowerupIcons.of(stack.id), line = y)
            builder.text(TextItem(stack.name, HUD_MARGIN + HUD_ICON_COLUMN, y, HUD_SMALL, HUD_TEXT))
            repeat(io.github.ksean.cyberslop.loot.Powerup.MAX_STACKS) { pip ->
                val batch = if (pip < stack.stacks) pips else empty
                batch.rect(
                    HUD_MARGIN + HUD_PIP_X + pip * (HUD_PIP + HUD_PIP_GAP),
                    y - HUD_PIP,
                    HUD_PIP,
                    HUD_PIP,
                )
            }
        }

        model.bossName?.let { name ->
            val barLeft = width / 2.0 - width * BOSS_BAR_FRACTION / 2.0
            val barTop = height - HUD_MARGIN - HUD_BAR_HEIGHT
            builder.batch(Layer.Hud, HUD_BACK, Primitive.Rect)
                .rect(barLeft, barTop, width * BOSS_BAR_FRACTION, HUD_BAR_HEIGHT)
            builder.batch(Layer.HudOverlay, palette.hazardGlow, Primitive.Rect)
                .rect(
                    barLeft, barTop,
                    width * BOSS_BAR_FRACTION * model.bossFraction, HUD_BAR_HEIGHT,
                )
            builder.text(
                TextItem(
                    name, width / 2.0, barTop - HUD_TEXT_INSET,
                    HUD_BODY, palette.hazardGlow, TextAlign.Centre, bold = true,
                ),
            )
        }
    }

    private fun discoveryCard(
        builder: SceneBuilder,
        palette: Palette,
        entry: DiscoveryEntry,
        width: Double,
        height: Double,
    ) {
        val cardWidth = minOf(width * DISCOVERY_WIDTH_FRACTION, DISCOVERY_MAX_WIDTH)
        val cardHeight = minOf(height * DISCOVERY_HEIGHT_FRACTION, DISCOVERY_MAX_HEIGHT)
        val left = (width - cardWidth) / 2.0
        val top = (height - cardHeight) / 2.0
        val panel = builder.batch(Layer.Hud, DISCOVERY_DIM, Primitive.Rect)
        panel.rect(0.0, 0.0, width, height)
        builder.batch(Layer.Hud, palette.accent, Primitive.Rect)
            .rect(left, top, cardWidth, cardHeight)
        builder.batch(Layer.Hud, DISCOVERY_BACK, Primitive.Rect).rect(
            left + DISCOVERY_BORDER,
            top + DISCOVERY_BORDER,
            cardWidth - DISCOVERY_BORDER * 2.0,
            cardHeight - DISCOVERY_BORDER * 2.0,
        )

        val centreX = width / 2.0
        val iconY = top + cardHeight * DISCOVERY_ICON_Y
        val iconScale = minOf(cardWidth, cardHeight) * DISCOVERY_ICON_SCALE
        IconPainter.paint(
            builder,
            entry.icon,
            centreX,
            iconY,
            iconScale,
            haloLayer = Layer.Hud,
            outlineLayer = Layer.HudOverlay,
            wearLayer = Layer.HudWear,
        )

        builder.text(
            TextItem(
                "NEW DISCOVERY",
                centreX,
                top + DISCOVERY_LABEL_TOP,
                DISCOVERY_LABEL_SIZE,
                palette.glow.last(),
                TextAlign.Centre,
                bold = true,
            ),
        )
        builder.text(
            TextItem(
                entry.name,
                centreX,
                top + cardHeight * DISCOVERY_NAME_Y,
                DISCOVERY_NAME_SIZE,
                palette.accent,
                TextAlign.Centre,
                bold = true,
            ),
        )
        wrapDiscoveryCopy(entry.description).forEachIndexed { index, line ->
            builder.text(
                TextItem(
                    line,
                    centreX,
                    top + cardHeight * DISCOVERY_COPY_Y + index * DISCOVERY_COPY_LINE,
                    DISCOVERY_COPY_SIZE,
                    HUD_TEXT,
                    TextAlign.Centre,
                ),
            )
        }
    }

    private fun wrapDiscoveryCopy(text: String): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        text.split(' ').forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (candidate.length <= DISCOVERY_COPY_COLUMNS || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private val STROKE_LADDER = doubleArrayOf(
        1.5, 2.0, 2.75, 3.5, 4.5, 6.0, 8.0, 10.5, 14.0, 18.0, 24.0, 32.0, 42.0, 56.0,
    )

    // Layout and proportion. Everything here is either a screen pixel or a fraction of a figure.
    private const val BACKDROP_HORIZON = 0.72
    private const val WINDOW_INSET = 0.30
    private const val WINDOW_FILL = 0.34
    private const val BACKDROP_STRUCTURE_STROKE = 2.75
    private const val BACKDROP_LIGHT_STROKE = 1.5
    private const val BACKDROP_ROOF_SCALE = 0.22
    private const val BACKDROP_FEATURE_SCALE = 0.12
    private const val BACKDROP_FEATURE_MIN = 2.0

    /**
     * How much of the camera's vertical travel the skyline takes, and how far it may ever slide.
     *
     * Un-damped, the horizon left the screen entirely the first time the player dropped down a
     * shaft: the backdrop is the far distance, and the far distance does not swing through a third
     * of the view because someone fell two tiles.
     */
    private const val VERTICAL_PARALLAX = 0.30
    private const val VERTICAL_LIMIT = 0.18
    private const val HAZE_PX = 6.0
    private const val EDGE_PX = 4.0
    private const val SEAM_PX = 2.0
    const val EXIT_SURFACE = "#38a8ff"
    const val EXIT_SPARK = "#bfeaff"
    const val EXIT_SPARK_PERIOD = 0.90
    private const val EXIT_SPARK_COUNT = 3
    private const val EXIT_SPARK_RISE = 8.0
    private const val EXIT_SPARK_RADIUS = 2.5
    private const val EXIT_PHASE_STEPS = 17
    private const val EXIT_PHASE_X = 7
    private const val EXIT_PHASE_Y = 11
    private const val EXIT_PHASE_INDEX = 5
    private val EXIT_SPARK_X = doubleArrayOf(0.22, 0.51, 0.80)
    private const val BUBBLE_CYCLE = 1.2
    private const val BUBBLE_RISE = 0.70
    private const val BUBBLE_MIN_RADIUS = 1.5
    private const val BUBBLE_MAX_RADIUS = 4.0
    private const val BUBBLE_RING = 0.9
    private const val BUBBLE_INNER_MIN = 0.5
    private const val BUBBLE_PHASE_STEPS = 17.0
    private const val BUBBLE_PHASE_X = 7
    private const val BUBBLE_PHASE_Y = 11
    private const val BUBBLE_PHASE_INDEX = 5
    private val BUBBLE_X = doubleArrayOf(0.2, 0.5, 0.8)
    private const val ARENA_PX = 3.0
    private const val FIRE_OUTER = "#ff5a1f"
    private const val FIRE_CORE = "#ffd166"
    private const val JET_WAVE_PERIOD = 0.72
    private const val JET_PHASE_EPSILON = 1e-9
    private const val JET_WAVE_TURNS = 0.6
    private const val JET_ZIGZAG = 0.09
    private const val JET_WAVE = 0.04
    private const val JET_BRANCH = 0.18
    private val JET_LENGTHS = doubleArrayOf(1.0, 0.72, 0.56)
    private val JET_DIRECTIONS = doubleArrayOf(0.0, -1.0, 1.0)
    private val JET_PHASES = doubleArrayOf(0.0, 0.34, 0.67)
    private val JET_OUTER_WIDTHS = doubleArrayOf(0.43, 0.34, 0.26, 0.18, 0.08)
    private val JET_CORE_WIDTHS = doubleArrayOf(0.18, 0.13, 0.09, 0.055, 0.025)
    private const val PIPE_NECK_WIDTH = 0.54
    private const val PIPE_NECK_HEIGHT = 0.46
    private const val PIPE_MOUTH_RADIUS = 0.23
    private const val PIPE_MOUTH_DROP = 0.07
    private const val PIPE_RIM_WIDTH = 5.0
    private const val PIPE_RIM_RADIUS = 0.25
    private const val PIPE_RIM_GAP = 0.055
    private const val PIPE_RIM_DROP = 0.075
    private const val PIPE_RIM_LIFT = 0.055
    private const val PIPE_RIM_LEFT_DROP = 0.055
    private const val PIPE_RIM_RIGHT_DROP = 0.11
    private const val PIPE_CRACK_WIDTH = 2.5
    private const val PIPE_CRACK_START_X = 0.08
    private const val PIPE_CRACK_START_Y = 0.22
    private const val PIPE_CRACK_JOINT_X = 0.17
    private const val PIPE_CRACK_JOINT_Y = 0.31
    private const val PIPE_CRACK_END_X = 0.11
    private const val PIPE_CRACK_END_Y = 0.43

    private const val PIP_PX = 2.0
    private const val PIP_HALO = 1.25
    private const val PIP_PITCH = 7.0
    private const val PIP_DROP = 7.0
    private const val OFF_SCREEN = 120.0
    private const val REFERENCE_SPEED = 70.0

    /** Matches the simulation's own enemy half-extent, used to find an enemy's centre. */
    private const val ENEMY_HALF = 7.0
    const val BURN_PERIOD = 0.75
    const val BLEED_PERIOD = 0.65
    const val BURN_OUTER = "#ff5a1f"
    const val BURN_CORE = "#ffd166"
    const val BLEED = "#d0143c"
    const val POISON_OUTER = "#42d68a"
    const val POISON_CORE = "#d5ffe9"
    private const val STATUS_COUNT = 3
    private const val STATUS_PHASE_STEPS = 17
    private const val STATUS_SPACING = 0.28
    private const val STATUS_START = 0.16
    private const val STATUS_TRAVEL = 0.68
    private const val STATUS_SHRINK = 0.42
    private const val BURN_SIZE = 5.5
    private const val BURN_EMBER = 2.1
    private const val BURN_OUTER_WIDTH = 4.0
    private const val BURN_CORE_WIDTH = 2.0
    private const val BLEED_SIZE = 5.0
    private const val BLEED_WIDTH = 2.0
    private const val POISON_PERIOD = 0.80
    private const val STATUS_CIRCLE = 360.0
    private const val POISON_CHORDS = 10
    private const val POISON_MIN_RADIUS = 2.4
    private const val POISON_GROWTH = 3.4
    private const val POISON_CORE_SCALE = 0.58
    private const val POISON_RISE = 22.0
    private const val POISON_SPACING = 5.0
    private const val POISON_OUTER_WIDTH = 3.0
    private const val POISON_CORE_WIDTH = 1.5
    private const val PLAYER_STATUS_SPACING = 7.0
    private const val PLAYER_STATUS_TRAVEL = 18.0
    private const val LIMB = 0.09
    private const val TORSO = 2.0
    private const val ARM = 0.8
    private const val EYE_LEAD = 0.45
    private const val EYE_SIZE = 0.35
    private const val BARREL_REACH = 0.5
    private const val WEAPON_REACH_TILES = 6.0
    private const val WEAPON_REACH_MIN = 0.35
    private const val WEAPON_REACH_MAX = 0.95

    /**
     * How much of the plain barrel's reach the held icon spans.
     *
     * Under one because an icon's box is square while a weapon is long: drawn at the full reach a
     * railgun stood as tall as the player's torso. Rendered at 1.0, 0.8 and 0.7 against a frame.
     */
    private const val HELD_ICON = 0.72
    private const val PLATE_WIDTH = 0.34
    private const val PLATE_HEIGHT = 0.05
    private const val PLATE_TOP = 0.26
    private const val PLATE_PITCH = 0.09
    private const val SPIKE_PITCH = 0.11
    private const val SPIKE_BASE = 0.10
    private const val SPIKE_TIP = 0.26
    private const val SPIKE_RISE = 0.12
    private const val SPIKE_WIDTH = 0.04
    private const val HOVER_DEGREES_PER_SECOND = 220.0
    private const val HOVER_BOB = 0.18
    private const val HOVER_BANK = 0.20
    private const val HOVER_CLEARANCE = 0.30
    private const val POD_HEIGHT = 0.55
    private const val EYE_OFFSET = 0.22
    private const val EYE = 0.13
    private const val PLUME_SPREAD = 0.24
    private const val PLUME_LENGTH = 0.34
    private const val PLUME_WIDTH = 0.10
    private const val BASE_HEIGHT = 0.42
    private const val BASE_WIDTH = 0.42
    private const val TURRET_HEAD = 0.52
    private const val BARREL = 0.7
    private const val BARREL_WIDTH = 0.14
    private const val CRAWLER_FOLDED_HEIGHT = 0.08
    private const val CRAWLER_LEG_HEIGHT = 0.30
    private const val CRAWLER_TUCK_HEIGHT = 0.18
    private const val CRAWLER_LEG_WIDTH = 0.08
    private const val CRAWLER_HIP_SPREAD = 0.24
    private const val CRAWLER_KNEE_SPREAD = 0.43
    private const val CRAWLER_TUCK_SPREAD = 0.16
    private const val CRAWLER_FOOT_SPREAD = 0.55
    private const val CRAWLER_FOOT_FOLDED = 0.22
    private const val CRAWLER_KNEE_HEIGHT = 0.48
    private const val CRAWLER_TUCK_FOOT = 0.25
    private const val CROWN_PITCH = 0.11
    private const val CROWN_HEIGHT = 0.16
    private const val CROWN_WIDTH = 0.05
    private const val BAR_GAP = 10.0
    private const val BAR_HEIGHT = 6.0
    /** How much of a projectile's travel its tracer shows (`specs/presentation.md`). */
    const val TRACER_SECONDS = 0.05

    /** A shot's glow and core dots as multiples of its hit radius (PROD-080). */
    const val SHOT_GLOW = 1.8
    const val SHOT_CORE = 0.45
    private const val TRACER_WIDTH = 2.0

    /** The bloom under a tracer's core line. */
    private const val TRACER_BLOOM_WIDTH = 5.0

    /** The body radius an impact is drawn at while it fades; the live shot's radius is not kept. */
    private const val IMPACT_PX = 5.0
    private const val BEAM_CORE_WIDTH = 2.0
    private const val BEAM_BLOOM_WIDTH = 8.0
    private const val BOSS_BEAM_CORE_WIDTH = 5.0
    private const val BOSS_BEAM_BLOOM_WIDTH = 14.0
    private const val CHAIN_WIDTH = 2.0
    private const val CHAIN_SPARK_PX = 4.0
    private const val SWING_SEGMENTS = 10
    private const val SWOOSH_RIBS = 3
    private const val SWING_WIDTH = 4.0
    private const val FLASH_PX = 7.0
    private const val FLASH_REACH = 22.0
    private const val FLASH_WIDTH = 3.0
    private const val FLASH_SPIKE_DEGREES = 35.0
    private const val PULSE_PX = 10.0
    private const val CHARGE = 0.16
    private const val CHARGE_OFFSET = 0.1
    private const val PULSE_GROWTH = 0.6
    private const val PULSE_SEGMENTS = 12

    /** A drop's kind ring, drawn rounder than an effect's pulse because it is a fixture, not a flash. */
    private const val KIND_RING_SEGMENTS = 16

    /** The hover phase `x / 40` radians (`specs/presentation.md`), in degrees per world px. */
    private const val HOVER_PHASE_DEGREES_PER_PX = 1.4324
    private const val STRIP_WIDTH = 2.0
    private const val STRIP_BASE_PX = 4.0
    private const val STRIP_POINTS = 3
    private const val STRIP_TOP = 0.35
    const val GLASS_RUST = "#7a3f2b"
    const val GLASS_EDGE = "#b66a45"
    private const val GLASS_WIDTH = 2.0
    private const val GLASS_CRUMB_RADIUS = 1.5
    private val GLASS_X_START = doubleArrayOf(0.08, 0.25, 0.43, 0.61, 0.79)
    private val GLASS_X_END = doubleArrayOf(0.21, 0.36, 0.57, 0.75, 0.92)
    private val GLASS_Y_START = doubleArrayOf(0.04, 0.16, 0.08, 0.20, 0.10)
    private val GLASS_Y_END = doubleArrayOf(0.22, 0.06, 0.27, 0.09, 0.24)
    private val GLASS_CRUMB_X = doubleArrayOf(0.18, 0.52, 0.84)
    private val GLASS_CRUMB_Y = doubleArrayOf(0.08, 0.14, 0.05)
    private const val DRUM_WIDTH = 0.7
    private const val DRUM_HEIGHT = 1.0
    private const val BAND_PX = 2.0
    private const val BARREL_WAVE_PERIOD = 0.72
    private const val BARREL_WAVE_TURNS = 0.55
    private const val BARREL_ZIGZAG = 0.05
    private const val BARREL_WAVE = 0.035
    private const val BARREL_PHASE_STEPS = 17
    private const val BARREL_PHASE_X = 7
    private const val BARREL_PHASE_Y = 11
    private val BARREL_FLAME_LENGTHS = doubleArrayOf(0.76, 0.57, 0.49)
    private val BARREL_FLAME_ANCHORS = doubleArrayOf(0.0, -0.16, 0.16)
    private val BARREL_FLAME_PHASES = doubleArrayOf(0.0, 0.34, 0.67)
    private val BARREL_OUTER_WIDTHS = doubleArrayOf(0.16, 0.12, 0.08, 0.045)
    private val BARREL_CORE_WIDTHS = doubleArrayOf(0.07, 0.05, 0.032, 0.015)
    private val BOSS_SCATTER_ANGLES = doubleArrayOf(-15.0, -7.5, 0.0, 7.5, 15.0)
    private const val BOSS_EVENT_FLASH_SECONDS = 0.10
    private const val BOSS_EVENT_SWING_SECONDS = 0.16
    private const val BOSS_SWING_ARC = 90.0
    private const val SLAM_FORWARD = 0.5
    private const val SLAM_DOWN = 0.87
    private const val BOSS_BARREL_REACH = 0.34
    private const val SIGNATURE_HEALTH = 0.60
    private const val SIGNATURE_BACK = 0.14
    private const val SIGNATURE_HIGH = 0.10
    private const val SIGNATURE_DEPLOY_FORWARD = 0.08
    private const val SIGNATURE_DEPLOY_HIGH = 0.16
    private const val HARDWARE_THIN = 0.035
    private const val HARDWARE_THICK = 0.085
    private const val WEIGHT_REACH = 0.18
    private const val WEIGHT_RADIUS = 0.09
    private const val BLADE_GUARD_ALONG = 0.04
    private const val BLADE_GUARD = 0.07
    private const val BLADE_REACH = 0.45
    private const val PAIRED_REACH = 0.27
    private const val RAM_ROOT = 0.02
    private const val RAM_HALF_HEIGHT = 0.13
    private const val RAM_REACH = 0.30
    private const val BARREL_MARKER_REACH = 0.35
    private const val BARREL_BORE = 0.025
    private const val MAGAZINE_ALONG = 0.15
    private const val MAGAZINE_DROP = 0.09
    private const val MAGAZINE_WIDTH = 0.09
    private const val MAGAZINE_HEIGHT = 0.18
    private const val SCATTER_REACH = 0.22
    private const val SCATTER_HALF_WIDTH = 0.12
    private const val SCATTER_PORTS = 5
    private const val SCATTER_PORT_RADIUS = 0.022
    private const val LASER_REACH = 0.24
    private const val LASER_RAIL_GAP = 0.07
    private const val LASER_LENS = 0.09
    private const val LASER_CORE = 0.045
    private const val LASER_CHARGE_BASE = 0.45
    private const val LASER_CHARGE_RANGE = 0.75
    private const val FLASH_SPIKE = 0.45
    private const val SPARK_PX = 2.5
    private val SWOOSH_RINGS = doubleArrayOf(1.0, 0.82, 0.64)
    private val SWOOSH_WIDTHS = doubleArrayOf(1.0, 0.7, 0.45)

    /**
     * The player's own colours, fixed rather than themed.
     *
     * They are public because they are how the player is identified — by a viewer glancing at the
     * screen, and by a test picking the player's own geometry out of a composed frame.
     */
    const val PLAYER_BODY = "#4a6a86"
    const val PLAYER_LIMB = "#2a3a4a"
    const val PLAYER_ARM = "#38566d"
    const val PLAYER_EYE = "#67e8f9"

    const val RAMEN_OUTLINE = "#20110f"
    const val RAMEN_BOWL = "#8f4a32"
    const val RAMEN_WEAR = "#c36b45"
    const val RAMEN_NOODLE = "#d6b85f"
    const val RAMEN_CHOPSTICK = "#7b4a2d"
    private const val RAMEN_RIM_RISE = 7.0
    private const val RAMEN_OUTLINE_WIDTH = 2.0
    private const val RAMEN_BODY_WIDTH = 1.5
    private const val RAMEN_DETAIL_WIDTH = 1.5
    private const val RAMEN_VISUAL_SCALE = 2.0

    const val SCRAP_GAIN_GOLD = "#ffd45a"
    private const val SCRAP_GAIN_SIZE = 18.0

    /** The development overlay's colour. Never part of what a player sees. */
    private const val ARC_MASK = "#1e3a5f"
    private const val HUD_BACK = "#0d1018"
    private const val HUD_HEALTH = "#39d98a"
    private const val HUD_TEXT = "#c7d2e0"
    private const val HUD_MARGIN = 16.0
    private const val HUD_BAR_HEIGHT = 16.0
    private const val HUD_BAR_FRACTION = 0.22
    private const val BOSS_BAR_FRACTION = 0.44
    private const val HUD_LINE = 18.0
    private const val HUD_BODY = 14.0
    private const val HUD_SMALL = 11.0
    private const val HUD_TEXT_INSET = 4.0
    /**
     * Half the width of an icon in the display.
     *
     * Under half [HUD_LINE], so consecutive rows do not touch. At 9.0 a powerup's casing bracket
     * reached into the row above it.
     */
    /** The display's icon scale: the smallest an icon is drawn at, which the wear-cue rule is held to. */
    const val HUD_ICON = 8.0

    /** Where a name starts, clear of the icon in front of it. */
    private const val HUD_ICON_COLUMN = 26.0

    /** How far the icon's centre sits above the text baseline it shares. */
    private const val HUD_ICON_RISE = 5.0
    private const val HUD_PIP_X = 132.0
    private const val HUD_PIP = 7.0
    private const val HUD_PIP_GAP = 3.0
    private const val DISCOVERY_DIM = "rgba(3, 5, 10, 0.82)"
    private const val DISCOVERY_BACK = "#111827"
    private const val DISCOVERY_WIDTH_FRACTION = 0.72
    private const val DISCOVERY_HEIGHT_FRACTION = 0.58
    private const val DISCOVERY_MAX_WIDTH = 620.0
    private const val DISCOVERY_MAX_HEIGHT = 360.0
    private const val DISCOVERY_BORDER = 4.0
    private const val DISCOVERY_LABEL_TOP = 30.0
    private const val DISCOVERY_LABEL_SIZE = 13.0
    private const val DISCOVERY_ICON_Y = 0.36
    private const val DISCOVERY_ICON_SCALE = 0.14
    private const val DISCOVERY_NAME_Y = 0.66
    private const val DISCOVERY_NAME_SIZE = 24.0
    private const val DISCOVERY_COPY_Y = 0.78
    private const val DISCOVERY_COPY_SIZE = 15.0
    private const val DISCOVERY_COPY_LINE = 21.0
    private const val DISCOVERY_COPY_COLUMNS = 58

}
