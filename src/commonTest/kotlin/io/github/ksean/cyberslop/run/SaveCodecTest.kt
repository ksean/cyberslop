package io.github.ksean.cyberslop.run

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MAP_FOR_SCORING = 1

class SaveCodecTest {
    @Test
    fun `a run round trips`() {
        val (run, meta) = sample()

        val restored = SaveCodec.decodeRun(SaveCodec.encodeRun(run, meta)).getOrThrow()

        assertEquals(run.seed, restored.first.seed)
        assertEquals(run.mapIndex, restored.first.mapIndex)
        assertEquals(run.loadout.weapon.id, restored.first.loadout.weapon.id)
        assertEquals(run.loadout.slots.held, restored.first.loadout.slots.held)
        assertEquals(meta.scrap, restored.second.scrap)
    }

    @Test
    fun `a save from another version is refused rather than guessed at`() {
        val (run, meta) = sample()
        listOf("99", "1").forEach { version ->
            val encoded = SaveCodec.encodeRun(run, meta)
                .replaceFirst("${SaveCodec.VERSION}|", "$version|")
            assertTrue(
                SaveCodec.decodeRun(encoded).isFailure,
                "a version $version save was accepted by a version ${SaveCodec.VERSION} build",
            )
        }
    }

    @Test
    fun `a truncated or corrupt save is refused, not partially applied`() {
        listOf(
            "",
            "1|garbage",
            "2|x|1|BrokenBottle||100.0|0|0",
            "2|5|99|BrokenBottle||100.0|0|0",
            "2|5|1|NoSuchWeapon||100.0|0|0",
        ).forEach { encoded ->
            assertTrue(SaveCodec.decodeRun(encoded).isFailure, "accepted '$encoded'")
        }
    }

    @Test
    fun `a dead run cannot be resumed from a save`() {
        val (run, meta) = sample()
        val encoded = SaveCodec.encodeRun(run.copy(health = 0.0), meta)

        assertTrue(SaveCodec.decodeRun(encoded).isFailure, "a dead run was resumable")
    }

    @Test
    fun `decoding never throws, whatever it is handed`() {
        listOf(null, "", "||||||||", "2|1|1|1|1|1|1|1").forEach {
            SaveCodec.decodeRun(it)
        }
    }

    private fun sample(): Pair<RunState, MetaProgression> {
        var loadout = Loadout(Weapons.of(WeaponId.VultureRailCarbine), Loadout.starting().slots)
        repeat(3) { loadout = loadout.collect(PowerupId.HollowpointFirmware, MAP_FOR_SCORING).first }
        repeat(2) { loadout = loadout.collect(PowerupId.OverclockCoil, MAP_FOR_SCORING).first }
        return RunState(0xC0FFEEuL, 4, loadout, 120.0, 55) to MetaProgression(scrap = 1200)
    }
}
