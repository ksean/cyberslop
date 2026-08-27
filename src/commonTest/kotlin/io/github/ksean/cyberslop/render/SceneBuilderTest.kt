package io.github.ksean.cyberslop.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ENG-061: drawing-state changes per frame are bounded by the number of style batches and do not
 * grow with what is drawn.
 *
 * `plan.md` §8.1 measured per-sprite `save`/`translate`/`rotate`/`restore` at 7.61x a bare draw —
 * 21% of the frame budget at 600 entities — and a rig multiplies per-entity draw count by roughly
 * six. The batch is what keeps that from mattering: one style assignment per batch, then a loop.
 */
class SceneBuilderTest {
    @Test
    fun `many primitives of one style stay one batch`() {
        val builder = SceneBuilder()
        builder.begin()
        val batch = builder.batch(Layer.Terrain, "#ff00ff", Primitive.Rect)
        repeat(600) { i -> batch.rect(i.toDouble(), 0.0, 1.0, 1.0) }

        val frame = builder.build()

        assertEquals(1, frame.batches.size, "600 rectangles of one colour became more than one batch")
        assertEquals(600, frame.batches[0].size, "the batch lost primitives")
    }

    @Test
    fun `a style and a primitive kind together identify a batch`() {
        val builder = SceneBuilder()
        builder.begin()
        builder.batch(Layer.Terrain, "#ff00ff", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Terrain, "#ff00ff", Primitive.Segment, 2.0).segment(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Terrain, "#00ffff", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Terrain, "#ff00ff", Primitive.Rect).rect(1.0, 1.0, 1.0, 1.0)

        val frame = builder.build()

        assertEquals(3, frame.batches.size, "batches were not keyed by style and kind together")
        assertEquals(
            2,
            frame.batches.first { it.style == "#ff00ff" && it.primitive == Primitive.Rect }.size,
            "the second rectangle of an existing batch opened a new one",
        )
    }

    @Test
    fun `coordinates survive the round trip`() {
        val builder = SceneBuilder()
        builder.begin()
        builder.batch(Layer.Terrain, "#fff000", Primitive.Segment, 5.0).segment(1.0, 2.0, 3.0, 4.0)
        builder.batch(Layer.Terrain, "#fff000", Primitive.Dot).dot(6.0, 7.0, 8.0)

        val frame = builder.build()
        val segment = frame.batches.first { it.primitive == Primitive.Segment }
        val dot = frame.batches.first { it.primitive == Primitive.Dot }

        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), (0 until 4).map { segment[it] })
        assertEquals(5.0, segment.width, "the batch lost the stroke width")
        assertEquals(listOf(6.0, 7.0, 8.0), (0 until 3).map { dot[it] })
    }

    @Test
    fun `beginning a frame empties the batches without discarding them`() {
        val builder = SceneBuilder()

        builder.begin()
        val first = builder.batch(Layer.Terrain, "#123456", Primitive.Rect)
        repeat(100) { first.rect(0.0, 0.0, 1.0, 1.0) }
        val firstFrame = builder.build().batches[0]

        builder.begin()
        val second = builder.batch(Layer.Terrain, "#123456", Primitive.Rect)
        second.rect(9.0, 9.0, 9.0, 9.0)
        val secondFrame = builder.build().batches[0]

        assertEquals(1, secondFrame.size, "the previous frame's primitives were still there")
        assertTrue(
            firstFrame === secondFrame,
            "the batch was reallocated between frames rather than reused, which puts an " +
                "allocation per style in every frame",
        )
        assertEquals(9.0, secondFrame[0])
    }

    @Test
    fun `an empty batch is not published`() {
        val builder = SceneBuilder()

        builder.begin()
        builder.batch(Layer.Terrain, "#abcdef", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.build()

        builder.begin()
        builder.batch(Layer.Terrain, "#fedcba", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        val frame = builder.build()

        assertEquals(
            1,
            frame.batches.size,
            "last frame's empty batch is still published, so the renderer sets a style it never uses",
        )
    }

    @Test
    fun `layers are painted in order, whatever order they were opened in`() {
        val builder = SceneBuilder()
        builder.begin()
        // Opened back to front on purpose: this is the defect a rendered frame found, where the
        // boss's health bar shared a style batch with the HUD panel and was painted underneath it.
        builder.batch(Layer.Hud, "#111111", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Sky, "#222222", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Actors, "#333333", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)

        val order = builder.build().batches.map { it.layer }

        assertEquals(listOf(Layer.Sky, Layer.Actors, Layer.Hud), order)
    }

    @Test
    fun `one style in two layers is two batches`() {
        val builder = SceneBuilder()
        builder.begin()
        builder.batch(Layer.Terrain, "#abcdef", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Hud, "#abcdef", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)

        assertEquals(
            2,
            builder.build().batches.size,
            "a shared colour collapsed two depths into one batch, which is how paint order was " +
                "lost in the first place",
        )
    }

    @Test
    fun `two widths of one style are two batches`() {
        val builder = SceneBuilder()
        builder.begin()
        builder.batch(Layer.Actors, "#abcdef", Primitive.Segment, 2.0).segment(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Actors, "#abcdef", Primitive.Segment, 6.0).segment(0.0, 0.0, 1.0, 1.0)

        assertEquals(
            2,
            builder.build().batches.size,
            "one batch is holding two stroke widths, so the renderer has to break the path " +
                "inside it — the exact hole a review round measured at 1,579 strokes",
        )
    }

    /**
     * Buffers survive between frames, so the map's own order is the order batches were *first*
     * created — which a review round showed could be several frames ago, by a scene that no longer
     * exists. A Flyer met before any biped opened the glow batch first, after which every biped's
     * head painted over its own eye.
     */
    @Test
    fun `a frame publishes in the order it opened batches, not the order they were created`() {
        val builder = SceneBuilder()

        builder.begin()
        builder.batch(Layer.Actors, "#aaaaaa", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Actors, "#bbbbbb", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.build()

        // The next frame opens them the other way round; both already exist.
        builder.begin()
        builder.batch(Layer.Actors, "#bbbbbb", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.batch(Layer.Actors, "#aaaaaa", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)

        assertEquals(
            listOf("#bbbbbb", "#aaaaaa"),
            builder.build().batches.map { it.style },
            "the frame published in an order set by an earlier frame, so what paints over what " +
                "depends on what was on screen first",
        )
    }

    @Test
    fun `a batch opened in an earlier frame but not this one is not published`() {
        val builder = SceneBuilder()

        builder.begin()
        builder.batch(Layer.Actors, "#cccccc", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        builder.build()

        builder.begin()
        builder.batch(Layer.Actors, "#dddddd", Primitive.Rect).rect(0.0, 0.0, 1.0, 1.0)
        val frame = builder.build()

        assertEquals(listOf("#dddddd"), frame.batches.map { it.style })
    }

    @Test
    fun `a batch refuses a primitive of another kind`() {
        val builder = SceneBuilder()
        builder.begin()
        val rects = builder.batch(Layer.Terrain, "#000000", Primitive.Rect)

        val refused = runCatching { rects.dot(0.0, 0.0, 1.0) }.isFailure

        assertTrue(refused, "a dot was written into a rectangle batch, so its stride is now wrong")
    }
}
