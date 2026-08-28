package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.world.ThemeId

/** One cell of the sheet. */
data class SheetEntry(val name: String, val icon: Icon, val weapon: Boolean)

/**
 * Renders every icon so a person can look at it.
 *
 * *"A shotgun looks like a shotgun"* is a claim about a person, and no assertion reaches it
 * (`specs/presentation.md`, Item icons). This is the mechanism that does: it validates recognisability the way
 * `specs/engineering.md` (Verification) validates everything tests cannot reach, by rendering the thing and having the owner
 * look at it.
 *
 * **It lives in `jvmTest` on purpose.** The task that planned it asked for a `Layer.Debug` scene in
 * `commonMain` and a browser test proving production could not reach it. This is the stronger form of
 * the same guarantee and needs no such test: `jvm()` is a verification-only target from which no
 * deployable artifact is produced (ENG-001), so the sheet is not in the bundle by construction rather
 * than by an assertion that it is not reachable.
 *
 * It draws through [SceneBuilder] and [FramePainter], not around them, so what it shows is what the
 * browser would issue — the same batching, the same layer order, the same halo-then-outline pair.
 */
object IconSheet {
    const val CELL = 132.0
    const val COLUMNS = 6

    /** The five tier scales `PickupLook` produces, so the sheet shows the sizes the game draws. */
    val tierScales: List<Double> = (0 until 5).map {
        PickupLook.MIN_SCALE + (PickupLook.MAX_SCALE - PickupLook.MIN_SCALE) * it / 4.0
    }

    /**
     * Every icon at the size it is drawn on the ground at the rarest tier, on one dark ground.
     * This is the sheet that answers "is it the thing".
     */
    fun identity(entries: List<SheetEntry>, builder: SceneBuilder): DrawList {
        builder.begin()
        val scale = Scene.PICKUP_PX * tierScales.last()
        entries.forEachIndexed { index, entry ->
            val column = index % COLUMNS
            val row = index / COLUMNS
            val x = CELL * (column + 0.5)
            val y = CELL * (row + 0.5)
            paint(builder, entry, x, y, scale, Vec2.Right)
            builder.text(
                TextItem(entry.name, x, y + CELL * 0.40, 11.0, "#c7d2e0", TextAlign.Centre),
            )
        }
        return builder.build()
    }

    /**
     * One icon at all five tier sizes over all ten palettes' terrain, plus a greyscale copy of the
     * whole thing. This is the sheet that answers PROD-051 — whether the pair is still visible.
     */
    fun legibility(entry: SheetEntry, builder: SceneBuilder): DrawList {
        builder.begin()
        ThemeId.entries.forEachIndexed { row, theme ->
            val palette = Palettes.of(theme)
            val y = CELL * (row + 0.5)
            // The terrain the icon has to survive being drawn against, as two bands.
            builder.batch(Layer.Sky, palette.sky, Primitive.Rect)
                .rect(0.0, y - CELL / 2.0, CELL * tierScales.size, CELL)
            builder.batch(Layer.Terrain, palette.tileBody, Primitive.Rect)
                .rect(0.0, y - CELL / 6.0, CELL * tierScales.size, CELL / 2.0)
            builder.batch(Layer.Terrain, palette.tileEdge, Primitive.Rect)
                .rect(0.0, y - CELL / 6.0, CELL * tierScales.size, 4.0)

            tierScales.forEachIndexed { column, tier ->
                paint(builder, entry, CELL * (column + 0.5), y, Scene.PICKUP_PX * tier, Vec2.Right)
            }
            builder.text(
                TextItem(
                    theme.name,
                    CELL * tierScales.size + 8.0,
                    y + 4.0,
                    11.0,
                    palette.tileEdge,
                ),
            )
        }
        return builder.build()
    }

    /** The same icon turned through a circle, which is how the player's hand will hold it. */
    fun orientation(entry: SheetEntry, builder: SceneBuilder): DrawList {
        builder.begin()
        val steps = 8
        repeat(steps) { step ->
            val degrees = 360.0 * step / steps
            val aim = io.github.ksean.cyberslop.core.TrigTable.rotate(Vec2.Right, degrees)
            val x = CELL * (step % COLUMNS + 0.5)
            val y = CELL * (step / COLUMNS + 0.5)
            builder.batch(Layer.Debug, "#1a2030", Primitive.Dot).dot(x, y, 3.0)
            paint(builder, entry, x, y, Scene.PICKUP_PX * tierScales.last(), aim)
        }
        return builder.build()
    }

    /**
     * Halo first, outline over it. The order is the point: a red line on a dark backing reads as a
     * red-edged object, where the reverse reads as a dark object with a red core.
     */
    private fun paint(
        builder: SceneBuilder,
        entry: SheetEntry,
        x: Double,
        y: Double,
        scale: Double,
        aim: Vec2,
    ) {
        IconPainter.paint(builder, entry.icon, entry.weapon, x, y, scale, Layer.ItemHalo, Layer.Items, aim)
    }
}
