package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.gen.DifficultyCurve
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PROD-046: one in five slain enemies drops something, at every map index.
 *
 * The rate had been a ramp, and two documents disagreed about which ramp: the old design document printed
 * 1.5% to 3% while the code ran 3% to 6%. A number nobody can look up is a number nobody can hold
 * the game to, so it is a requirement now rather than a pair of private constants.
 */
class DropRateTest {
    @Test
    fun `one kill in five drops something, on every map`() {
        for (mapIndex in 1..DifficultyCurve.MAPS) {
            assertEquals(
                0.20,
                DropTable.killDropChance(mapIndex),
                absoluteTolerance = 1e-12,
                message = "map $mapIndex does not drop at one in five",
            )
        }
    }

    @Test
    fun `three drops in ten are weapons`() {
        assertEquals(
            0.30,
            DropTable.weaponShare(),
            absoluteTolerance = 1e-12,
            message = "the weapon share is not the three-in-ten PROD-046 requires",
        )
    }

    @Test
    fun `the rate does not ramp`() {
        val rates = (1..DifficultyCurve.MAPS).map { DropTable.killDropChance(it) }.distinct()
        assertEquals(1, rates.size, "the drop rate still varies by map: $rates")
    }
}
