package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.GroundItem
import io.github.ksean.cyberslop.world.ThemeId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a drop is drawn (PROD-044, PROD-050, PROD-051) — properties 30 and 31.
 *
 * Before this, a weapon on the ground was a bar in the theme's `accent` and a powerup was a block in
 * `hazardGlow`, **the acid colour**: on every map, a powerup was drawn the same colour as the thing
 * that kills you, and neither carried what it was.
 */
class PickupIconTest {
    @Test
    fun `a weapon is outlined red and a powerup blue, whatever the theme`() {
        val sim = simulation()
        sim.items.add(GroundItem(dropAt(sim, 0.0), Weapons.of(WeaponId.SableCorpRailgun), null))
        sim.items.add(GroundItem(dropAt(sim, 40.0), null, Powerups.of(PowerupId.ChillProtocol)))

        val styles = itemStyles(compose(sim))

        assertTrue(
            IconStyles.WEAPON_OUTLINE in styles,
            "no weapon outline in the drop layer; found $styles",
        )
        assertTrue(IconStyles.POWERUP_OUTLINE in styles, "no powerup outline; found $styles")
        // Fixed rather than themed: the outlines must be nothing any palette hands out, or a player
        // could not learn "red means weapon" on one map and rely on it on the next.
        ThemeId.entries.forEach { theme ->
            val palette = Palettes.of(theme).colours
            assertTrue(
                IconStyles.WEAPON_OUTLINE !in palette && IconStyles.POWERUP_OUTLINE !in palette,
                "$theme hands out one of the drop outline colours, so on that map the outline is " +
                    "whatever the theme already uses it for",
            )
        }
    }

    /**
     * P-30 (specs/presentation.md), and asserted of the **pair**.
     *
     * Neither line clears the margin alone and that is measured, not assumed: the red outline sits
     * 2.0 from `ArcologyVault.tileBody`, and a near-black halo sits 0.1 from `ReactorCore.sky`.
     * What the pair guarantees is that one of the two always separates.
     */
    @Test
    fun `an outline and its halo separate from every palette's ground and sky`() {
        val halo = Palette.luminanceOf(IconStyles.HALO)

        listOf(IconStyles.WEAPON_OUTLINE, IconStyles.POWERUP_OUTLINE).forEach { outline ->
            val line = Palette.luminanceOf(outline)
            ThemeId.entries.forEach { theme ->
                val palette = Palettes.of(theme)
                backgrounds(palette).forEach { (name, colour) ->
                    val behind = Palette.luminanceOf(colour)
                    val best = maxOf(abs(line - behind), abs(halo - behind))
                    assertTrue(
                        best >= MIN_SEPARATION,
                        "$outline over $theme.$name ($colour) separates by only $best; neither " +
                            "the line nor its halo is visible against it",
                    )
                }
            }
        }
    }

    /** A drop must not be drawn in a colour the same frame gives a hazard or a projectile. */
    @Test
    fun `no drop colour is also a hazard or an effect colour`() {
        val sim = simulation()
        sim.items.add(GroundItem(dropAt(sim, 0.0), Weapons.of(WeaponId.ChromeFang), null))
        sim.items.add(GroundItem(dropAt(sim, 40.0), null, Powerups.of(PowerupId.BurnRig)))
        val frame = compose(sim)

        val drops = itemStyles(frame)
        val elsewhere = frame.batches
            .filter { it.layer == Layer.Hazard || it.layer == Layer.Effects }
            .map { it.style }
            .toSet()

        assertTrue(drops.isNotEmpty(), "nothing was drawn on the item layer")
        assertTrue(
            drops.intersect(elsewhere).isEmpty(),
            "${drops.intersect(elsewhere)} is used for both a drop and a hazard or projectile",
        )
    }

    /** Shape is spent on identity now, so rarity has to be carried by something else (PROD-044). */
    @Test
    fun `a rarer drop is drawn larger and carries more tier pips`() {
        val street = drawn(WeaponId.BrokenBottle)
        val ascended = drawn(WeaponId.VoiceOfTheDeadNet)

        assertTrue(
            ascended.width > street.width * 1.3,
            "an Ascended drop spans ${ascended.width} against a Street drop's ${street.width}",
        )
        assertEquals(1, street.pips, "a Street drop should carry one pip")
        assertEquals(5, ascended.pips, "an Ascended drop should carry five pips")
    }

    /**
     * P-31 (specs/presentation.md), counted at the sink.
     *
     * **This test was written wrong twice, which is the third time a batch-bound property in this
     * project has been.** The first version compared one drop against forty-four and expected the
     * same cost; the second compared forty-four distinct icons against forty-four copies of two.
     * Both compared frames holding different *mixes*, and ENG-061 bounds cost by the kinds of thing
     * drawn — a tier is a scale, a scale picks a ladder width, and an icon that uses no `Slab`
     * opens no `Slab` batch. They failed at 157 against 124 and 157 against 145, and were right to.
     *
     * What is true, and what this asserts, is the same shape `SceneTest` settled on: hold the mix
     * and grow the count. Every one of the forty-four icons is on screen in both frames; the second
     * has four times as many of them.
     */
    @Test
    fun `four of every icon costs no more drawing state than one of every icon`() {
        val single = simulation().also { it.items.clear(); it.stockOneOfEach(1) }
        val quadruple = simulation().also { it.items.clear(); it.stockOneOfEach(4) }

        val sparse = CountingSink().also { FramePainter.paint(compose(single), it) }
        val crowded = CountingSink().also { FramePainter.paint(compose(quadruple), it) }

        assertEquals(
            sparse.stateChanges,
            crowded.stateChanges,
            "176 drops cost ${crowded.stateChanges} against ${sparse.stateChanges} for 44",
        )
        assertTrue(
            crowded.primitives > sparse.primitives * 3,
            "the crowded frame drew ${crowded.primitives} against ${sparse.primitives}",
        )
    }

    /**
     * And the ceiling itself, which is the half a same-mix comparison cannot reach: forty-four
     * distinct shapes must not open more than a fixed vocabulary of batches, however different from
     * each other they are.
     */
    @Test
    fun `all forty-four icons share one bounded vocabulary of batches`() {
        val sim = simulation().also { it.items.clear(); it.stockOneOfEach(1) }

        val onItems = compose(sim).batches
            .count { it.layer == Layer.Items || it.layer == Layer.ItemHalo }

        assertTrue(
            onItems <= MAX_ITEM_BATCHES,
            "forty-four icons opened $onItems batches on the item layer, over the " +
                "$MAX_ITEM_BATCHES this design claims",
        )
    }

    /** [rounds] copies of every weapon and every powerup, so two frames hold the same mix. */
    private fun GameSimulation.stockOneOfEach(rounds: Int) {
        var offset = 0.0
        repeat(rounds) {
            WeaponId.entries.forEach {
                items.add(GroundItem(dropAt(this, offset), Weapons.of(it), null))
                offset += SPACING
            }
            PowerupId.entries.forEach {
                items.add(GroundItem(dropAt(this, offset), null, Powerups.of(it)))
                offset += SPACING
            }
        }
    }

    @Test
    fun `more drops on screen cost no more drawing state`() {
        val few = simulation().also { it.items.clear(); it.stockOneOfEach(1) }
        val many = simulation().also { it.items.clear(); it.stockOneOfEach(12) }

        val sparse = CountingSink().also { FramePainter.paint(compose(few), it) }
        val crowded = CountingSink().also { FramePainter.paint(compose(many), it) }

        assertEquals(
            sparse.stateChanges,
            crowded.stateChanges,
            "twelve times the drops cost ${crowded.stateChanges} against ${sparse.stateChanges}",
        )
        assertTrue(
            crowded.primitives > sparse.primitives * 2,
            "the crowded frame drew ${crowded.primitives} against ${sparse.primitives}; not " +
                "enough more for the comparison to mean anything",
        )
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private class CountingSink : PaintSink {
        var stateChanges = 0
            private set
        var primitives = 0
            private set

        override fun fillRects(style: String, batch: DrawBatch) = record(batch, 1)
        override fun strokeSegments(style: String, width: Double, batch: DrawBatch) = record(batch, 3)
        override fun fillDots(style: String, batch: DrawBatch) = record(batch, 1)
        override fun drawText(item: TextItem) {
            stateChanges += 3
        }

        private fun record(batch: DrawBatch, cost: Int) {
            stateChanges += cost
            primitives += batch.size
        }
    }

    private class Drawn(val width: Double, val pips: Int)

    /** What one drop of [id] puts on the item layer, alone in the frame. */
    private fun drawn(id: WeaponId): Drawn {
        val sim = simulation()
        // A generated level already carries its static drops (PROD-047), and they would be measured
        // along with this one — the first version of this test measured a span of 363 px for a 53 px
        // icon and did not notice.
        sim.items.clear()
        sim.items.add(GroundItem(dropAt(sim, 0.0), Weapons.of(id), null))
        val frame = compose(sim)

        val outline = frame.batches.filter {
            it.layer == Layer.Items && it.style == IconStyles.WEAPON_OUTLINE
        }
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        outline.filter { it.primitive == Primitive.Segment }.forEach { batch ->
            for (index in 0 until batch.size * Primitive.Segment.stride step 2) {
                val x = batch[index]
                if (x < low) low = x
                if (x > high) high = x
            }
        }
        val pips = outline.filter { it.primitive == Primitive.Dot }.sumOf { it.size } -
            WeaponIcons.of(id).dots.size
        return Drawn(high - low, pips)
    }

    private fun itemStyles(frame: DrawList): Set<String> =
        frame.batches
            .filter { it.layer == Layer.Items || it.layer == Layer.ItemHalo }
            .map { it.style }
            .toSet()

    private fun backgrounds(palette: Palette): List<Pair<String, String>> = listOf(
        "sky" to palette.sky,
        "skyLow" to palette.skyLow,
        "backdropFar" to palette.backdropFar,
        "backdropMid" to palette.backdropMid,
        "backdropNear" to palette.backdropNear,
        "tileBody" to palette.tileBody,
        "tileEdge" to palette.tileEdge,
        "tileDeep" to palette.tileDeep,
        "haze" to palette.haze,
    )

    private fun compose(sim: GameSimulation): DrawList = Scene.compose(
        sim,
        Camera(0.0, 0.0, VIEW_WIDTH, VIEW_HEIGHT),
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim.run, sim.level.theme, MAPS, sim.boss.spec.name, sim.boss.healthFraction),
        0.0,
        SceneBuilder(),
    )

    /** On screen, so the drop is drawn rather than culled. */
    private fun dropAt(sim: GameSimulation, offset: Double) =
        Vec2(sim.player.x + offset, sim.player.y)

    private fun simulation(): GameSimulation {
        val level = LevelGenerator.generate(SEED, 1).level
        return GameSimulation(level, RunState.begin(SEED), SEED)
    }

    private companion object {
        const val SEED = 20260827uL
        const val VIEW_WIDTH = 260.0
        const val VIEW_HEIGHT = 150.0
        const val MAPS = 10
        /**
         * Tight, so that four copies of every icon are all still inside the view. At 3.0 the extra
         * copies fell outside the cull and the frame drew 1.8x rather than 4x the primitives — the
         * guard caught it, which is what the guard is for.
         */
        const val SPACING = 1.0
        const val MIN_SEPARATION = 40.0
        const val MAX_ITEM_BATCHES = 24
    }
}
