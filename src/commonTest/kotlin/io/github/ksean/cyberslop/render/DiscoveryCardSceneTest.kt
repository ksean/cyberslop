package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.progression.DiscoveryCatalog
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-57: a discovery is a centred, code-native card over a dimmed live frame. */
class DiscoveryCardSceneTest {
    @Test
    fun `card is centred names and describes the item and paints only its canonical icon`() {
        val sim = TestLevels.simulation()
        val camera = Camera(0.0, 180.0, VIEW_WIDTH, VIEW_HEIGHT)
        val entry = DiscoveryCatalog.of(DiscoveryId.Weapon(WeaponId.RiotbreakerShotgun))
        val frame = Scene.compose(
            sim,
            camera,
            Backdrops.of(SEED, sim.level),
            HudModel.of(sim),
            0.0,
            SceneBuilder(),
            discovery = entry,
        )
        val screenWidth = VIEW_WIDTH * Scene.ZOOM
        val screenHeight = VIEW_HEIGHT * Scene.ZOOM
        val rects = frame.batches
            .filter { it.layer == Layer.Hud && it.primitive == Primitive.Rect }
            .flatMap(::rectangles)

        assertTrue(rects.any { it == Rect(0.0, 0.0, screenWidth, screenHeight) }, "the game frame is not dimmed")
        val panels = rects.filter { it.width < screenWidth && it.height < screenHeight }
        assertTrue(panels.size >= 2, "the card has no bordered panel")
        panels.forEach { panel ->
            assertEquals(screenWidth / 2.0, panel.x + panel.width / 2.0, TOLERANCE)
            assertEquals(screenHeight / 2.0, panel.y + panel.height / 2.0, TOLERANCE)
        }

        assertTrue(frame.texts.any { it.text == "NEW DISCOVERY" && it.align == TextAlign.Centre })
        assertTrue(frame.texts.any { it.text == entry.name && it.bold && it.align == TextAlign.Centre })
        assertEquals(
            entry.description,
            frame.texts.filter { it.text !in setOf("NEW DISCOVERY", entry.name) }.joinToString(" ") { it.text },
        )

        val outlineMarks = frame.batches
            .filter { it.layer == Layer.HudOverlay }
            .sumOf { it.size }
        assertEquals(entry.icon.ops.size, outlineMarks, "the picture added a ground ring or rarity pips")
        val ringColours = (0 until 5).map(IconStyles::weaponRing) + IconStyles.POWERUP_RING
        assertTrue(
            frame.batches.none { it.layer in setOf(Layer.Hud, Layer.HudOverlay, Layer.HudWear) && it.style in ringColours },
            "the discovery card added a ground ring or bloom",
        )
        assertTrue(Layer.Hud.ordinal < Layer.HudOverlay.ordinal)
        assertTrue(Layer.HudOverlay.ordinal < Layer.HudWear.ordinal)
    }

    private fun rectangles(batch: DrawBatch): List<Rect> = (0 until batch.size).map { index ->
        val at = index * Primitive.Rect.stride
        Rect(batch[at], batch[at + 1], batch[at + 2], batch[at + 3])
    }

    private data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)

    private companion object {
        const val SEED = 0xD15C0uL
        const val VIEW_WIDTH = 240.0
        const val VIEW_HEIGHT = 130.0
        const val TOLERANCE = 1e-9
    }
}
