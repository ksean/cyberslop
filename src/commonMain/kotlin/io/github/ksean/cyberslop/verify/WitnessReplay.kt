package io.github.ksean.cyberslop.verify

import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileMap

/** A cell the replayed player actually stood on, in tile coordinates. */
data class Foothold(val column: Int, val row: Int)

data class ReplayResult(
    val reachedMiniboss: Boolean,
    val reachedBoss: Boolean,
    val touchedLethal: Boolean,
    val finalState: PlayerState,
    val ticks: Int,
    /**
     * Every cell the player was grounded on during the replay.
     *
     * This is the only thing in the level that is **proof** of reachability rather than a proxy for
     * it: the witness put the player's feet there, through the game's own movement model. The arc
     * mask is not proof — [SpineWalker.rollback][io.github.ksean.cyberslop.gen.SpineWalker.rollback]
     * deliberately does not rewind it, so it retains cells from abandoned move proposals that no
     * witness ever traverses.
     */
    val footholds: Set<Foothold> = emptySet(),
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
    /** Keeps a foot resting exactly on a tile boundary in the tile it is standing in. */
    private const val SKIN = 0.001

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
        val footholds = mutableSetOf<Foothold>()

        fun observe() {
            val column = TileMap.toTile(state.x + physics.width / 2.0)
            if (level.miniboss.containsColumn(column)) reachedMiniboss = true
            if (column >= level.boss.leftTile) reachedBoss = true
            if (state.touchedLethal) touchedLethal = true
            if (burnedByJet(level, state, physics, ticks * TICK_SECONDS)) touchedLethal = true
            if (state.onGround) {
                // The row the player's feet occupy, which is the cell a pickup would stand in.
                footholds.add(
                    Foothold(column, TileMap.toTile(state.y + state.height(physics) - SKIN)),
                )
            }
        }

        observe()

        for (step in witness.steps) {
            for (frame in step.frames) {
                state = MovementModel.step(state, frame, level.tiles, physics)
                ticks++
                observe()
            }
        }

        return ReplayResult(reachedMiniboss, reachedBoss, touchedLethal, state, ticks, footholds)
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
