package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.Anchor
import io.github.ksean.cyberslop.combat.AutoFire
import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.Falloff
import io.github.ksean.cyberslop.combat.FirePattern
import io.github.ksean.cyberslop.combat.HitEffect
import io.github.ksean.cyberslop.combat.Homing
import io.github.ksean.cyberslop.combat.ResolvedWeapon
import io.github.ksean.cyberslop.combat.Shot
import io.github.ksean.cyberslop.combat.Targeting
import io.github.ksean.cyberslop.combat.WeaponClass
import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.entity.Bosses
import io.github.ksean.cyberslop.loot.DropTable
import io.github.ksean.cyberslop.loot.Pickup
import io.github.ksean.cyberslop.loot.Powerup
import io.github.ksean.cyberslop.loot.WeaponPickup
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.gen.Populator
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap

/**
 * A weapon or a powerup lying on the ground. Contact resolves it either way (PROD-030).
 *
 * [guaranteed] marks the awards `LootFloor` is computed from — the boss and mini-boss drops and map
 * one's starter cache. They are never refused by a full build, which is what makes the floor a bound
 * on a real player rather than on one particular route.
 */
class GroundItem(
    val position: Vec2,
    val weapon: WeaponSpec?,
    val powerup: Powerup?,
    val guaranteed: Boolean = false,
)

data class TickReport(
    val playerDied: Boolean = false,
    val mapCleared: Boolean = false,
    val bossDefeated: Boolean = false,
)

/**
 * The whole game, as one tick.
 *
 * Everything the registries describe is executed here — crit, arcs, falloff, homing, cursor
 * anchoring, status effects, boss phases and gates — because a registry entry nothing runs is
 * decoration. An earlier version resolved only "melee hits everything nearby" versus "spawn a
 * projectile", which made the broken bottle hit enemies behind the player and through walls, and
 * left the seeking and slowing powerups the brief specifically asks for with no effect at all.
 */
class GameSimulation(
    val level: Level,
    startingRun: RunState,
    seed: ULong,
    private val unlockedWeapons: Int = io.github.ksean.cyberslop.combat.Weapons.all.size,
    /**
     * Whether optional loot exists at all: the static caches and the kill drops. Off for the
     * reference player of `LootFloor`, who takes only guaranteed awards — a harness cannot strip
     * a drop that is created and collected inside one tick, so the simulation has to not make it.
     */
    private val optionalLoot: Boolean = true,
) {
    // Per-map, per-phase stream (ENG-053), so loot on map 3 is not the same draw as loot on map 1.
    internal val lootRng = Rng.derive(seed, level.mapIndex, "loot")
    private val rng: Rng get() = lootRng

    var run: RunState = startingRun
        private set
    var player: PlayerState = level.spawnState()
        private set
    var previousPlayer: PlayerState = player
        private set
    var facing: Int = 1
        private set
    var elapsedTicks: Int = 0
        private set
    var exitReached: Boolean = false
        private set

    /**
     * The most recent melee swing, for the renderer.
     *
     * A swing resolves in a single tick, so without something to draw it the player sees enemies
     * lose health for no visible reason — which, with an automatic weapon they never trigger, reads
     * as nothing happening at all.
     */
    var lastSwing: SwingVisual? = null
        private set

    /** The most recent shot leaving the muzzle, for the renderer. */
    var lastShot: MuzzleFlash? = null
        private set

    /**
     * Where the weapon is currently pointing — a unit vector, updated every tick.
     *
     * Aiming takes no input (PROD-022), so the only way a player learns what the game has locked
     * onto is by watching the figure hold its weapon that way. Without it the arm pointed the way
     * the player was walking while shots left on whatever bearing the nearest target happened to
     * be, including behind them.
     */
    var aimDirection: Vec2 = Vec2.Right
        private set

    /**
     * How far the player has walked, which is what the gait cycle reads (`specs/presentation.md`).
     *
     * Here rather than on `PlayerState` on purpose. That value's hash is pinned to a committed
     * golden across both targets by `PhysicsDeterminismTest`; putting a presentational field into it
     * would either break that test or quietly widen what "physics state" means. `lastSwing` set the
     * precedent — presentation the simulation carries, kept out of the physics value.
     */
    var playerStridePx: Double = 0.0
        private set

    /**
     * Every point of damage the player has taken this map, before lifesteal and before death
     * clamps it: the *gross incoming damage* the pressure harnesses measure (`specs/enemies.md`).
     */
    var grossDamageTaken: Double = 0.0
        private set

    private fun hurt(amount: Double) {
        grossDamageTaken += amount
        run = run.damaged(amount)
    }

    /** How long the player has been grounded and clear of committed columns; see [playerExposed]. */
    private var exposedSeconds: Double = LANDING_GRACE

    val enemies = mutableListOf<LiveEnemy>()
    val projectiles = mutableListOf<LiveProjectile>()
    val items = mutableListOf<GroundItem>()

    val miniboss = LiveBoss(Bosses.miniboss(level.mapIndex), level.miniboss, level.tiles)
    val boss = LiveBoss(Bosses.boss(level.mapIndex), level.boss, level.tiles)

    /** Declared above `init`, which places static pickups and therefore draws from it. */
    private val runPool = DropTable.runPool(Rng.derive(seed, 0, "pool"), level.mapIndex)

    /**
     * What the pickups already lying on the map turn out to be.
     *
     * Its own stream, because it is drawn before the player has done anything and the combat stream
     * is not: sharing one made the number of static caches shift every later crit, stun and kill
     * drop on the map (ENG-053). Isolating it leaves `rng` untouched until the first shot, which is
     * a stronger position than the code was in before static drops existed.
     */
    private val cacheRng = Rng.derive(seed, level.mapIndex, "cache-content")

    /**
     * The map-one starter cache, which is a different award from the optional static drops beside
     * it — PROD-047 says so, and `specs/enemies.md` requires this one so a mini-boss is never met with the
     * broken bottle.
     *
     * Its own stream because sharing one made the *guaranteed* award depend on how many *optional*
     * pickups the generator happened to place: at seed 1, three static pickups gave a Chrome Fang
     * and removing them gave a Sable Corp Railgun. Isolating combat from the caches in the round
     * before this left the two caches still coupled to each other.
     */
    private val starterRng = Rng.derive(seed, level.mapIndex, "starter-cache")

    internal val autoFire = AutoFire(run.loadout.weapon, run.loadout.slots)
    private var minibossRewarded = false
    private var bossRewarded = false

    init {
        level.enemies.forEach { spawn ->
            enemies.add(
                LiveEnemy(
                    archetype = spawn.archetype,
                    position = Vec2(TileMap.toWorld(spawn.column), TileMap.toWorld(spawn.row)),
                    health = spawn.archetype.healthOn(level.mapIndex),
                    homeX = TileMap.toWorld(spawn.column),
                    patrolPx = TileMap.toWorld(spawn.patrolTiles),
                ),
            )
        }
        // Statically placed pickups (PROD-047). Generation chose where; what each yields is decided
        // here, because it depends on the run's unlocks and its powerup pool.
        level.pickups.filter { optionalLoot }.forEach { site ->
            val at = site.centre
            items.add(
                if (cacheRng.nextDouble() < DropTable.weaponShare()) {
                    GroundItem(
                        at,
                        DropTable.rollWeapon(
                            cacheRng, level.mapIndex, shifts = CACHE_TIER_SHIFTS,
                            unlocked = unlockedWeapons,
                        ),
                        null,
                    )
                } else {
                    GroundItem(
                        at,
                        null,
                        DropTable.rollPowerup(
                            cacheRng, level.mapIndex, runPool, shifts = CACHE_TIER_SHIFTS,
                        ),
                    )
                },
            )
        }

        // The starter cache. Map one must never meet its mini-boss with the broken bottle.
        if (level.mapIndex == 1) {
            items.add(
                GroundItem(
                    Vec2(TileMap.toWorld(level.spawnColumn + STARTER_CACHE_TILES),
                        TileMap.toWorld(level.spawnRow) - TILE_SIZE),
                    // Never the bottle itself: the cache exists to replace it (`specs/enemies.md`).
                    DropTable.rollWeapon(
                        starterRng, 1, floor = io.github.ksean.cyberslop.combat.Tier.Street,
                        unlocked = unlockedWeapons, excluding = io.github.ksean.cyberslop.combat.Weapons.startingWeapon.id,
                    ),
                    null,
                    guaranteed = true,
                ),
            )
        }
    }

    fun tick(input: InputFrame): TickReport {
        previousPlayer = player
        if (input.direction != 0) facing = input.direction

        player = MovementModel.step(player, input, level.tiles)
        elapsedTicks++

        val muzzle = centreOf(player)
        val aim = Targeting.aimPoint(muzzle, targets(), facing)
        aimDirection = (aim - muzzle).normalisedOr(Vec2(facing.toDouble(), 0.0))

        // Distance, not time: a gait driven by elapsed time and a speed-dependent rate jumps
        // whenever speed changes, and the feet skate.
        if (player.onGround) {
            playerStridePx += (if (player.vx < 0.0) -player.vx else player.vx) * TICK_SECONDS
        }

        lastSwing = lastSwing?.let { swing ->
            val remaining = swing.secondsLeft - TICK_SECONDS
            if (remaining > 0.0) swing.copy(secondsLeft = remaining) else null
        }
        lastShot = lastShot?.let { flash ->
            val remaining = flash.secondsLeft - TICK_SECONDS
            if (remaining > 0.0) flash.copy(secondsLeft = remaining) else null
        }
        // Exposure is read off the box the movement model just produced, *before* anything can
        // hit it: resolved after the projectiles, the tick on which the player entered a committed
        // column still carried the previous tick's exposure and could hurt.
        advanceExposure()
        autoFire.tick(TICK_SECONDS, muzzle, aim).forEach { emit(it, muzzle, aim) }
        advanceProjectiles()
        advanceStatuses()
        advanceEnemies()
        advanceBosses()
        collectItems()
        drainHazards()

        if (player.touchedLethal || burnedByJet()) run = run.copy(health = 0.0)

        // The exit sits past the boss arena, behind a gate that only the boss's death opens
        // (PROD-020). Clearing the map means walking out of it.
        if (boss.fight.exitOpen && TileMap.toTile(muzzle.x) > level.gateColumn) {
            exitReached = true
        }

        return TickReport(
            playerDied = run.dead,
            mapCleared = exitReached,
            bossDefeated = boss.fight.defeated,
        )
    }

    /**
     * A canonical digest of every mutable, future-affecting field (P-40, `specs/enemies.md`):
     * doubles by their IEEE bits, lists by length then elements, presentation-only fields excluded.
     * The one number that lets the JVM and Wasm builds be compared against a committed golden.
     */
    fun digest(): ULong = Digest().apply {
        add(run.health); add(run.scrap); add(run.mapIndex); add(run.loadout.weapon.id.ordinal)
        val held = run.loadout.slots.held.entries.sortedBy { it.key.ordinal }
        add(held.size); held.forEach { add(it.key.ordinal); add(it.value) }
        add(player.x); add(player.y); add(player.vx); add(player.vy)
        add(player.onGround); add(player.stance.ordinal); add(player.touchedLethal)
        add(facing); add(elapsedTicks); add(exitReached); add(exposedSeconds)
        add(autoFire.remaining); add(lootRng.state)
        add(enemies.size)
        enemies.forEach { e ->
            add(e.position); add(e.vy); add(e.health); add(e.facing); add(e.engaged)
            add(e.cooldownLeft); add(e.windUpLeft); add(e.windUpTotal); add(e.attackDirection); add(e.attackTarget)
            add(e.slowSecondsLeft); add(e.slowFraction); add(e.stunSecondsLeft)
            add(e.burn.perSecond); add(e.burn.secondsLeft); add(e.bleed.perSecond); add(e.bleed.secondsLeft)
        }
        add(projectiles.size)
        projectiles.forEach { p ->
            add(p.position); add(p.velocity); add(p.damage); add(p.pierceLeft); add(p.secondsLeft)
            add(p.passesTerrain); add(p.fromPlayer); add(p.homingTurn); add(p.homingRadius); add(p.radius)
        }
        add(items.size)
        items.forEach { i ->
            add(i.position); add(i.weapon?.id?.ordinal ?: -1); add(i.powerup?.id?.ordinal ?: -1); add(i.guaranteed)
        }
        listOf(miniboss to minibossRewarded, boss to bossRewarded).forEach { (b, rewarded) ->
            add(b.position); add(b.fight.health); add(b.fight.engaged); add(b.facing); add(b.aimedX)
            add(b.currentAttack?.name?.hashCode() ?: -1); add(b.attackElapsed); add(b.restSecondsLeft)
            add(b.attackIndex); add(rewarded)
        }
        // The exit state is geometry: the tiles `openGate` clears, not a flag standing in for them.
        val floor = level.boss.floorRow
        for (column in level.boss.leftTile until level.widthTiles) {
            for (row in floor - EXIT_CLEARANCE until floor) add(level.tiles[column, row].ordinal)
        }
    }.value

    /** FNV-1a-style folding over 64-bit words; every input is reduced to whole words first. */
    private class Digest {
        var value: ULong = 0xCBF29CE484222325uL
            private set

        fun add(word: ULong) {
            value = (value xor word) * 0x100000001B3uL
        }

        fun add(d: Double) = add(d.toRawBits().toULong())
        fun add(i: Int) = add(i.toLong().toULong())
        fun add(b: Boolean) = add(if (b) 1uL else 0uL)
        fun add(v: Vec2) { add(v.x); add(v.y) }
    }

    /** Live targets for auto-aim: what is actually there now, not where things started. */
    private fun targets(): List<Vec2> = buildList {
        enemies.filter { it.alive }.forEach { add(centreOfEnemy(it)) }
        if (miniboss.fight.vulnerable && !miniboss.fight.defeated) add(miniboss.centre)
        if (boss.fight.vulnerable && !boss.fight.defeated) add(boss.centre)
    }

    // ---- firing -------------------------------------------------------------------------------

    private fun emit(shot: Shot, muzzle: Vec2, aim: Vec2) {
        val weapon = shot.weapon
        val origin = if (weapon.spec.anchor == Anchor.Cursor) aim else muzzle

        when (weapon.spec.cls) {
            WeaponClass.Melee -> resolveArc(shot, muzzle)
            else -> {
                lastShot = MuzzleFlash(
                    direction = shot.direction,
                    secondsLeft = FLASH_VISIBLE_SECONDS,
                    totalSeconds = FLASH_VISIBLE_SECONDS,
                )
                when (weapon.spec.pattern) {
                    is FirePattern.Blast, is FirePattern.Strike ->
                        resolveBlast(shot, origin, (weapon.spec.pattern as? FirePattern.Blast)?.radius
                            ?: (weapon.spec.pattern as FirePattern.Strike).radius)
                    is FirePattern.Chain -> resolveChain(shot, origin)
                    is FirePattern.Orbit -> resolveBlast(shot, origin, weapon.spec.rangePx)
                    is FirePattern.Pull -> resolveBlast(shot, origin, (weapon.spec.pattern as FirePattern.Pull).radius)
                    else -> spawnProjectiles(shot, origin)
                }
            }
        }
    }

    /** A swing covers an arc around the aim direction — not a circle around the player. */
    private fun resolveArc(shot: Shot, muzzle: Vec2) {
        val weapon = shot.weapon
        val arc = (weapon.spec.pattern as? FirePattern.ArcSwing)?.arcDegrees ?: FULL_CIRCLE
        val reach = weapon.spec.rangePx * weapon.reachScale * weapon.hitboxScale

        lastSwing = SwingVisual(
            origin = muzzle,
            direction = shot.direction,
            arcDegrees = arc,
            reachPx = reach,
            secondsLeft = SWING_VISIBLE_SECONDS,
            totalSeconds = SWING_VISIBLE_SECONDS,
        )
        val halfArc = arc / 2.0
        var struck = 0

        forEachTargetNear(muzzle, reach) { hit ->
            if (struck > weapon.pierce) return@forEachTargetNear
            val toTarget = (hit.position - muzzle).normalisedOr(shot.direction)
            if (!TrigTable.withinArc(shot.direction, toTarget, halfArc)) return@forEachTargetNear
            applyHit(hit, weapon, shot.direction)
            struck++
        }
    }

    private fun resolveBlast(shot: Shot, centre: Vec2, radius: Double) {
        val scaled = radius * shot.weapon.hitboxScale
        forEachTargetNear(centre, scaled) { applyHit(it, shot.weapon, shot.direction) }
    }

    /**
     * Jumps from the nearest target to the next. Bosses are targets like anything else: a chain
     * that leapt over trash only left a run whose guaranteed weapon was a chain unable to win.
     */
    private fun resolveChain(shot: Shot, origin: Vec2) {
        val pattern = shot.weapon.spec.pattern as FirePattern.Chain
        val jumps = pattern.jumps + shot.weapon.chainTargets
        var from = origin
        var scale = 1.0
        val struck = mutableSetOf<Any>()

        repeat(jumps) {
            val candidates = enemies.filter { it.alive && it !in struck }.map { Target.Enemy(it) } +
                listOf(miniboss, boss).filter { it.fight.vulnerable && !it.fight.defeated && it !in struck }
                    .map { Target.Boss(it) }
            val next = candidates.minByOrNull { (it.position - from).lengthSquared } ?: return
            if ((next.position - from).lengthSquared > pattern.jumpRange * pattern.jumpRange) return
            struck.add(if (next is Target.Enemy) next.enemy else (next as Target.Boss).boss)
            applyHit(next, shot.weapon, shot.direction, scale)
            from = next.position
            scale *= (1.0 - pattern.decay)
        }
    }

    private fun spawnProjectiles(shot: Shot, origin: Vec2) {
        if (projectiles.size >= MAX_PROJECTILES) return
        val weapon = shot.weapon
        val speed = if (weapon.spec.projectileSpeed > 0.0) {
            weapon.spec.projectileSpeed * weapon.reachScale
        } else {
            DEFAULT_PROJECTILE_SPEED
        }
        val homing = weapon.homing as? Homing.Seek

        repeat(weapon.projectileCount) { index ->
            val offset = if (weapon.projectileCount == 1) {
                0.0
            } else {
                (index - (weapon.projectileCount - 1) / 2.0) *
                    (weapon.spec.spreadDegrees / weapon.projectileCount)
            }
            projectiles.add(
                LiveProjectile(
                    position = origin,
                    velocity = TrigTable.rotate(shot.direction, offset) * speed,
                    damage = weapon.damagePerProjectile,
                    pierceLeft = weapon.pierce.coerceAtMost(MAX_PIERCE),
                    secondsLeft = lifetimeOf(weapon),
                    passesTerrain = weapon.spec.cls == WeaponClass.Psychic,
                    fromPlayer = true,
                    homingTurn = homing?.turnDegreesPerSecond ?: 0.0,
                    homingRadius = homing?.radiusPx ?: 0.0,
                    radius = PROJECTILE_RADIUS * weapon.hitboxScale,
                ),
            )
        }
    }

    // ---- hit resolution -----------------------------------------------------------------------

    private sealed interface Target {
        val position: Vec2
        class Enemy(val enemy: LiveEnemy) : Target {
            override val position: Vec2 get() = Vec2(enemy.position.x + 7.0, enemy.position.y + 7.0)
        }
        class Boss(val boss: LiveBoss) : Target {
            override val position: Vec2 get() = boss.centre
        }
    }

    private inline fun forEachTargetNear(centre: Vec2, radius: Double, action: (Target) -> Unit) {
        val squared = radius * radius
        enemies.forEach { enemy ->
            if (enemy.alive && (centreOfEnemy(enemy) - centre).lengthSquared <= squared) {
                action(Target.Enemy(enemy))
            }
        }
        listOf(miniboss, boss).forEach { live ->
            if (!live.fight.vulnerable || live.fight.defeated) return@forEach
            // A boss is a large body, so its own size counts toward whether a hit lands. Treating it
            // as a point made it smaller than a trash enemy to strike.
            val reach = radius + live.radius
            if ((live.centre - centre).lengthSquared <= reach * reach) action(Target.Boss(live))
        }
    }

    private fun applyHit(
        target: Target,
        weapon: ResolvedWeapon,
        direction: Vec2,
        scale: Double = 1.0,
    ) {
        val distance = (target.position - centreOf(player)).length
        val crit = rng.nextDouble() < weapon.critChance
        val amount = weapon.damagePerProjectile *
            scale *
            falloffAt(weapon.spec.falloff, distance) *
            (if (crit) weapon.critMultiplier else 1.0)

        when (target) {
            is Target.Enemy -> damageEnemy(target.enemy, amount, weapon, direction)
            is Target.Boss -> {
                target.boss.fight.damage(amount)
                if (weapon.slowFraction > 0.0) Unit // Bosses resist slow entirely.
            }
        }

        if (weapon.blastFraction > 0.0) {
            forEachTargetNear(target.position, BLAST_RADIUS * weapon.hitboxScale) { splash ->
                if (splash !== target && splash is Target.Enemy) {
                    damageEnemy(splash.enemy, amount * weapon.blastFraction, weapon, direction, splash = true)
                }
            }
        }

        val healed = amount * weapon.lifestealFraction
        if (healed > 0.0) {
            run = run.copy(health = (run.health + minOf(healed, LIFESTEAL_CAP)).coerceAtMost(run.maxHealth))
        }
    }

    private fun damageEnemy(
        enemy: LiveEnemy,
        amount: Double,
        weapon: ResolvedWeapon,
        direction: Vec2,
        splash: Boolean = false,
    ) {
        var total = amount
        weapon.spec.onHit.forEach { effect ->
            when (effect) {
                is HitEffect.Bleed -> enemy.bleed.apply(effect.seconds, effect.perSecond)
                is HitEffect.Ignite -> enemy.burn.apply(
                    effect.seconds, effect.fractionPerSecond * weapon.damagePerProjectile,
                )
                is HitEffect.Stun -> if (rng.nextDouble() < effect.chance) enemy.stun(effect.seconds)
                is HitEffect.Slow -> enemy.slow(effect.fraction, effect.seconds)
                is HitEffect.Execute ->
                    if (enemy.health <= enemy.archetype.healthOn(level.mapIndex) * effect.healthFraction) {
                        total = enemy.health
                    }
                is HitEffect.Shock, is HitEffect.BlastOnHit -> Unit
            }
        }
        if (weapon.igniteFraction > 0.0) {
            enemy.burn.apply(BURN_SECONDS, weapon.igniteFraction * weapon.damagePerProjectile)
        }
        if (weapon.slowFraction > 0.0) enemy.slow(weapon.slowFraction, SLOW_SECONDS)
        if (weapon.stunChance > 0.0 && rng.nextDouble() < weapon.stunChance) {
            enemy.stun(STUN_SECONDS)
        }
        if (!splash && weapon.knockbackScale > 1.0) {
            enemy.position = enemy.position +
                direction * (weapon.spec.knockback * weapon.knockbackScale * TICK_SECONDS)
        }

        enemy.health -= total
        if (!enemy.alive) onKilled(enemy)
    }

    // ---- world advance ------------------------------------------------------------------------

    private fun advanceProjectiles() {
        projectiles.forEach { projectile ->
            if (projectile.homingTurn > 0.0) steer(projectile)
            projectile.position = projectile.position + projectile.velocity * TICK_SECONDS
            projectile.secondsLeft -= TICK_SECONDS

            if (!projectile.passesTerrain && blocked(projectile.position)) {
                projectile.secondsLeft = 0.0
                return@forEach
            }

            if (projectile.fromPlayer) {
                forEachTargetNear(projectile.position, projectile.radius) { target ->
                    if (projectile.spent) return@forEachTargetNear
                    when (target) {
                        is Target.Enemy ->
                            damageEnemy(target.enemy, projectile.damage, autoFire.weapon, projectile.velocity)
                        is Target.Boss -> target.boss.fight.damage(projectile.damage)
                    }
                    projectile.pierceLeft--
                }
            } else if ((centreOf(player) - projectile.position).lengthSquared <
                projectile.radius * projectile.radius * 4.0
            ) {
                if (enemyDamageAllowed()) hurt(projectile.damage)
                projectile.secondsLeft = 0.0
            }
        }
        projectiles.removeAll { it.spent }
    }

    private fun steer(projectile: LiveProjectile) {
        val nearest = enemies.filter { it.alive }
            .minByOrNull { (centreOfEnemy(it) - projectile.position).lengthSquared } ?: return
        val offset = centreOfEnemy(nearest) - projectile.position
        if (offset.lengthSquared > projectile.homingRadius * projectile.homingRadius) return
        val speed = projectile.velocity.length
        val desired = offset.normalisedOr(projectile.velocity)
        val maxTurn = projectile.homingTurn * TICK_SECONDS
        projectile.velocity = TrigTable.turnToward(projectile.velocity, desired, maxTurn) * speed
    }

    private fun advanceStatuses() {
        enemies.filter { it.alive }.forEach { enemy ->
            enemy.health -= enemy.burn.drain(TICK_SECONDS)
            enemy.health -= enemy.bleed.drain(TICK_SECONDS)
            enemy.slowSecondsLeft -= TICK_SECONDS
            enemy.stunSecondsLeft -= TICK_SECONDS
            if (!enemy.alive) onKilled(enemy)
        }
    }

    /**
     * Enemies act by role once they have noticed the player, and patrol until then
     * (`specs/enemies.md`). Walkers have gravity and never take a step off a ledge or onto a lethal
     * tile; a Flyer never enters a committed column, which is what keeps the route-safety argument
     * true once enemies are free to move.
     */
    private fun advanceEnemies() {
        val playerCentre = centreOf(player)
        enemies.filter { it.alive }.forEach { enemy ->
            updateAwareness(enemy, playerCentre)
            if (!enemy.archetype.ignoresTerrain) fall(enemy)
            decayVisuals(enemy)
            if (!enemy.alive) return@forEach
            enemy.cooldownLeft -= TICK_SECONDS
            if (enemy.stunned) return@forEach

            if (enemy.windingUp) {
                windUp(enemy, playerCentre)
                return@forEach
            }

            val speed = ENEMY_SPEED * enemy.speedScale(DamagePipeline.MIN_ENEMY_SPEED_FRACTION) *
                enemy.archetype.speedScale
            if (enemy.engaged) act(enemy, playerCentre, speed) else patrol(enemy, speed)

            if (enemy.cooldownLeft <= 0.0) beginAttack(enemy, playerCentre)
        }
    }

    private fun decayVisuals(enemy: LiveEnemy) {
        enemy.lastSwing = enemy.lastSwing?.let { it.copy(secondsLeft = it.secondsLeft - TICK_SECONDS) }
            ?.takeIf { it.secondsLeft > 0.0 }
        enemy.lastShot = enemy.lastShot?.let { it.copy(secondsLeft = it.secondsLeft - TICK_SECONDS) }
            ?.takeIf { it.secondsLeft > 0.0 }
    }

    /**
     * Starts a telegraph when the attack's condition holds: a melee enemy with the player in
     * reach, or a shooter with the player in range and in sight. The aim is fixed now, so the
     * player can read the wind-up and move out of it.
     */
    private fun beginAttack(enemy: LiveEnemy, playerCentre: Vec2) {
        val offset = playerCentre - centreOfEnemy(enemy)
        if (enemy.archetype.shoots) {
            val shot = EnemyAttacks.SHOT
            if (offset.lengthSquared > shot.rangePx * shot.rangePx) return
            if (!hasLineOfSight(centreOfEnemy(enemy), playerCentre)) return
            enemy.attackTarget = playerCentre
            enemy.attackDirection = offset.normalisedOr(Vec2.Right)
            enemy.windUpTotal = shot.windUpSeconds
            enemy.windUpLeft = shot.windUpSeconds
        } else {
            val swing = EnemyAttacks.swing(enemy.archetype)
            if (offset.lengthSquared > swing.reachPx * swing.reachPx) return
            enemy.attackDirection = offset.normalisedOr(Vec2(enemy.facing.toDouble(), 0.0))
            enemy.windUpTotal = swing.windUpSeconds
            enemy.windUpLeft = swing.windUpSeconds
        }
    }

    private fun windUp(enemy: LiveEnemy, playerCentre: Vec2) {
        enemy.windUpLeft -= TICK_SECONDS
        // Half a tick of slack, so a wind-up of n ticks resolves on tick n rather than n + 1 when
        // the subtraction leaves a residue of 1e-17.
        if (enemy.windUpLeft > TICK_SECONDS / 2.0) return
        enemy.windUpLeft = 0.0
        if (enemy.archetype.shoots) fire(enemy) else strike(enemy, playerCentre)
    }

    private fun strike(enemy: LiveEnemy, playerCentre: Vec2) {
        val swing = EnemyAttacks.swing(enemy.archetype)
        enemy.cooldownLeft = swing.cooldownSeconds
        enemy.lastSwing = SwingVisual(
            origin = centreOfEnemy(enemy),
            direction = enemy.attackDirection,
            arcDegrees = swing.arcDegrees,
            reachPx = swing.reachPx,
            secondsLeft = SWING_VISIBLE_SECONDS,
            totalSeconds = SWING_VISIBLE_SECONDS,
        )
        val offset = playerCentre - centreOfEnemy(enemy)
        if (offset.lengthSquared > swing.reachPx * swing.reachPx) return
        if (!TrigTable.withinArc(enemy.attackDirection, offset, swing.arcDegrees / 2.0)) return
        if (!enemyDamageAllowed()) return
        hurt(Balance.contactDamage(level.mapIndex) * swing.damageShare)
    }

    private fun fire(enemy: LiveEnemy) {
        val shot = EnemyAttacks.SHOT
        enemy.cooldownLeft = shot.cooldownSeconds
        val origin = centreOfEnemy(enemy)
        val direction = (enemy.attackTarget - origin).normalisedOr(enemy.attackDirection)
        enemy.lastShot = MuzzleFlash(direction, FLASH_VISIBLE_SECONDS, FLASH_VISIBLE_SECONDS)
        projectiles.add(
            LiveProjectile(
                position = origin,
                velocity = direction * shot.speedPx,
                damage = Balance.contactDamage(level.mapIndex) * shot.damageShare,
                pierceLeft = 0,
                secondsLeft = shot.lifetimeSeconds,
                passesTerrain = false,
                fromPlayer = false,
            ),
        )
    }

    /**
     * The fairness rule (`specs/completability.md`): nothing an enemy does lands while the player's
     * box overlaps a committed column, nor until they have been grounded and clear of one for the
     * landing grace — so the first grounded tick after a crossing is never a hit with no reaction
     * window.
     */
    private fun playerExposed(): Boolean = exposedSeconds >= LANDING_GRACE

    /**
     * What an *enemy* may do to the player: exposed under the fairness rule, and not standing on
     * the boss's ground (`specs/enemies.md`) — a Shooter held at an arena's edge is still in range
     * of someone inside, so the ground has to be fair as well as unenterable. Bosses are not
     * bound by this: their ground is where they fight.
     */
    private fun enemyDamageAllowed(): Boolean = playerExposed() && !playerOnArenaGround()

    private fun playerOnArenaGround(): Boolean {
        val left = TileMap.toTile(player.x)
        val right = TileMap.toTile(player.x + Physics.Default.width - EDGE)
        return (left..right).any { level.isArenaGround(it, Populator.ARENA_APPROACH_TILES) }
    }

    private fun playerOverCommitted(): Boolean {
        val left = TileMap.toTile(player.x)
        // Any overlap at all counts, down to a fraction of a pixel: `width - 1` left the last
        // pixel of the box out of the test.
        val right = TileMap.toTile(player.x + Physics.Default.width - EDGE)
        return (left..right).any { level.isCommitted(it) }
    }

    private fun advanceExposure() {
        exposedSeconds = if (player.onGround && !playerOverCommitted()) exposedSeconds + TICK_SECONDS else 0.0
    }

    /** Euclidean and strict at the radius, with hysteresis so an enemy at the edge does not flicker. */
    private fun updateAwareness(enemy: LiveEnemy, playerCentre: Vec2) {
        val distanceSquared = (playerCentre - centreOfEnemy(enemy)).lengthSquared
        // Strictly inside, the same predicate auto-aim uses, so the two boundaries agree at equality.
        enemy.engaged = when {
            // Engaged until the distance *exceeds* the radius: equality keeps it.
            enemy.engaged -> distanceSquared <= DISENGAGE_PX * DISENGAGE_PX
            else -> distanceSquared < AWARE_PX * AWARE_PX
        }
    }

    private fun patrol(enemy: LiveEnemy, speed: Double) {
        if (enemy.position.x > enemy.homeX + enemy.patrolPx) enemy.facing = -1
        if (enemy.position.x < enemy.homeX - enemy.patrolPx) enemy.facing = 1
        if (!walk(enemy, enemy.facing * speed * TICK_SECONDS)) enemy.facing = -enemy.facing
    }

    private fun act(enemy: LiveEnemy, playerCentre: Vec2, speed: Double) {
        val offset = playerCentre - centreOfEnemy(enemy)
        val toward = if (offset.x < 0.0) -1 else 1
        enemy.facing = toward
        when {
            enemy.archetype == io.github.ksean.cyberslop.entity.EnemyArchetype.Turret -> Unit
            enemy.archetype.ignoresTerrain -> fly(enemy, offset, speed)
            enemy.archetype.melee -> {
                if (kotlin.math.abs(offset.x) > CLOSE_ENOUGH_PX) walk(enemy, toward * speed * TICK_SECONDS)
            }
            else -> {
                val distanceSquared = offset.lengthSquared
                when {
                    distanceSquared > SHOOTER_RANGE * SHOOTER_RANGE -> walk(enemy, toward * speed * TICK_SECONDS)
                    distanceSquared < RETREAT_PX * RETREAT_PX -> walk(enemy, -toward * speed * TICK_SECONDS)
                    else -> Unit
                }
            }
        }
    }

    /**
     * A voluntary horizontal step under the ledge rule: refused unless the destination footprint is
     * supported by non-lethal ground and nothing solid stands at body height. Returns whether it
     * was taken.
     */
    private fun walk(enemy: LiveEnemy, dx: Double): Boolean {
        if (dx == 0.0) return true
        val next = enemy.position + Vec2(dx, 0.0)
        val feetRow = TileMap.toTile(next.y + ENEMY_FEET)
        val headRow = TileMap.toTile(next.y + 1.0)
        val bodyRow = TileMap.toTile(next.y + ENEMY_SIZE - 1.0)
        val leading = TileMap.toTile(if (dx > 0) next.x + ENEMY_SIZE - 1.0 else next.x)
        val trailing = TileMap.toTile(if (dx > 0) next.x else next.x + ENEMY_SIZE - 1.0)
        // Down to the last fraction of a pixel, unlike the ledge test's whole-pixel footprint.
        if (entersArena(enemy, TileMap.toTile(if (dx > 0) next.x + ENEMY_SIZE - EDGE else next.x))) return false
        val tiles = level.tiles
        if (!enemy.archetype.ignoresTerrain) {
            if (tiles.blocksMovement(leading, headRow) || tiles.blocksMovement(leading, bodyRow)) return false
            if (enemy.vy == 0.0) {
                if (!tiles.blocksMovement(leading, feetRow) || !tiles.blocksMovement(trailing, feetRow)) return false
                if (tiles.isLethal(leading, feetRow) || tiles.isLethal(trailing, feetRow)) return false
            }
        }
        enemy.position = next
        enemy.stridePx += kotlin.math.abs(dx)
        return true
    }

    /**
     * The boss's ground is the boss's (`specs/enemies.md`, Pursuit): an enemy outside an arena's
     * approach never steps onto it, so a pack the player outran waits at the door rather than
     * joining a fight tuned as a boss fight. An enemy already on it is not trapped by the rule.
     */
    private fun entersArena(enemy: LiveEnemy, leadingColumn: Int): Boolean {
        if (!level.isArenaGround(leadingColumn, Populator.ARENA_APPROACH_TILES)) return false
        val here = TileMap.toTile(enemy.position.x)..TileMap.toTile(enemy.position.x + ENEMY_SIZE - 1.0)
        return here.none { level.isArenaGround(it, Populator.ARENA_APPROACH_TILES) }
    }

    private fun fly(enemy: LiveEnemy, offset: Vec2, speed: Double) {
        val direction = offset.normalisedOr(Vec2.Right)
        val step = direction * (speed * TICK_SECONDS)
        // The whole body stays out, not just its centre: a pod could otherwise hang half a tile
        // over the gap it is forbidden to enter.
        val nextX = enemy.position.x + step.x
        val leading = TileMap.toTile(nextX)
        val trailing = TileMap.toTile(nextX + 2 * ENEMY_HALF - EDGE)
        val blocked = level.isCommitted(leading) || level.isCommitted(trailing) ||
            entersArena(enemy, if (step.x > 0) trailing else leading)
        val horizontal = if (blocked) 0.0 else step.x
        enemy.position = enemy.position + Vec2(horizontal, step.y)
        enemy.stridePx += kotlin.math.abs(horizontal)
    }

    /** Gravity for walkers. Landing on a lethal tile kills; landing on solid ground stops the fall. */
    private fun fall(enemy: LiveEnemy) {
        val physics = Physics.Default
        val feetRow = TileMap.toTile(enemy.position.y + ENEMY_FEET)
        val left = TileMap.toTile(enemy.position.x)
        val right = TileMap.toTile(enemy.position.x + ENEMY_SIZE - 1.0)
        val supported = enemy.vy == 0.0 &&
            (level.tiles.blocksMovement(left, feetRow) || level.tiles.blocksMovement(right, feetRow))
        if (supported) return

        enemy.vy = (enemy.vy + physics.gravity * TICK_SECONDS).coerceAtMost(physics.terminalVelocity)
        var travel = enemy.vy * TICK_SECONDS
        while (travel > 0.0) {
            val slice = minOf(travel, TILE_SIZE / 2.0)
            travel -= slice
            val nextY = enemy.position.y + slice
            val row = TileMap.toTile(nextY + ENEMY_FEET)
            if (level.tiles.isLethal(left, row) || level.tiles.isLethal(right, row)) {
                enemy.health = 0.0
                enemy.position = Vec2(enemy.position.x, nextY)
                return
            }
            if (level.tiles.blocksMovement(left, row) || level.tiles.blocksMovement(right, row)) {
                enemy.position = Vec2(enemy.position.x, TileMap.toWorld(row) - ENEMY_FEET)
                enemy.vy = 0.0
                return
            }
            enemy.position = Vec2(enemy.position.x, nextY)
        }
    }

    private fun advanceBosses() {
        val target = BossTarget(
            centre = centreOf(player),
            onGround = player.onGround,
            crouched = player.stance == io.github.ksean.cyberslop.physics.Stance.Crouch,
        )
        listOf(miniboss, boss).forEach { live ->
            if (!live.fight.engaged && (target.centre - live.centre).lengthSquared < AWARE_PX * AWARE_PX) {
                live.fight.engage()
            }
            val damage = live.tick(TICK_SECONDS, target)
            if (damage > 0.0 && playerExposed()) hurt(damage)
        }

        if (miniboss.fight.defeated && !minibossRewarded) {
            minibossRewarded = true
            award(miniboss.centre, io.github.ksean.cyberslop.combat.Tier.Scav, powerup = level.mapIndex >= 4)
        }
        if (boss.fight.defeated && !bossRewarded) {
            bossRewarded = true
            award(
                boss.centre, io.github.ksean.cyberslop.combat.Tier.Chromed, powerup = true, shifts = 2,
                powerupFloor = io.github.ksean.cyberslop.loot.PowerupTier.Scav,
            )
            run = run.copy(scrap = run.scrap + BOSS_SCRAP)
            openGate()
        }
    }

    /**
     * Clears everything between the arena and the map's edge that could stop the player leaving.
     *
     * Deliberately every blocking tile at head height across the whole exit run, not just the gate
     * column: a player who has killed the boss has finished the map, and any wall still standing is
     * a soft-lock however it got there.
     */
    private fun openGate() {
        val floor = level.boss.floorRow
        for (column in level.boss.leftTile until level.widthTiles) {
            for (row in floor - EXIT_CLEARANCE until floor) {
                if (level.tiles.blocksMovement(column, row)) {
                    level.tiles[column, row] = io.github.ksean.cyberslop.world.TileKind.Empty
                }
            }
        }
    }

    private fun award(
        at: Vec2,
        floor: io.github.ksean.cyberslop.combat.Tier,
        powerup: Boolean,
        shifts: Int = 1,
        powerupFloor: io.github.ksean.cyberslop.loot.PowerupTier? = null,
    ) {
        items.add(
            GroundItem(
                at,
                DropTable.rollWeapon(rng, level.mapIndex, floor, shifts, unlockedWeapons),
                null,
                guaranteed = true,
            ),
        )
        if (powerup) {
            items.add(
                GroundItem(
                    at + Vec2(TILE_SIZE.toDouble(), 0.0),
                    null,
                    DropTable.rollPowerup(rng, level.mapIndex, runPool, floor = powerupFloor),
                    guaranteed = true,
                ),
            )
        }
    }


    private fun onKilled(enemy: LiveEnemy) {
        run = run.copy(scrap = run.scrap + SCRAP_PER_KILL)
        if (autoFire.weapon.killRefundChance > 0.0 &&
            rng.nextDouble() < autoFire.weapon.killRefundChance
        ) {
            autoFire.clearCooldown()
        }
        if (rng.nextDouble() > DropTable.killDropChance(level.mapIndex)) return
        // Rolled whether or not it is kept: the loot stream also feeds crits and stuns, and a
        // guaranteed-only run has to be the same fight with the loot merely withheld.
        val drop = if (rng.nextDouble() < DropTable.weaponShare()) {
            GroundItem(centreOfEnemy(enemy), DropTable.rollWeapon(rng, level.mapIndex, unlocked = unlockedWeapons), null)
        } else {
            GroundItem(centreOfEnemy(enemy), null, DropTable.rollPowerup(rng, level.mapIndex, runPool))
        }
        if (optionalLoot) items.add(drop)
    }

    private fun collectItems() {
        val reach = TILE_SIZE.toDouble()
        val taken = items.filter { (it.position - centreOf(player)).lengthSquared < reach * reach }
        taken.forEach { item ->
            when {
                item.weapon != null -> {
                    val (next, outcome) = run.loadout.collect(item.weapon, level.mapIndex)
                    // Both outcomes yield Scrap: an upgrade sells what it displaced, and a weapon
                    // that loses the comparison is sold where it lies.
                    val scrap = when (outcome) {
                        is WeaponPickup.Equipped -> outcome.scrap
                        is WeaponPickup.Scrapped -> outcome.scrap
                    }
                    run = run.copy(loadout = next, scrap = run.scrap + scrap)
                }

                item.powerup != null -> {
                    val (next, outcome) =
                        run.loadout.collect(item.powerup.id, level.mapIndex, item.guaranteed)
                    run = run.copy(loadout = next)
                    // Both losing outcomes pay out: the pickup that lost, or the slot it displaced.
                    val scrap = when (outcome) {
                        is Pickup.Scrapped -> outcome.scrap
                        is Pickup.Displaced -> outcome.scrap
                        is Pickup.Applied -> 0
                    }
                    if (scrap > 0) run = run.copy(scrap = run.scrap + scrap)
                }
            }
            autoFire.rebuild(run.loadout.weapon, run.loadout.slots)
        }
        items.removeAll(taken)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun centreOf(state: PlayerState) = Vec2(
        state.x + Physics.Default.width / 2.0,
        state.y + state.height(Physics.Default) / 2.0,
    )

    private fun centreOfEnemy(enemy: LiveEnemy) =
        Vec2(enemy.position.x + ENEMY_HALF, enemy.position.y + ENEMY_HALF)

    private fun falloffAt(falloff: Falloff, distance: Double): Double = when (falloff) {
        Falloff.None -> 1.0
        is Falloff.Linear -> when {
            distance <= falloff.startPx -> 1.0
            distance >= falloff.endPx -> falloff.minimum
            else -> 1.0 - (distance - falloff.startPx) / (falloff.endPx - falloff.startPx) *
                (1.0 - falloff.minimum)
        }
    }

    /** Damaging hazards drain by overlap and never move the player (`specs/hazards.md`). */
    private fun drainHazards() {
        val rate = Hazards.ratePerSecond(
            level, player.x, player.y, Physics.Default.width, player.height(Physics.Default),
        )
        if (rate > 0.0) hurt(rate * Balance.contactDamage(level.mapIndex) * TICK_SECONDS)
    }

    private fun burnedByJet(): Boolean {
        if (level.jets.isEmpty()) return false
        val now = elapsedTicks * TICK_SECONDS
        val left = TileMap.toTile(player.x)
        val right = TileMap.toTile(player.x + Physics.Default.width - 0.001)
        val top = TileMap.toTile(player.y)
        val bottom = TileMap.toTile(player.y + player.height(Physics.Default) - 0.001)
        return level.jets.any { jet ->
            jet.column in left..right && (top..bottom).any { jet.coversRow(it) } && jet.isOnAt(now)
        }
    }

    private fun hasLineOfSight(from: Vec2, to: Vec2): Boolean {
        val offset = to - from
        val steps = (offset.length / (TILE_SIZE / 2.0)).toInt().coerceAtLeast(1)
        for (step in 1 until steps) {
            val at = from + offset * (step.toDouble() / steps)
            if (blocked(at)) return false
        }
        return true
    }

    private fun blocked(point: Vec2): Boolean =
        level.tiles.blocksMovement(TileMap.toTile(point.x), TileMap.toTile(point.y))

    private fun lifetimeOf(weapon: ResolvedWeapon): Double =
        (weapon.spec.pattern as? FirePattern.Projectile)?.lifetimeSeconds ?: DEFAULT_LIFETIME

    companion object {
        const val STARTER_CACHE_TILES = 6
        const val MAX_PROJECTILES = 300
        const val MAX_PIERCE = 8
        const val DEFAULT_PROJECTILE_SPEED = 520.0
        const val DEFAULT_LIFETIME = 2.0
        const val PROJECTILE_RADIUS = 7.0
        const val ENEMY_HALF = 7.0
        const val ENEMY_SIZE = 14.0
        /** An enemy stands in a cell: its feet are the cell's bottom edge, not its 14 px body's. */
        const val ENEMY_FEET = TILE_SIZE.toDouble()
        /** How near the player has to be for an enemy to notice them (`specs/enemies.md`). */
        const val AWARE_PX = 22.0 * TILE_SIZE
        const val DISENGAGE_PX = 28.0 * TILE_SIZE
        /** Inside this a shooter backs away. */
        const val RETREAT_PX = 5.0 * TILE_SIZE
        const val CLOSE_ENOUGH_PX = 4.0
        /** Grounded-and-clear time an enemy attack needs before it can land after a crossing. */
        const val LANDING_GRACE = 0.25

        /** A box resting exactly on a tile boundary does not overlap the tile beyond it. */
        private const val EDGE = 0.001
        const val ENEMY_SPEED = 70.0
        /**
         * How close the player has to be for a shooter or a turret to open fire.
         *
         * Public because presentation needs it: an enemy that is tracking the player should *look*
         * like it is, and a second constant in the renderer would drift from this one the way the
         * swing window already did.
         */
        const val SHOOTER_RANGE = EnemyAttacks.SHOT_RANGE_PX
        const val SCRAP_PER_KILL = 2
        const val BOSS_SCRAP = 40
        /** A static cache draws twice for its tier and keeps the better roll (`specs/combat.md`). */
        const val CACHE_TIER_SHIFTS = 1
        const val BLAST_RADIUS = 36.0
        const val BURN_SECONDS = 3.0
        const val SLOW_SECONDS = 2.0
        const val STUN_SECONDS = 0.5
        const val LIFESTEAL_CAP = 4.0
        const val FULL_CIRCLE = 360.0
        const val SWING_VISIBLE_SECONDS = 0.16
        const val FLASH_VISIBLE_SECONDS = 0.10
        const val EXIT_CLEARANCE = 6
    }
}
