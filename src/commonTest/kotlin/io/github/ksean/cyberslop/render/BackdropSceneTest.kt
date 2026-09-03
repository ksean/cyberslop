package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-76 over the composed frame: themed marks keep their depth and their batching. */
class BackdropSceneTest {
    @Test
    fun `distant landscape paints behind buildings and every primitive follows its slower parallax`() {
        val sim = TestLevels.simulation()
        val backdrop = backdrop(buildingCount = 1)
        val before = frame(sim, backdrop, cameraX = 0.0)
        val after = frame(sim, backdrop, cameraX = CAMERA_STEP)
        val distant = backdrop.distant

        assertEquals(
            listOf(Layer.BackdropDistant, Layer.BackdropFar, Layer.BackdropMid, Layer.BackdropNear),
            before.batches.map { it.layer }.filter { it.name.startsWith("Backdrop") }.distinct(),
        )
        assertEquals(
            Primitive.entries.toSet(),
            before.batches.filter { it.layer == distant.layer }.map { it.primitive }.toSet(),
            "the distant fixture did not exercise its complete primitive vocabulary",
        )

        val first = xCoordinates(before, distant.layer)
        val moved = xCoordinates(after, distant.layer)
        assertEquals(first.size, moved.size)
        first.zip(moved).forEach { (x, nextX) ->
            assertEquals(
                x - CAMERA_STEP * distant.parallax * Scene.ZOOM,
                nextX,
                TOLERANCE,
                "a distant primitive moved at a rate other than ${distant.parallax}",
            )
        }
    }

    @Test
    fun `every map distant landscape uses its subdued tint and exact window accent`() {
        ThemeId.entries.forEachIndexed { index, theme ->
            val sim = TestLevels.simulation(
                TestLevels.flat(mapIndex = index + 1, theme = theme),
            )
            val palette = Palettes.of(theme)
            val styles = frame(sim, Backdrops.of(SEED, sim.level), cameraX = 0.0)
                .batches
                .filter { it.layer == Layer.BackdropDistant }
                .map { it.style }
                .toSet()

            assertEquals(setOf(palette.backdropDistant, palette.theme), styles, "$theme lost its landscape colour cue")
        }
    }

    @Test
    fun `every backdrop primitive follows the parallax of its own layer`() {
        val sim = TestLevels.simulation()
        val backdrop = backdrop(buildingCount = 1)
        val before = frame(sim, backdrop, cameraX = 0.0)
        val after = frame(sim, backdrop, cameraX = CAMERA_STEP)

        backdrop.layers.forEach { layer ->
            val primitives = before.batches
                .filter { it.layer == layer.layer }
                .map { it.primitive }
                .toSet()
            assertEquals(
                setOf(Primitive.Rect, Primitive.Segment, Primitive.Dot),
                primitives,
                "${layer.layer} did not draw the complete structure vocabulary",
            )

            val first = xCoordinates(before, layer.layer)
            val moved = xCoordinates(after, layer.layer)
            assertEquals(first.size, moved.size)
            first.zip(moved).forEach { (x, nextX) ->
                assertEquals(
                    x - CAMERA_STEP * layer.parallax * Scene.ZOOM,
                    nextX,
                    TOLERANCE,
                    "a ${layer.layer} detail moved at a different depth than its building",
                )
            }
        }
    }

    @Test
    fun `backdrop batches do not grow with building or detail count`() {
        val sim = TestLevels.simulation()
        val sparse = frame(sim, backdrop(buildingCount = 1), cameraX = 0.0)
        val crowded = frame(sim, backdrop(buildingCount = MANY), cameraX = 0.0)
        val layers = Layer.entries.filter { it.name.startsWith("Backdrop") }.toSet()
        val sparseBatches = sparse.batches.filter { it.layer in layers }
        val crowdedBatches = crowded.batches.filter { it.layer in layers }

        assertEquals(
            sparseBatches.map { Triple(it.layer, it.style, it.primitive) }.toSet(),
            crowdedBatches.map { Triple(it.layer, it.style, it.primitive) }.toSet(),
        )
        assertTrue(
            crowdedBatches.sumOf { it.size } > sparseBatches.sumOf { it.size } * 20,
            "the crowded skyline did not draw materially more geometry",
        )
    }

    @Test
    fun `distant batches do not grow with feature count`() {
        val sim = TestLevels.simulation()
        val sparse = frame(sim, backdrop(buildingCount = 1, distantCopies = 1), cameraX = 0.0)
        val crowded = frame(sim, backdrop(buildingCount = 1, distantCopies = MANY), cameraX = 0.0)
        val sparseBatches = sparse.batches.filter { it.layer == Layer.BackdropDistant }
        val crowdedBatches = crowded.batches.filter { it.layer == Layer.BackdropDistant }

        assertEquals(
            sparseBatches.map { Triple(it.style, it.primitive, it.width) }.toSet(),
            crowdedBatches.map { Triple(it.style, it.primitive, it.width) }.toSet(),
        )
        assertTrue(
            crowdedBatches.sumOf { it.size } > sparseBatches.sumOf { it.size } * 20,
            "more landscape features did not add materially more geometry",
        )
    }

    private fun frame(
        sim: GameSimulation,
        backdrop: Backdrop,
        cameraX: Double,
    ): DrawList = Scene.compose(
        sim = sim,
        camera = Camera(cameraX, 0.0, VIEW_WIDTH, VIEW_HEIGHT),
        backdrop = backdrop,
        hud = HudModel.of(sim),
        timeSeconds = 0.0,
        builder = SceneBuilder(),
    )

    private fun backdrop(buildingCount: Int, distantCopies: Int = 1): Backdrop {
        val layers = listOf(
            Triple(0.12, "#182039", Layer.BackdropFar),
            Triple(0.30, "#243052", Layer.BackdropMid),
            Triple(0.55, "#32416b", Layer.BackdropNear),
        ).map { (parallax, tint, layer) ->
            BackdropLayer(
                parallax = parallax,
                tint = tint,
                layer = layer,
                buildings = List(buildingCount) { index -> building(index) },
            )
        }
        return Backdrop(
            distant = DistantLandscape(
                profile = DistantLandscapeProfiles.of(ThemeId.NeonSlums),
                parallax = DistantLandscapes.PARALLAX,
                tint = "#170b22",
                layer = Layer.BackdropDistant,
                features = (0 until distantCopies).flatMap { copy ->
                    distantFeatures.map { feature ->
                        feature.copy(x = feature.x + copy * DISTANT_PITCH)
                    }
                },
            ),
            layers = layers,
            horizonFraction = 0.62,
            referenceY = 0.0,
        )
    }

    private fun building(index: Int) = Building(
        x = FIRST_X + index * BUILDING_PITCH,
        width = BUILDING_WIDTH,
        height = BUILDING_HEIGHT,
        windowColumns = 2,
        windowRows = 2,
        windows = 0b1001,
        roof = BackdropRoof.Broken,
        windowLayout = BackdropWindows.Grid,
        features = BackdropMotif.entries.mapIndexed { featureIndex, motif ->
            BackdropFeature(
                motif = motif,
                anchor = 0.15 + featureIndex % 4 * 0.20,
                scale = 1.0,
                variant = featureIndex % 4,
            )
        },
    )

    private val distantFeatures = listOf(
        distantFeature(DistantMotif.AshMesa, 12.0, 36.0, 24.0),
        distantFeature(DistantMotif.MegastructureRib, 50.0, 30.0, 20.0),
        distantFeature(DistantMotif.DustMark, 82.0, 2.0, 2.0),
        distantFeature(DistantMotif.DustStreak, 90.0, 12.0, DistantLandscapes.TEXTURE_STROKE_WORLD),
    )

    private fun distantFeature(
        motif: DistantMotif,
        x: Double,
        width: Double,
        height: Double,
    ) = DistantFeature(
        motif = motif,
        x = x,
        baselineOffset = 0.0,
        width = width,
        height = height,
        strokeWidth = DistantLandscapes.TEXTURE_STROKE_WORLD,
        variant = 1,
    )

    private fun xCoordinates(frame: DrawList, layer: Layer): List<Double> = buildList {
        frame.batches.filter { it.layer == layer }.forEach { batch ->
            when (batch.primitive) {
                Primitive.Rect -> repeat(batch.size) { add(batch[it * 4]) }
                Primitive.Triangle -> repeat(batch.size) {
                    add(batch[it * 6])
                    add(batch[it * 6 + 2])
                    add(batch[it * 6 + 4])
                }
                Primitive.Segment -> repeat(batch.size) {
                    add(batch[it * 4])
                    add(batch[it * 4 + 2])
                }

                Primitive.Dot -> repeat(batch.size) { add(batch[it * 3]) }
            }
        }
    }

    private companion object {
        val SEED = 0xBACC0FFuL
        const val CAMERA_STEP = 10.0
        const val VIEW_WIDTH = 1_200.0
        const val VIEW_HEIGHT = 180.0
        const val FIRST_X = 30.0
        const val BUILDING_PITCH = 20.0
        const val BUILDING_WIDTH = 16.0
        const val BUILDING_HEIGHT = 26.0
        const val DISTANT_PITCH = 30.0
        const val MANY = 50
        const val TOLERANCE = 1e-9
    }
}
