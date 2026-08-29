package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P-62: a boss advertises its whole assigned loadout with silhouette geometry. */
class BossLookTest {
    @Test
    fun `every attack module has one unique silhouette marker`() {
        val markers = BossModule.entries.map(BossLooks::markerOf)

        assertEquals(BossModule.entries.size, markers.distinct().size)
        assertEquals(BossMarker.WeightedForearm, BossLooks.markerOf(BossModule.Slam))
        assertEquals(BossMarker.LongBlade, BossLooks.markerOf(BossModule.Sweep))
        assertEquals(BossMarker.PairedBlades, BossLooks.markerOf(BossModule.Flurry))
        assertEquals(BossMarker.RamPlate, BossLooks.markerOf(BossModule.Rush))
        assertEquals(BossMarker.NarrowBarrel, BossLooks.markerOf(BossModule.Bolt))
        assertEquals(BossMarker.BurstMagazine, BossLooks.markerOf(BossModule.Burst))
        assertEquals(BossMarker.ScatterPorts, BossLooks.markerOf(BossModule.Scatter))
        assertEquals(BossMarker.LaserLens, BossLooks.markerOf(BossModule.Laser))
    }

    @Test
    fun `a profile draws all and only its enabled modules in their declared mounts`() {
        val profile = BossProfile(BossModule.Flurry, BossModule.Scatter, BossModule.Laser)

        val look = BossLooks.of(profile, mapIndex = 7, isMain = true)

        assertEquals(profile.modules, look.hardware.map { it.module })
        assertEquals(BossMount.LeadArm, look.hardware[0].mount)
        assertEquals(BossMount.RearShoulder, look.hardware[1].mount)
        assertEquals(BossMount.HighBack, look.hardware[2].mount)
        assertTrue(look.hardware[2].folded, "the locked signature is not visibly folded")
        assertTrue(look.hardware.take(2).none { it.folded }, "primary hardware was folded")
    }

    @Test
    fun `the same modules in different roles produce different colour-stripped geometry`() {
        val rangedPrimary = BossLooks.of(
            BossProfile(BossModule.Slam, BossModule.Bolt, BossModule.Scatter),
            mapIndex = 7,
            isMain = true,
        )
        val rangedSignature = BossLooks.of(
            BossProfile(BossModule.Slam, BossModule.Scatter, BossModule.Bolt),
            mapIndex = 7,
            isMain = true,
        )

        assertEquals(
            rangedPrimary.hardware.map { it.module }.toSet(),
            rangedSignature.hardware.map { it.module }.toSet()
        )
        assertNotEquals(rangedPrimary.hardware, rangedSignature.hardware)
    }

    @Test
    fun `the old emplacement is now a mobile crawler silhouette`() {
        val turret = EnemyLooks.of(io.github.ksean.cyberslop.entity.EnemyArchetype.Turret, 1)

        assertEquals(EnemyForm.Crawler, turret.form)
        assertTrue(turret.strideRate > 0.0)
    }
}
