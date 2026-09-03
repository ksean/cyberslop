package io.github.ksean.cyberslop.render

/**
 * Writes a composed frame out as SVG, so a person can look at what the renderer would issue.
 *
 * It implements [PaintSink], the same interface the browser's canvas renderer implements, and is
 * driven by the same [FramePainter] — so the file it produces is the frame's own batches in the
 * frame's own order, not a second drawing of the same idea. Round caps because that is what the
 * canvas renderer sets, and a round-capped stroke is how an icon gets mass without a rectangle.
 */
class SvgPaintSink(
    private val width: Double,
    private val height: Double,
    private val background: String,
) : PaintSink {
    private val body = StringBuilder()
    var strokeSetups: Int = 0
        private set
    var fillSetups: Int = 0
        private set

    override fun fillRects(style: String, batch: DrawBatch) {
        fillSetups++
        for (index in 0 until batch.size) {
            val at = index * Primitive.Rect.stride
            body.append(
                """<rect x="${batch[at]}" y="${batch[at + 1]}" width="${batch[at + 2]}" """ +
                    """height="${batch[at + 3]}" fill="$style"/>""",
            ).append('\n')
        }
    }

    override fun fillTriangles(style: String, batch: DrawBatch) {
        fillSetups++
        for (index in 0 until batch.size) {
            val at = index * Primitive.Triangle.stride
            body.append(
                """<polygon points="${batch[at]},${batch[at + 1]} ${batch[at + 2]},${batch[at + 3]} """ +
                    """${batch[at + 4]},${batch[at + 5]}" fill="$style"/>""",
            ).append('\n')
        }
    }

    override fun strokeSegments(style: String, width: Double, batch: DrawBatch) {
        strokeSetups++
        for (index in 0 until batch.size) {
            val at = index * Primitive.Segment.stride
            body.append(
                """<line x1="${batch[at]}" y1="${batch[at + 1]}" x2="${batch[at + 2]}" """ +
                    """y2="${batch[at + 3]}" stroke="$style" stroke-width="$width" """ +
                    """stroke-linecap="round"/>""",
            ).append('\n')
        }
    }

    override fun fillDots(style: String, batch: DrawBatch) {
        fillSetups++
        for (index in 0 until batch.size) {
            val at = index * Primitive.Dot.stride
            body.append(
                """<circle cx="${batch[at]}" cy="${batch[at + 1]}" r="${batch[at + 2]}" """ +
                    """fill="$style"/>""",
            ).append('\n')
        }
    }

    override fun drawText(item: TextItem) {
        val anchor = when (item.align) {
            TextAlign.Left -> "start"
            TextAlign.Centre -> "middle"
            TextAlign.Right -> "end"
        }
        body.append(
            """<text x="${item.x}" y="${item.y}" font-size="${item.sizePx}" fill="${item.style}" """ +
                """text-anchor="$anchor" font-family="monospace"""" +
                (if (item.bold) """ font-weight="bold"""" else "") + ">" +
                item.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") +
                "</text>",
        ).append('\n')
    }

    fun toSvg(): String = buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """)
        append("""viewBox="0 0 $width $height">""").append('\n')
        append("""<rect width="$width" height="$height" fill="$background"/>""").append('\n')
        append(body)
        append("</svg>\n")
    }
}
