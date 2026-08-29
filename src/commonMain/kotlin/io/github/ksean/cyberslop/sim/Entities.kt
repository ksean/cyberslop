package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.ResolvedWeapon

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.AttackVisual
import io.github.ksean.cyberslop.entity.BossAttack
import io.github.ksean.cyberslop.entity.BossFight
import io.github.ksean.cyberslop.entity.BossSpec
import io.github.ksean.cyberslop.entity.Dodge
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
     * Whether this enemy has noticed the player (`specs/enemies.md`, Awareness). Engaged, it acts by
     * role instead of patrolling, and is no longer confined to its patrol span.
     */
    var engaged: Boolean = false

    /** Vertical speed, for walkers under gravity. Flyers never fall. */
    var vy: Double = 0.0

    /**
     * Distance walked, which is what drives the gait (`specs/presentation.md`).
     *
     * Presentational, and deliberately here rather than on a physics value: enemies have no shared
     * state hash to disturb, but keeping the two kinds of state in the same place as the player's
     * is what makes the rule easy to follow.
     */
    var stridePx: Double = 0.0
    var slowSecondsLeft: Double = 0.0
    var slowFraction: Double = 0.0
    var stunSecondsLeft: Double = 0.0
    val burn = OverTime()
    val bleed = OverTime()

    /** Seconds until this enemy may start another attack. */
    var cooldownLeft: Double = 0.0

    /** Seconds of telegraph left on the attack being wound up; zero when none is. */
    var windUpLeft: Double = 0.0
    var windUpTotal: Double = 0.0

    /** Where the attack being wound up is aimed: a direction for a swing, a point for a shot. */
    var attackDirection: Vec2 = Vec2.Right
    var attackTarget: Vec2 = Vec2.Zero

    /** The most recent strike and shot, for the renderer; decayed by the simulation. */
    var lastSwing: SwingVisual? = null
    var lastShot: MuzzleFlash? = null

    /** Seconds of hurt flash left (PROD-076): presentation only, outside the digest. */
    var hurtSecondsLeft: Double = 0.0

    /** What the enemy spawned with, which is what its health bar is measured against (PROD-077). */
    val maxHealth: Double = health

    val alive: Boolean get() = health > 0.0
    val healthFraction: Double get() = (health / maxHealth).coerceIn(0.0, 1.0)
    val stunned: Boolean get() = stunSecondsLeft > 0.0
    val windingUp: Boolean get() = windUpLeft > 0.0

    /** Slows take the strongest rather than compounding, and never pass the floor. */
    fun slow(fraction: Double, seconds: Double) {
        if (fraction <= 0.0) return
        slowFraction = maxOf(slowFraction, fraction)
        slowSecondsLeft = maxOf(slowSecondsLeft, seconds)
    }

    /** A stun also cancels whatever was being wound up: a stunned enemy neither moves nor attacks. */
    fun stun(seconds: Double) {
        stunSecondsLeft = maxOf(stunSecondsLeft, seconds)
        windUpLeft = 0.0
    }

    fun speedScale(floor: Double): Double =
        if (slowSecondsLeft > 0.0) maxOf(floor, 1.0 - slowFraction) else 1.0
}

/** What a boss attack is aimed at and tested against: where the player is and what they are doing. */
data class BossTarget(val centre: Vec2, val onGround: Boolean, val crouched: Boolean)

/**
 * A boss that actually fights.
 *
 * Inert until it notices the player; then it walks toward them under the ledge rule, wherever they
 * are, and cycles the attacks of whichever phase its health puts it in — each with a telegraph
 * during which nothing is dangerous, and each with a hit condition its listed dodge defeats
 * (`specs/enemies.md`).
 */
class LiveBoss(
    val spec: BossSpec,
    val arena: Arena,
    private val tiles: TileMap,
    /** The attack-choice stream (PROD-072): the boss's own, so it never disturbs loot or crit rolls. */
    internal val rng: Rng = Rng(0uL),
) {
    val fight = BossFight(spec)

    /** The boss's feet, standing on the arena floor. */
    var position: Vec2 = Vec2(
        TileMap.toWorld(arena.centreTile),
        TileMap.toWorld(arena.floorRow),
    )
        private set

    /** Test hook: stand the boss somewhere in particular. */
    internal fun placeAt(feet: Vec2) { position = feet }

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

    /** Which way the boss faces, and where a Volley was aimed when its telegraph began. */
    var facing: Int = -1
        private set
    var aimedX: Double = position.x
        private set

    /** Distance walked, for the gait (presentational, like an enemy's), and whether it stepped this tick. */
    var stridePx: Double = 0.0
        private set

    /** Seconds of hurt flash left (PROD-076): presentation only, outside the digest. */
    var hurtSecondsLeft: Double = 0.0
    var moving: Boolean = false
        private set

    /** Round-robin positions, one per kind, so choosing by distance starves no attack. */
    internal var meleeIndex = 0
    internal var rangedIndex = 0
    internal var restSecondsLeft = OPENING_REST

    val healthFraction: Double get() = (fight.health / spec.maxHealth).coerceIn(0.0, 1.0)

    val telegraphing: Boolean
        get() = currentAttack?.let { attackElapsed < it.telegraphSeconds } ?: false

    /** Inside an attack's active window: the swing, lunge or volley itself. */
    val striking: Boolean
        get() = currentAttack?.let { attackElapsed >= it.telegraphSeconds } ?: false

    /** Advances the fight and returns the damage the player takes this tick. */
    fun tick(delta: Double, target: BossTarget): Double {
        moving = false
        if (!fight.engaged || fight.defeated) return 0.0

        val attack = currentAttack
        // An attack holds its aim: the boss turns only between attacks, so a player crossing it
        // mid-telegraph sees the swing go where the tell said it would.
        if (attack == null) facing = if (target.centre.x < position.x) -1 else 1
        if (attack == null) {
            approach(delta, target.centre)
            restSecondsLeft -= delta
            if (restSecondsLeft <= 0.0) {
                currentAttack = chooseAttack((target.centre - position).length)
                attackElapsed = 0.0
                aimedX = target.centre.x
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
        // A Rush is a lunge: through its active window the boss carries forward under the ledge
        // rule. The hit resolves on the window's first tick, before the lunge moves it.
        if (isDangerous && attack.visual == AttackVisual.Lunge) step(facing * LUNGE_SPEED * delta)
        if (!isDangerous || wasDangerous) return 0.0

        return if (hits(target, attack)) attack.damage else 0.0
    }

    /**
     * Ranged more often on a far player, melee more often on a near one (`specs/enemies.md`,
     * "Choosing the next attack"). A phase holding one kind draws nothing from the stream.
     */
    private fun chooseAttack(distance: Double): BossAttack {
        val attacks = fight.currentPhase().attacks
        val melee = attacks.filter { !it.visual.ranged }
        val ranged = attacks.filter { it.visual.ranged }
        val useRanged = when {
            melee.isEmpty() -> true
            ranged.isEmpty() -> false
            else -> rng.nextDouble() < rangedWeight(distance)
        }
        return if (useRanged) ranged[rangedIndex++ % ranged.size] else melee[meleeIndex++ % melee.size]
    }

    /**
     * The hit condition, which the attack's listed dodge is exactly what escapes: Slam and Rush
     * strike the ground and miss an airborne player; Sweep is level and passes over a crouched
     * one; Volley is aimed where the player stood when the telegraph began.
     */
    private fun hits(target: BossTarget, attack: BossAttack): Boolean {
        if ((target.centre - position).lengthSquared > attack.reachPx * attack.reachPx) return false
        return when (attack.dodge) {
            Dodge.Jump -> target.onGround
            Dodge.Crouch -> !target.crouched
            Dodge.MoveAside -> kotlin.math.abs(target.centre.x - aimedX) <= VOLLEY_WIDTH
        }
    }

    /**
     * Closes on the player, wherever they are, under the ledge rule: no step off an edge, onto a
     * lethal tile or into a wall.
     */
    private fun approach(delta: Double, playerCentre: Vec2) {
        val toPlayer = playerCentre.x - position.x
        if (kotlin.math.abs(toPlayer) < CLOSE_ENOUGH) return
        step((if (toPlayer > 0) 1.0 else -1.0) * SPEED * delta)
    }

    /** One horizontal step under the ledge rule: no step off an edge, onto a lethal tile or into a wall. */
    private fun step(step: Double) {
        val nextX = position.x + step
        val feetRow = TileMap.toTile(position.y)
        val leading = TileMap.toTile(if (step > 0) nextX + halfWidth else nextX - halfWidth)
        val trailing = TileMap.toTile(if (step > 0) nextX - halfWidth else nextX + halfWidth)
        val supported = tiles.blocksMovement(leading, feetRow) && tiles.blocksMovement(trailing, feetRow) &&
            !tiles.isLethal(leading, feetRow) && !tiles.isLethal(trailing, feetRow)
        val bodyRows = TileMap.toTile(position.y - height + 1.0)..TileMap.toTile(position.y - 1.0)
        val blocked = bodyRows.any { tiles.blocksMovement(leading, it) }
        if (!supported || blocked) return
        position = Vec2(nextX, position.y)
        stridePx += kotlin.math.abs(step)
        moving = true
    }

    companion object {
        private const val BODY_HEIGHT = 56.0
        private const val BODY_WIDTH = 44.0
        private const val OPENING_REST = 0.8
        private const val REST_BETWEEN = 0.9
        const val SPEED = 55.0
        /** How fast a Rush carries the boss: 0.4 s of it covers about its 128 px reach. */
        const val LUNGE_SPEED = 300.0
        private const val CLOSE_ENOUGH = 8.0
        /** Half the width of the band a Volley covers around where it was aimed. */
        const val VOLLEY_WIDTH = 24.0

        /** Inside the Slam and Sweep reach a ranged opener is the exception. */
        const val MELEE_REACH = 80.0
        /** At the Volley's reach and beyond a melee opener is. */
        const val RANGED_PREFERRED_PX = 128.0
        const val RANGED_WEIGHT_NEAR = 0.2
        const val RANGED_WEIGHT_FAR = 0.8

        /** Probability of a ranged attack at [distance]: linear between the two reaches. */
        fun rangedWeight(distance: Double): Double {
            val t = ((distance - MELEE_REACH) / (RANGED_PREFERRED_PX - MELEE_REACH)).coerceIn(0.0, 1.0)
            return RANGED_WEIGHT_NEAR + (RANGED_WEIGHT_FAR - RANGED_WEIGHT_NEAR) * t
        }
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

/**
 * Where an instantly resolving attack went (PROD-071): the geometry the hit test used, kept for
 * the flash window so the renderer can draw it. Presentation only — outside the digest like
 * [SwingVisual] and [MuzzleFlash].
 */
sealed interface HitShape {
    /** A strike from above: the beam's [foot] is the strike centre, [radius] the scaled blast radius. */
    data class Beam(val foot: Vec2, val radius: Double) : HitShape

    /** The weapon, then every target struck, in strike order. */
    data class Chain(val points: List<Vec2>) : HitShape

    /** A blast, pull or orbit at its resolved [radius]. */
    data class Ring(val centre: Vec2, val radius: Double) : HitShape

    /**
     * A projectile spent this tick — by a hit or by terrain — where it stopped, how it was moving
     * and whether a psychic build fired it, so it is drawn as the shot it was (PROD-080).
     */
    data class Impact(val at: Vec2, val velocity: Vec2, val fromPlayer: Boolean, val psychic: Boolean = false) : HitShape
}

data class HitIndicator(
    val shape: HitShape,
    val secondsLeft: Double,
    val totalSeconds: Double,
) {
    /** One at the hit, falling to zero as it fades. */
    val strength: Double get() = (secondsLeft / totalSeconds).coerceIn(0.0, 1.0)
}

/**
 * The rounds of a machine-gun activation still to leave (PROD-075): how many, when the next is
 * due, the aim recorded at the trigger, and the build that pulled it. Simulation state, digested.
 */
data class PendingBurst(
    val roundsLeft: Int,
    val secondsToNext: Double,
    val direction: Vec2,
    val weapon: ResolvedWeapon,
)

class LiveProjectile(
    var position: Vec2,
    var velocity: Vec2,
    /** Falls by [GameSimulation.BOUNCE_DAMAGE] at each bounce. */
    var damage: Double,
    var pierceLeft: Int,
    var secondsLeft: Double,
    val passesTerrain: Boolean,
    val fromPlayer: Boolean,
    val homingTurn: Double = 0.0,
    val homingRadius: Double = 0.0,
    val radius: Double = 6.0,
    /** The build that fired a player's shot: its hit effects land as fired, whatever is held when it lands (PROD-070). */
    val weapon: ResolvedWeapon? = null,
    /** Terrain contacts this projectile can still reflect off (PROD-074). */
    var bouncesLeft: Int = 0,
) {
    val spent: Boolean get() = secondsLeft <= 0.0 || pierceLeft < 0
}
