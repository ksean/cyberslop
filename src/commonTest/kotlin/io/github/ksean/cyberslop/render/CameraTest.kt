package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.gen.LevelGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraTest {
    @Test
    fun `the view never shows anything left of or above the map`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        var camera = start()

        camera = Camera.following(camera, playerX = 0.0, playerY = 0.0, facing = -1, level = level)

        assertEquals(0.0, camera.x)
        assertEquals(0.0, camera.y)
    }

    @Test
    fun `the view never runs past the right edge of the map`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        var camera = start()

        repeat(200) {
            camera = Camera.following(
                camera, playerX = level.tiles.widthPx * 2, playerY = 0.0, facing = 1, level = level,
            )
        }

        assertEquals(level.tiles.widthPx - camera.viewWidth, camera.x)
    }

    @Test
    fun `a player inside the dead zone does not move the view`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        val camera = Camera(x = 500.0, y = 300.0, viewWidth = VIEW_W, viewHeight = VIEW_H)

        val next = Camera.following(
            camera,
            playerX = camera.x + VIEW_W / 2.0,
            playerY = camera.y + VIEW_H / 2.0,
            facing = 0,
            level = level,
        )

        assertEquals(camera.x, next.x)
        assertEquals(camera.y, next.y)
    }

    @Test
    fun `the view follows a player who leaves the dead zone`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        val camera = Camera(x = 500.0, y = 300.0, viewWidth = VIEW_W, viewHeight = VIEW_H)

        val next = Camera.following(
            camera, playerX = camera.x + VIEW_W, playerY = camera.y + VIEW_H / 2.0,
            facing = 1, level = level,
        )

        assertTrue(next.x > camera.x, "view did not follow the player right")
    }

    @Test
    fun `screen and world coordinates round trip`() {
        val camera = Camera(x = 1234.5, y = 67.25, viewWidth = VIEW_W, viewHeight = VIEW_H)

        val (worldX, worldY) = camera.screenToWorld(400.0, 200.0)
        val (screenX, screenY) = camera.worldToScreen(worldX, worldY)

        assertEquals(400.0, screenX, absoluteTolerance = 1e-9)
        assertEquals(200.0, screenY, absoluteTolerance = 1e-9)
    }

    @Test
    fun `aiming accounts for the camera, so the same cursor means different world points`() {
        val near = Camera(x = 0.0, y = 0.0, viewWidth = VIEW_W, viewHeight = VIEW_H)
        val far = near.copy(x = 5000.0)

        val (nearWorld, _) = near.screenToWorld(480.0, 270.0)
        val (farWorld, _) = far.screenToWorld(480.0, 270.0)

        assertEquals(480.0, nearWorld)
        assertEquals(5480.0, farWorld)
    }

    @Test
    fun `an arena is framed within the map bounds`() {
        val level = LevelGenerator.generate(SEED, mapIndex = 1).level
        val boss = level.boss

        val camera = Camera.framing(
            start(),
            leftPx = boss.leftTile * 16.0,
            rightPx = boss.rightTile * 16.0,
            floorPx = boss.floorRow * 16.0,
            level = level,
        )

        assertTrue(camera.x >= 0.0)
        assertTrue(camera.x <= level.tiles.widthPx - camera.viewWidth)
    }

    private fun start() = Camera(x = 0.0, y = 0.0, viewWidth = VIEW_W, viewHeight = VIEW_H)

    private companion object {
        const val VIEW_W = 960.0
        const val VIEW_H = 540.0
        val SEED = 0xC0FFEEuL
    }
}
