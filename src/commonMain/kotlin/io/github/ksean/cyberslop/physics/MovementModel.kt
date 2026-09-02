package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The single source of truth for motion (ENG-052). The game loop and map verification both call
 * this; verification never approximates it, which is what lets a generated map's witness be proved
 * by replaying it rather than by trusting a model of the physics.
 */
object MovementModel {
    private const val SKIN = 0.001

    /** Depth of the ground probe: small enough not to register a floor the player is not on. */
    private const val PROBE = 0.05

    fun step(
        state: PlayerState,
        input: InputFrame,
        world: TileMap,
        physics: Physics = Physics.Default,
    ): PlayerState {
        val stance = resolveStance(state, input, world, physics)
        val height = if (stance == Stance.Crouch) physics.crouchingHeight else physics.standingHeight
        val topSpeed = topSpeed(stance, physics)

        // A stance change alters the box's height. Anchor it at the feet, or crouching would lift
        // the player 12 px off the floor, drop `onGround`, and immediately un-crouch them.
        val anchoredY = state.y + (state.height(physics) - height)

        var vx = accelerate(state, input, topSpeed, physics)
        var vy = state.vy

        // No grounded check here on purpose: whether a jump is legal is IntentFilter's decision,
        // so that assists cannot change what a recorded input frame means on replay.
        if (input.jumpStart && stance == Stance.Stand) {
            vy = -physics.jumpImpulse
        } else if (!input.jump && vy < -physics.jumpReleaseClamp) {
            vy = -physics.jumpReleaseClamp
        }
        vy = (vy + physics.gravity * TICK_SECONDS).coerceAtMost(physics.terminalVelocity)

        // Sub-step so that a tick at terminal velocity - 16.67 px, wider than a tile - cannot pass
        // through a one-tile floor without ever testing it.
        val travel = max(abs(vx), abs(vy)) * TICK_SECONDS
        val steps = ceil(travel / (TILE_SIZE / 2.0)).toInt().coerceAtLeast(1)
        val slice = TICK_SECONDS / steps

        var x = state.x
        var y = anchoredY
        // Re-derived every tick from an actual downward block. Carrying the previous value forward
        // meant a player who walked off a ledge stayed "grounded" for the whole fall.
        var onGround = false
        var lethalContact: LethalContact? = null

        repeat(steps) {
            val movedX = moveHorizontally(x, y, height, vx * slice, world, physics)
            x = movedX.position
            if (movedX.blocked) vx = 0.0

            val movedY = moveVertically(x, y, height, vy * slice, world, physics)
            y = movedY.position
            if (movedY.blocked) {
                if (vy > 0.0) onGround = true
                vy = 0.0
            }

            // Sampled per sub-step, so a tick that crosses a hazard layer at terminal velocity
            // cannot step over it unnoticed.
            if (lethalContact == null) {
                lethalContact = firstLethalContact(x, y, physics.width, height, world)
            }
        }

        // A player resting on a floor may not move far enough in a tick to be blocked again;
        // a zero-length probe below the feet answers "is there still ground here".
        if (!onGround && vy >= 0.0) {
            onGround = overlapsSolid(x, y + PROBE, physics.width, height, world)
        }

        return PlayerState(
            x = x,
            y = y,
            vx = vx,
            vy = vy,
            onGround = onGround,
            stance = stance,
            touchedLethal = lethalContact != null,
            lethalContact = lethalContact,
        )
    }

    private fun topSpeed(stance: Stance, physics: Physics): Double =
        if (stance == Stance.Crouch) physics.maxRunSpeed * physics.crouchSpeedFactor
        else physics.maxRunSpeed

    private fun accelerate(
        state: PlayerState,
        input: InputFrame,
        topSpeed: Double,
        physics: Physics,
    ): Double {
        val direction = input.direction
        val rate = if (state.onGround) physics.groundAccel else physics.airAccel
        val next = when {
            direction != 0 -> approach(state.vx, direction * topSpeed, rate * TICK_SECONDS)
            state.onGround -> approach(state.vx, 0.0, physics.groundFriction * TICK_SECONDS)
            else -> state.vx
        }
        return next.coerceIn(-topSpeed, topSpeed)
    }

    private fun approach(current: Double, target: Double, maxDelta: Double): Double {
        val delta = target - current
        return if (abs(delta) <= maxDelta) target else current + maxDelta * (if (delta > 0) 1.0 else -1.0)
    }

    /**
     * Crouching is entered on request while grounded and cannot be left while the standing box would
     * intersect the ceiling — which is what makes a duct a duct rather than a soft-lock.
     */
    private fun resolveStance(
        state: PlayerState,
        input: InputFrame,
        world: TileMap,
        physics: Physics,
    ): Stance = when {
        input.crouch && state.onGround -> Stance.Crouch
        state.stance == Stance.Crouch && !canStand(state, world, physics) -> Stance.Crouch
        else -> Stance.Stand
    }

    fun canStand(state: PlayerState, world: TileMap, physics: Physics = Physics.Default): Boolean {
        val bottom = state.y + state.height(physics)
        val top = bottom - physics.standingHeight
        return !overlapsSolid(state.x, top, physics.width, physics.standingHeight, world)
    }

    private fun firstLethalContact(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        world: TileMap,
    ): LethalContact? {
        val firstColumn = TileMap.toTile(x)
        val lastColumn = TileMap.toTile(x + width - SKIN)
        val firstRow = TileMap.toTile(y)
        val lastRow = TileMap.toTile(y + height - SKIN)
        for (column in firstColumn..lastColumn) {
            for (row in firstRow..lastRow) {
                when (world[column, row]) {
                    TileKind.Acid -> return LethalContact.Acid
                    TileKind.Void -> return LethalContact.Void
                    else -> Unit
                }
            }
        }
        return null
    }

    private fun overlapsSolid(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        world: TileMap,
    ): Boolean {
        return anyTile(x, y, width, height) { column, row -> world.blocksMovement(column, row) }
    }

    private inline fun anyTile(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        predicate: (Int, Int) -> Boolean,
    ): Boolean {
        val firstColumn = TileMap.toTile(x)
        val lastColumn = TileMap.toTile(x + width - SKIN)
        val firstRow = TileMap.toTile(y)
        val lastRow = TileMap.toTile(y + height - SKIN)
        for (column in firstColumn..lastColumn) {
            for (row in firstRow..lastRow) {
                if (predicate(column, row)) return true
            }
        }
        return false
    }

    private class Move(val position: Double, val blocked: Boolean)

    private fun moveHorizontally(
        x: Double,
        y: Double,
        height: Double,
        delta: Double,
        world: TileMap,
        physics: Physics,
    ): Move {
        if (delta == 0.0) return Move(x, blocked = false)
        val target = x + delta
        val firstRow = TileMap.toTile(y)
        val lastRow = TileMap.toTile(y + height - SKIN)

        if (delta > 0.0) {
            val column = TileMap.toTile(target + physics.width - SKIN)
            for (row in firstRow..lastRow) {
                if (world.blocksMovement(column, row)) {
                    return Move(TileMap.toWorld(column) - physics.width, blocked = true)
                }
            }
        } else {
            val column = TileMap.toTile(target)
            for (row in firstRow..lastRow) {
                if (world.blocksMovement(column, row)) {
                    return Move(TileMap.toWorld(column + 1), blocked = true)
                }
            }
        }
        return Move(target, blocked = false)
    }

    private fun moveVertically(
        x: Double,
        y: Double,
        height: Double,
        delta: Double,
        world: TileMap,
        physics: Physics,
    ): Move {
        if (delta == 0.0) return Move(y, blocked = false)
        val target = y + delta
        val firstColumn = TileMap.toTile(x)
        val lastColumn = TileMap.toTile(x + physics.width - SKIN)

        if (delta > 0.0) {
            val row = TileMap.toTile(target + height - SKIN)
            for (column in firstColumn..lastColumn) {
                if (world.blocksMovement(column, row)) {
                    return Move(TileMap.toWorld(row) - height, blocked = true)
                }
            }
        } else {
            val row = TileMap.toTile(target)
            for (column in firstColumn..lastColumn) {
                if (world.blocksMovement(column, row)) {
                    return Move(TileMap.toWorld(row + 1), blocked = true)
                }
            }
        }
        return Move(target, blocked = false)
    }
}
