package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.MeleeSector
import io.github.ksean.cyberslop.combat.ResolvedWeapon

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.BossAttack
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossFight
import io.github.ksean.cyberslop.entity.BossSpec
import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap

/** A presentation-only Scrap award, fixed at the world point where it appeared. */
data class ScrapGain(
    val amount: Int,
    val origin: Vec2,
    val previousSecondsLeft: Double,
    val secondsLeft: Double,
    internal val bornTick: Int,
)

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

    /** Actual combat-centre displacement from the most recently completed tick, per second. */
    var aimingVelocity: Vec2 = Vec2.Zero

    /**
     * Whether this enemy has noticed the player (`specs/enemies.md`, Awareness). Engaged, it acts by
     * role instead of patrolling, and is no longer confined to its patrol span.
     */
    var engaged: Boolean = false

    /** Vertical speed, for walkers under gravity. Flyers never fall. */
    var vy: Double = 0.0

    /** A previewed pursuit leap; direction stays fixed until the next safe landing. */
    var leap: EnemyLeap? = null
    var landingCooldownLeft: Double = 0.0

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
    val centre: Vec2 get() = position + Vec2(BODY_HALF, BODY_HALF)

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

    companion object {
        const val BODY_SIZE = 14.0
        const val BODY_HALF = BODY_SIZE / 2.0
        /** The body stands in one tile cell, with its feet at that cell's bottom edge. */
        const val FEET_OFFSET = TILE_SIZE.toDouble()
    }
}

/** What a boss attack is aimed at and tested against: where the player is and what they are doing. */
data class BossTarget(val centre: Vec2, val onGround: Boolean, val crouched: Boolean)

/** One separately timed event inside a telegraphed boss attack. */
data class BossAttackEvent(
    val attack: BossAttack,
    val eventIndex: Int,
    val origin: Vec2,
    val direction: Vec2,
    val aimedAt: Vec2,
)

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
    /** Present in live games so leap previews include barrels and jets; tile-only unit fixtures omit it. */
    private val level: Level? = null,
    /** Independent from attack choice: a melee charge must not shift which module is selected next. */
    internal val chargeRng: Rng = Rng(0uL),
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

    /** Which way the boss faces, and where its ranged attack was aimed when the telegraph began. */
    var facing: Int = -1
        private set
    var aimedX: Double = position.x
        private set
    var aimedAt: Vec2 = centre
        private set
    var aimDirection: Vec2 = Vec2(-1.0, 0.0)
        private set

    /** Locks the point and direction used by every event in one attack. */
    internal fun lockAim(point: Vec2) {
        aimedX = point.x
        aimedAt = point
        aimDirection = (point - centre).normalisedOr(Vec2(facing.toDouble(), 0.0))
    }

    private val emittedEvents = mutableListOf<BossAttackEvent>()
    val events: List<BossAttackEvent> get() = emittedEvents

    /** Distance walked, for the gait (presentational, like an enemy's), and whether it stepped this tick. */
    var stridePx: Double = 0.0
        private set

    /** Seconds of hurt flash left (PROD-076): presentation only, outside the digest. */
    var hurtSecondsLeft: Double = 0.0
    var moving: Boolean = false
        private set

    /** Actual combat-centre displacement from the most recently completed tick, per second. */
    var aimingVelocity: Vec2 = Vec2.Zero
        internal set

    var vy: Double = 0.0
        private set
    var leap: EnemyLeap? = null
        private set
    var landingCooldownLeft: Double = 0.0
        private set

    /** Simulation time used by fire-jet previews; advances even before engagement. */
    var elapsedSeconds: Double = 0.0
        private set

    /** Round-robin positions, one per kind, so choosing by distance starves no attack. */
    internal var meleeIndex = 0
    internal var rangedIndex = 0
    internal var restSecondsLeft = OPENING_REST

    /** Rule-bearing state for the current melee activation (PROD-104). */
    internal var meleeChargeSelected = false
    internal var meleeChargeStopped = false
    internal val consumedChargeEvents = mutableSetOf<Int>()

    val healthFraction: Double get() = (fight.health / spec.maxHealth).coerceIn(0.0, 1.0)

    val telegraphing: Boolean
        get() = currentAttack?.let { attackElapsed < it.telegraphSeconds } ?: false

    /** Inside an attack's active window: the swing, lunge or volley itself. */
    val striking: Boolean
        get() = currentAttack?.let { attackElapsed >= it.telegraphSeconds } ?: false

    /** Advances the fight and returns the damage the player takes this tick. */
    fun tick(delta: Double, target: BossTarget): Double {
        emittedEvents.clear()
        moving = false
        elapsedSeconds += delta
        if (!fight.engaged || fight.defeated) return 0.0

        landingCooldownLeft = (landingCooldownLeft - delta).coerceAtLeast(0.0)
        if (leap != null) {
            advanceLeap(delta)
            return 0.0
        }
        if (landingCooldownLeft > 0.0) return 0.0

        val attack = currentAttack
        // An attack holds its aim: the boss turns only between attacks, so a player crossing it
        // mid-telegraph sees the swing go where the tell said it would.
        if (attack == null) facing = if (target.centre.x < position.x) -1 else 1
        if (attack == null) {
            approach(delta, target.centre)
            if (leap != null) return 0.0
            restSecondsLeft -= delta
            if (restSecondsLeft <= 0.0) {
                val selected = chooseAttack((target.centre - position).length)
                currentAttack = selected
                attackElapsed = 0.0
                lockAim(target.centre)
                meleeChargeSelected = !selected.visual.ranged && rollsCharge(spec.mapIndex, chargeRng)
                meleeChargeStopped = false
                consumedChargeEvents.clear()
            }
            return 0.0
        }

        val before = attackElapsed
        attackElapsed += delta
        val chargeStart = position
        val activeSeconds = activeSecondsBetween(attack, before, attackElapsed)
        if (meleeChargeSelected && activeSeconds > 0.0) advanceCharge(activeSeconds)
        val chargeEnd = position

        var damage = 0.0
        attack.eventsBetween(before, attackElapsed).forEach { eventIndex ->
            emittedEvents += BossAttackEvent(attack, eventIndex, centre, aimDirection, aimedAt)
            if (!attack.visual.ranged && !meleeChargeSelected && hits(target, attack)) damage += attack.damage
        }
        if (meleeChargeSelected) {
            damage += chargedDamage(attack, before, attackElapsed, chargeStart, chargeEnd, target)
        }
        if (attackElapsed > attack.totalSeconds) {
            currentAttack = null
            restSecondsLeft = if (healthFraction <= CLOSING_HEALTH) CLOSING_REST else REST_BETWEEN
            meleeChargeSelected = false
            meleeChargeStopped = false
            consumedChargeEvents.clear()
        }
        return damage
    }

    /** Portion of this tick which lies inside the attack's active window. */
    private fun activeSecondsBetween(attack: BossAttack, before: Double, after: Double): Double {
        val start = maxOf(before, attack.telegraphSeconds)
        val end = minOf(after, attack.totalSeconds)
        return (end - start).coerceAtLeast(0.0)
    }

    /** Moves in half-tile-or-smaller pieces, stopping permanently when the charged path is unsafe. */
    private fun advanceCharge(seconds: Double) {
        if (meleeChargeStopped) return
        var distanceLeft = LUNGE_SPEED * seconds
        while (distanceLeft > Vec2.EPSILON) {
            val distance = minOf(distanceLeft, TILE_SIZE / 2.0)
            if (!step(facing * distance)) {
                meleeChargeStopped = true
                return
            }
            distanceLeft -= distance
        }
    }

    /**
     * A charged event remains live until the next event (or active-window end), and its ordinary
     * radial reach is swept along the movement completed by this tick.
     */
    private fun chargedDamage(
        attack: BossAttack,
        before: Double,
        after: Double,
        pathStart: Vec2,
        pathEnd: Vec2,
        target: BossTarget,
    ): Double {
        val tickStart = maxOf(before, attack.telegraphSeconds)
        val tickEnd = minOf(after, attack.totalSeconds)
        val enteredActiveWindow = before < attack.telegraphSeconds && after >= attack.telegraphSeconds
        if (tickEnd < tickStart || (tickEnd == tickStart && !enteredActiveWindow)) return 0.0

        val tickDuration = tickEnd - tickStart
        var damage = 0.0
        attack.eventOffsets.indices.forEach { eventIndex ->
            if (eventIndex in consumedChargeEvents) return@forEach
            val eventStart = attack.telegraphSeconds + attack.eventOffsets[eventIndex]
            val eventEnd = attack.telegraphSeconds +
                (attack.eventOffsets.getOrNull(eventIndex + 1) ?: attack.activeSeconds)
            val overlapStart = maxOf(tickStart, eventStart)
            val overlapEnd = minOf(tickEnd, eventEnd)
            if (overlapEnd < overlapStart || tickEnd < eventStart || tickStart > eventEnd) return@forEach

            val from = positionAlongTick(pathStart, pathEnd, overlapStart - tickStart, tickDuration)
            val to = positionAlongTick(pathStart, pathEnd, overlapEnd - tickStart, tickDuration)
            if (hitsAlong(target, attack, from, to)) {
                consumedChargeEvents += eventIndex
                damage += attack.damage
            }
        }
        return damage
    }

    private fun positionAlongTick(start: Vec2, end: Vec2, elapsed: Double, duration: Double): Vec2 {
        if (duration <= Vec2.EPSILON) return start
        return start + (end - start) * (elapsed / duration).coerceIn(0.0, 1.0)
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
     * The direct-melee hit condition: Slam and Rush strike the ground and miss an airborne player;
     * Sweep is level and passes over a crouched one. MoveAside attacks are ranged events whose
     * projectile or beam geometry is resolved by [GameSimulation].
     */
    private fun hits(target: BossTarget, attack: BossAttack): Boolean {
        if ((target.centre - position).lengthSquared > attack.reachPx * attack.reachPx) return false
        return targetCanBeHit(target, attack)
    }

    /** The target centre touching the reach-expanded movement segment is a swept charged hit. */
    private fun hitsAlong(target: BossTarget, attack: BossAttack, from: Vec2, to: Vec2): Boolean {
        if (!targetCanBeHit(target, attack)) return false
        val segment = to - from
        val along = if (segment.lengthSquared <= Vec2.EPSILON) {
            0.0
        } else {
            val offset = target.centre - from
            ((offset.x * segment.x + offset.y * segment.y) / segment.lengthSquared).coerceIn(0.0, 1.0)
        }
        val nearest = from + segment * along
        return (target.centre - nearest).lengthSquared <= attack.reachPx * attack.reachPx
    }

    private fun targetCanBeHit(target: BossTarget, attack: BossAttack): Boolean =
        when (attack.dodge) {
            Dodge.Jump -> target.onGround
            Dodge.Crouch -> !target.crouched
            Dodge.MoveAside -> false
        }

    /**
     * Closes on the player, wherever they are, under the ledge rule: no step off an edge, onto a
     * lethal tile or into a wall.
     */
    private fun approach(delta: Double, playerCentre: Vec2) {
        val toPlayer = playerCentre.x - position.x
        if (kotlin.math.abs(toPlayer) < CLOSE_ENOUGH) return
        val direction = if (toPlayer > 0) 1 else -1
        if (needsLeap(direction) && beginLeap(direction)) return
        if (!step(direction * SPEED * delta)) beginLeap(direction)
    }

    /** One horizontal step under the ledge rule: no step off an edge, onto a lethal tile or into a wall. */
    private fun step(step: Double): Boolean {
        val nextX = position.x + step
        if (!canStand(nextX)) return false
        position = Vec2(nextX, position.y)
        stridePx += kotlin.math.abs(step)
        moving = true
        return true
    }

    private fun needsLeap(direction: Int): Boolean {
        val pieces = (EnemyLeap.LOOK_AHEAD_PX / (TILE_SIZE / 2.0)).toInt()
        repeat(pieces) { index ->
            if (!canStand(position.x + direction * (index + 1) * (TILE_SIZE / 2.0))) return true
        }
        return false
    }

    private fun canStand(centreX: Double): Boolean {
        val feetRow = TileMap.toTile(position.y)
        val left = TileMap.toTile(centreX - halfWidth)
        val right = TileMap.toTile(centreX + halfWidth - EDGE)
        if (!tiles.blocksMovement(left, feetRow) || !tiles.blocksMovement(right, feetRow)) return false
        if (tiles.isLethal(left, feetRow) || tiles.isLethal(right, feetRow)) return false
        val topLeft = Vec2(centreX - halfWidth, position.y - height)
        if (bodyBlocked(topLeft)) return false
        val liveLevel = level ?: return true
        return Hazards.overlapped(liveLevel, topLeft.x, topLeft.y, BODY_WIDTH, BODY_HEIGHT).isEmpty() &&
            !activeJetOverlap(topLeft)
    }

    private fun beginLeap(direction: Int): Boolean {
        if (leap != null || currentAttack != null) return false
        val topLeft = Vec2(position.x - halfWidth, position.y - height)
        val plan = EnemyLeap.plan(
            tiles = tiles,
            level = level,
            topLeft = topLeft,
            width = BODY_WIDTH,
            height = BODY_HEIGHT,
            feetOffset = BODY_HEIGHT,
            direction = direction,
            timeSeconds = elapsedSeconds,
        ) ?: return false
        leap = plan
        vy = EnemyLeap.VY
        facing = direction
        return true
    }

    private fun advanceLeap(delta: Double) {
        val active = leap ?: return
        vy = (vy + Physics.Default.gravity * delta).coerceAtMost(Physics.Default.terminalVelocity)
        val travel = Vec2(active.direction * EnemyLeap.VX * delta, vy * delta)
        val pieces = maxOf(
            1,
            kotlin.math.ceil(maxOf(kotlin.math.abs(travel.x), kotlin.math.abs(travel.y)) / (TILE_SIZE / 2.0)).toInt(),
        )
        var topLeft = Vec2(position.x - halfWidth, position.y - height)
        repeat(pieces) {
            val next = topLeft + travel * (1.0 / pieces)
            if (bodyBlocked(next)) {
                if (vy > 0.0 && land(next)) return
                leap = null
                vy = 0.0
                return
            }
            topLeft = next
            position = Vec2(topLeft.x + halfWidth, topLeft.y + height)
            stridePx += kotlin.math.abs(travel.x / pieces)
            moving = true
        }
    }

    private fun land(topLeft: Vec2): Boolean {
        val row = TileMap.toTile(topLeft.y + height)
        val left = TileMap.toTile(topLeft.x)
        val right = TileMap.toTile(topLeft.x + BODY_WIDTH - EDGE)
        if (!tiles.blocksMovement(left, row) || !tiles.blocksMovement(right, row)) return false
        if (tiles.isLethal(left, row) || tiles.isLethal(right, row)) return false
        position = Vec2(topLeft.x + halfWidth, TileMap.toWorld(row))
        leap = null
        vy = 0.0
        landingCooldownLeft = EnemyLeap.LANDING_COOLDOWN
        return true
    }

    private fun bodyBlocked(topLeft: Vec2): Boolean {
        val left = TileMap.toTile(topLeft.x)
        val right = TileMap.toTile(topLeft.x + BODY_WIDTH - EDGE)
        val top = TileMap.toTile(topLeft.y)
        val bottom = TileMap.toTile(topLeft.y + BODY_HEIGHT - EDGE)
        return (left..right).any { column -> (top..bottom).any { row -> tiles.blocksMovement(column, row) } }
    }

    private fun activeJetOverlap(topLeft: Vec2): Boolean {
        val liveLevel = level ?: return false
        val left = TileMap.toTile(topLeft.x)
        val right = TileMap.toTile(topLeft.x + BODY_WIDTH - EDGE)
        val top = TileMap.toTile(topLeft.y)
        val bottom = TileMap.toTile(topLeft.y + BODY_HEIGHT - EDGE)
        return liveLevel.jets.any { jet ->
            jet.column in left..right && (top..bottom).any(jet::coversRow) && jet.isOnAt(elapsedSeconds)
        }
    }

    companion object {
        private const val BODY_HEIGHT = 56.0
        private const val BODY_WIDTH = 44.0
        private const val OPENING_REST = 0.8
        private const val REST_BETWEEN = 0.9
        private const val CLOSING_REST = 0.65
        private const val CLOSING_HEALTH = 0.25
        const val SPEED = 55.0
        /** Every selected melee charge advances at this speed for its module's active window. */
        const val LUNGE_SPEED = 300.0
        private const val CLOSE_ENOUGH = 8.0
        private const val EDGE = 0.001
        /** Inside the Slam and Sweep reach a ranged opener is the exception. */
        const val MELEE_REACH = 80.0
        /** At the ranged modules' preferred reach and beyond, a melee opener is the exception. */
        const val RANGED_PREFERRED_PX = 128.0
        const val RANGED_WEIGHT_NEAR = 0.2
        const val RANGED_WEIGHT_FAR = 0.8

        private const val CHARGE_CHANCE_FIRST = 0.50
        private const val CHARGE_CHANCE_LAST = 0.90
        private const val LAST_MAP_INDEX = 10

        /** Probability of a ranged attack at [distance]: linear between the two reaches. */
        fun rangedWeight(distance: Double): Double {
            val t = ((distance - MELEE_REACH) / (RANGED_PREFERRED_PX - MELEE_REACH)).coerceIn(0.0, 1.0)
            return RANGED_WEIGHT_NEAR + (RANGED_WEIGHT_FAR - RANGED_WEIGHT_NEAR) * t
        }

        /** PROD-104: 50 % on map 1, 90 % on map 10, linear in between. */
        fun chargeChance(mapIndex: Int): Double {
            require(mapIndex in 1..LAST_MAP_INDEX) { "map index outside 1..$LAST_MAP_INDEX: $mapIndex" }
            val depth = (mapIndex - 1).toDouble() / (LAST_MAP_INDEX - 1)
            return CHARGE_CHANCE_FIRST + (CHARGE_CHANCE_LAST - CHARGE_CHANCE_FIRST) * depth
        }

        internal fun rollsCharge(mapIndex: Int, rng: Rng): Boolean =
            rng.nextDouble() < chargeChance(mapIndex)
    }
}

/** A melee swing the renderer can draw: where, which way, how wide, and how long it lingers. */
data class SwingVisual(
    val origin: Vec2,
    val direction: Vec2,
    val arcDegrees: Double,
    val reachPx: Double,
    val secondsLeft: Double,
    val totalSeconds: Double,
) {
    /** One at the moment of the swing, falling to zero as it fades. */
    val strength: Double get() = (secondsLeft / totalSeconds).coerceIn(0.0, 1.0)
}

enum class CombatTargetKind { Enemy, Miniboss, Boss }

/** Stable identity within one simulation, kept so one activation cannot hit a body twice. */
data class CombatTargetId(val kind: CombatTargetKind, val index: Int = 0)

/** Future-affecting state snapshotted when a player's `ArcSwing` triggers. */
data class ActiveMeleeSwing(
    val origin: Vec2,
    val direction: Vec2,
    val arcDegrees: Double,
    val reachPx: Double,
    val elapsedSeconds: Double,
    val totalSeconds: Double,
    val weapon: ResolvedWeapon,
    val hitTargets: Set<CombatTargetId> = emptySet(),
) {
    val progress: Double get() = (elapsedSeconds / totalSeconds).coerceIn(0.0, 1.0)
    val sector: MeleeSector get() = MeleeSector(origin, direction, reachPx, arcDegrees, progress)

    fun visual(): SwingVisual = SwingVisual(
        origin = origin,
        direction = direction,
        arcDegrees = arcDegrees,
        reachPx = reachPx,
        secondsLeft = (totalSeconds - elapsedSeconds).coerceAtLeast(0.0),
        totalSeconds = totalSeconds,
    )
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
    /** Trigger-time target snapshot for a future positive-gravity burst. */
    val aimPoint: Vec2? = null,
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
    /** Boss shots may hurt on boss ground; ordinary enemy shots may not. */
    val bossOwned: Boolean = false,
    val bossModule: BossModule? = null,
    val homingTurn: Double = 0.0,
    val homingRadius: Double = 0.0,
    val radius: Double = 6.0,
    /** The build that fired a player's shot: its hit effects land as fired, whatever is held when it lands (PROD-070). */
    val weapon: ResolvedWeapon? = null,
    /** Terrain contacts this projectile can still reflect off (PROD-074). */
    var bouncesLeft: Int = 0,
    /** Stable targets already damaged by this projectile (PROD-098). */
    var hitTargets: Set<CombatTargetId> = emptySet(),
    /** Downward acceleration in screen pixels per second squared (PROD-097). */
    val gravity: Double = 0.0,
) {
    val spent: Boolean get() = secondsLeft <= 0.0 || pierceLeft < 0
}

/** A finite locked boss beam, kept live for its visible active window and allowed to hit once. */
class LiveBossBeam(
    val start: Vec2,
    val end: Vec2,
    val damage: Double,
    var secondsLeft: Double,
    val totalSeconds: Double,
    var hitPlayer: Boolean = false,
) {
    val strength: Double get() = (secondsLeft / totalSeconds).coerceIn(0.0, 1.0)
}
