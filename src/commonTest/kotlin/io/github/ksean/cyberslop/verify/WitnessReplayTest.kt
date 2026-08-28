package io.github.ksean.cyberslop.verify

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The completability guarantee (PROD-024) in executable form. The generator emits a witness as it
 * carves; this replays that witness through the game's own movement model and asserts it arrives.
 * A generator that produced an uncrossable map would fail here rather than reaching a player.
 */
class WitnessReplayTest {
    @Test
    fun `the witness reaches the boss arena alive`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)

        val result = WitnessReplay.replay(generated.level, generated.witness)

        assertTrue(result.reachedBoss, "witness stopped at ${result.finalState.x}")
        assertFalse(result.touchedLethal, "witness path passes through a lethal tile")
    }

    @Test
    fun `the witness transits the mini-boss arena on the way`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)

        val result = WitnessReplay.replay(generated.level, generated.witness)

        assertTrue(result.reachedMiniboss, "witness never entered the mini-boss arena")
    }

    @Test
    fun `replaying the same witness twice gives the same result`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)

        val first = WitnessReplay.replay(generated.level, generated.witness)
        val second = WitnessReplay.replay(generated.level, generated.witness)

        assertTrue(first.ticks == second.ticks && first.finalState == second.finalState)
    }

    @Test
    fun `a witness truncated before the end does not report success`() {
        val generated = LevelGenerator.generate(SEED, mapIndex = 1)
        val truncated = Witness(generated.witness.steps.dropLast(generated.witness.steps.size / 2))

        val result = WitnessReplay.replay(generated.level, truncated)

        assertFalse(result.reachedBoss, "a half witness claimed to reach the boss")
    }

    private companion object {
        val SEED = 0xC0FFEEuL
    }

    /**
     * The generator replays the level **before** it places static pickups, because the replay is
     * what proves where a pickup may stand. That is only honest if a pickup cannot change a replay.
     *
     * The concern is not hypothetical: this project has already had a finding for replaying a
     * different object than the one it returned. This is the assertion that keeps the single replay
     * sound rather than the second replay that would otherwise be needed.
     */
    @Test
    fun `adding pickups cannot change a replay`() {
        val generated = LevelGenerator.generate(SEED, 1)
        val level = generated.level
        assertTrue(level.pickups.isNotEmpty(), "the level placed no pickups, so nothing is proved")

        val bare = Level(
            mapIndex = level.mapIndex, theme = level.theme, tiles = level.tiles,
            floorMask = level.floorMask, arcMask = level.arcMask,
            spawnColumn = level.spawnColumn, spawnRow = level.spawnRow,
            miniboss = level.miniboss, boss = level.boss, jets = level.jets,
            enemies = level.enemies, pickups = emptyList(), gateColumn = level.gateColumn,
        )

        val withPickups = WitnessReplay.replay(level, generated.witness)
        val without = WitnessReplay.replay(bare, generated.witness)

        assertEquals(without, withPickups, "a pickup changed what the witness replay produced")
    }

    /**
     * Damaging hazards are placed after the replay and confirmed by a second one (`specs/hazards.md`),
     * which is only sound if a hazard cannot change where the tape goes: none blocks movement and
     * none displaces the player.
     */
    @Test
    fun `damaging hazards cannot change where the tape goes`() {
        val generated = LevelGenerator.generate(SEED, 10)
        val level = generated.level
        assertTrue(level.barrels.isNotEmpty() || Hazards.spikeCells(level).isNotEmpty(), "map 10 placed no hazards, so nothing is proved")

        val cleared = TileMap(level.tiles.width, level.tiles.height)
        for (x in 0 until level.tiles.width) for (y in 0 until level.tiles.height) {
            cleared[x, y] = level.tiles[x, y].takeIf { it != TileKind.Spikes } ?: TileKind.Empty
        }
        val bare = Level(
            mapIndex = level.mapIndex, theme = level.theme, tiles = cleared,
            floorMask = level.floorMask, arcMask = level.arcMask,
            spawnColumn = level.spawnColumn, spawnRow = level.spawnRow,
            miniboss = level.miniboss, boss = level.boss, jets = level.jets,
            enemies = level.enemies, pickups = level.pickups, gateColumn = level.gateColumn,
        )

        val withHazards = WitnessReplay.replay(level, generated.witness)
        val without = WitnessReplay.replay(bare, generated.witness)

        assertEquals(without.finalState, withHazards.finalState, "a hazard changed where the witness ended")
        assertEquals(without.footholds, withHazards.footholds, "a hazard changed where the witness stood")
        assertEquals(0, withHazards.damagingContacts, "the shipped route touches a damaging hazard")
    }

    /** PROD-047's reachability, discharged by the tape rather than by a mask. */
    @Test
    fun `every static pickup stands on ground the witness stood on`() {
        val generated = LevelGenerator.generate(SEED, 1)
        val footholds = WitnessReplay.replay(generated.level, generated.witness).footholds

        assertTrue(footholds.isNotEmpty(), "the replay recorded no footholds at all")
        generated.level.pickups.forEach { site ->
            assertTrue(
                Foothold(site.column, site.row) in footholds,
                "a pickup at ${site.column},${site.row} is not on the verified route",
            )
        }
    }
}
