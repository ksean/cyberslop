package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeathDropPlacementTest {
    @Test
    fun `a flat-ground death drop preserves x and rests two tiles above its support`() {
        val level = TestLevels.flat()
        val death = Vec2(TileMap.toWorld(20) + 7.0, TileMap.toWorld(TestLevels.FLOOR_ROW) + 7.0)

        val drop = DeathDropPlacement(level).place(death, paired = false)

        assertEquals(death.x, drop.x)
        assertEquals(
            TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - 2.0 * TILE_SIZE,
            drop.y,
        )
        val standingCentreY =
            TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - Physics.Default.standingHeight / 2.0
        assertTrue(drop.y < standingCentreY - TILE_SIZE, "the item did not clear the standing player's head")
        assertFalse(inReachOf(drop, restingUnder(drop, Stance.Stand)))
        assertFalse(inReachOf(drop, restingUnder(drop, Stance.Crouch)))
        assertTrue(jumpReaches(level.tiles, drop), "a normal held jump did not reach the item")
    }

    @Test
    fun `paired icons fall back far enough from adjacent raised ground`() {
        val level = TestLevels.flat()
        level.tiles[21, TestLevels.FLOOR_ROW] = TileKind.Solid
        val death = Vec2(TileMap.toWorld(20) + 7.0, TileMap.toWorld(TestLevels.FLOOR_ROW) + 7.0)

        val drop = DeathDropPlacement(level).place(death, paired = true)

        assertEquals(TileMap.toWorld(21) + TILE_SIZE / 2.0, drop.x)
        assertEquals(
            TileMap.toWorld(TestLevels.FLOOR_ROW) - DeathDropPlacement.DEATH_DROP_RISE,
            drop.y,
        )
    }

    @Test
    fun `a low ceiling rejects the death projection`() {
        val level = TestLevels.flat()
        level.tiles[20, TestLevels.FLOOR_ROW - 1] = TileKind.Solid
        val death = Vec2(TileMap.toWorld(20) + 7.0, TileMap.toWorld(TestLevels.FLOOR_ROW) + 7.0)

        val drop = DeathDropPlacement(level).place(death, paired = false)

        assertEquals(TileMap.toWorld(20) + TILE_SIZE / 2.0, drop.x)
        assertEquals(
            TileMap.toWorld(TestLevels.FLOOR_ROW - 1) - DeathDropPlacement.DEATH_DROP_RISE,
            drop.y,
        )
    }

    @Test
    fun `an inaccessible platform falls back to reachable ground below it`() {
        val level = TestLevels.flat()
        for (row in 0..12) {
            level.tiles[29, row] = TileKind.Solid
            level.tiles[31, row] = TileKind.Solid
        }
        level.tiles[30, 8] = TileKind.Solid
        level.tiles[30, 12] = TileKind.Solid
        val death = Vec2(TileMap.toWorld(30) + TILE_SIZE / 2.0, TileMap.toWorld(11) + TILE_SIZE / 2.0)

        val drop = DeathDropPlacement(level).place(death, paired = false)

        assertEquals(death.x, drop.x)
        assertEquals(
            TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - DeathDropPlacement.DEATH_DROP_RISE,
            drop.y,
        )
    }

    @Test
    fun `an airborne death over a lethal gap uses the lower-column equal-distance site`() {
        val level = TestLevels.flat(gapColumns = 20..22, acidColumns = 20..22)
        val death = Vec2(TileMap.toWorld(21) + TILE_SIZE / 2.0, TileMap.toWorld(8))
        val placement = DeathDropPlacement(level)

        val first = placement.place(death, paired = false)
        val repeated = placement.place(death, paired = false)

        assertEquals(TileMap.toWorld(19) + TILE_SIZE / 2.0, first.x)
        assertEquals(first, repeated)
    }

    private fun restingUnder(item: Vec2, stance: Stance): PlayerState {
        val height =
            if (stance == Stance.Stand) Physics.Default.standingHeight else Physics.Default.crouchingHeight
        return PlayerState(
            x = item.x - Physics.Default.width / 2.0,
            y = TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - height,
            onGround = true,
            stance = stance,
        )
    }

    private fun jumpReaches(tiles: TileMap, item: Vec2): Boolean {
        var player = restingUnder(item, Stance.Stand)
        repeat(240) { tick ->
            player = MovementModel.step(
                player,
                InputFrame(jump = true, jumpStart = tick == 0),
                tiles,
            )
            if (!player.onGround && inReachOf(item, player)) return true
        }
        return false
    }

    private fun inReachOf(item: Vec2, player: PlayerState): Boolean {
        val centre = Vec2(
            player.x + Physics.Default.width / 2.0,
            player.y + player.height(Physics.Default) / 2.0,
        )
        return (item - centre).lengthSquared <
            DeathDropPlacement.PICKUP_REACH * DeathDropPlacement.PICKUP_REACH
    }
}
