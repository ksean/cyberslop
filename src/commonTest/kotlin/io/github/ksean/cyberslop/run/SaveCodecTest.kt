package io.github.ksean.cyberslop.run

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAP_FOR_SCORING = 1

class SaveCodecTest {
    @Test
    fun `a run round trips`() {
        val run = sample()

        val restored = SaveCodec.decodeRun(SaveCodec.encodeRun(run)).getOrThrow()

        assertEquals(run.seed, restored.run.seed)
        assertEquals(run.mapIndex, restored.run.mapIndex)
        assertEquals(run.loadout.weapon.id, restored.run.loadout.weapon.id)
        assertEquals(run.loadout.slots.held, restored.run.loadout.slots.held)
        assertEquals(run.health, restored.run.health)
        assertEquals(run.scrap, restored.run.scrap)
        assertNull(restored.legacyMetaScrap)
    }

    @Test
    fun `a version two run remains resumable and exposes its legacy scrap for migration`() {
        val restored = SaveCodec.decodeRun(
            "2|12648430|4|VultureRailCarbine|HollowpointFirmware:3,OverclockCoil:2|120.0|55|1200",
        ).getOrThrow()

        assertEquals(4, restored.run.mapIndex)
        assertEquals(WeaponId.VultureRailCarbine, restored.run.loadout.weapon.id)
        assertEquals(55, restored.run.scrap)
        assertEquals(1_200, restored.legacyMetaScrap)
    }

    @Test
    fun `a save from another version is refused rather than guessed at`() {
        val run = sample()
        listOf("99", "1").forEach { version ->
            val encoded = SaveCodec.encodeRun(run)
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
            "3|x|1|BrokenBottle||100.0|0",
            "3|5|99|BrokenBottle||100.0|0",
            "3|5|1|NoSuchWeapon||100.0|0",
            "3|5|1|BrokenBottle||100.0|-1",
            "2|5|1|BrokenBottle||100.0|0|-1",
        ).forEach { encoded ->
            assertTrue(SaveCodec.decodeRun(encoded).isFailure, "accepted '$encoded'")
        }
    }

    @Test
    fun `a dead run cannot be resumed from a save`() {
        val run = sample()
        val encoded = SaveCodec.encodeRun(run.copy(health = 0.0))

        assertTrue(SaveCodec.decodeRun(encoded).isFailure, "a dead run was resumable")
    }

    @Test
    fun `decoding never throws, whatever it is handed`() {
        listOf(null, "", "||||||||", "3|1|1|1|1|1|1", "2|1|1|1|1|1|1|1").forEach {
            SaveCodec.decodeRun(it)
        }
    }

    private fun sample(): RunState {
        var loadout = Loadout(Weapons.of(WeaponId.VultureRailCarbine), Loadout.starting().slots)
        repeat(3) { loadout = loadout.collect(PowerupId.HollowpointFirmware, MAP_FOR_SCORING).first }
        repeat(2) { loadout = loadout.collect(PowerupId.OverclockCoil, MAP_FOR_SCORING).first }
        return RunState(0xC0FFEEuL, 4, loadout, 120.0, 55)
    }
}
