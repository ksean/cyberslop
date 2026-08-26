package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement

/**
 * Placeholder graphics over the browser's own 2D context. No engine, no framework (ENG-013).
 *
 * Two rules from measurement shape this. Only the tiles inside the view are drawn, because a
 * 720-tile map is 46,000 cells and all but a few hundred are off-screen. And `save`/`restore` is
 * never used per sprite: it measured at 7.6x the cost of a bare draw, which is the difference
 * between 3% and 21% of the frame budget at 600 entities.
 */
class CanvasRenderer(
    private val canvas: HTMLCanvasElement,
    private val context: CanvasRenderingContext2D,
) {
    var showDebugOverlay: Boolean = false

    fun resizeToDisplay() {
        val width = canvas.clientWidth
        val height = canvas.clientHeight
        if (width > 0 && height > 0 && (canvas.width != width || canvas.height != height)) {
            canvas.width = width
            canvas.height = height
        }
        context.imageSmoothingEnabled = false
    }

    fun draw(sim: GameSimulation, camera: Camera, timeSeconds: Double) {
        val level = sim.level
        fill(BACKGROUND, 0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())

        drawTiles(level, camera)
        drawArenas(level, camera)
        drawJets(level, camera, timeSeconds)
        drawItems(sim, camera)
        drawEnemies(sim, camera)
        drawBosses(sim, camera)
        drawProjectiles(sim, camera)
        drawSwing(sim, camera)
        drawPlayer(sim.player, camera)
        drawHud(sim)
        if (showDebugOverlay) drawMasks(level, camera)
    }

    private fun drawEnemies(sim: GameSimulation, camera: Camera) {
        context.fillStyle = ENEMY
        sim.enemies.forEach { enemy ->
            if (!enemy.alive) return@forEach
            context.fillRect(
                enemy.position.x - camera.x, enemy.position.y - camera.y,
                ENEMY_SIZE, ENEMY_SIZE,
            )
        }
    }

    /**
     * Draws the bosses, with a health bar and a flash while they wind up.
     *
     * Nothing drew them at all until a playtester stood in an arena with everything else dead and no
     * idea what was keeping the exit shut.
     */
    private fun drawBosses(sim: GameSimulation, camera: Camera) {
        listOf(sim.miniboss to false, sim.boss to true).forEach { (live, isMain) ->
            if (live.fight.defeated) return@forEach
            val width = live.halfWidth * 2.0 * (if (isMain) 1.0 else MINIBOSS_SCALE)
            val height = live.height * (if (isMain) 1.0 else MINIBOSS_SCALE)
            val x = live.position.x - camera.x - width / 2.0
            val y = live.position.y - camera.y - height

            context.fillStyle = when {
                live.telegraphing -> BOSS_TELEGRAPH
                !live.fight.vulnerable -> BOSS_DORMANT
                else -> BOSS_BODY
            }
            context.fillRect(x, y, width, height)

            // A bar over its head, so "how much longer" is visible without a HUD element.
            context.fillStyle = HUD_BACK
            context.fillRect(x, y - BAR_GAP, width, BAR_HEIGHT)
            context.fillStyle = BOSS_HEALTH
            context.fillRect(x, y - BAR_GAP, width * live.healthFraction, BAR_HEIGHT)
        }
    }

    /**
     * Draws the melee swing.
     *
     * The weapon fires itself and a swing resolves in one tick, so with nothing drawn the player
     * sees enemies lose health for no visible reason — or, facing nothing, sees no sign the game is
     * responding at all. Approximated as a wedge of short bars along the arc, which needs no path
     * API and no per-sprite transform.
     */
    private fun drawSwing(sim: GameSimulation, camera: Camera) {
        val swing = sim.lastSwing ?: return
        context.fillStyle = SWING
        val steps = SWING_SEGMENTS
        val half = swing.arcDegrees / 2.0
        for (step in 0..steps) {
            val offset = -half + swing.arcDegrees * step / steps
            val direction = TrigTable.rotate(swing.direction, offset)
            val reach = swing.reachPx * swing.strength
            val x = swing.origin.x + direction.x * reach - camera.x
            val y = swing.origin.y + direction.y * reach - camera.y
            context.fillRect(x - SWING_DOT / 2.0, y - SWING_DOT / 2.0, SWING_DOT, SWING_DOT)
        }
    }

    private fun drawProjectiles(sim: GameSimulation, camera: Camera) {
        context.fillStyle = PROJECTILE
        sim.projectiles.forEach { shot ->
            context.fillRect(
                shot.position.x - camera.x - 2.0, shot.position.y - camera.y - 2.0, 4.0, 4.0,
            )
        }
    }

    private fun drawItems(sim: GameSimulation, camera: Camera) {
        sim.items.forEach { item ->
            context.fillStyle = if (item.weapon != null) ITEM_WEAPON else ITEM_POWERUP
            context.fillRect(
                item.position.x - camera.x - 4.0, item.position.y - camera.y - 4.0, 8.0, 8.0,
            )
        }
    }

    /** A health bar and nothing else; placeholder graphics, as the brief allows. */
    private fun drawHud(sim: GameSimulation) {
        val width = canvas.width * HUD_WIDTH_FRACTION
        context.fillStyle = HUD_BACK
        context.fillRect(HUD_MARGIN, HUD_MARGIN, width, HUD_HEIGHT)
        context.fillStyle = HUD_HEALTH
        val fraction = (sim.run.health / sim.run.maxHealth).coerceIn(0.0, 1.0)
        context.fillRect(HUD_MARGIN, HUD_MARGIN, width * fraction, HUD_HEIGHT)
    }

    private fun drawTiles(level: Level, camera: Camera) {
        val first = (TileMap.toTile(camera.x) - 1).coerceAtLeast(0)
        val last = (TileMap.toTile(camera.x + camera.viewWidth) + 1)
            .coerceAtMost(level.widthTiles - 1)
        val top = (TileMap.toTile(camera.y) - 1).coerceAtLeast(0)
        val bottom = (TileMap.toTile(camera.y + camera.viewHeight) + 1)
            .coerceAtMost(level.tiles.height - 1)

        // Grouped by kind so the fill style is set twice per frame rather than once per tile.
        for (kind in listOf(TileKind.Solid, TileKind.Acid)) {
            context.fillStyle = colourOf(kind)
            for (x in first..last) {
                for (y in top..bottom) {
                    if (level.tiles[x, y] != kind) continue
                    context.fillRect(
                        TileMap.toWorld(x) - camera.x,
                        TileMap.toWorld(y) - camera.y,
                        TILE_SIZE.toDouble(),
                        TILE_SIZE.toDouble(),
                    )
                }
            }
        }
    }

    private fun drawArenas(level: Level, camera: Camera) {
        context.fillStyle = ARENA
        listOf(level.miniboss, level.boss).forEach { arena ->
            context.fillRect(
                TileMap.toWorld(arena.leftTile) - camera.x,
                TileMap.toWorld(arena.floorRow) - camera.y - 2.0,
                (arena.widthTiles * TILE_SIZE).toDouble(),
                2.0,
            )
        }
    }

    private fun drawJets(level: Level, camera: Camera, timeSeconds: Double) {
        context.fillStyle = JET_ON
        level.jets.forEach { jet ->
            if (!jet.isOnAt(timeSeconds)) return@forEach
            context.fillRect(
                TileMap.toWorld(jet.column) - camera.x,
                TileMap.toWorld(jet.topRow) - camera.y,
                TILE_SIZE.toDouble(),
                ((jet.bottomRow - jet.topRow + 1) * TILE_SIZE).toDouble(),
            )
        }
    }

    private fun drawPlayer(player: PlayerState, camera: Camera) {
        context.fillStyle = PLAYER
        context.fillRect(
            player.x - camera.x,
            player.y - camera.y,
            Physics.Default.width,
            player.height(Physics.Default),
        )
    }

    private fun drawMasks(level: Level, camera: Camera) {
        val first = (TileMap.toTile(camera.x) - 1).coerceAtLeast(0)
        val last = (TileMap.toTile(camera.x + camera.viewWidth) + 1)
            .coerceAtMost(level.widthTiles - 1)
        val top = (TileMap.toTile(camera.y) - 1).coerceAtLeast(0)
        val bottom = (TileMap.toTile(camera.y + camera.viewHeight) + 1)
            .coerceAtMost(level.tiles.height - 1)

        context.fillStyle = ARC_MASK
        for (x in first..last) {
            for (y in top..bottom) {
                if (!level.arcMask[x, y]) continue
                context.fillRect(
                    TileMap.toWorld(x) - camera.x,
                    TileMap.toWorld(y) - camera.y,
                    TILE_SIZE.toDouble(),
                    TILE_SIZE.toDouble(),
                )
            }
        }
    }

    private fun fill(colour: JsString, x: Double, y: Double, width: Double, height: Double) {
        context.fillStyle = colour
        context.fillRect(x, y, width, height)
    }

    private fun colourOf(kind: TileKind): JsString = when (kind) {
        TileKind.Solid -> SOLID
        TileKind.Acid -> ACID
        else -> BACKGROUND
    }

    private companion object {
        // Converted once. `fillStyle` is `JsAny?`, so a raw String does not compile, and converting
        // per draw would put a needless allocation in the hot loop.
        val BACKGROUND = "#0b0b12".toJsString()
        val SOLID = "#2b2f45".toJsString()
        val ACID = "#4ade80".toJsString()
        val JET_ON = "#f97316".toJsString()
        val ARENA = "#e11d48".toJsString()
        val PLAYER = "#22d3ee".toJsString()
        val ARC_MASK = "#1e3a5f".toJsString()
        val ENEMY = "#f43f5e".toJsString()
        val PROJECTILE = "#fde047".toJsString()
        val ITEM_WEAPON = "#38bdf8".toJsString()
        val ITEM_POWERUP = "#a78bfa".toJsString()
        val HUD_BACK = "#1f2937".toJsString()
        val HUD_HEALTH = "#22c55e".toJsString()
        const val ENEMY_SIZE = 14.0
        const val HUD_MARGIN = 12.0
        const val HUD_HEIGHT = 10.0
        const val HUD_WIDTH_FRACTION = 0.25
        val BOSS_BODY = "#dc2626".toJsString()
        val BOSS_DORMANT = "#57534e".toJsString()
        val BOSS_TELEGRAPH = "#fbbf24".toJsString()
        val BOSS_HEALTH = "#ef4444".toJsString()
        val SWING = "#e2e8f0".toJsString()
        const val MINIBOSS_SCALE = 0.7
        const val BAR_GAP = 8.0
        const val BAR_HEIGHT = 5.0
        const val SWING_SEGMENTS = 9
        const val SWING_DOT = 5.0
    }
}
