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
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap

/** A weapon or a powerup lying on the ground. Contact resolves it either way (PROD-030). */
class GroundItem(val position: Vec2, val weapon: WeaponSpec?, val powerup: Powerup?)

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
) {
    // Per-map, per-phase stream (ENG-053), so loot on map 3 is not the same draw as loot on map 1.
    private val rng = Rng.derive(seed, level.mapIndex, "loot")

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
     * How far the player has walked, which is what the gait cycle reads (`plan.md` §15.4).
     *
     * Here rather than on `PlayerState` on purpose. That value's hash is pinned to a committed
     * golden across both targets by `PhysicsDeterminismTest`; putting a presentational field into it
     * would either break that test or quietly widen what "physics state" means. `lastSwing` set the
     * precedent — presentation the simulation carries, kept out of the physics value.
     */
    var playerStridePx: Double = 0.0
        private set

    val enemies = mutableListOf<LiveEnemy>()
    val projectiles = mutableListOf<LiveProjectile>()
    val items = mutableListOf<GroundItem>()

    val miniboss = LiveBoss(
        Bosses.miniboss(level.mapIndex), level.miniboss,
        commitColumn = level.miniboss.leftTile + COMMIT_OFFSET,
    )
    val boss = LiveBoss(
        Bosses.boss(level.mapIndex), level.boss,
        commitColumn = level.boss.leftTile + COMMIT_OFFSET,
    )

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
     * it — PROD-047 says so, and change 0003 requires this one so a mini-boss is never met with the
     * broken bottle.
     *
     * Its own stream because sharing one made the *guaranteed* award depend on how many *optional*
     * pickups the generator happened to place: at seed 1, three static pickups gave a Chrome Fang
     * and removing them gave a Sable Corp Railgun. Isolating combat from the caches in the round
     * before this left the two caches still coupled to each other.
     */
    private val starterRng = Rng.derive(seed, level.mapIndex, "starter-cache")

    private val autoFire = AutoFire(run.loadout.weapon, run.loadout.slots)
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
        level.pickups.forEach { site ->
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
                    DropTable.rollWeapon(starterRng, 1, floor = io.github.ksean.cyberslop.combat.Tier.Street, unlocked = unlockedWeapons),
                    null,
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
        autoFire.tick(TICK_SECONDS, muzzle, aim).forEach { emit(it, muzzle, aim) }
        advanceProjectiles()
        advanceStatuses()
        advanceEnemies()
        advanceBosses()
        collectItems()

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

    private fun resolveChain(shot: Shot, origin: Vec2) {
        val pattern = shot.weapon.spec.pattern as FirePattern.Chain
        val jumps = pattern.jumps + shot.weapon.chainTargets
        var from = origin
        var scale = 1.0
        val struck = mutableSetOf<LiveEnemy>()

        repeat(jumps) {
            val next = enemies.filter { it.alive && it !in struck }
                .minByOrNull { (centreOfEnemy(it) - from).lengthSquared } ?: return
            if ((centreOfEnemy(next) - from).lengthSquared > pattern.jumpRange * pattern.jumpRange) {
                return
            }
            struck.add(next)
            applyHit(Target.Enemy(next), shot.weapon, shot.direction, scale)
            from = centreOfEnemy(next)
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
                run = run.damaged(projectile.damage)
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
     * Enemies stay inside the patrol span generation constrained them to, and non-flyers respect
     * terrain.
     *
     * That is not a simplification — it is what makes the generation-time invariant true at
     * runtime. An enemy free to chase would walk into the corridor the invariant promises is
     * crossable without forced combat, and the promise would be worthless.
     */
    private fun advanceEnemies() {
        val contact = Balance.contactDamage(level.mapIndex)
        enemies.filter { it.alive }.forEach { enemy ->
            if (enemy.stunned) return@forEach
            val speed = ENEMY_SPEED * enemy.speedScale(DamagePipeline.MIN_ENEMY_SPEED_FRACTION) *
                enemy.archetype.speedScale()

            if (enemy.position.x > enemy.homeX + enemy.patrolPx) enemy.facing = -1
            if (enemy.position.x < enemy.homeX - enemy.patrolPx) enemy.facing = 1

            val step = Vec2(enemy.facing * speed * TICK_SECONDS, 0.0)
            val next = enemy.position + step
            if (enemy.archetype.ignoresTerrain || !blocked(next)) {
                enemy.position = next
                enemy.stridePx += speed * TICK_SECONDS
            } else {
                enemy.facing = -enemy.facing
            }

            if (enemy.archetype.shoots) shootAtPlayer(enemy)

            if ((centreOf(player) - centreOfEnemy(enemy)).lengthSquared < CONTACT_RADIUS_SQUARED) {
                run = run.damaged(contact * TICK_SECONDS)
            }
        }
    }

    private fun shootAtPlayer(enemy: LiveEnemy) {
        enemy.fireCooldown -= TICK_SECONDS
        if (enemy.fireCooldown > 0.0) return
        val offset = centreOf(player) - centreOfEnemy(enemy)
        if (offset.lengthSquared > SHOOTER_RANGE * SHOOTER_RANGE) return
        // Terrain has to matter. Firing on range alone meant shooting through walls and floors,
        // which also made the generation-time line-of-fire invariant describe nothing about play.
        if (!hasLineOfSight(centreOfEnemy(enemy), centreOf(player))) return
        enemy.fireCooldown = SHOOTER_COOLDOWN
        projectiles.add(
            LiveProjectile(
                position = centreOfEnemy(enemy),
                velocity = offset.normalisedOr(Vec2.Right) * SHOOTER_SPEED,
                damage = Balance.contactDamage(level.mapIndex) * SHOOTER_DAMAGE_SHARE,
                pierceLeft = 0,
                secondsLeft = SHOOTER_LIFETIME,
                passesTerrain = false,
                fromPlayer = false,
            ),
        )
    }

    private fun advanceBosses() {
        val column = TileMap.toTile(centreOf(player).x)
        miniboss.fight.playerMoved(if (level.miniboss.containsColumn(column)) column else -1)
        boss.fight.playerMoved(if (level.boss.containsColumn(column)) column else -1)

        run = run.damaged(miniboss.tick(TICK_SECONDS, centreOf(player)))
        run = run.damaged(boss.tick(TICK_SECONDS, centreOf(player)))

        if (miniboss.fight.defeated && !minibossRewarded) {
            minibossRewarded = true
            award(miniboss.centre, io.github.ksean.cyberslop.combat.Tier.Scav, powerup = level.mapIndex >= 4)
        }
        if (boss.fight.defeated && !bossRewarded) {
            bossRewarded = true
            award(boss.centre, io.github.ksean.cyberslop.combat.Tier.Chromed, powerup = true, shifts = 2)
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
    ) {
        items.add(GroundItem(at, DropTable.rollWeapon(rng, level.mapIndex, floor, shifts, unlockedWeapons), null))
        if (powerup) {
            items.add(
                GroundItem(
                    at + Vec2(TILE_SIZE.toDouble(), 0.0),
                    null,
                    DropTable.rollPowerup(rng, level.mapIndex, runPool),
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
        if (rng.nextDouble() < DropTable.weaponShare()) {
            items.add(GroundItem(centreOfEnemy(enemy), DropTable.rollWeapon(rng, level.mapIndex, unlocked = unlockedWeapons), null))
        } else {
            items.add(GroundItem(centreOfEnemy(enemy), null, DropTable.rollPowerup(rng, level.mapIndex, runPool)))
        }
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
                    val (next, outcome) = run.loadout.collect(item.powerup.id)
                    run = run.copy(loadout = next)
                    if (outcome is Pickup.Scrapped) run = run.copy(scrap = run.scrap + outcome.scrap)
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
        const val COMMIT_OFFSET = 4
        const val STARTER_CACHE_TILES = 6
        const val MAX_PROJECTILES = 300
        const val MAX_PIERCE = 8
        const val DEFAULT_PROJECTILE_SPEED = 520.0
        const val DEFAULT_LIFETIME = 2.0
        const val PROJECTILE_RADIUS = 7.0
        const val ENEMY_HALF = 7.0
        const val CONTACT_RADIUS_SQUARED = 18.0 * 18.0
        const val ENEMY_SPEED = 70.0
        /**
         * How close the player has to be for a shooter or a turret to open fire.
         *
         * Public because presentation needs it: an enemy that is tracking the player should *look*
         * like it is, and a second constant in the renderer would drift from this one the way the
         * swing window already did.
         */
        const val SHOOTER_RANGE = 220.0
        const val SHOOTER_COOLDOWN = 1.6
        const val SHOOTER_SPEED = 260.0
        const val SHOOTER_LIFETIME = 2.5
        const val SHOOTER_DAMAGE_SHARE = 0.6
        const val SCRAP_PER_KILL = 2
        const val BOSS_SCRAP = 40
        /** A static cache draws twice for its tier and keeps the better roll (`plan.md` §6.7). */
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

private fun io.github.ksean.cyberslop.entity.EnemyArchetype.speedScale(): Double = when (this) {
    io.github.ksean.cyberslop.entity.EnemyArchetype.Swarm -> 1.4
    io.github.ksean.cyberslop.entity.EnemyArchetype.Brute -> 0.6
    io.github.ksean.cyberslop.entity.EnemyArchetype.Flyer -> 1.1
    io.github.ksean.cyberslop.entity.EnemyArchetype.Turret -> 0.0
    io.github.ksean.cyberslop.entity.EnemyArchetype.Shooter -> 0.8
}
