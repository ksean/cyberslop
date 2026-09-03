package io.github.ksean.cyberslop.render

/**
 * What a frame is issued to.
 *
 * One call per batch, and each call is a fixed amount of drawing-state configuration followed by a
 * loop over numbers — one property for a fill, three for a stroke.
 * That is ENG-061's bound expressed as a type: a sink cannot be handed a batch that mixes styles or
 * stroke widths, because a batch's identity includes both.
 *
 * It exists because the bound was, twice, asserted about the wrong thing. Round one of review found
 * the renderer breaking its stroke path inside a batch while the batch count held constant; round
 * two found the replacement test assigning every batch a cost of one *by definition* and never
 * touching the renderer at all. Putting the traversal in `commonMain` behind this interface makes
 * what a batch costs a thing a test can count (ENG-060).
 */
interface PaintSink {
    /** Set the fill style once, then fill `batch.size` rectangles of `x, y, width, height`. */
    fun fillRects(style: String, batch: DrawBatch)

    /** Set the fill style once, then fill `batch.size` triangles from their three points. */
    fun fillTriangles(style: String, batch: DrawBatch)

    /** Set stroke style and width once, then stroke `batch.size` segments of `x1, y1, x2, y2`. */
    fun strokeSegments(style: String, width: Double, batch: DrawBatch)

    /** Set the fill style once, then fill `batch.size` circles of `x, y, radius`. */
    fun fillDots(style: String, batch: DrawBatch)

    fun drawText(item: TextItem)
}

/**
 * Issues a composed frame, in layer order, at a fixed drawing-state cost per batch.
 *
 * The browser layer supplies the five primitive operations and nothing else — no order, no
 * grouping, and no decision about what a frame contains.
 */
object FramePainter {
    fun paint(frame: DrawList, sink: PaintSink) {
        frame.batches.forEach { batch ->
            when (batch.primitive) {
                Primitive.Rect -> sink.fillRects(batch.style, batch)
                Primitive.Triangle -> sink.fillTriangles(batch.style, batch)
                Primitive.Segment -> sink.strokeSegments(batch.style, batch.width, batch)
                Primitive.Dot -> sink.fillDots(batch.style, batch)
            }
        }
        frame.texts.forEach { sink.drawText(it) }
    }
}
