package io.github.ksean.cyberslop.verify

import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileMap

data class ReplayResult(
    val reachedMiniboss: Boolean,
    val reachedBoss: Boolean,
    val touchedLethal: Boolean,
    val finalState: PlayerState,
    val ticks: Int,
) {
    val succeeded: Boolean get() = reachedMiniboss && reachedBoss && !touchedLethal
}

/**
 * The completability guarantee, discharged.
 *
 * The witness runs through the game's own [MovementModel] against the real level, with the real
 * level clock driving the fire jets. Nothing here models the physics; it runs it. That is the whole
 * reason the guarantee is worth stating: a claim about geometry can be wrong, and a tape that
 * arrives cannot.
 */
object WitnessReplay {
    fun replay(
        level: Level,
        witness: Witness,
        physics: Physics = Physics.Default,
    ): ReplayResult {
        var state = level.spawnState(physics)
        var ticks = 0
        var touchedLethal = false
        var reachedMiniboss = false
        var reachedBoss = false

        fun observe() {
            val column = TileMap.toTile(state.x + physics.width / 2.0)
            if (level.miniboss.containsColumn(column)) reachedMiniboss = true
            if (column >= level.boss.leftTile) reachedBoss = true
            if (state.touchedLethal) touchedLethal = true
            if (burnedByJet(level, state, physics, ticks * TICK_SECONDS)) touchedLethal = true
        }

        observe()

        for (step in witness.steps) {
            for (frame in step.frames) {
                state = MovementModel.step(state, frame, level.tiles, physics)
                ticks++
                observe()
            }
        }

        return ReplayResult(reachedMiniboss, reachedBoss, touchedLethal, state, ticks)
    }

    private fun burnedByJet(
        level: Level,
        state: PlayerState,
        physics: Physics,
        timeSeconds: Double,
    ): Boolean {
        if (level.jets.isEmpty()) return false
        val left = TileMap.toTile(state.x)
        val right = TileMap.toTile(state.x + physics.width - 0.001)
        val top = TileMap.toTile(state.y)
        val bottom = TileMap.toTile(state.y + state.height(physics) - 0.001)

        return level.jets.any { jet ->
            jet.column in left..right &&
                (top..bottom).any { jet.coversRow(it) } &&
                jet.isOnAt(timeSeconds)
        }
    }
}
