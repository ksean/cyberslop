package io.github.ksean.cyberslop.combat

import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeleeSectorTest {
    private val sector = MeleeSector(
        origin = Vec2.Zero,
        direction = Vec2.Right,
        reachPx = 10.0,
        arcDegrees = 90.0,
        progress = 1.0,
    )

    @Test
    fun `closed sector contains bodies inside and tangent to every boundary`() {
        assertTrue(sector.intersects(CombatBody(Vec2(5.0, 0.0), 1.0)))
        assertTrue(sector.intersects(CombatBody(Vec2(11.0, 0.0), 1.0)), "radial tangency")
        assertTrue(sector.intersects(edgeBody(-45.0, outwardDegrees = -90.0, offset = 1.0)), "trailing tangency")
        assertTrue(sector.intersects(edgeBody(45.0, outwardDegrees = 90.0, offset = 1.0)), "leading tangency")
    }

    @Test
    fun `a body epsilon outside each closed boundary does not intersect`() {
        val epsilon = 1e-5
        assertFalse(sector.intersects(CombatBody(Vec2(11.0 + epsilon, 0.0), 1.0)), "past reach")
        assertFalse(
            sector.intersects(edgeBody(-45.0, outwardDegrees = -90.0, offset = 1.0 + epsilon)),
            "past trailing edge",
        )
        assertFalse(
            sector.intersects(edgeBody(45.0, outwardDegrees = 90.0, offset = 1.0 + epsilon)),
            "past leading edge",
        )
    }

    @Test
    fun `a body intersects when its centre is outside but its radius reaches the sector`() {
        assertTrue(sector.intersects(CombatBody(Vec2(10.5, 0.0), 0.5)))
        assertFalse(sector.contains(Vec2(10.5, 0.0)))
    }

    @Test
    fun `progress exposes one cumulative angular interval from trailing to leading edge`() {
        val half = sector.copy(progress = 0.5)
        assertTrue(half.contains(TrigTable.rotate(Vec2.Right, -45.0) * 5.0))
        assertTrue(half.contains(Vec2(5.0, 0.0)))
        assertFalse(half.contains(TrigTable.rotate(Vec2.Right, 0.1) * 5.0))
    }

    private fun edgeBody(edgeDegrees: Double, outwardDegrees: Double, offset: Double): CombatBody {
        val edge = TrigTable.rotate(Vec2.Right, edgeDegrees)
        val outward = TrigTable.rotate(edge, outwardDegrees)
        return CombatBody(edge * 5.0 + outward * offset, radius = 1.0)
    }
}
