package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.BossAttack
import io.github.ksean.cyberslop.entity.BossFight
import io.github.ksean.cyberslop.entity.BossSpec
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap

/** Damage that keeps arriving after the hit that caused it. */
class OverTime(var perSecond: Double = 0.0, var secondsLeft: Double = 0.0) {
    fun apply(seconds: Double, rate: Double) {
        // Refreshes rather than stacks, so a fast weapon cannot multiply an effect by its fire rate.
        secondsLeft = maxOf(secondsLeft, seconds)
        perSecond = maxOf(perSecond, rate)
    }

    fun drain(delta: Double): Double {
        if (secondsLeft <= 0.0) return 0.0
        val slice = minOf(delta, secondsLeft)
        secondsLeft -= slice
        return perSecond * slice
    }
}

class LiveEnemy(
    val archetype: EnemyArchetype,
    var position: Vec2,
    var health: Double,
    val homeX: Double,
    val patrolPx: Double,
) {
    var facing: Int = 1

    /**
     * Distance walked, which is what drives the gait (`plan.md` §15.4).
     *
     * Presentational, and deliberately here rather than on a physics value: enemies have no shared
     * state hash to disturb, but keeping the two kinds of state in the same place as the player's
     * is what makes the rule easy to follow.
     */
    var stridePx: Double = 0.0
    var slowSecondsLeft: Double = 0.0
    var slowFraction: Double = 0.0
    var stunSecondsLeft: Double = 0.0
    var fireCooldown: Double = 0.0
    val burn = OverTime()
    val bleed = OverTime()

    val alive: Boolean get() = health > 0.0
    val stunned: Boolean get() = stunSecondsLeft > 0.0

    /** Slows take the strongest rather than compounding, and never pass the floor. */
    fun slow(fraction: Double, seconds: Double) {
        if (fraction <= 0.0) return
        slowFraction = maxOf(slowFraction, fraction)
        slowSecondsLeft = maxOf(slowSecondsLeft, seconds)
    }

    fun stun(seconds: Double) {
        stunSecondsLeft = maxOf(stunSecondsLeft, seconds)
    }

    fun speedScale(floor: Double): Double =
        if (slowSecondsLeft > 0.0) maxOf(floor, 1.0 - slowFraction) else 1.0
}

/**
 * A boss that actually fights.
 *
 * It cycles the attacks of whichever phase its health puts it in, each with a telegraph during which
 * nothing is dangerous. Before the player crosses the commit line it is inert and invulnerable —
 * sealing the arena is the player's own act, never something automatic fire can do for them.
 */
class LiveBoss(val spec: BossSpec, val arena: Arena, commitColumn: Int) {
    val fight = BossFight(spec, commitColumn)

    /** The boss's feet, standing on the arena floor. */
    var position: Vec2 = Vec2(
        TileMap.toWorld(arena.centreTile),
        TileMap.toWorld(arena.floorRow),
    )
        private set

    val height: Double = BODY_HEIGHT
    val halfWidth: Double = BODY_WIDTH / 2.0

    /**
     * The middle of the body, which is what a hit is measured against.
     *
     * Measuring to a single anchor 40 px above the floor made the boss unhittable: a melee weapon
     * with 27 px of reach could not cover the vertical gap from a player standing on the same floor,
     * so the fight could not be won at all. A hit now tests against the body's centre and counts the
     * body's own size, so a large target is easier to hit rather than impossible.
     */
    val centre: Vec2 get() = Vec2(position.x, position.y - height / 2.0)

    /** How far from [centre] the body extends, for hit tests. */
    val radius: Double get() = maxOf(halfWidth, height / 2.0)

    var currentAttack: BossAttack? = null
        private set
    var attackElapsed: Double = 0.0
        private set

    private var attackIndex = 0
    private var restSecondsLeft = OPENING_REST

    /**
     * What a renderer needs to show the fight: how much is left, and whether the boss is winding up.
     *
     * A boss that is drawn nowhere is a boss the player cannot know to fight. That is exactly how a
     * playtester ended up standing in the arena with every enemy dead, facing a wall.
     */
    val healthFraction: Double get() = (fight.health / spec.maxHealth).coerceIn(0.0, 1.0)

    val telegraphing: Boolean
        get() = currentAttack?.let { attackElapsed < it.telegraphSeconds } ?: false

    /** Advances the fight and returns the damage the player takes this tick. */
    fun tick(delta: Double, playerPosition: Vec2): Double {
        if (!fight.committed || fight.defeated) return 0.0

        approach(delta, playerPosition)

        val attack = currentAttack
        if (attack == null) {
            restSecondsLeft -= delta
            if (restSecondsLeft <= 0.0) {
                val attacks = fight.currentPhase().attacks
                currentAttack = attacks[attackIndex % attacks.size]
                attackIndex++
                attackElapsed = 0.0
            }
            return 0.0
        }

        val before = attackElapsed
        attackElapsed += delta
        if (attackElapsed > attack.totalSeconds) {
            currentAttack = null
            restSecondsLeft = REST_BETWEEN
            return 0.0
        }

        // Only the newly-elapsed slice can hurt, so a long frame cannot apply an attack twice.
        val wasDangerous = attack.damageAt(before) > 0.0
        val isDangerous = attack.damageAt(attackElapsed) > 0.0
        if (!isDangerous || wasDangerous) return 0.0

        return if (inRange(playerPosition, attack)) attack.damage else 0.0
    }

    /**
     * Closes on the player, within the arena.
     *
     * A boss that holds its ground is not a fight: the player walks past it, is stopped by the exit
     * gate well beyond its reach, and every swing after that misses. A playtester experienced this
     * as an unkillable boss, and holding right in a test reproduced it exactly.
     */
    private fun approach(delta: Double, playerPosition: Vec2) {
        val toPlayer = playerPosition.x - position.x
        if (kotlin.math.abs(toPlayer) < CLOSE_ENOUGH) return
        val step = (if (toPlayer > 0) 1.0 else -1.0) * SPEED * delta
        val left = TileMap.toWorld(arena.leftTile) + halfWidth
        val right = TileMap.toWorld(arena.rightTile) - halfWidth
        position = Vec2((position.x + step).coerceIn(left, right), position.y)
    }

    private fun inRange(playerPosition: Vec2, attack: BossAttack): Boolean {
        val reach = when (attack.name) {
            "Volley" -> TileMap.toWorld(arena.widthTiles)
            "Rush" -> TILE_SIZE * 8.0
            else -> TILE_SIZE * 5.0
        }
        return (playerPosition - position).lengthSquared <= reach * reach
    }

    private companion object {
        const val BODY_HEIGHT = 56.0
        const val BODY_WIDTH = 44.0
        const val OPENING_REST = 0.8
        const val REST_BETWEEN = 0.9
        const val SPEED = 55.0
        const val CLOSE_ENOUGH = 8.0
    }
}

/** A melee swing the renderer can draw: where, which way, how wide, and how long it lingers. */
data class SwingVisual(
    val origin: io.github.ksean.cyberslop.core.Vec2,
    val direction: io.github.ksean.cyberslop.core.Vec2,
    val arcDegrees: Double,
    val reachPx: Double,
    val secondsLeft: Double,
    val totalSeconds: Double,
) {
    /** One at the moment of the swing, falling to zero as it fades. */
    val strength: Double get() = (secondsLeft / totalSeconds).coerceIn(0.0, 1.0)
}

/**
 * A shot leaving the muzzle, for the renderer.
 *
 * The counterpart to [SwingVisual]. Without it a ranged weapon firing on its own cooldown produced
 * a projectile that simply appeared a few pixels away from the player, with nothing tying it to the
 * figure that fired it.
 *
 * It carries no origin. The flash is drawn at the posed lead hand, because it belongs to the weapon
 * the figure is holding — and for a cursor-anchored psychic weapon the shot's origin is the target,
 * which is the last place a muzzle flash should appear. An origin field existed and was read by
 * nothing.
 */
data class MuzzleFlash(
    val direction: Vec2,
    val secondsLeft: Double,
    val totalSeconds: Double,
) {
    /** One at the shot, falling to zero as it fades. */
    val strength: Double get() = (secondsLeft / totalSeconds).coerceIn(0.0, 1.0)
}

class LiveProjectile(
    var position: Vec2,
    var velocity: Vec2,
    val damage: Double,
    var pierceLeft: Int,
    var secondsLeft: Double,
    val passesTerrain: Boolean,
    val fromPlayer: Boolean,
    val homingTurn: Double = 0.0,
    val homingRadius: Double = 0.0,
    val radius: Double = 6.0,
) {
    val spent: Boolean get() = secondsLeft <= 0.0 || pierceLeft < 0
}
