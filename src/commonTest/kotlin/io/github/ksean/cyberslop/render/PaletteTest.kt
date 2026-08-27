package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.world.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROD-040: ten sub-themes, ten palettes, told apart without reading the map name.
 *
 * The game shipped one palette for all ten, so `Reactor Core` and `Flooded Undercity` were the same
 * four colours with different geometry — which is exactly the "varies in decoration alone" that
 * PROD-025 already forbids for geometry.
 */
class PaletteTest {
    @Test
    fun `every theme has a palette`() {
        ThemeId.entries.forEach { theme ->
            val palette = Palettes.of(theme)
            assertTrue(
                palette.colours.all { it.startsWith("#") && it.length == 7 },
                "$theme has a colour that is not an opaque hex triplet: ${palette.colours}",
            )
        }
    }

    @Test
    fun `no two themes share a palette`() {
        val distinct = ThemeId.entries.map { Palettes.of(it).colours }.distinct()

        assertEquals(
            ThemeId.entries.size,
            distinct.size,
            "two sub-themes are drawn identically, so the run looks like one place ten times",
        )
    }

    @Test
    fun `a palette holds the roles the renderer needs`() {
        val palette = Palettes.of(ThemeId.NeonSlums)

        // Named rather than counted: a test that only counts colours passes for a palette whose
        // hazard and its background are the same shade.
        assertTrue(palette.tileBody != palette.tileEdge, "tiles have no lit surface")
        assertTrue(palette.hazard != palette.tileBody, "acid is the same colour as the floor")
        assertTrue(palette.accent != palette.sky, "the neon accent vanishes into the sky")
        assertEquals(
            GLOW_TONES,
            palette.glow.size,
            "enemy luminance has to come from a bounded set of tones, or every enemy is its own " +
                "draw batch (ENG-061)",
        )
    }

    private companion object {
        const val GLOW_TONES = 3
    }
}
