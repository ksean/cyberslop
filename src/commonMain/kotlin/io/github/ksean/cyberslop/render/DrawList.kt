package io.github.ksean.cyberslop.render

/**
 * What a batch is painted over.
 *
 * Batching by style alone was wrong and the first rendered frame showed why: two things far apart in
 * depth that happen to share a colour end up in one batch, painted at whichever of them was reached
 * first. The boss's health bar was drawn under the HUD panel, and the skyline's lit windows under
 * the buildings in front of them.
 *
 * A fixed set of layers restores painter's order without giving up ENG-061: batches are keyed by
 * layer as well as style, so their number is bounded by `layers x styles` — a constant — and still
 * never grows with how many things are drawn.
 */
enum class Layer {
    Sky,
    BackdropFar,
    BackdropMid,
    BackdropNear,
    Haze,
    Terrain,
    Hazard,
    /** Acid bubble bodies, structurally over their glow dots on [Hazard]. */
    HazardSurface,

    /*
     * An icon is drawn twice — a dark halo, then the coloured line over it — and which of those two
     * is on top cannot be left to the order batches happen to be opened in.
     *
     * Found by rendering a frame with four drops of different rarities in it. A rarer drop is drawn
     * larger, so its halo snaps to a wider ladder step and opens a *new* batch; its outline widths
     * were already opened by the smaller drop, so they sit earlier in the frame. The result is a
     * halo painted after an outline it is supposed to sit under, and every thin stroke on the
     * larger icon came out solid black. Two layers make the order structural, which is the same fix
     * [Layer] itself was introduced for.
     */
    ItemHalo,
    Items,

    /*
     * The weathering streak on a drop (PROD-078) is an overlay on its material, and for the same
     * reason as the halo it cannot share the material's layer: a Street drop opens a streak batch
     * at one ladder width that a rarer drop then reuses, while the rarer drop's wider material
     * batch opens later — and paints over its own rust. Review round 1 of the materials change.
     */
    ItemWear,

    /*
     * An actor is five roles, not one layer.
     *
     * Batching by style merges the same part of every actor on screen, so a batch can only sit at
     * one depth — and with one actor layer that depth was whichever part happened to be drawn
     * first, by whichever archetype was met first. A review round found a Flyer's glow batch opening
     * before any biped existed, after which every biped's head painted over its own eye. Roles are
     * layers, so all torsos precede all heads precede all eyes, whatever order the actors arrive in.
     *
     * Heads earned their own layer a round later, for the same reason one level down: sharing
     * [Actors] with torsos meant a Flyer's pod could still paint over a biped's head if the biped
     * was drawn first. Overlapping *bodies* may interleave — they are opaque either way — but a
     * part that sits inside another must never be able to fall behind it.
     */
    ActorBehind,
    Actors,
    ActorHead,
    ActorFront,
    ActorTrim,
    /** The held weapon's weathering, over its materials on [ActorTrim]. */
    ActorWear,
    ActorGlow,

    /*
     * A shot is three marks in a fixed order — glow, body, core (PROD-080) — and an impact's
     * tracer thins with its window, so a fresher impact opens a wider tracer batch after an older
     * one's dots and would paint over them. Three layers make the order structural.
     */
    ShotGlow,
    ShotBody,
    ShotCore,

    Effects,
    /** Development only, over the world and under the display. */
    Debug,
    Hud,
    HudOverlay,
    /** The display's icons' weathering, over their materials on [HudOverlay]. */
    HudWear,
}

/** What a batch draws. Three shapes cover everything the game puts on screen. */
enum class Primitive(val stride: Int) {
    /** `x, y, width, height` */
    Rect(4),

    /**
     * `x1, y1, x2, y2` — a limb, a barrel, an arc segment.
     *
     * Round-capped, and its width belongs to the batch rather than to the segment. Per-segment
     * width was measured to be the hole in ENG-061: batch count held at 34 while the renderer's
     * `beginPath`/`stroke` pairs went 45 to 279 to 1,579 as entity count went 10 to 100 to 600,
     * because a width change inside a batch has to break the path.
     */
    Segment(4),

    /** `x, y, radius` — an eye, a muzzle flash, a projectile. */
    Dot(3),
}

/**
 * Every primitive of one style and one shape, as a flat run of coordinates.
 *
 * Flat rather than a list of point objects because `specs/presentation.md` measured boxed collections as a
 * real cost in the hot path, and because the renderer's inner loop should read numbers rather than
 * chase references.
 */
class DrawBatch internal constructor(
    val layer: Layer,
    val style: String,
    val primitive: Primitive,
    /** Stroke width, for [Primitive.Segment]. Zero for the others. */
    val width: Double = 0.0,
) {
    /** Which frame last opened this batch, so publishing can order by *this* frame's sequence. */
    internal var frame: Int = -1
    private var data = DoubleArray(INITIAL_CAPACITY)
    private var used = 0

    /** How many primitives, not how many numbers. */
    val size: Int get() = used / primitive.stride

    operator fun get(index: Int): Double = data[index]

    fun rect(x: Double, y: Double, width: Double, height: Double) {
        require(primitive == Primitive.Rect) { "$primitive batch cannot take a rectangle" }
        reserve(4)
        data[used] = x; data[used + 1] = y; data[used + 2] = width; data[used + 3] = height
        used += 4
    }

    fun segment(x1: Double, y1: Double, x2: Double, y2: Double) {
        require(primitive == Primitive.Segment) { "$primitive batch cannot take a segment" }
        reserve(4)
        data[used] = x1; data[used + 1] = y1; data[used + 2] = x2; data[used + 3] = y2
        used += 4
    }

    fun dot(x: Double, y: Double, radius: Double) {
        require(primitive == Primitive.Dot) { "$primitive batch cannot take a dot" }
        reserve(3)
        data[used] = x; data[used + 1] = y; data[used + 2] = radius
        used += 3
    }

    internal fun clear() {
        used = 0
    }

    internal val isEmpty: Boolean get() = used == 0

    private fun reserve(count: Int) {
        if (used + count <= data.size) return
        var capacity = data.size
        while (capacity < used + count) capacity *= 2
        data = data.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}

/** Where a label sits relative to its anchor. */
enum class TextAlign { Left, Centre, Right }

/**
 * A label.
 *
 * Text is a list of objects rather than a run of coordinates because a frame holds a dozen of them
 * against thousands of rectangles, and because a string cannot go in a `DoubleArray` anyway. Its
 * position, size and wording are decided here so the browser layer decides nothing (ENG-060).
 */
data class TextItem(
    val text: String,
    val x: Double,
    val y: Double,
    val sizePx: Double,
    val style: String,
    val align: TextAlign = TextAlign.Left,
    val bold: Boolean = false,
    /** One is opaque and zero transparent; decided in common code and restored by the sink. */
    val opacity: Double = 1.0,
    /**
     * The typeface stack.
     *
     * Here rather than in the renderer because it is a visual decision, and ENG-060 puts those in
     * `commonMain`. A review round found it hard-coded in the browser layer, where nothing without
     * a browser could see it.
     */
    val font: String = UI_FONT,
) {
    init {
        require(opacity in 0.0..1.0) { "text opacity must be between zero and one: $opacity" }
    }

    companion object {
        /** No web font: presentation adds no runtime asset (ENG-063). */
        const val UI_FONT = "system-ui, -apple-system, 'Segoe UI', sans-serif"
    }
}

/** One frame, ready to draw. The renderer sets a style once per batch and then loops. */
class DrawList(val batches: List<DrawBatch>, val texts: List<TextItem> = emptyList())

/**
 * Assembles a frame, reusing its buffers.
 *
 * The contract ENG-061 rests on: a caller takes a [batch] handle once and pushes many primitives
 * into it, so the drawing state a frame costs is **fixed per batch** — one style for a fill, three
 * properties for a stroke, three for a label — and never a function of how many things are drawn.
 * The number per batch is a small constant; what matters is that it does not move with the scene.
 *
 * That exactness is the correction a review round forced. With width carried per segment instead,
 * the batch count was a proxy the renderer did not deliver: it held at 34 while the real
 * `beginPath`/`stroke` count grew from 45 to 1,579 between 10 and 600 entities.
 *
 * Coordinate buffers and the batch objects themselves survive [begin] and are never reallocated, so
 * no primitive allocates. **Per-frame allocation is not constant**, and an earlier version of this
 * comment claiming "a small constant — the two lists [build] publishes" was wrong twice over:
 * [build] allocates a filtered list, a sorted list and a copy of the texts, and [batch] builds a
 * composite `String` key on **every** lookup — four or so per visible figure. What is true is that
 * none of it is per-primitive, so it grows with the number of actors on screen and not with the
 * thousands of tiles and limbs they draw.
 */
class SceneBuilder {
    private val batches = LinkedHashMap<String, DrawBatch>()

    /** The batches this frame has opened, in the order it opened them. */
    private val opened = mutableListOf<DrawBatch>()
    private val texts = mutableListOf<TextItem>()
    private var frame = 0

    fun begin() {
        opened.forEach { it.clear() }
        opened.clear()
        texts.clear()
        frame++
    }

    /**
     * A batch is one style assignment and, for segments, one `beginPath`/`stroke` pair — so [width]
     * is part of its identity. Callers snap widths to a bounded ladder before asking, which is what
     * keeps the number of batches a constant rather than a function of how many actors are on
     * screen (ENG-061).
     */
    fun batch(
        layer: Layer,
        style: String,
        primitive: Primitive,
        width: Double = 0.0,
    ): DrawBatch {
        val batch = batches.getOrPut("${layer.ordinal}|$style|${primitive.ordinal}|$width") {
            DrawBatch(layer, style, primitive, width)
        }
        // Buffers survive between frames, so the map's own order is the order batches were *first*
        // created — possibly several frames ago, and possibly by a scene that no longer exists.
        // What a frame is published in has to be the order that frame opened them.
        if (batch.frame != frame) {
            batch.frame = frame
            opened.add(batch)
        }
        return batch
    }

    /**
     * Publishes only the batches that hold something. A style the previous frame used and this one
     * did not would otherwise still cost the renderer a state change for an empty loop.
     */
    fun text(item: TextItem) {
        texts.add(item)
    }

    /**
     * Publishes in layer order, and within a layer in the order **this frame** opened them — which
     * is what lets a caller rely on "the lit edge is drawn after the tile body" without saying so.
     * The sort is stable, so the within-layer sequence is the calling code's own reading order.
     */
    fun build(): DrawList = DrawList(
        opened.filter { !it.isEmpty }.sortedBy { it.layer.ordinal },
        texts.toList(),
    )
}
