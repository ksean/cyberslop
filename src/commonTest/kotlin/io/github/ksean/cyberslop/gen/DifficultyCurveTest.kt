package io.github.ksean.cyberslop.gen

import kotlin.test.Test
import kotlin.test.assertEquals

/** P-92: population stays on its existing curve while damaging-hazard density becomes linear. */
class DifficultyCurveTest {
    @Test
    fun `enemy and damaging hazard density retain their specified linear endpoints`() {
        val curves = (1..DifficultyCurve.MAPS).map(DifficultyCurve::at)

        assertEquals(4.0, curves.first().enemiesPerHundredTiles, TOLERANCE)
        assertEquals(9.0, curves.last().enemiesPerHundredTiles, TOLERANCE)
        assertConstantIncrement(curves.map { it.enemiesPerHundredTiles })

        val hazardDensity = curves.map { it.damagingHazardsPerHundredTiles }
        assertEquals(7.0 / 3.0, hazardDensity.first(), TOLERANCE)
        assertEquals(7.0, hazardDensity.last(), TOLERANCE)
        assertEquals(3.0 * hazardDensity.first(), hazardDensity.last(), TOLERANCE)
        assertConstantIncrement(hazardDensity)
    }

    private fun assertConstantIncrement(values: List<Double>) {
        val increment = values[1] - values[0]
        values.zipWithNext().forEach { (earlier, later) ->
            assertEquals(increment, later - earlier, TOLERANCE)
        }
    }

    private companion object {
        const val TOLERANCE = 1e-12
    }
}
