package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the icon sheets and checks the little a sheet can be checked for.
 *
 * The sheets exist for a person to look at (`plan.md` §16.7); what this test can assert is that
 * every entry actually put marks on the page at every tier, which is what catches an icon that
 * silently draws nothing. Recognisability is the owner's call, made against the files this writes.
 */
class IconSheetTest {
    @Test
    fun `every icon puts marks on the sheet at every tier`() {
        val builder = SceneBuilder()

        entries().forEach { entry ->
            IconSheet.tierScales.forEach { tier ->
                builder.begin()
                IconPainter.paint(
                    builder,
                    entry.icon,
                    entry.weapon,
                    0.0,
                    0.0,
                    Scene.PICKUP_PX * tier,
                    Layer.ItemHalo,
                    Layer.Items,
                )
                val marks = builder.build().batches.sumOf { it.size }
                assertTrue(
                    marks >= entry.icon.ops.size * 2,
                    "${entry.name} at tier $tier drew $marks marks for ${entry.icon.ops.size} ops " +
                        "over two passes",
                )
            }
        }
    }

    @Test
    fun `the sheets are written for the owner to look at`() {
        val builder = SceneBuilder()
        val entries = entries()

        val rows = (entries.size + IconSheet.COLUMNS - 1) / IconSheet.COLUMNS
        write(
            "icon-sheet-identity.svg",
            IconSheet.identity(entries, builder),
            IconSheet.CELL * IconSheet.COLUMNS,
            IconSheet.CELL * rows,
            "#0b0e16",
        )
        entries.filter { it.name in LEGIBILITY }.forEach { entry ->
            write(
                "icon-sheet-legibility-${entry.name.lowercase().replace(' ', '-')}.svg",
                IconSheet.legibility(entry, builder),
                IconSheet.CELL * IconSheet.tierScales.size + 140.0,
                IconSheet.CELL * 10,
                "#0b0e16",
            )
        }
        write(
            "icon-sheet-orientation.svg",
            IconSheet.orientation(entries.first { it.weapon }, builder),
            IconSheet.CELL * IconSheet.COLUMNS,
            IconSheet.CELL * 2,
            "#0b0e16",
        )

        assertTrue(sheetDirectory().resolve("icon-sheet-identity.svg").length() > 0)
    }

    private fun write(name: String, frame: DrawList, width: Double, height: Double, ground: String) {
        val sink = SvgPaintSink(width, height, ground)
        FramePainter.paint(frame, sink)
        sheetDirectory().resolve(name).writeText(sink.toSvg())
    }

    private fun sheetDirectory(): File =
        File("build/icon-sheets").also { it.mkdirs() }

    /** One of each family, which is all the legibility sheet is asking about. */
    private companion object {
        val LEGIBILITY = setOf("BrokenBottle", "SableCorpRailgun", "OverclockCoil")
    }

    private fun entries(): List<SheetEntry> =
        WeaponId.entries.map { SheetEntry(it.name, WeaponIcons.of(it), weapon = true) } +
            PowerupId.entries.map { SheetEntry(it.name, PowerupIcons.of(it), weapon = false) }
}
