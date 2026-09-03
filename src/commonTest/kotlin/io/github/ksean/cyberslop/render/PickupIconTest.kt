package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.DeathDropPlacement
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
    fun `weapon rings and pips follow tier while powerups remain blue`() {
        val sim = simulation()
        TIER_RINGS.forEachIndexed { tier, (id, colour) ->
            sim.items.clear()
            sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = Weapons.of(id)))
            val frame = compose(sim)

            assertEquals(RING_CHORDS, ringOf(frame, colour).size, "$id drew no tier-$tier ring in $colour")
            val pips = frame.batches
                .filter { it.layer == Layer.Items && it.style == colour && it.primitive == Primitive.Dot }
                .sumOf { it.size }
            assertEquals(tier + 1, pips, "$id's pips do not share its tier-ring colour")
        }

        sim.items.clear()
        sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), powerup = Powerups.of(PowerupId.ChillProtocol)))
        val powerupFrame = compose(sim)
        assertEquals(RING_CHORDS, ringOf(powerupFrame, IconStyles.POWERUP_RING).size)
        assertTrue(
            TIER_RINGS.none { (_, colour) -> colour in itemStyles(powerupFrame) },
            "a powerup used a weapon-tier colour",
        )

        // Fixed rather than themed: a tier colour must mean the same thing on every map.
        val fixed = ringColours() + materialColours()
        ThemeId.entries.forEach { theme ->
            val palette = Palettes.of(theme).colours
            assertTrue(
                fixed.none { it in palette },
                "$theme hands out one of the drop colours ${fixed.filter { it in palette }}",
            )
        }
    }

    @Test
    fun `only T4 and T5 weapon rings bloom and T5 is stronger`() {
        val sim = simulation()
        val bloomWidths = TIER_RINGS.mapIndexed { tier, (id, colour) ->
            sim.items.clear()
            val weapon = Weapons.of(id)
            sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = weapon))
            val blooms = compose(sim).batches.filter {
                it.layer == Layer.ItemHalo && it.style == colour && it.primitive == Primitive.Segment
            }

            if (tier < 3) {
                assertTrue(blooms.isEmpty(), "$id has a coloured bloom below T4")
                null
            } else {
                val expectedFraction = if (tier == 3) 0.25 else 0.34
                val expectedWidth = Scene.strokeWidth(expectedFraction * Scene.PICKUP_PX * PickupLook.of(weapon).scale)
                assertEquals(1, blooms.size, "$id should open one bloom batch")
                assertEquals(RING_CHORDS, blooms.single().size, "$id's bloom is not the ring's sixteen chords")
                assertEquals(expectedWidth, blooms.single().width, "$id has the wrong bloom width")
                blooms.single().width
            }
        }

        assertTrue(bloomWidths[4]!! > bloomWidths[3]!!, "T5 does not glow more strongly than T4")
    }

    @Test
    fun `a paired drop keeps the weapon tier ring and the powerup blue independent`() {
        val sim = simulation().also { it.items.clear() }
        sim.items.add(
            GroundItem.equipment(
                position = dropAt(sim, 0.0),
                weapon = Weapons.of(WeaponId.SableCorpRailgun),
                powerup = Powerups.of(PowerupId.ForkBomb),
            ),
        )
        val frame = compose(sim)

        assertEquals(RING_CHORDS, ringOf(frame, IconStyles.T4_WEAPON_RING).size)
        assertEquals(RING_CHORDS, ringOf(frame, IconStyles.POWERUP_RING).size)
        assertTrue(ringOf(frame, IconStyles.T5_WEAPON_RING).isEmpty(), "the T4 half was promoted to a red ring")
        val bloom = frame.batches.single {
            it.layer == Layer.ItemHalo && it.style == IconStyles.T4_WEAPON_RING && it.primitive == Primitive.Segment
        }
        assertEquals(RING_CHORDS, bloom.size)
        assertTrue(
            frame.batches.none {
                it.layer == Layer.ItemHalo && it.style == IconStyles.POWERUP_RING && it.primitive == Primitive.Segment
            },
            "the powerup half inherited the weapon bloom",
        )
    }

    /**
     * P-51: the ring is a stroked circle of `KIND_RING × scale` about the icon's drawn origin, in
     * the kind's colour, on the item layer over a halo. Measured from the segments themselves so
     * the hover (P-52) cannot move the centre out from under the assertion.
     */
    @Test
    fun `a drop's ring is a circle of the kind's colour at the icon's scale`() {
        listOf(
            Triple(WeaponId.BrokenBottle, weaponRing(WeaponId.BrokenBottle), PickupLook.of(Weapons.of(WeaponId.BrokenBottle))),
            Triple(WeaponId.VoiceOfTheDeadNet, weaponRing(WeaponId.VoiceOfTheDeadNet), PickupLook.of(Weapons.of(WeaponId.VoiceOfTheDeadNet))),
        ).forEach { (id, colour, look) ->
            val sim = simulation().also { it.items.clear() }
            sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = Weapons.of(id)))
            val ring = ringOf(compose(sim), colour)

            val expected = IconStyles.KIND_RING * Scene.PICKUP_PX * look.scale
            assertTrue(ring.isNotEmpty(), "$id drew no $colour segment on the item layer")
            ring.forEach { (radius, _) ->
                assertTrue(abs(radius - expected) < RING_TOLERANCE, "$id's ring point sits at $radius, not $expected")
            }
            assertTrue(ring.size >= MIN_RING_SEGMENTS, "$id's ring is ${ring.size} segments; that is a polygon")
        }
        val sim = simulation().also { it.items.clear() }
        sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), powerup = Powerups.of(PowerupId.ForkBomb)))
        assertTrue(ringOf(compose(sim), IconStyles.POWERUP_RING).isNotEmpty(), "a powerup drew no blue ring")
        assertTrue(TIER_RINGS.none { ringOf(compose(sim), it.second).isNotEmpty() }, "a powerup drew a weapon-tier ring")
    }

    /** Review round 1: "over a halo" must be asserted of the ring's own halo, on the halo layer. */
    @Test
    fun `a drop's ring sits on a halo ring of the same radius on the halo layer`() {
        val sim = simulation().also { it.items.clear() }
        sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = Weapons.of(WeaponId.ChromeFang)))
        val frame = compose(sim)
        val colour = weaponRing(WeaponId.ChromeFang)
        val centre = ringCentreOf(frame, colour)
        val radius = IconStyles.KIND_RING * Scene.PICKUP_PX * PickupLook.of(Weapons.of(WeaponId.ChromeFang)).scale

        val haloPoints = frame.batches
            .filter { it.layer == Layer.ItemHalo && it.style == IconStyles.HALO && it.primitive == Primitive.Segment }
            .flatMap { batch -> (0 until batch.size).map { n -> Vec2(batch[n * 4], batch[n * 4 + 1]) } }
            .count { abs((it - centre).length - radius) < RING_TOLERANCE }
        assertEquals(RING_CHORDS, haloPoints, "the ring's halo is not a $RING_CHORDS-chord circle under it")
        assertEquals(RING_CHORDS, ringOf(frame, colour).size, "the ring itself is not $RING_CHORDS chords")
        assertTrue(Layer.ItemHalo.ordinal < Layer.Items.ordinal)
    }

    /** Review round 1: the powerup half of a paired award hovers too, out of step with its weapon. */
    @Test
    fun `both halves of a paired drop hover, out of step, and neither item moves`() {
        val sim = simulation().also { it.items.clear() }
        val item = GroundItem.equipment(
            dropAt(sim, 0.0),
            weapon = Weapons.of(WeaponId.ChromeFang),
            powerup = Powerups.of(PowerupId.ForkBomb),
        )
        sim.items.add(item)
        val before = item.position to item.powerupPosition

        val weapon = (0 until SAMPLES).map { ringCentreOf(compose(sim, Scene.HOVER_PERIOD * it / SAMPLES), weaponRing(WeaponId.ChromeFang)).y }
        val blue = (0 until SAMPLES).map { ringCentreOf(compose(sim, Scene.HOVER_PERIOD * it / SAMPLES), IconStyles.POWERUP_RING).y }
        assertTrue(abs(weapon.max() - weapon.min() - 2 * Scene.HOVER_PX) < HOVER_TOLERANCE, "the weapon half swung ${weapon.max() - weapon.min()}")
        assertTrue(abs(blue.max() - blue.min() - 2 * Scene.HOVER_PX) < HOVER_TOLERANCE, "the powerup half swung ${blue.max() - blue.min()}")
        val redPeak = weapon.indexOf(weapon.min())
        val bluePeak = blue.indexOf(blue.min())
        assertTrue(redPeak != bluePeak, "the two halves peak together at sample $redPeak; the phase is not by position")
        assertEquals(before, item.position to item.powerupPosition, "drawing moved the paired item")
    }

    /** Review round 1: the browser frame lies between two ticks; the hover follows the frame, not the tick. */
    @Test
    fun `the hover follows the interpolated frame time`() {
        val sim = simulation().also { it.items.clear() }
        sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = Weapons.of(WeaponId.ChromeFang)))
        val t = 0.7
        val tick = io.github.ksean.cyberslop.physics.TICK_SECONDS

        val colour = weaponRing(WeaponId.ChromeFang)
        val atTick = ringCentreOf(compose(sim, t, alpha = 1.0), colour).y
        val midway = ringCentreOf(compose(sim, t, alpha = 0.5), colour).y
        val earlier = ringCentreOf(compose(sim, t - 0.5 * tick, alpha = 1.0), colour).y

        assertTrue(abs(midway - earlier) < 1e-9, "a frame halfway to the tick ($midway) is not drawn at the halfway time ($earlier)")
        assertTrue(abs(midway - atTick) > 1e-6, "the frame time made no difference")
        assertEquals(0.0, Scene.presentationTime(0.0, 0.0), "the first frame's time went negative")
    }

    /** Review round 1: a rarer drop's wider material batch opens after a commoner drop's streak batch. */
    @Test
    fun `weathering is on its own layer over the materials, whatever the mix of tiers`() {
        val sim = simulation().also { it.items.clear() }
        sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = Weapons.of(WeaponId.CorpoRiotBaton)))
        sim.items.add(GroundItem.equipment(dropAt(sim, 40.0), weapon = Weapons.of(WeaponId.SableCorpRailgun)))
        val frame = compose(sim)
        val icons = listOf(WeaponIcons.of(WeaponId.CorpoRiotBaton), WeaponIcons.of(WeaponId.SableCorpRailgun))
        val scales = listOf(Weapons.of(WeaponId.CorpoRiotBaton), Weapons.of(WeaponId.SableCorpRailgun))
            .map { Scene.PICKUP_PX * PickupLook.of(it).scale }

        // Steel's rust is also the Rust material's colour, so a style cannot tell a streak from a
        // rusted part on the material layer; the count on the wear layer can — one per weathered
        // stroke of each icon, and none of them anywhere else.
        fun expected(material: Material) = icons.zip(scales).sumOf { (icon, scale) ->
            icon.strokes.count { it.material == material && IconStyles.streakWidthOf(it.weight, scale) != null }
        }
        fun onWear(style: String) = frame.batches
            .filter { it.layer == Layer.ItemWear && it.style == style && it.primitive == Primitive.Segment }
            .sumOf { it.size }
        assertTrue(expected(Material.Steel) > 0 && expected(Material.Wood) > 0, "fixture: nothing to weather")
        assertEquals(expected(Material.Steel), onWear(Material.Steel.weathering!!), "steel rust streaks on the wear layer")
        assertEquals(expected(Material.Wood), onWear(Material.Wood.weathering!!), "wood grain streaks on the wear layer")
        assertTrue(
            frame.batches.none { it.layer == Layer.ItemWear && it.style !in setOf(Material.Steel.weathering, Material.Wood.weathering) },
            "something other than weathering is on the wear layer",
        )
        assertTrue(
            frame.batches.none { it.layer == Layer.Items && it.style == Material.Wood.weathering },
            "wood grain is on the material layer",
        )
    }

    /**
     * P-52: a drop hovers (PROD-079). The ring's centre — and with it the halo and the pips — rises
     * and falls by `HOVER_PX` about the rest position over `HOVER_PERIOD`, and the simulation's own
     * item never moves.
     */
    @Test
    fun `a drop hovers about its resting position and the item itself does not move`() {
        val sim = simulation().also { it.items.clear() }
        val raised = dropAt(sim, 0.0) - Vec2(0.0, DeathDropPlacement.DEATH_DROP_RISE)
        val item = GroundItem.equipment(raised, weapon = Weapons.of(WeaponId.ChromeFang))
        sim.items.add(item)
        val before = item.position

        val samples = (0 until SAMPLES).map { step ->
            val t = Scene.HOVER_PERIOD * step / SAMPLES
            t to compose(sim, t)
        }
        val colour = weaponRing(WeaponId.ChromeFang)
        val centres = samples.map { (_, frame) -> ringCentreOf(frame, colour).y }
        val swing = centres.max() - centres.min()
        assertTrue(abs(swing - 2 * Scene.HOVER_PX) < HOVER_TOLERANCE, "the drop swung $swing px, not ${2 * Scene.HOVER_PX}")
        assertTrue(centres.toSet().size > 2, "the drop sat still: $centres")
        assertTrue(
            abs(centres.average() - raised.y * Scene.ZOOM) < HOVER_TOLERANCE,
            "the raised drop hovered about ${centres.average()}, not its resting y ${raised.y * Scene.ZOOM}",
        )

        val rest = ringCentreOf(compose(sim, 0.0), colour)
        val later = ringCentreOf(compose(sim, Scene.HOVER_PERIOD), colour)
        assertTrue(abs(rest.y - later.y) < HOVER_TOLERANCE, "a period later the drop was at ${later.y}, not ${rest.y}")
        assertEquals(rest.x, later.x, "the hover moved the drop sideways")

        // The halo and the pips travel with the ring, measured at the sample where the drop sat
        // highest — the phase depends on where the drop is, so no fixed time is its peak.
        val peak = samples[centres.indexOf(centres.min())].first
        val ringShift = ringCentreOf(compose(sim, peak), colour).y - rest.y
        val pipShift = pipYOf(compose(sim, peak), colour) - pipYOf(compose(sim, 0.0), colour)
        val haloShift = haloCentreOf(compose(sim, peak)) - haloCentreOf(compose(sim, 0.0))
        assertTrue(abs(ringShift) > 0.5, "at its peak the drop had not moved from its rest: $ringShift")
        assertTrue(abs(pipShift - ringShift) < HOVER_TOLERANCE, "the pips shifted $pipShift against the ring's $ringShift")
        assertTrue(abs(haloShift - ringShift) < HOVER_TOLERANCE, "the halo shifted $haloShift against the ring's $ringShift")

        assertEquals(before, item.position, "drawing moved the item in the simulation")
    }

    /**
     * P-30 (specs/presentation.md), and asserted of the **pair**.
     *
     * Neither line clears the margin alone and that is measured, not assumed: the red outline sits
     * 2.0 from `ArcologyVault.tileBody`, and a near-black halo sits 0.1 from `ReactorCore.sky`.
     * What the pair guarantees is that one of the two always separates.
     */
    @Test
    fun `every material and ring colour separates with its halo from every palette's ground and sky`() {
        val halo = Palette.luminanceOf(IconStyles.HALO)

        // The materials and the rings, which meet the background; a weathering streak lies on
        // its own material and is judged against it on the sheet, not here.
        (ringColours() + Material.entries.map { it.colour }).forEach { outline ->
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
        sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = Weapons.of(WeaponId.ChromeFang)))
        sim.items.add(GroundItem.equipment(dropAt(sim, 40.0), powerup = Powerups.of(PowerupId.BurnRig)))
        val frame = compose(sim)

        val drops = itemStyles(frame)
        val elsewhere = frame.batches
            .filter { it.layer == Layer.Hazard || (it.layer.ordinal >= Layer.ShotGlow.ordinal && it.layer.ordinal <= Layer.Effects.ordinal) }
            .map { it.style }
            .toSet()

        assertTrue(drops.isNotEmpty(), "nothing was drawn on the item layer")
        assertTrue(
            drops.intersect(elsewhere).isEmpty(),
            "${drops.intersect(elsewhere)} is used for both a drop and a hazard or projectile",
        )
        // And against the fixed shot looks by construction, not only by what this frame happened to draw.
        val fixed = (ringColours() + materialColours()).intersect(ShotLooks.fixedColours.toSet())
        assertTrue(fixed.isEmpty(), "$fixed is both an item colour and a shot colour")
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
            .count { it.layer == Layer.Items || it.layer == Layer.ItemHalo || it.layer == Layer.ItemWear }

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
                items.add(GroundItem.equipment(dropAt(this, offset), weapon = Weapons.of(it)))
                offset += SPACING
            }
            PowerupId.entries.forEach {
                items.add(GroundItem.equipment(dropAt(this, offset), powerup = Powerups.of(it)))
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
        override fun fillTriangles(style: String, batch: DrawBatch) = record(batch, 1)
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
        sim.items.add(GroundItem.equipment(dropAt(sim, 0.0), weapon = Weapons.of(id)))
        val frame = compose(sim)

        // The icon's own extent is its halo pass, which is one stroke per stroke of the icon;
        // the ring is measured elsewhere and the pips are the only tier-coloured dots on the layer.
        val halo = frame.batches.filter {
            it.layer == Layer.ItemHalo && it.style == IconStyles.HALO && it.primitive == Primitive.Segment &&
                it.width < RING_HALO_WIDTH_CEILING
        }
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        halo.forEach { batch ->
            for (index in 0 until batch.size * Primitive.Segment.stride step 2) {
                val x = batch[index]
                if (x < low) low = x
                if (x > high) high = x
            }
        }
        val pips = frame.batches
            .filter { it.layer == Layer.Items && it.style == weaponRing(id) && it.primitive == Primitive.Dot }
            .sumOf { it.size }
        return Drawn(high - low, pips)
    }

    private fun ringColours() = TIER_RINGS.map { it.second } + IconStyles.POWERUP_RING

    private fun weaponRing(id: WeaponId): String =
        IconStyles.weaponRing(Weapons.of(id).tier.ordinal)

    private fun materialColours() = Material.entries.flatMap { listOfNotNull(it.colour, it.weathering) }.distinct()

    /** Every segment endpoint of [colour] on the item layer, as (distance from their centroid, point). */
    private fun ringOf(frame: DrawList, colour: String): List<Pair<Double, Vec2>> {
        val points = frame.batches
            .filter { it.layer == Layer.Items && it.style == colour && it.primitive == Primitive.Segment }
            .flatMap { batch ->
                (0 until batch.size).map { index ->
                    val at = index * Primitive.Segment.stride
                    Vec2(batch[at], batch[at + 1])
                }
            }
        if (points.isEmpty()) return emptyList()
        val centre = Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
        return points.map { point ->
            val dx = point.x - centre.x
            val dy = point.y - centre.y
            kotlin.math.sqrt(dx * dx + dy * dy) to point
        }
    }

    private fun ringCentreOf(frame: DrawList, colour: String): Vec2 {
        val points = ringOf(frame, colour).map { it.second }
        assertTrue(points.isNotEmpty(), "no $colour ring in the frame")
        return Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
    }

    private fun pipYOf(frame: DrawList, colour: String): Double {
        val pips = frame.batches.filter {
            it.layer == Layer.Items && it.style == colour && it.primitive == Primitive.Dot
        }
        assertTrue(pips.isNotEmpty(), "no pips in the frame")
        return pips.first()[1]
    }

    private fun haloCentreOf(frame: DrawList): Double {
        val halo = frame.batches.filter { it.layer == Layer.ItemHalo && it.primitive == Primitive.Segment }
        var sum = 0.0
        var count = 0
        halo.forEach { batch ->
            for (index in 0 until batch.size) {
                sum += batch[index * Primitive.Segment.stride + 1]
                count++
            }
        }
        assertTrue(count > 0, "no halo in the frame")
        return sum / count
    }

    private fun itemStyles(frame: DrawList): Set<String> =
        frame.batches
            .filter { it.layer == Layer.Items || it.layer == Layer.ItemHalo || it.layer == Layer.ItemWear }
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

    private fun compose(sim: GameSimulation, timeSeconds: Double = 0.0, alpha: Double = 1.0): DrawList = Scene.compose(
        sim,
        Camera(0.0, 0.0, VIEW_WIDTH, VIEW_HEIGHT),
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim.run, sim.level.theme, MAPS, sim.boss.spec.name, sim.boss.healthFraction),
        timeSeconds,
        SceneBuilder(),
        alpha = alpha,
    )

    /** On screen, so the drop is drawn rather than culled. */
    private fun dropAt(sim: GameSimulation, offset: Double) =
        Vec2(sim.player.x + offset, sim.player.y)

    private fun simulation(): GameSimulation {
        val level = LevelGenerator.generate(SEED, 1).level
        return GameSimulation(level, RunState.begin(SEED), SEED)
    }

    private companion object {
        val TIER_RINGS = listOf(
            WeaponId.BrokenBottle to "#f4f4f4",
            WeaponId.CorpoRiotBaton to "#39d353",
            WeaponId.StaticLash to "#ffd45a",
            WeaponId.SableCorpRailgun to "#b45cff",
            WeaponId.VoiceOfTheDeadNet to "#ff2f2f",
        )
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
        /**
         * P-31's `ITEM_BATCHES`, derived from the vocabulary in `specs/presentation.md` (Item
         * icons): every style the item layers may open, times the distinct ladder widths a weight
         * collapses onto across the five tiers — derived below, at most four (`Slab` is 3.5, 4.5,
         * 6, 6, 8). Halo: three weights of segment, one dot, and
         * the ring's halo plus the fixed T4/T5 bloom batches. Items: five materials and two streak
         * colours over three weights of segment, five material dots, five tier-specific weapon
         * ring widths plus the powerup's ladder widths, and six pip dot styles.
         */
        private val TIER_SCALES = (0 until 5).map {
            Scene.PICKUP_PX * (PickupLook.MIN_SCALE + (PickupLook.MAX_SCALE - PickupLook.MIN_SCALE) * it / 4.0)
        }
        private fun distinct(width: (StrokeWeight, Double) -> Double?): Int =
            StrokeWeight.entries.sumOf { weight -> TIER_SCALES.mapNotNull { width(weight, it) }.toSet().size }
        private val HALO_WIDTHS = distinct { w, s -> IconStyles.haloWidthOf(w, s) }
        private val BODY_WIDTHS = distinct { w, s -> IconStyles.widthOf(w, s) }
        private val STREAK_WIDTHS = distinct { w, s -> IconStyles.streakWidthOf(w, s) }
        private val RING_HALO_WIDTHS = TIER_SCALES.map { IconStyles.haloWidthOf(StrokeWeight.Hair, it) }.toSet().size
        private val RING_WIDTHS = TIER_SCALES.map { IconStyles.widthOf(StrokeWeight.Hair, it) }.toSet().size
        private const val MATERIALS = 5
        private const val STREAK_COLOURS = 2
        private const val WEAPON_TIERS = 5
        private const val RING_COLOURS = WEAPON_TIERS + 1
        private const val BLOOM_BATCHES = 2
        val MAX_ITEM_BATCHES: Int =
            (HALO_WIDTHS + 1 + RING_HALO_WIDTHS + BLOOM_BATCHES) +
                (MATERIALS * BODY_WIDTHS + MATERIALS + WEAPON_TIERS + RING_WIDTHS + RING_COLOURS) +
                (STREAK_COLOURS * STREAK_WIDTHS)
        const val RING_TOLERANCE = 0.5
        const val MIN_RING_SEGMENTS = 12
        const val RING_CHORDS = 16
        const val SAMPLES = 16
        const val HOVER_TOLERANCE = 0.05
        /** A ring's halo is wider than any icon stroke's halo could be at Ascended scale. */
        const val RING_HALO_WIDTH_CEILING = 1e9
    }
}
