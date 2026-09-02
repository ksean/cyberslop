package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.ThemeId
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-76: every map gets authored dystopian structure, then its seed supplies variation. */
class BackdropProfileTest {
    @Test
    fun `every theme has its own required motif signature`() {
        val profiles = ThemeId.entries.map(BackdropProfiles::of)

        assertEquals(ThemeId.entries, profiles.map { it.theme })
        assertEquals(profiles.size, profiles.map { it.motifs.toSet() }.distinct().size)
        profiles.forEach { profile ->
            assertTrue(profile.motifs.size >= 4, "${profile.theme} has too little authored structure")
            assertTrue(profile.roofs.isNotEmpty(), "${profile.theme} has no roof language")
            assertTrue(profile.windows.isNotEmpty(), "${profile.theme} has no window language")
        }

        assertEquals(
            expectedMotifs,
            profiles.associate { it.theme to it.motifs.toSet() },
        )
    }

    @Test
    fun `nearer layers put more profile detail on every building`() {
        val theme = ThemeId.NeonSlums
        val profile = BackdropProfiles.of(theme)
        val backdrop = Backdrops.of(SEED, level(theme))

        assertEquals(listOf(1, 2, 3), backdrop.layers.map { layer ->
            layer.buildings.minOf { it.features.size }
        })
        backdrop.layers.forEach { layer ->
            layer.buildings.forEach { building ->
                assertTrue(building.features.all { it.motif in profile.motifs })
            }
        }
    }

    private fun level(theme: ThemeId) = Level(
        mapIndex = theme.ordinal + 1,
        theme = theme,
        tiles = TileMap(80, 40),
        floorMask = Mask(80, 40),
        arcMask = Mask(80, 40),
        spawnColumn = 2,
        spawnRow = 30,
        miniboss = Arena(20, 30, 30),
        boss = Arena(40, 50, 30),
        jets = emptyList(),
    )

    private companion object {
        val SEED = 0xC0FFEEuL

        val expectedMotifs = mapOf(
            ThemeId.RuinedCitySprawl to setOf(
                BackdropMotif.RoofDamage,
                BackdropMotif.Antenna,
                BackdropMotif.BridgeFragment,
                BackdropMotif.Buttress,
            ),
            ThemeId.RustFlats to setOf(
                BackdropMotif.Tank,
                BackdropMotif.Gantry,
                BackdropMotif.Stack,
                BackdropMotif.Pipe,
            ),
            ThemeId.FloodedUndercity to setOf(
                BackdropMotif.Pipe,
                BackdropMotif.BridgeFragment,
                BackdropMotif.Vent,
                BackdropMotif.Tank,
            ),
            ThemeId.ChemFoundry to setOf(
                BackdropMotif.Stack,
                BackdropMotif.Tank,
                BackdropMotif.Pipe,
                BackdropMotif.Vent,
            ),
            ThemeId.NeonSlums to setOf(
                BackdropMotif.SignFrame,
                BackdropMotif.Antenna,
                BackdropMotif.Cable,
                BackdropMotif.Gantry,
            ),
            ThemeId.SableRefinery to setOf(
                BackdropMotif.Stack,
                BackdropMotif.Pipe,
                BackdropMotif.Tank,
                BackdropMotif.Gantry,
                BackdropMotif.Buttress,
            ),
            ThemeId.ServerStacks to setOf(
                BackdropMotif.Vent,
                BackdropMotif.Cable,
                BackdropMotif.Buttress,
                BackdropMotif.LightStrip,
            ),
            ThemeId.SkybridgeRuin to setOf(
                BackdropMotif.BridgeFragment,
                BackdropMotif.Cable,
                BackdropMotif.Antenna,
                BackdropMotif.Buttress,
            ),
            ThemeId.ReactorCore to setOf(
                BackdropMotif.Tank,
                BackdropMotif.Stack,
                BackdropMotif.Pipe,
                BackdropMotif.Buttress,
                BackdropMotif.LightStrip,
            ),
            ThemeId.ArcologyVault to setOf(
                BackdropMotif.Buttress,
                BackdropMotif.Antenna,
                BackdropMotif.BridgeFragment,
                BackdropMotif.Gantry,
                BackdropMotif.LightStrip,
            ),
        )
    }
}
