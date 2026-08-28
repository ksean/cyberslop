package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import org.w3c.dom.CENTER
import org.w3c.dom.CanvasLineCap
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.CanvasTextAlign
import org.w3c.dom.LEFT
import org.w3c.dom.RIGHT
import org.w3c.dom.ROUND
import org.w3c.dom.HTMLCanvasElement

/**
 * Draws a [DrawList] over the browser's own 2D context. No engine, no framework (ENG-013).
 *
 * It decides nothing. What a frame looks like is [Scene]'s business, in `commonMain`, where it is
 * tested without a browser (ENG-060); this file sets a style, loops over a run of numbers, and
 * repeats. That division is what let the whole visual layer be built against assertions rather than
 * against screenshots.
 *
 * Two measurements from `plan.md` §8.1 shape it and both survive. Only the tiles inside the view are
 * built, because a 720-tile map is 46,000 cells. And no per-sprite transform is ever set: it
 * measured at 7.61x a bare draw, which is the difference between 3% and 21% of the frame budget at
 * 600 entities. Limbs are stroked segments precisely so there is nothing to rotate.
 */
class CanvasRenderer(
    private val canvas: HTMLCanvasElement,
    private val context: CanvasRenderingContext2D,
) {
    var showDebugOverlay: Boolean = false

    private val builder = SceneBuilder()
    private val sink = CanvasSink()
    private var backdrop: Backdrop? = null

    /**
     * `fillStyle` is `JsAny?`, so a colour has to be converted before it can be assigned. Converting
     * per draw would put an allocation in the hot loop; the cache is affordable exactly because
     * ENG-061 bounds how many distinct styles a frame can hold.
     */
    private val styles = HashMap<String, JsString>()

    fun resizeToDisplay() {
        val width = canvas.clientWidth
        val height = canvas.clientHeight
        if (width > 0 && height > 0 && (canvas.width != width || canvas.height != height)) {
            canvas.width = width
            canvas.height = height
        }
        context.imageSmoothingEnabled = false
    }

    /**
     * Builds this level's skyline once. Generating it per frame would make the city crawl, and
     * would put a few hundred allocations into every frame for something that never changes.
     */
    fun enterLevel(sim: GameSimulation, seed: ULong) {
        backdrop = Backdrops.of(seed, sim.level)
    }

    fun draw(sim: GameSimulation, camera: Camera, timeSeconds: Double, alpha: Double = 1.0) {
        val skyline = backdrop ?: Backdrops.of(sim.run.seed, sim.level).also { backdrop = it }

        FramePainter.paint(
            Scene.compose(
                sim, camera, skyline, HudModel.of(sim), timeSeconds, builder, alpha,
                debugMasks = showDebugOverlay,
            ),
            sink,
        )
    }

    /**
     * The four primitive operations, and nothing else.
     *
     * Order, grouping and content are [FramePainter]'s, in `commonMain`, where they are tested
     * (ENG-060). Each of these configures a fixed amount of drawing state — one property for a fill,
     * three for a stroke, three for a label — and then loops, which is what makes
     * a batch's fixed cost true of the running game and not only of the draw list.
     */
    private inner class CanvasSink : PaintSink {
        override fun fillRects(style: String, batch: DrawBatch) {
            context.fillStyle = styleOf(style)
            var i = 0
            val end = batch.size * Primitive.Rect.stride
            while (i < end) {
                context.fillRect(batch[i], batch[i + 1], batch[i + 2], batch[i + 3])
                i += Primitive.Rect.stride
            }
        }

        override fun strokeSegments(style: String, width: Double, batch: DrawBatch) {
            context.strokeStyle = styleOf(style)
            context.lineWidth = width
            context.lineCap = CanvasLineCap.ROUND
            context.beginPath()
            var i = 0
            val end = batch.size * Primitive.Segment.stride
            while (i < end) {
                context.moveTo(batch[i], batch[i + 1])
                context.lineTo(batch[i + 2], batch[i + 3])
                i += Primitive.Segment.stride
            }
            context.stroke()
        }

        override fun fillDots(style: String, batch: DrawBatch) {
            context.fillStyle = styleOf(style)
            context.beginPath()
            var i = 0
            val end = batch.size * Primitive.Dot.stride
            while (i < end) {
                // Moving to the rim first keeps the arcs from being joined into one shape.
                context.moveTo(batch[i] + batch[i + 2], batch[i + 1])
                context.arc(batch[i], batch[i + 1], batch[i + 2], 0.0, FULL_CIRCLE)
                i += Primitive.Dot.stride
            }
            context.fill()
        }

        override fun drawText(item: TextItem) {
            context.fillStyle = styleOf(item.style)
            context.font = "${if (item.bold) "bold " else ""}${item.sizePx}px ${item.font}"
            context.textAlign = when (item.align) {
                TextAlign.Left -> CanvasTextAlign.LEFT
                TextAlign.Centre -> CanvasTextAlign.CENTER
                TextAlign.Right -> CanvasTextAlign.RIGHT
            }
            context.fillText(item.text, item.x, item.y)
        }
    }

    private fun styleOf(colour: String): JsString =
        styles.getOrPut(colour) { colour.toJsString() }

    private companion object {
        const val FULL_CIRCLE = 6.283185307179586
    }
}
