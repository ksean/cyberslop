package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.Anchor
import io.github.ksean.cyberslop.combat.WeaponClass
import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.AttackVisual
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.HitShape
import io.github.ksean.cyberslop.sim.LiveBoss
import io.github.ksean.cyberslop.sim.LiveEnemy
import io.github.ksean.cyberslop.sim.MuzzleFlash
import io.github.ksean.cyberslop.sim.SwingVisual
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
    ): DrawList {
        builder.begin()
        val palette = Palettes.of(sim.level.theme)
        val width = camera.viewWidth * ZOOM
        val height = camera.viewHeight * ZOOM

        sky(builder, palette, width, height)
        skyline(builder, palette, backdrop, camera, width, height)
        tiles(builder, palette, sim.level, camera)
        arenas(builder, palette, sim.level, camera)
        jets(builder, palette, sim.level, camera, timeSeconds)
        pickups(builder, sim, camera)
        enemies(builder, palette, sim, camera, timeSeconds)
        bosses(builder, palette, sim, camera)
        projectiles(builder, palette, sim, camera)
        hitIndicator(builder, palette, sim, camera)
        // Both the arc and the figure hang off one interpolated position, or the swing sits ahead
        // of the hand that threw it by a tick of travel.
        val muzzle = drawnMuzzle(sim, alpha)
        swing(builder, palette, sim, camera, muzzle)
        player(builder, palette, sim, camera, muzzle)
        if (debugMasks) masks(builder, sim.level, camera)
        hud(builder, palette, hud, width, height)

        return builder.build()
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
            val batch = builder.batch(layer.layer, layer.tint, Primitive.Rect)
            // Opened after the tint so the lit windows are painted over their own building rather
            // than under it — and per depth, so a near tower still occludes a far window.
            val windows = builder.batch(layer.layer, palette.window, Primitive.Rect)
            val offset = camera.x * layer.parallax * ZOOM
            // Vertical parallax, anchored to the height the horizon fraction was calibrated at.
            val horizon = height * backdrop.horizonFraction +
                verticalDrift(backdrop, camera, layer.parallax, height)
            layer.buildings.forEach { building ->
                val x = building.x * ZOOM - offset
                val drawWidth = building.width * ZOOM
                if (x + drawWidth < 0.0 || x > width) return@forEach

                val drawHeight = building.height * ZOOM
                batch.rect(x, horizon - drawHeight, drawWidth, drawHeight)

                val cellWidth = drawWidth / building.windowColumns
                val cellHeight = drawHeight / building.windowRows
                for (column in 0 until building.windowColumns) {
                    for (row in 0 until building.windowRows) {
                        if (!building.hasWindow(column, row)) continue
                        windows.rect(
                            x + cellWidth * (column + WINDOW_INSET),
                            horizon - drawHeight + cellHeight * (row + WINDOW_INSET),
                            cellWidth * WINDOW_FILL,
                            cellHeight * WINDOW_FILL,
                        )
                    }
                }
            }
        }
        val haze = height * backdrop.horizonFraction +
            verticalDrift(backdrop, camera, backdrop.layers.last().parallax, height)
        builder.batch(Layer.Haze, palette.haze, Primitive.Rect)
            .rect(0.0, haze - HAZE_PX, width, HAZE_PX * 2.0)
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
    private fun tiles(builder: SceneBuilder, palette: Palette, level: Level, camera: Camera) {
        val first = (TileMap.toTile(camera.x) - 1).coerceAtLeast(0)
        val last = (TileMap.toTile(camera.x + camera.viewWidth) + 1)
            .coerceAtMost(level.widthTiles - 1)
        val top = (TileMap.toTile(camera.y) - 1).coerceAtLeast(0)
        val bottom = (TileMap.toTile(camera.y + camera.viewHeight) + 1)
            .coerceAtMost(level.tiles.height - 1)

        val body = builder.batch(Layer.Terrain, palette.tileBody, Primitive.Rect)
        val deep = builder.batch(Layer.Terrain, palette.tileDeep, Primitive.Rect)
        val edge = builder.batch(Layer.Terrain, palette.tileEdge, Primitive.Rect)
        val hazard = builder.batch(Layer.Hazard, palette.hazard, Primitive.Rect)
        val hazardGlow = builder.batch(Layer.Hazard, palette.hazardGlow, Primitive.Rect)
        val spikes = builder.batch(Layer.Hazard, palette.hazardGlow, Primitive.Segment, strokeWidth(STRIP_WIDTH))
        val size = TILE_SIZE * ZOOM

        for (x in first..last) {
            for (y in top..bottom) {
                val screenX = (TileMap.toWorld(x) - camera.x) * ZOOM
                val screenY = (TileMap.toWorld(y) - camera.y) * ZOOM
                when (level.tiles[x, y]) {
                    TileKind.Solid -> {
                        body.rect(screenX, screenY, size, size)
                        if (!level.tiles.blocksMovement(x, y - 1)) {
                            edge.rect(screenX, screenY, size, EDGE_PX)
                        }
                        deep.rect(screenX, screenY + size - SEAM_PX, size, SEAM_PX)
                    }

                    TileKind.Acid -> {
                        hazard.rect(screenX, screenY, size, size)
                        if (level.tiles[x, y - 1] != TileKind.Acid) {
                            hazardGlow.rect(screenX, screenY, size, EDGE_PX)
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

                    else -> Unit
                }
            }
        }
        barrels(builder, palette, level, camera, first..last)
    }

    /** A burning barrel: a body in its floor cell and a flame licking up through the cell above. */
    private fun barrels(builder: SceneBuilder, palette: Palette, level: Level, camera: Camera, visible: IntRange) {
        if (level.barrels.isEmpty()) return
        val size = TILE_SIZE * ZOOM
        val body = builder.batch(Layer.Hazard, palette.hazard, Primitive.Rect)
        val bands = builder.batch(Layer.Hazard, palette.tileEdge, Primitive.Rect)
        val flame = builder.batch(Layer.Hazard, palette.hazardGlow, Primitive.Segment, strokeWidth(FLAME_WIDTH))
        val core = builder.batch(Layer.Hazard, palette.hazardGlow, Primitive.Dot)
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
            val centre = x + size / 2.0
            flame.segment(centre - width * 0.3, top, centre, top - size * FLAME_HEIGHT)
            flame.segment(centre + width * 0.3, top, centre, top - size * FLAME_HEIGHT)
            core.dot(centre, top - size * FLAME_HEIGHT * 0.4, size * FLAME_CORE)
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
    ) {
        val outer = builder.batch(
            Layer.Hazard, palette.accent, Primitive.Segment,
            strokeWidth(TILE_SIZE * ZOOM * JET_OUTER),
        )
        val core = builder.batch(
            Layer.Hazard, palette.hazardGlow, Primitive.Segment,
            strokeWidth(TILE_SIZE * ZOOM * JET_CORE),
        )
        val pool = builder.batch(Layer.Hazard, palette.accent, Primitive.Rect)

        level.jets.forEach { jet ->
            if (!jet.isOnAt(timeSeconds)) return@forEach
            val x = (TileMap.toWorld(jet.column) - camera.x) * ZOOM + TILE_SIZE * ZOOM / 2.0
            val top = (TileMap.toWorld(jet.topRow) - camera.y) * ZOOM
            val bottom = (TileMap.toWorld(jet.bottomRow + 1) - camera.y) * ZOOM
            outer.segment(x, top, x, bottom)
            core.segment(x, top, x, bottom)
            // The pool of light it throws on the floor, which is what makes a lit jet read as
            // lighting the room rather than as a bright stripe drawn over it.
            pool.rect(
                x - TILE_SIZE * ZOOM * JET_POOL / 2.0,
                bottom - TILE_SIZE * ZOOM * JET_POOL_HEIGHT,
                TILE_SIZE * ZOOM * JET_POOL,
                TILE_SIZE * ZOOM * JET_POOL_HEIGHT,
            )
        }
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
    ) {
        sim.items.forEach { item ->
            // A paired award (weapon and powerup on one item) draws both, the powerup a tile to
            // the right, so it looks like the two drops it resolves as.
            item.weapon?.let { pickup(builder, camera, item.position, PickupLook.of(it), WeaponIcons.of(it.id)) }
            item.powerup?.let { pickup(builder, camera, item.powerupPosition, PickupLook.of(it), PowerupIcons.of(it.id)) }
        }
    }

    private fun pickup(builder: SceneBuilder, camera: Camera, at: Vec2, look: PickupLook, icon: Icon) {
        val x = (at.x - camera.x) * ZOOM
        val y = (at.y - camera.y) * ZOOM
        if (x < -OFF_SCREEN || x > camera.viewWidth * ZOOM + OFF_SCREEN) return

        val scale = PICKUP_PX * look.scale
        IconPainter.paint(builder, icon, look.weapon, x, y, scale, Layer.ItemHalo, Layer.Items)
        tierPips(builder, look, x, y + scale + PIP_DROP)
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
            IconStyles.outlineOf(look.weapon),
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
                EnemyForm.Fixed -> fixed(builder, palette, look, enemy, engagement(enemy, look, player), x, ground)
            }
            // The boss's bar, for anyone who has been hurt (PROD-077); full health shows none.
            if (enemy.health < enemy.maxHealth) {
                healthBar(builder, palette, x, ground - look.height * ZOOM - BAR_GAP, GameSimulation.ENEMY_SIZE * ZOOM, enemy.healthFraction)
            }
        }
    }

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
            speedX = enemy.facing * look.strideRate * REFERENCE_SPEED,
            onGround = true,
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

    /** A fixed base with a sweeping head. Nothing else in the game is bolted down. */
    private fun fixed(
        builder: SceneBuilder,
        palette: Palette,
        look: EnemyLook,
        enemy: LiveEnemy,
        aim: Vec2,
        x: Double,
        ground: Double,
    ) {
        val size = look.height * ZOOM
        val baseHeight = size * BASE_HEIGHT
        val feet = ground

        val hurt = enemy.hurtSecondsLeft > 0.0
        builder.batch(Layer.Actors, hurtOr(hurt, Palettes.ENEMY_DARK), Primitive.Rect)
            .rect(
                x - size * look.bulk * BASE_WIDTH, feet - baseHeight,
                size * look.bulk * BASE_WIDTH * 2.0, baseHeight,
            )
        // Behind the head, so it reads as emerging from the housing rather than bolted onto it.
        val head = feet - baseHeight - size * TURRET_HEAD / 2.0
        // The head sweeps: the barrel follows whatever the emplacement is tracking, rather than
        // pointing along a patrol direction a bolted-down thing never has.
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
                    weapon = true,
                    originX = handX + pose.weaponAim.x * scale,
                    originY = handY + pose.weaponAim.y * scale,
                    scale = scale,
                    // Over the arm that holds it, under nothing else the actor draws.
                    haloLayer = Layer.ActorFront,
                    outlineLayer = Layer.ActorTrim,
                    aim = pose.weaponAim,
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
            val look = EnemyLooks.boss(sim.level.mapIndex, isMain)
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
            )
            crown(builder, palette, look, x, feet - look.height * ZOOM, hurtOr(hurt, palette.glow[palette.glow.size - 1]))
            healthBar(builder, palette, x, feet - look.height * ZOOM - BAR_GAP, look.height * ZOOM, live.healthFraction)
            bossStrike(builder, palette, live, pose, Vec2(x, feet), camera)
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
        val forward = Vec2(live.facing.toDouble(), 0.0)
        return Motion(
            speedX = if (live.moving) live.facing * LiveBoss.SPEED else 0.0,
            onGround = true,
            facing = live.facing,
            stridePx = live.stridePx,
            secondsSinceShot = if (attack != null && attack.visual.ranged) active ?: Double.MAX_VALUE else Double.MAX_VALUE,
            secondsSinceSwing = if (attack != null && !attack.visual.ranged) active ?: Double.MAX_VALUE else Double.MAX_VALUE,
            windingUp = live.telegraphing,
            shotSeconds = attack?.activeSeconds ?: Actor.FIRE_SECONDS,
            swingSeconds = attack?.activeSeconds ?: Actor.SWING_SECONDS,
            swingDirection = attack?.let { strikeDirection(it.visual, forward) } ?: forward,
            weaponAim = forward,
            scale = look.height / Physics.Default.standingHeight,
        )
    }

    /** A slam comes down at the ground ahead; a lunge trails its swoosh behind; the rest go level. */
    private fun strikeDirection(visual: AttackVisual, forward: Vec2): Vec2 = when (visual) {
        AttackVisual.GroundSlam -> Vec2(forward.x * SLAM_FORWARD, SLAM_DOWN)
        AttackVisual.Lunge -> forward * -1.0
        AttackVisual.LevelSweep, AttackVisual.MuzzleFan -> forward
    }

    /** The effect of an attack's active window, from the posed hand, over exactly that window. */
    private fun bossStrike(builder: SceneBuilder, palette: Palette, live: LiveBoss, pose: Pose, feet: Vec2, camera: Camera) {
        val attack = live.currentAttack ?: return
        if (!live.striking) return
        val secondsLeft = (attack.totalSeconds - live.attackElapsed).coerceAtLeast(0.0)
        val style = palette.hazardGlow
        val direction = strikeDirection(attack.visual, Vec2(live.facing.toDouble(), 0.0))
        if (attack.visual.ranged) {
            val flash = MuzzleFlash(direction, secondsLeft, attack.activeSeconds)
            val barrel = feet + barrelTip(pose) * ZOOM
            muzzleFlash(builder, style, style, barrel, flash)
            // Where it went (PROD-071): the Volley lands on the band around the x it was aimed at
            // when the telegraph began, not along the boss's facing. The band is drawn on the
            // floor in the enemy-shot colour, and the fan is tracers travelling from the barrel to
            // it, further along the later in the window.
            val aimed = Vec2((live.aimedX - camera.x) * ZOOM, feet.y)
            val bandY = feet.y
            val shots = builder.batch(Layer.Effects, palette.hazard, Primitive.Segment, strokeWidth(TRACER_WIDTH))
            val left = aimed.x - LiveBoss.VOLLEY_WIDTH * ZOOM
            val right = aimed.x + LiveBoss.VOLLEY_WIDTH * ZOOM
            shots.segment(left, bandY, right, bandY)
            val bodies = builder.batch(Layer.Effects, palette.hazard, Primitive.Dot)
            val progress = 1.0 - flash.strength
            for (n in 0 until FAN_DOTS) {
                val landing = Vec2(left + (right - left) * n / (FAN_DOTS - 1), bandY)
                val head = barrel + (landing - barrel) * progress
                val tail = barrel + (landing - barrel) * (progress - FAN_TRACER).coerceAtLeast(0.0)
                bodies.dot(head.x, head.y, FAN_DOT_PX)
                shots.segment(head.x, head.y, tail.x, tail.y)
            }
        } else {
            val swing = SwingVisual(
                origin = Vec2.Zero, direction = direction, arcDegrees = BOSS_SWING_ARC,
                reachPx = attack.reachPx, secondsLeft = secondsLeft, totalSeconds = attack.activeSeconds,
            )
            swoosh(builder, style, feet + pose.leadHand * ZOOM, swing)
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

    private fun projectiles(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
    ) {
        // A body and a tracer (PROD-071): the dot is the shot, the segment behind it is
        // TRACER_SECONDS of its travel, so a shot reads as a line of flight rather than a
        // floating point. Two batches per side, never one per shot.
        val mineStyle = palette.glow[palette.glow.size - 1]
        val mine = builder.batch(Layer.Effects, mineStyle, Primitive.Dot)
        val mineTracer = builder.batch(Layer.Effects, mineStyle, Primitive.Segment, strokeWidth(TRACER_WIDTH))
        val theirs = builder.batch(Layer.Effects, palette.hazard, Primitive.Dot)
        val theirTracer = builder.batch(Layer.Effects, palette.hazard, Primitive.Segment, strokeWidth(TRACER_WIDTH))
        sim.projectiles.forEach { shot ->
            val x = (shot.position.x - camera.x) * ZOOM
            val y = (shot.position.y - camera.y) * ZOOM
            (if (shot.fromPlayer) mine else theirs).dot(x, y, shot.radius * ZOOM)
            (if (shot.fromPlayer) mineTracer else theirTracer).segment(
                x, y,
                x - shot.velocity.x * TRACER_SECONDS * ZOOM, y - shot.velocity.y * TRACER_SECONDS * ZOOM,
            )
        }
        // A shot spent inside the tick it was fired was never in the list above; its last line of
        // flight is kept for the flash window so a point-blank hit is still seen to go somewhere.
        sim.impacts.forEach { hit ->
            val impact = hit.shape as? HitShape.Impact ?: return@forEach
            val x = (impact.at.x - camera.x) * ZOOM
            val y = (impact.at.y - camera.y) * ZOOM
            // Thins with the window like every indicator; the ladder bounds how many batches open.
            val style = if (impact.fromPlayer) mineStyle else palette.hazard
            builder.batch(Layer.Effects, style, Primitive.Segment, strokeWidth(TRACER_WIDTH * hit.strength)).segment(
                x, y,
                x - impact.velocity.x * TRACER_SECONDS * ZOOM, y - impact.velocity.y * TRACER_SECONDS * ZOOM,
            )
        }
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
    private fun ring(builder: SceneBuilder, style: String, at: Vec2, radius: Double, width: Double = FLASH_WIDTH) {
        val batch = builder.batch(Layer.Effects, style, Primitive.Segment, strokeWidth(width))
        var previous = Vec2(at.x + radius, at.y)
        for (step in 1..PULSE_SEGMENTS) {
            val direction = TrigTable.rotate(Vec2.Right, 360.0 * step / PULSE_SEGMENTS)
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
        val swing = sim.lastSwing ?: return
        // Drawn from where the hand is now rather than where it was on the tick the swing
        // resolved: over the 0.16 s it lingers a running player travels nearly 40 px, and an
        // arc left behind in world space visibly detaches from the figure that made it. The
        // direction and reach are the ones that actually resolved damage.
        swoosh(builder, palette.hazardGlow, Vec2((muzzle.x - camera.x) * ZOOM, (muzzle.y - camera.y) * ZOOM), swing)
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
        val pose = Actor.pose(motionOf(sim))
        val x = (muzzle.x - camera.x) * ZOOM
        val feet = (muzzle.y + state.height(Physics.Default) / 2.0 - camera.y) * ZOOM

        figure(
            builder, palette, pose, x, feet,
            look = null,
            bodyStyle = PLAYER_BODY,
            limbStyle = PLAYER_LIMB,
            trimStyle = palette.accent,
            armStyle = PLAYER_ARM,
            // The player carries a weapon at all times (PROD-023), and `specs/presentation.md` says it
            // attaches to the lead hand. It did not: the geometry was gated on an enemy archetype
            // being armed, and the player has no archetype, so the one figure that always holds
            // something was the only one drawn empty-handed.
            armed = true,
            weaponReach = weaponReach(sim.run.loadout.weapon),
            // The same geometry the drop was drawn with, so picking a weapon up teaches the player
            // what that shape means on the floor of the next map (PROD-049).
            heldIcon = WeaponIcons.of(sim.run.loadout.weapon.id),
            // Fixed rather than themed. The player has to be the one figure on screen that is never
            // in doubt, and against a dark map full of enemies in the same faction colours a themed
            // eye put them in the same read as everything trying to kill them.
            eyeStyle = PLAYER_EYE,
        )

        // The cue sits at the weapon, not the hand (PROD-066): the flash at the muzzle of a weapon
        // that has one, a pulse around the dish of one that does not.
        sim.lastShot?.let { flash ->
            val weapon = sim.run.loadout.weapon
            val muzzle = Vec2(x, feet) + muzzleOf(pose, weapon) * ZOOM
            if (weapon.anchor == Anchor.Cursor || weapon.cls == WeaponClass.Psychic) {
                pulse(builder, palette.hazardGlow, muzzle, flash)
            } else {
                muzzleFlash(builder, palette.glow[palette.glow.size - 1], palette.hazardGlow, muzzle, flash)
            }
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
        return Motion(
            speedX = state.vx,
            verticalSpeed = state.vy,
            onGround = state.onGround,
            crouched = state.stance == io.github.ksean.cyberslop.physics.Stance.Crouch,
            facing = sim.facing,
            stridePx = sim.playerStridePx,
            secondsSinceShot = sim.lastShot
                ?.let { it.totalSeconds - it.secondsLeft } ?: Double.MAX_VALUE,
            secondsSinceSwing = sim.lastSwing
                ?.let { it.totalSeconds - it.secondsLeft } ?: Double.MAX_VALUE,
            // The simulation's own windows, so the arm finishes its sweep on the tick the swing
            // stops being drawn rather than snapping back partway through it.
            shotSeconds = sim.lastShot?.totalSeconds ?: Actor.FIRE_SECONDS,
            swingSeconds = sim.lastSwing?.totalSeconds ?: Actor.SWING_SECONDS,
            swingDirection = sim.lastSwing?.direction ?: Vec2.Right,
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
    private fun hudIcon(builder: SceneBuilder, icon: Icon, weapon: Boolean, line: Double) {
        IconPainter.paint(
            builder,
            icon,
            weapon,
            HUD_MARGIN + HUD_ICON,
            line - HUD_ICON_RISE,
            HUD_ICON,
            haloLayer = Layer.Hud,
            outlineLayer = Layer.HudOverlay,
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
        hudIcon(builder, WeaponIcons.of(model.weaponId), weapon = true, line = weaponLine)
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
            hudIcon(builder, PowerupIcons.of(stack.id), weapon = false, line = y)
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

    private val STROKE_LADDER = doubleArrayOf(
        1.5, 2.0, 2.75, 3.5, 4.5, 6.0, 8.0, 10.5, 14.0, 18.0, 24.0, 32.0, 42.0, 56.0,
    )

    // Layout and proportion. Everything here is either a screen pixel or a fraction of a figure.
    private const val BACKDROP_HORIZON = 0.72
    private const val WINDOW_INSET = 0.30
    private const val WINDOW_FILL = 0.34

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
    private const val ARENA_PX = 3.0
    private const val JET_OUTER = 0.75
    private const val JET_CORE = 0.3
    private const val JET_POOL = 2.6
    private const val JET_POOL_HEIGHT = 0.18

    private const val PIP_PX = 2.0
    private const val PIP_HALO = 1.25
    private const val PIP_PITCH = 7.0
    private const val PIP_DROP = 7.0
    private const val OFF_SCREEN = 120.0
    private const val REFERENCE_SPEED = 70.0

    /** Matches the simulation's own enemy half-extent, used to find an enemy's centre. */
    private const val ENEMY_HALF = 7.0
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
    private const val CROWN_PITCH = 0.11
    private const val CROWN_HEIGHT = 0.16
    private const val CROWN_WIDTH = 0.05
    private const val BAR_GAP = 10.0
    private const val BAR_HEIGHT = 6.0
    /** How much of a projectile's travel its tracer shows (`specs/presentation.md`). */
    const val TRACER_SECONDS = 0.05
    private const val TRACER_WIDTH = 2.0
    private const val BEAM_CORE_WIDTH = 2.0
    private const val BEAM_BLOOM_WIDTH = 8.0
    private const val CHAIN_WIDTH = 2.0
    private const val CHAIN_SPARK_PX = 4.0
    private const val SWING_SEGMENTS = 10
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
    private const val STRIP_WIDTH = 2.0
    private const val STRIP_BASE_PX = 4.0
    private const val STRIP_POINTS = 3
    private const val STRIP_TOP = 0.35
    private const val DRUM_WIDTH = 0.7
    private const val DRUM_HEIGHT = 0.9
    private const val BAND_PX = 2.0
    private const val FLAME_WIDTH = 3.0
    private const val FLAME_HEIGHT = 0.8
    private const val FLAME_CORE = 0.14
    private const val FAN_DOTS = 5
    private const val FAN_DEGREES = 40.0
    private const val FAN_DOT_PX = 3.0
    /** How much of the barrel-to-band flight a Volley tracer shows. */
    private const val FAN_TRACER = 0.15
    private const val BOSS_SWING_ARC = 90.0
    private const val SLAM_FORWARD = 0.5
    private const val SLAM_DOWN = 0.87
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
    private const val HUD_ICON = 8.0

    /** Where a name starts, clear of the icon in front of it. */
    private const val HUD_ICON_COLUMN = 26.0

    /** How far the icon's centre sits above the text baseline it shares. */
    private const val HUD_ICON_RISE = 5.0
    private const val HUD_PIP_X = 132.0
    private const val HUD_PIP = 7.0
    private const val HUD_PIP_GAP = 3.0

}
