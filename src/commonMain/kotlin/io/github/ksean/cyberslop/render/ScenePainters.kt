package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.progression.DiscoveryEntry
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.GroundItem
import io.github.ksean.cyberslop.world.TILE_SIZE

/** Focused package boundaries used by [Scene] to preserve one explicit frame order (ENG-014). */
internal object WorldHazardPainter {
    fun paint(
        builder: SceneBuilder,
        palette: Palette,
        backdrop: Backdrop,
        sim: GameSimulation,
        camera: Camera,
        width: Double,
        height: Double,
        timeSeconds: Double,
        presentationTime: Double,
    ) = Scene.paintWorldAndHazards(
        builder, palette, backdrop, sim, camera, width, height, timeSeconds, presentationTime,
    )
}

internal object GroundItemPainter {
    fun paint(builder: SceneBuilder, sim: GameSimulation, camera: Camera, presentationTime: Double) {
        sim.items.forEach { item ->
            when (val payload = item.payload) {
                is GroundItem.Equipment -> {
                    payload.weapon?.let {
                        pickup(
                            builder, camera, item.position, PickupLook.of(it),
                            WeaponIcons.of(it.id), presentationTime,
                        )
                    }
                    payload.powerup?.let {
                        pickup(
                            builder, camera, item.powerupPosition, PickupLook.of(it),
                            PowerupIcons.of(it.id), presentationTime,
                        )
                    }
                }
                GroundItem.Ramen -> ramen(builder, camera, item.position)
            }
        }
    }

    /** A fixed ground-aligned bowl, separate from hovering weapon and powerup icon grammar. */
    private fun ramen(builder: SceneBuilder, camera: Camera, at: Vec2) {
        val x = (at.x - camera.x) * Scene.ZOOM
        if (x < -CULL_MARGIN || x > camera.viewWidth * Scene.ZOOM + CULL_MARGIN) return
        val groundY = (at.y + TILE_SIZE / 2.0 - camera.y) * Scene.ZOOM
        val rimY = groundY - RAMEN_RIM_RISE * RAMEN_VISUAL_SCALE
        val baseY = groundY - RAMEN_OUTLINE_WIDTH * RAMEN_VISUAL_SCALE / 2.0

        fun scaled(value: Double): Double = value * RAMEN_VISUAL_SCALE

        fun bowl(batch: DrawBatch) {
            batch.segment(x - scaled(8.0), rimY, x + scaled(8.0), rimY)
            batch.segment(x - scaled(7.5), rimY + scaled(0.5), x - scaled(4.0), baseY)
            batch.segment(x + scaled(7.5), rimY + scaled(0.5), x + scaled(4.0), baseY)
            batch.segment(x - scaled(4.0), baseY, x + scaled(4.0), baseY)
        }

        bowl(
            builder.batch(
                Layer.ItemHalo,
                Scene.RAMEN_OUTLINE,
                Primitive.Segment,
                scaled(RAMEN_OUTLINE_WIDTH),
            ),
        )
        bowl(
            builder.batch(
                Layer.Items,
                Scene.RAMEN_BOWL,
                Primitive.Segment,
                scaled(RAMEN_BODY_WIDTH),
            ),
        )
        builder.batch(
            Layer.ItemWear,
            Scene.RAMEN_WEAR,
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
            Scene.RAMEN_NOODLE,
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
            Scene.RAMEN_CHOPSTICK,
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

    private fun pickup(
        builder: SceneBuilder,
        camera: Camera,
        at: Vec2,
        look: PickupLook,
        icon: Icon,
        timeSeconds: Double,
    ) {
        val x = (at.x - camera.x) * Scene.ZOOM
        val y = (at.y - camera.y) * Scene.ZOOM - Scene.hoverOffset(timeSeconds, at.x)
        if (x < -CULL_MARGIN || x > camera.viewWidth * Scene.ZOOM + CULL_MARGIN) return

        val scale = Scene.PICKUP_PX * look.scale
        kindRing(builder, look, x, y, scale)
        IconPainter.paint(builder, icon, x, y, scale, Layer.ItemHalo, Layer.Items, Layer.ItemWear)
        tierPips(builder, look, x, y + scale * IconStyles.KIND_RING + PIP_DROP)
    }

    private fun kindRing(builder: SceneBuilder, look: PickupLook, x: Double, y: Double, scale: Double) {
        val radius = IconStyles.KIND_RING * scale
        val at = Vec2(x, y)
        val colour = IconStyles.ringOf(look)
        IconStyles.bloomWidthOf(look, scale)?.let { width ->
            Scene.ring(builder, colour, at, radius, Layer.ItemHalo, width, KIND_RING_SEGMENTS)
        }
        Scene.ring(
            builder, IconStyles.HALO, at, radius, Layer.ItemHalo,
            IconStyles.haloWidthOf(StrokeWeight.Hair, scale), KIND_RING_SEGMENTS,
        )
        Scene.ring(
            builder, colour, at, radius, Layer.Items,
            IconStyles.widthOf(StrokeWeight.Hair, scale), KIND_RING_SEGMENTS,
        )
    }

    private fun tierPips(builder: SceneBuilder, look: PickupLook, centreX: Double, y: Double) {
        val count = look.tierOrdinal + 1
        val halo = builder.batch(Layer.ItemHalo, IconStyles.HALO, Primitive.Dot)
        val pips = builder.batch(Layer.Items, IconStyles.ringOf(look), Primitive.Dot)
        val first = centreX - (count - 1) * PIP_PITCH / 2.0
        for (index in 0 until count) {
            val x = first + index * PIP_PITCH
            halo.dot(x, y, PIP_PX + PIP_HALO)
            pips.dot(x, y, PIP_PX)
        }
    }

    private const val CULL_MARGIN = 120.0
    private const val PIP_PX = 2.0
    private const val PIP_HALO = 1.25
    private const val PIP_PITCH = 7.0
    private const val PIP_DROP = 7.0
    private const val KIND_RING_SEGMENTS = 16
    private const val RAMEN_RIM_RISE = 7.0
    private const val RAMEN_OUTLINE_WIDTH = 2.0
    private const val RAMEN_BODY_WIDTH = 1.5
    private const val RAMEN_DETAIL_WIDTH = 1.5
    private const val RAMEN_VISUAL_SCALE = 2.0
}

internal object ActorBossPainter {
    fun paintEnemiesAndBosses(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
        presentationTime: Double,
    ) = Scene.paintEnemiesAndBosses(builder, palette, sim, camera, presentationTime)

    fun paintPlayer(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
        muzzle: Vec2,
    ) = Scene.paintPlayer(builder, palette, sim, camera, muzzle)
}

internal object CombatEffectPainter {
    fun paintProjectilesAndHits(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
    ) = Scene.paintProjectilesAndHits(builder, palette, sim, camera)

    fun paintMelee(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
        muzzle: Vec2,
    ) = Scene.paintMelee(builder, palette, sim, camera, muzzle)

    /** Floating world-space feedback for every positive active-gameplay Scrap award (PROD-086). */
    fun paintScrapFeedback(builder: SceneBuilder, sim: GameSimulation, camera: Camera, alpha: Double) {
        sim.scrapGains.forEach { gain ->
            val secondsLeft = gain.previousSecondsLeft +
                (gain.secondsLeft - gain.previousSecondsLeft) * alpha.coerceIn(0.0, 1.0)
            val opacity = (secondsLeft / GameSimulation.SCRAP_GAIN_SECONDS).coerceIn(0.0, 1.0)
            val progress = 1.0 - opacity
            builder.text(
                TextItem(
                    text = "+${gain.amount}",
                    x = (gain.origin.x - camera.x) * Scene.ZOOM,
                    y = (gain.origin.y - camera.y) * Scene.ZOOM -
                        GameSimulation.SCRAP_GAIN_RISE_PX * progress,
                    sizePx = SCRAP_GAIN_SIZE,
                    style = Scene.SCRAP_GAIN_GOLD,
                    align = TextAlign.Centre,
                    bold = true,
                    opacity = opacity,
                ),
            )
        }
    }

    private const val SCRAP_GAIN_SIZE = 18.0
}

internal object HudOverlayPainter {
    fun paint(
        builder: SceneBuilder,
        palette: Palette,
        sim: GameSimulation,
        camera: Camera,
        hud: HudModel,
        width: Double,
        height: Double,
        debugMasks: Boolean,
        discovery: DiscoveryEntry?,
    ) = Scene.paintHudAndOverlay(
        builder, palette, sim, camera, hud, width, height, debugMasks, discovery,
    )
}
