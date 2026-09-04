package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.Anchor
import io.github.ksean.cyberslop.combat.AutoFire
import io.github.ksean.cyberslop.combat.BallisticLaunch
import io.github.ksean.cyberslop.combat.CombatBodies
import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.Falloff
import io.github.ksean.cyberslop.combat.FirePattern
import io.github.ksean.cyberslop.combat.HitEffect
import io.github.ksean.cyberslop.combat.Homing
import io.github.ksean.cyberslop.combat.ProjectileBallistics
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
import io.github.ksean.cyberslop.entity.BossRoster
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.loot.DropTable
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.Pickup
import io.github.ksean.cyberslop.loot.Powerup
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.LethalContact
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.progression.DiscoveryId
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.gen.Populator
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap

data class TickReport(
    val playerDied: Boolean = false,
    /** True only once the four-second terminal interval may be replaced by the end screen. */
    val deathSequenceComplete: Boolean = false,
    val mapCleared: Boolean = false,
    val bossDefeated: Boolean = false,
    /** Items whose contact fully resolved this tick, in resolution order. */
    val collectedDiscoveries: List<DiscoveryId> = emptyList(),
    /** Presentation-only sound transitions, in the order they occurred this tick (PROD-102). */
    val audioCues: List<AudioCue> = emptyList(),
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
    /** Injectable for deterministic reconstruction tests; live games derive it from [seed]. */
    bossRoster: BossRoster = BossRoster.forRun(seed),
) {
    // Per-map, per-phase stream (ENG-053), so loot on map 3 is not the same draw as loot on map 1.
    internal val lootRng = Rng.derive(seed, level.mapIndex, "loot")
    private val rng: Rng get() = lootRng

    /** Independent of combat and equipment loot, so food cannot perturb either (PROD-110). */
    internal val ramenRng = Rng.derive(seed, level.mapIndex, "ramen")

    private var gameplayViewport = fullLevelViewport()

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

    /** The live, future-affecting player arc. Its geometry is also what presentation consumes. */
    var activeSwing: ActiveMeleeSwing? = null
        internal set

    /** Presentation retained only for the Halo's separately specified immediate ring attack. */
    private var legacyMeleeVisual: SwingVisual? = null

    val lastSwing: SwingVisual? get() = activeSwing?.visual() ?: legacyMeleeVisual

    /** The most recent shot leaving the muzzle, for the renderer. */
    var lastShot: MuzzleFlash? = null
        private set

    /** The last instant-hit geometry or completed exceptional-melee miss; presentation only. */
    var lastHit: HitIndicator? = null
        internal set

    /**
     * Projectiles spent this flash window (PROD-071): a shot that spawns, flies and hits inside one
     * tick is never in [projectiles] when a frame is drawn, so its line of flight is kept here.
     * Presentation only.
     */
    val impacts: List<HitIndicator> get() = spent
    private val spent = mutableListOf<HitIndicator>()

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

    /** Seconds of player hurt flash remaining; presentation-only and deliberately undigested. */
    var playerHurtSecondsLeft: Double = 0.0
        internal set

    /** Seconds of player heal flash remaining; presentation-only and deliberately undigested. */
    var playerHealSecondsLeft: Double = 0.0
        internal set

    /** Null while playing; immutable cause plus fixed-tick age after terminal player damage. */
    var deathSequence: DeathSequence? = null
        internal set

    /** World presentation keeps animating from terminal age while gameplay [elapsedTicks] is frozen. */
    val presentationTimeSeconds: Double
        get() = elapsedTicks * TICK_SECONDS + (deathSequence?.ageSeconds ?: 0.0)

    /**
     * Every point of damage the player has taken this map, before lifesteal and before death
     * clamps it: the *gross incoming damage* the pressure harnesses measure (`specs/enemies.md`).
     */
    var grossDamageTaken: Double = 0.0
        private set

    private fun hurt(amount: Double, source: PlayerDamageSource) {
        val reduced = amount * run.upgrades.incomingDamageMultiplier
        val before = run.health
        grossDamageTaken += reduced
        run = run.damaged(reduced)
        if (run.health < before) playerHurtSecondsLeft = HURT_FLASH_SECONDS
        if (before > 0.0 && run.dead) beginDeath(source)
    }

    private fun kill(source: PlayerDamageSource) {
        if (run.dead) return
        run = run.copy(health = 0.0)
        beginDeath(source)
    }

    private fun beginDeath(source: PlayerDamageSource) {
        if (deathSequence != null) return
        deathSequence = DeathSequence(source)
        // Terminal frames must not repeatedly interpolate from the pre-lethal movement state.
        previousPlayer = player
    }

    /** Grounded grace accumulated since the most recent committed-column overlap; see [playerExposed]. */
    private var exposedSeconds: Double = LANDING_GRACE

    /** The life-steal budget (PROD-073): a token bucket of 12 HP refilling at 12 HP/s, spent by each heal. */
    internal var lifestealBudget: Double = LIFESTEAL_PER_SECOND

    /** The rounds of the last machine-gun trigger still to leave (PROD-075). */
    internal var pendingBurst: PendingBurst? = null

    val enemies = mutableListOf<LiveEnemy>()
    val projectiles = mutableListOf<LiveProjectile>()
    val bossBeams = mutableListOf<LiveBossBeam>()
    val items = mutableListOf<GroundItem>()
    private val deathDropPlacement = DeathDropPlacement(level)
    private val liveScrapGains = mutableListOf<ScrapGain>()
    private val emittedAudioCues = mutableListOf<AudioCue>()
    val scrapGains: List<ScrapGain> get() = liveScrapGains

    val miniboss = LiveBoss(
        Bosses.miniboss(level.mapIndex, bossRoster.miniboss(level.mapIndex)), level.miniboss, level.tiles,
        Rng.derive(seed, level.mapIndex, "miniboss-attacks"), level,
        Rng.derive(seed, level.mapIndex, "miniboss-melee-charges"),
    )
    val boss = LiveBoss(
        Bosses.boss(level.mapIndex, bossRoster.boss(level.mapIndex)), level.boss, level.tiles,
        Rng.derive(seed, level.mapIndex, "boss-attacks"), level,
        Rng.derive(seed, level.mapIndex, "boss-melee-charges"),
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
     * it — PROD-047 says so, and `specs/enemies.md` requires this one so a mini-boss is never met with the
     * broken bottle.
     *
     * Its own stream because sharing one made the *guaranteed* award depend on how many *optional*
     * pickups the generator happened to place: at seed 1, three static pickups gave a Chrome Fang
     * and removing them gave a Sable Corp Railgun. Isolating combat from the caches in the round
     * before this left the two caches still coupled to each other.
     */
    private val starterRng = Rng.derive(seed, level.mapIndex, "starter-cache")

    internal val autoFire = AutoFire(
        run.loadout.weapon,
        run.loadout.slots,
        run.upgrades.weaponDamageMultiplier,
    )
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
                    GroundItem.equipment(
                        position = at,
                        weapon = DropTable.rollWeapon(
                            cacheRng, level.mapIndex, shifts = CACHE_TIER_SHIFTS,
                            unlocked = unlockedWeapons,
                        ),
                    )
                } else {
                    GroundItem.equipment(
                        position = at,
                        powerup = DropTable.rollPowerup(
                            cacheRng, level.mapIndex, runPool, shifts = CACHE_TIER_SHIFTS,
                        ),
                    )
                },
            )
        }

        // The starter cache. Map one must never meet its mini-boss with the broken bottle.
        if (level.mapIndex == 1) {
            items.add(
                GroundItem.equipment(
                    position = Vec2(TileMap.toWorld(level.spawnColumn + STARTER_CACHE_TILES),
                        TileMap.toWorld(level.spawnRow) - TILE_SIZE),
                    // Never the bottle itself: the cache exists to replace it (`specs/enemies.md`).
                    weapon = DropTable.rollWeapon(
                        starterRng, 1, floor = io.github.ksean.cyberslop.combat.Tier.Street,
                        unlocked = unlockedWeapons, excluding = io.github.ksean.cyberslop.combat.Weapons.startingWeapon.id,
                    ),
                    guaranteed = true,
                ),
            )
        }
    }

    fun tick(input: InputFrame): TickReport = tick(input, fullLevelViewport())

    fun tick(input: InputFrame, viewport: GameplayViewport): TickReport {
        emittedAudioCues.clear()
        deathSequence?.let { terminal ->
            deathSequence = terminal.advance()
            return TickReport(
                playerDied = true,
                deathSequenceComplete = requireNotNull(deathSequence).complete,
            )
        }
        gameplayViewport = viewport
        val enemyCentresBeforeTick = enemies.map(LiveEnemy::centre)
        val minibossCentreBeforeTick = miniboss.centre
        val bossCentreBeforeTick = boss.centre
        previousPlayer = player
        val hurtWasActive = playerHurtSecondsLeft > 0.0
        playerHurtSecondsLeft = (playerHurtSecondsLeft - TICK_SECONDS).coerceAtLeast(0.0)
        if (!hurtWasActive) {
            playerHealSecondsLeft = (playerHealSecondsLeft - TICK_SECONDS).coerceAtLeast(0.0)
        }
        advanceScrapGains()
        if (input.direction != 0) facing = input.direction

        player = MovementModel.step(player, input, level.tiles)
        elapsedTicks++

        val muzzle = player.centre(Physics.Default)
        val selectedTarget = selectedAimTarget(muzzle)
        val aim = selectedTarget?.position
            ?: Targeting.aimPoint(muzzle, emptyList(), facing)
        aimDirection = (aim - muzzle).normalisedOr(Vec2(facing.toDouble(), 0.0))

        // Distance, not time: a gait driven by elapsed time and a speed-dependent rate jumps
        // whenever speed changes, and the feet skate.
        if (player.onGround) {
            playerStridePx += (if (player.vx < 0.0) -player.vx else player.vx) * TICK_SECONDS
        }

        val completedMeleeMiss = advanceActiveSwing(muzzle)
        legacyMeleeVisual = legacyMeleeVisual?.let { swing ->
            val remaining = swing.secondsLeft - TICK_SECONDS
            if (remaining > 0.0) swing.copy(secondsLeft = remaining) else null
        }
        lastShot = lastShot?.let { flash ->
            val remaining = flash.secondsLeft - TICK_SECONDS
            if (remaining > 0.0) flash.copy(secondsLeft = remaining) else null
        }
        lastHit = lastHit?.let { hit ->
            val remaining = hit.secondsLeft - TICK_SECONDS
            if (remaining > VISUAL_EPSILON) hit.copy(secondsLeft = remaining) else null
        }
        completedMeleeMiss?.let(::showHit)
        for (index in spent.indices) spent[index] = spent[index].copy(secondsLeft = spent[index].secondsLeft - TICK_SECONDS)
        spent.removeAll { it.secondsLeft <= 0.0 }
        // Exposure is read off the box the movement model just produced, *before* anything can
        // hit it: resolved after the projectiles, the tick on which the player entered a committed
        // column still carried the previous tick's exposure and could hurt.
        advanceExposure()
        refillLifesteal()
        // A trigger discards whatever burst was pending — before a round of it due this very
        // tick can leave, or the discard would be a tick late (P-46).
        val shots = autoFire.tick(TICK_SECONDS, muzzle, aim)
        if (shots.isNotEmpty()) pendingBurst = null
        advanceBurst(muzzle)
        shots.forEach { emit(it, muzzle, aim, selectedTarget?.velocity ?: Vec2.Zero) }
        advanceProjectiles()
        advanceBossBeams()
        advanceStatuses()
        advanceEnemies()
        advanceBosses()
        resolveActiveSwing()
        val collectedDiscoveries = collectItems()
        drainHazards()
        drainContact()

        when (player.lethalContact) {
            LethalContact.Acid -> kill(PlayerDamageSource.Acid)
            LethalContact.Void -> kill(PlayerDamageSource.Void)
            null -> Unit
        }
        if (burnedByJet()) kill(PlayerDamageSource.Fire)

        // The exit sits past the boss arena, behind a gate that only the boss's death opens
        // (PROD-020). Clearing the map means walking out of it.
        if (!run.dead && boss.fight.exitOpen && TileMap.toTile(muzzle.x) > level.gateColumn) {
            exitReached = true
        }

        enemies.forEachIndexed { index, enemy ->
            val before = enemyCentresBeforeTick.getOrNull(index) ?: enemy.centre
            enemy.aimingVelocity = (enemy.centre - before) * (1.0 / TICK_SECONDS)
        }
        miniboss.aimingVelocity = (miniboss.centre - minibossCentreBeforeTick) * (1.0 / TICK_SECONDS)
        boss.aimingVelocity = (boss.centre - bossCentreBeforeTick) * (1.0 / TICK_SECONDS)

        return TickReport(
            playerDied = run.dead,
            deathSequenceComplete = deathSequence?.complete == true,
            mapCleared = exitReached,
            bossDefeated = boss.fight.defeated,
            collectedDiscoveries = collectedDiscoveries,
            audioCues = emittedAudioCues.toList(),
        )
    }

    private fun fullLevelViewport() = GameplayViewport(
        left = 0.0,
        top = 0.0,
        right = level.tiles.widthPx,
        bottom = level.tiles.heightPx,
    )

    /**
     * A canonical digest of every mutable, future-affecting field (P-40, `specs/enemies.md`):
     * doubles by their IEEE bits, lists by length then elements, presentation-only fields excluded.
     * The one number that lets the JVM and Wasm builds be compared against a committed golden.
     */
    fun digest(): ULong = Digest().apply {
        add(run.health); add(run.scrap); add(run.mapIndex); add(run.loadout.weapon.id.ordinal)
        // Keep the committed rank-zero cross-target golden stable. Non-default profile snapshots
        // still carry an explicit tagged family because they change future health and damage rules.
        if (
            run.upgrades.reinforcedChassis != 0 ||
            run.upgrades.blackMarketFirmware != 0 ||
            run.upgrades.reactiveDermalWeave != 0
        ) {
            add(UPGRADE_DIGEST_TAG)
            add(run.upgrades.reinforcedChassis)
            add(run.upgrades.blackMarketFirmware)
            add(run.upgrades.reactiveDermalWeave)
        }
        val held = run.loadout.slots.held.entries.sortedBy { it.key.ordinal }
        add(held.size); held.forEach { add(it.key.ordinal); add(it.value) }
        add(player.x); add(player.y); add(player.vx); add(player.vy)
        add(player.onGround); add(player.stance.ordinal); add(player.touchedLethal)
        add(facing); add(elapsedTicks); add(exitReached); add(exposedSeconds)
        deathSequence?.let { terminal ->
            add(DEATH_SEQUENCE_DIGEST_TAG)
            add(terminal.elapsedTicks)
        }
        add(autoFire.remaining); add(lootRng.state); add(ramenRng.state); add(lifestealBudget)
        pendingBurst.let { b ->
            add(b?.roundsLeft ?: -1)
            if (b != null) {
                add(b.secondsToNext); add(b.direction)
                add(b.aimPoint != null); b.aimPoint?.let { add(it) }
                addPayload(b.weapon)
            }
        }
        activeSwing?.let { swing ->
            add(ACTIVE_SWING_DIGEST_TAG)
            add(swing.origin); add(swing.direction); add(swing.arcDegrees); add(swing.reachPx)
            add(swing.elapsedSeconds); add(swing.totalSeconds); addPayload(swing.weapon)
            val hit = swing.hitTargets.sortedWith(compareBy<CombatTargetId> { it.kind.ordinal }.thenBy { it.index })
            add(hit.size); hit.forEach { add(it.kind.ordinal); add(it.index) }
        }
        add(enemies.size)
        enemies.forEach { e ->
            add(e.position); add(e.vy); add(e.aimingVelocity); add(e.health); add(e.facing); add(e.engaged)
            add(e.leap?.direction ?: 0); add(e.leap?.landingX ?: 0.0); add(e.landingCooldownLeft)
            add(e.cooldownLeft); add(e.windUpLeft); add(e.windUpTotal); add(e.attackDirection); add(e.attackTarget)
            add(e.slowSecondsLeft); add(e.slowFraction); add(e.stunSecondsLeft)
            add(e.burn.perSecond); add(e.burn.secondsLeft); add(e.bleed.perSecond); add(e.bleed.secondsLeft)
        }
        add(projectiles.size)
        projectiles.forEach { p ->
            add(p.position); add(p.velocity); add(p.damage); add(p.pierceLeft); add(p.secondsLeft)
            add(p.passesTerrain); add(p.fromPlayer); add(p.homingTurn); add(p.homingRadius); add(p.radius)
            add(p.bossOwned); add(p.bossModule?.ordinal ?: -1)
            add(p.bouncesLeft)
            val hit = p.hitTargets.sortedWith(COMBAT_TARGET_ORDER)
            add(hit.size); hit.forEach { add(it.kind.ordinal); add(it.index) }
            add(p.gravity)
            addPayload(p.weapon)
        }
        add(bossBeams.size)
        bossBeams.forEach { beam ->
            add(beam.start); add(beam.end); add(beam.damage); add(beam.secondsLeft); add(beam.hitPlayer)
        }
        add(items.size)
        items.forEach { i ->
            add(i.position)
            when (val payload = i.payload) {
                is GroundItem.Equipment -> {
                    add(payload.weapon?.id?.ordinal ?: -1)
                    add(payload.powerup?.id?.ordinal ?: -1)
                    add(payload.guaranteed)
                    add(false)
                }
                GroundItem.Ramen -> {
                    add(-1); add(-1); add(false); add(true)
                }
            }
        }
        listOf(miniboss to minibossRewarded, boss to bossRewarded).forEach { (b, rewarded) ->
            add(b.spec.profile.primaryMelee.ordinal); add(b.spec.profile.primaryRanged.ordinal)
            add(b.spec.profile.signature?.ordinal ?: -1)
            add(b.position); add(b.aimingVelocity); add(b.fight.health); add(b.fight.engaged); add(b.facing); add(b.aimedX)
            add(b.aimedAt); add(b.aimDirection)
            add(b.vy); add(b.leap?.direction ?: 0); add(b.leap?.landingX ?: 0.0); add(b.landingCooldownLeft)
            add(b.elapsedSeconds)
            add(b.currentAttack?.module?.ordinal ?: -1); add(b.attackElapsed); add(b.restSecondsLeft)
            add(b.meleeIndex); add(b.rangedIndex); add(b.rng.state); add(b.chargeRng.state)
            add(b.meleeChargeSelected); add(b.meleeChargeStopped)
            val consumedChargeEvents = b.consumedChargeEvents.sorted()
            add(consumedChargeEvents.size); consumedChargeEvents.forEach(::add)
            add(rewarded)
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

        /**
         * The build a player's shot or pending burst carries (PROD-070): every resolved field that
         * changes what spawning or landing does, keyed by the weapon it resolved from.
         */
        fun addPayload(w: ResolvedWeapon?) {
            add(w?.spec?.id?.ordinal ?: -1)
            if (w == null) return
            add(w.damagePerProjectile); add(w.cooldown); add(w.projectileCount); add(w.pierce)
            add(w.critChance); add(w.critMultiplier); add(w.chainTargets); add(w.bounces)
            val seek = w.homing as? Homing.Seek
            add(seek?.turnDegreesPerSecond ?: 0.0); add(seek?.radiusPx ?: 0.0)
            add(w.hitboxScale); add(w.reachScale); add(w.knockbackScale); add(w.stunChance)
            add(w.killRefundChance); add(w.slowFraction); add(w.blastFraction); add(w.igniteFraction)
            add(w.lifestealFraction)
        }
    }

    private data class AimTarget(val position: Vec2, val velocity: Vec2)

    /** Nearest live target by current position, carrying its last completed actual movement. */
    private fun selectedAimTarget(muzzle: Vec2): AimTarget? {
        val candidates = buildList {
            enemies.filter { it.alive }.forEach { add(AimTarget(it.centre, it.aimingVelocity)) }
            if (miniboss.fight.vulnerable && !miniboss.fight.defeated) {
                add(AimTarget(miniboss.centre, miniboss.aimingVelocity))
            }
            if (boss.fight.vulnerable && !boss.fight.defeated) add(AimTarget(boss.centre, boss.aimingVelocity))
        }
        val nearest = Targeting.nearest(muzzle, candidates.map(AimTarget::position)) ?: return null
        return candidates.first { it.position == nearest }
    }

    // ---- firing -------------------------------------------------------------------------------

    private fun emit(shot: Shot, muzzle: Vec2, aim: Vec2, targetVelocity: Vec2) {
        val weapon = shot.weapon
        val origin = if (weapon.spec.anchor == Anchor.Cursor) aim else muzzle

        when (weapon.spec.cls) {
            WeaponClass.Melee -> {
                emittedAudioCues += AudioCue.MeleeSwing
                resolveArc(shot, muzzle)
            }
            else -> {
                emitPlayerFireCue(weapon.spec.cls)
                lastShot = MuzzleFlash(
                    direction = shot.direction,
                    secondsLeft = FLASH_VISIBLE_SECONDS,
                    totalSeconds = FLASH_VISIBLE_SECONDS,
                )
                // An instant attack leaves the geometry its hit test used, so the frame can show
                // where it went and not only that it fired (PROD-071).
                when (val pattern = weapon.spec.pattern) {
                    is FirePattern.Blast -> showHit(HitShape.Ring(origin, resolveBlast(shot, origin, pattern.radius)))
                    is FirePattern.Strike -> showHit(HitShape.Beam(origin, resolveBlast(shot, origin, pattern.radius)))
                    is FirePattern.Chain -> resolveChain(shot, origin)
                    is FirePattern.Orbit -> showHit(HitShape.Ring(origin, resolveBlast(shot, origin, pattern.radius)))
                    is FirePattern.Pull -> showHit(HitShape.Ring(origin, resolveBlast(shot, origin, pattern.radius)))
                    else -> {
                        val launch = spawnProjectiles(shot, origin, aim, targetVelocity)
                        lastShot = lastShot?.copy(direction = launch)
                    }
                }
            }
        }
    }

    private fun emitPlayerFireCue(weaponClass: WeaponClass) {
        when (weaponClass) {
            WeaponClass.Melee -> Unit
            WeaponClass.Ranged -> emittedAudioCues += AudioCue.RangedFire
            WeaponClass.Psychic -> emittedAudioCues += AudioCue.PsychicFire
        }
    }

    /** An `ArcSwing` starts empty and grows through the pattern's own live window. */
    private fun resolveArc(shot: Shot, muzzle: Vec2) {
        val weapon = shot.weapon
        val pattern = weapon.spec.pattern as? FirePattern.ArcSwing
        if (pattern == null) {
            resolveLegacyMelee(shot, muzzle)
            return
        }
        activeSwing = ActiveMeleeSwing(
            origin = muzzle,
            direction = shot.direction,
            arcDegrees = (pattern.arcDegrees * weapon.hitboxScale).coerceAtMost(FULL_CIRCLE),
            reachPx = weapon.spec.rangePx * weapon.reachScale,
            elapsedSeconds = 0.0,
            totalSeconds = pattern.lingerSeconds,
            weapon = weapon,
        )
        legacyMeleeVisual = null
    }

    /** Keeps the non-`ArcSwing` Halo path independent of the live player-sector rule. */
    private fun resolveLegacyMelee(shot: Shot, muzzle: Vec2) {
        val weapon = shot.weapon
        val reach = weapon.spec.rangePx * weapon.reachScale * weapon.hitboxScale
        legacyMeleeVisual = SwingVisual(
            origin = muzzle,
            direction = shot.direction,
            arcDegrees = FULL_CIRCLE,
            reachPx = reach,
            secondsLeft = SWING_VISIBLE_SECONDS,
            totalSeconds = SWING_VISIBLE_SECONDS,
        )
        var struck = 0

        forEachTargetNear(muzzle, reach) { hit ->
            if (struck > weapon.pierce) return@forEachTargetNear
            val toTarget = (hit.position - muzzle).normalisedOr(shot.direction)
            if (!TrigTable.withinArc(shot.direction, toTarget, FULL_CIRCLE / 2.0)) return@forEachTargetNear
            applyHit(hit, weapon, shot.direction)
            struck++
        }
        if (struck == 0 && needsMeleeMissFeedback(weapon)) {
            showHit(HitShape.MeleeMiss(muzzle, shot.direction, reach))
        }
    }

    private fun advanceActiveSwing(origin: Vec2): HitShape.MeleeMiss? {
        val swing = activeSwing ?: return null
        if (swing.elapsedSeconds >= swing.totalSeconds) {
            activeSwing = null
            return if (swing.hitTargets.isEmpty() && needsMeleeMissFeedback(swing.weapon)) {
                HitShape.MeleeMiss(swing.origin, swing.direction, swing.reachPx)
            } else {
                null
            }
        }
        activeSwing = swing.copy(
            origin = origin,
            elapsedSeconds = minOf(swing.totalSeconds, swing.elapsedSeconds + TICK_SECONDS),
        )
        return null
    }

    /** Non-swoosh melee and weapons with a native contact chain need explicit miss reach (PROD-115). */
    private fun needsMeleeMissFeedback(weapon: ResolvedWeapon): Boolean =
        weapon.spec.pattern !is FirePattern.ArcSwing ||
            weapon.spec.onHit.any { effect -> effect is HitEffect.Shock }

    /** Tests the positions the next frame will draw, after both player and target movement. */
    private fun resolveActiveSwing() {
        val swing = activeSwing ?: return
        val hitTargets = swing.hitTargets.toMutableSet()

        enemies.forEachIndexed { index, enemy ->
            if (!enemy.alive) return@forEachIndexed
            val id = CombatTargetId(CombatTargetKind.Enemy, index)
            if (id in hitTargets || !swing.sector.intersects(CombatBodies.enemy(enemy.centre))) {
                return@forEachIndexed
            }
            hitTargets += id
            applyHit(Target.Enemy(enemy), swing.weapon, swing.direction)
        }

        listOf(miniboss to CombatTargetKind.Miniboss, boss to CombatTargetKind.Boss)
            .forEach { (live, kind) ->
                if (!live.fight.vulnerable || live.fight.defeated) return@forEach
                val id = CombatTargetId(kind)
                val body = CombatBodies.boss(live.centre, isMain = live === boss)
                if (id in hitTargets || !swing.sector.intersects(body)) return@forEach
                hitTargets += id
                applyHit(Target.Boss(live), swing.weapon, swing.direction)
            }

        activeSwing = swing.copy(hitTargets = hitTargets)
    }

    /** Resolves a blast and returns the radius it actually used. */
    private fun resolveBlast(shot: Shot, centre: Vec2, radius: Double): Double {
        val scaled = radius * shot.weapon.hitboxScale
        forEachTargetNear(centre, scaled) { applyHit(it, shot.weapon, shot.direction) }
        return scaled
    }

    private fun showHit(shape: HitShape) {
        lastHit = HitIndicator(shape, FLASH_VISIBLE_SECONDS, FLASH_VISIBLE_SECONDS)
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
        val points = mutableListOf(origin)

        var jumpsLeft = jumps
        while (jumpsLeft-- > 0) {
            val candidates = (enemies.filter { it.alive && it !in struck }.map { Target.Enemy(it) } +
                listOf(miniboss, boss).filter { it.fight.vulnerable && !it.fight.defeated && it !in struck }
                    .map { Target.Boss(it) })
                .filter { canDamage(it, shot.weapon) }
            val next = candidates.minByOrNull { (it.position - from).lengthSquared } ?: break
            if ((next.position - from).lengthSquared > pattern.jumpRange * pattern.jumpRange) break
            struck.add(if (next is Target.Enemy) next.enemy else (next as Target.Boss).boss)
            applyHit(next, shot.weapon, shot.direction, scale)
            from = next.position
            points.add(from)
            scale *= (1.0 - pattern.decay)
        }
        // A chain that struck nothing has nowhere to draw; the activation pulse is its only cue.
        if (points.size > 1) showHit(HitShape.Chain(points))
    }

    /**
     * A machine gun fires one round now and queues the rest (PROD-075); anything else fans all of
     * its projectiles at once across the spread. A new trigger always replaces a pending burst, so
     * a burst can never carry a stale build.
     */
    private fun spawnProjectiles(
        shot: Shot,
        origin: Vec2,
        aimPoint: Vec2,
        targetVelocity: Vec2,
    ): Vec2 {
        val weapon = shot.weapon
        val interval = weapon.spec.burstIntervalSeconds
        val lobbedPattern = (weapon.spec.pattern as? FirePattern.Projectile)
            ?.takeIf { pattern -> pattern.gravity > 0.0 }
        val ballisticLaunch = lobbedPattern?.let { pattern ->
            val speed = if (weapon.spec.projectileSpeed > 0.0) {
                weapon.spec.projectileSpeed * weapon.reachScale
            } else {
                DEFAULT_PROJECTILE_SPEED
            }
            ProjectileBallistics.solve(
                origin = origin,
                target = aimPoint,
                nominalSpeed = speed,
                gravity = pattern.gravity,
                lifetimeSeconds = pattern.lifetimeSeconds,
                tickSeconds = TICK_SECONDS,
                targetVelocity = targetVelocity,
            )
        }
        val snapshottedAim = ballisticLaunch?.intercept ?: aimPoint
        if (interval > 0.0) {
            pendingBurst = PendingBurst(
                weapon.projectileCount - 1,
                interval,
                shot.direction,
                weapon,
                aimPoint = snapshottedAim.takeIf { lobbedPattern != null },
            )
            return spawnRound(
                weapon, origin, shot.direction, snapshottedAim,
                offsetDegrees = 0.0,
                ballisticLaunch = ballisticLaunch,
            )
                ?.normalisedOr(shot.direction) ?: shot.direction
        }
        // Fanned evenly across the whole spread: the outermost projectiles sit on its edges.
        val count = weapon.projectileCount
        var launchDirection = shot.direction
        repeat(count) { index ->
            val offset = if (count == 1) 0.0 else (index - (count - 1) / 2.0) * (weapon.spec.spreadDegrees / (count - 1))
            val velocity = spawnRound(
                weapon, origin, shot.direction, snapshottedAim, offset,
                ballisticLaunch = ballisticLaunch,
            )
            if (index == 0 && velocity != null) launchDirection = velocity.normalisedOr(shot.direction)
        }
        return launchDirection
    }

    /** Fires the next pending round when it is due, from the muzzle as it is now, along the trigger aim. */
    private fun advanceBurst(muzzle: Vec2) {
        val burst = pendingBurst ?: return
        val due = burst.secondsToNext - TICK_SECONDS
        // A round due within rounding is due: three ticks off 0.05 s leaves 7e-18, not a fourth tick.
        if (due > BURST_EPSILON) {
            pendingBurst = burst.copy(secondsToNext = due)
            return
        }
        val velocity = spawnRound(burst.weapon, muzzle, burst.direction, burst.aimPoint, offsetDegrees = 0.0)
        if (velocity != null) emitPlayerFireCue(burst.weapon.spec.cls)
        lastShot = MuzzleFlash(
            velocity?.normalisedOr(burst.direction) ?: burst.direction,
            FLASH_VISIBLE_SECONDS,
            FLASH_VISIBLE_SECONDS,
        )
        pendingBurst = if (burst.roundsLeft > 1) {
            burst.copy(roundsLeft = burst.roundsLeft - 1, secondsToNext = due + burst.weapon.spec.burstIntervalSeconds)
        } else {
            null
        }
    }

    private fun spawnRound(
        weapon: ResolvedWeapon,
        origin: Vec2,
        direction: Vec2,
        aimPoint: Vec2?,
        offsetDegrees: Double,
        ballisticLaunch: BallisticLaunch? = null,
    ): Vec2? {
        if (projectiles.size >= MAX_PROJECTILES) return null
        val speed = if (weapon.spec.projectileSpeed > 0.0) {
            weapon.spec.projectileSpeed * weapon.reachScale
        } else {
            DEFAULT_PROJECTILE_SPEED
        }
        val pattern = weapon.spec.pattern as? FirePattern.Projectile
        val gravity = pattern?.gravity ?: 0.0
        val baseVelocity = if (pattern != null && pattern.gravity > 0.0) {
            ballisticLaunch?.velocity ?: ProjectileBallistics.solve(
                origin = origin,
                target = requireNotNull(aimPoint) { "lobbed projectile requires an aim point" },
                nominalSpeed = speed,
                gravity = gravity,
                lifetimeSeconds = pattern.lifetimeSeconds,
                tickSeconds = TICK_SECONDS,
            ).velocity
        } else {
            direction * speed
        }
        val velocity = TrigTable.rotate(baseVelocity, offsetDegrees)
        val homing = weapon.homing as? Homing.Seek
        projectiles.add(
            LiveProjectile(
                position = origin,
                velocity = velocity,
                damage = weapon.damagePerProjectile,
                pierceLeft = weapon.pierce.coerceAtMost(MAX_PIERCE),
                secondsLeft = lifetimeOf(weapon),
                passesTerrain = weapon.spec.cls == WeaponClass.Psychic,
                fromPlayer = true,
                homingTurn = homing?.turnDegreesPerSecond ?: 0.0,
                homingRadius = homing?.radiusPx ?: 0.0,
                radius = PROJECTILE_RADIUS * weapon.hitboxScale,
                weapon = weapon,
                bouncesLeft = if (weapon.spec.cls == WeaponClass.Psychic) 0 else weapon.bounces,
                gravity = gravity,
            ),
        )
        return velocity
    }

    // ---- hit resolution -----------------------------------------------------------------------

    private sealed interface Target {
        val position: Vec2
        class Enemy(val enemy: LiveEnemy) : Target {
            override val position: Vec2 get() = enemy.centre
        }
        class Boss(val boss: LiveBoss) : Target {
            override val position: Vec2 get() = boss.centre
        }
    }

    private inline fun forEachTargetNear(centre: Vec2, radius: Double, action: (Target) -> Unit) {
        val squared = radius * radius
        enemies.forEach { enemy ->
            if (enemy.alive && (enemy.centre - centre).lengthSquared <= squared) {
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
    ): Boolean {
        if (!canDamage(target, weapon)) return false
        val distance = (target.position - player.centre(Physics.Default)).length
        val crit = rng.nextDouble() < weapon.critChance
        val amount = weapon.damagePerProjectile *
            scale *
            falloffAt(weapon.spec.falloff, distance) *
            (if (crit) weapon.critMultiplier else 1.0)

        when (target) {
            is Target.Enemy -> damageEnemy(target.enemy, amount, weapon, direction)
            is Target.Boss -> damageBoss(target.boss, amount, weapon) // Bosses resist slow entirely.
        }

        if (weapon.blastFraction > 0.0) {
            forEachTargetNear(target.position, BLAST_RADIUS * weapon.hitboxScale) { splash ->
                if (splash !== target && splash is Target.Enemy && canDamage(splash, weapon)) {
                    damageEnemy(splash.enemy, amount * weapon.blastFraction, weapon, direction, splash = true)
                }
            }
        }
        return true
    }

    private fun canDamage(target: Target, weapon: ResolvedWeapon): Boolean {
        if (weapon.spec.cls != WeaponClass.Ranged) return true
        val body = when (target) {
            is Target.Enemy -> CombatBodies.enemy(target.position)
            is Target.Boss -> CombatBodies.boss(target.position, isMain = target.boss === boss)
        }
        return gameplayViewport.overlaps(body)
    }

    private fun damageBoss(boss: LiveBoss, amount: Double, weapon: ResolvedWeapon) {
        val before = boss.fight.health
        if (!boss.fight.damage(amount)) return
        boss.hurtSecondsLeft = HURT_FLASH_SECONDS
        stealLife(before - boss.fight.health, weapon)
    }

    /**
     * Life steal on every hit (PROD-073): a fraction of the damage dealt, capped per hit and by
     * the budget — a token bucket of [LIFESTEAL_PER_SECOND] refilling at that rate — and never
     * above max health. Damage over time never arrives here.
     */
    private fun stealLife(dealt: Double, weapon: ResolvedWeapon) {
        // A hit landing after the blow that killed the player in the same tick heals nobody.
        if (run.dead) return
        val missing = run.maxHealth - run.health
        val healed = minOf(dealt * weapon.lifestealFraction, LIFESTEAL_CAP, lifestealBudget, missing)
        if (healed <= 0.0) return
        lifestealBudget -= healed
        run = run.copy(health = run.health + healed)
    }

    private fun refillLifesteal() {
        lifestealBudget = minOf(LIFESTEAL_PER_SECOND, lifestealBudget + LIFESTEAL_PER_SECOND * TICK_SECONDS)
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
                is HitEffect.Bleed -> enemy.bleed.apply(
                    effect.seconds,
                    effect.perSecond * weapon.permanentDamageMultiplier,
                )
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
            enemy.position += direction * (weapon.spec.knockback * weapon.knockbackScale * TICK_SECONDS)
        }

        val dealt = minOf(total, enemy.health)
        enemy.health -= total
        enemy.hurtSecondsLeft = HURT_FLASH_SECONDS
        stealLife(dealt, weapon)
        if (!enemy.alive) onKilled(enemy)
    }

    // ---- world advance ------------------------------------------------------------------------

    private fun advanceProjectiles() {
        projectiles.forEach { projectile ->
            if (projectile.homingTurn > 0.0) steer(projectile)
            if (projectile.gravity > 0.0) {
                projectile.velocity = Vec2(
                    projectile.velocity.x,
                    projectile.velocity.y + projectile.gravity * TICK_SECONDS,
                )
            }
            projectile.secondsLeft -= TICK_SECONDS
            if (!move(projectile)) return@forEach
        }
        projectiles.forEach {
            if (it.spent) {
                val psychic = it.weapon?.spec?.cls == WeaponClass.Psychic
                spent.add(HitIndicator(HitShape.Impact(it.position, it.velocity, it.fromPlayer, psychic), FLASH_VISIBLE_SECONDS, FLASH_VISIBLE_SECONDS))
            }
        }
        projectiles.removeAll { it.spent }
    }

    /**
     * Moves a projectile one tick, in pieces no longer than half a tile so a fast shot cannot cross
     * a wall or target between two samples (a Railgun covers 23 px a tick against 16 px tiles).
     * Returns false where terrain or an exhausted pierce budget spent it.
     */
    private fun move(projectile: LiveProjectile): Boolean {
        val step = projectile.velocity * TICK_SECONDS
        val pieces = maxOf(1, kotlin.math.ceil(step.length / MAX_PROJECTILE_STEP).toInt())
        repeat(pieces) {
            val before = projectile.position
            val after = before + projectile.velocity * (TICK_SECONDS / pieces)
            val terrainEntry = if (projectile.passesTerrain) null else terrainEntryFraction(before, after)
            val viewportExit = rangedViewportExit(projectile, before, after)
            val blockingEntry = when {
                terrainEntry == null -> viewportExit
                viewportExit == null -> terrainEntry
                else -> minOf(terrainEntry, viewportExit)
            }
            if (projectile.fromPlayer && !hitTargetsAlong(projectile, before, after, blockingEntry)) {
                return false
            }
            if (!projectile.fromPlayer && hitPlayerAlong(projectile, before, after, blockingEntry)) {
                return false
            }
            if (viewportExit != null && (terrainEntry == null || viewportExit <= terrainEntry)) {
                projectile.position = before + (after - before) * viewportExit
                projectile.secondsLeft = 0.0
                return false
            }
            projectile.position = after
            if (projectile.bossOwned && outsideLevel(after)) {
                projectile.position = before
                projectile.secondsLeft = 0.0
                return false
            }
            if (projectile.passesTerrain || terrainEntry == null) return@repeat
            if (projectile.bouncesLeft > 0) {
                bounce(projectile, before)
            } else {
                projectile.secondsLeft = 0.0
                return false
            }
        }
        return true
    }

    /** Resolves the first hostile-round contact with the player's current movement body (PROD-111). */
    private fun hitPlayerAlong(
        projectile: LiveProjectile,
        from: Vec2,
        to: Vec2,
        terrainEntry: Double?,
    ): Boolean {
        val fraction = sweptDiscAabbEntry(
            from = from,
            to = to,
            radius = projectile.radius,
            left = player.x,
            top = player.y,
            right = player.x + Physics.Default.width,
            bottom = player.y + player.height(Physics.Default),
        ) ?: return false
        if (terrainEntry != null && fraction >= terrainEntry) return false

        projectile.position = from + (to - from) * fraction
        if (if (projectile.bossOwned) bossDamageAllowed() else enemyDamageAllowed()) {
            hurt(projectile.damage, PlayerDamageSource.Projectile)
        }
        projectile.secondsLeft = 0.0
        return true
    }

    private fun rangedViewportExit(projectile: LiveProjectile, from: Vec2, to: Vec2): Double? {
        if (!projectile.fromPlayer) return null
        val weapon = projectile.weapon ?: autoFire.weapon
        if (weapon.spec.cls != WeaponClass.Ranged) return null
        return gameplayViewport.exitFraction(from, to)
    }

    private data class ProjectileContact(
        val fraction: Double,
        val id: CombatTargetId,
        val target: Target,
    )

    /** Applies player-shot contacts along one movement piece in geometric travel order (PROD-098). */
    private fun hitTargetsAlong(
        projectile: LiveProjectile,
        from: Vec2,
        to: Vec2,
        terrainEntry: Double?,
    ): Boolean {
        if (projectile.spent) return true
        val contacts = buildList {
            enemies.forEachIndexed { index, enemy ->
                if (!enemy.alive) return@forEachIndexed
                val id = CombatTargetId(CombatTargetKind.Enemy, index)
                addProjectileContact(projectile, from, to, terrainEntry, id, Target.Enemy(enemy), projectile.radius)
            }
            listOf(
                Triple(miniboss, CombatTargetKind.Miniboss, miniboss.radius),
                Triple(boss, CombatTargetKind.Boss, boss.radius),
            ).forEach { (live, kind, radius) ->
                if (!live.fight.vulnerable || live.fight.defeated) return@forEach
                addProjectileContact(
                    projectile,
                    from,
                    to,
                    terrainEntry,
                    CombatTargetId(kind),
                    Target.Boss(live),
                    projectile.radius + radius,
                )
            }
        }.sortedWith(
            compareBy<ProjectileContact> { it.fraction }
                .thenBy { it.id.kind.ordinal }
                .thenBy { it.id.index },
        )

        val weapon = projectile.weapon ?: autoFire.weapon
        contacts.forEach { contact ->
            projectile.position = from + (to - from) * contact.fraction
            projectile.hitTargets += contact.id
            when (val target = contact.target) {
                is Target.Enemy -> damageEnemy(target.enemy, projectile.damage, weapon, projectile.velocity)
                is Target.Boss -> damageBoss(target.boss, projectile.damage, weapon)
            }
            projectile.pierceLeft--
            if (projectile.spent) return false
        }
        return true
    }

    private fun MutableList<ProjectileContact>.addProjectileContact(
        projectile: LiveProjectile,
        from: Vec2,
        to: Vec2,
        terrainEntry: Double?,
        id: CombatTargetId,
        target: Target,
        radius: Double,
    ) {
        if (id in projectile.hitTargets) return
        val weapon = projectile.weapon ?: autoFire.weapon
        if (!canDamage(target, weapon)) return
        val fraction = segmentDiscEntry(from, to, target.position, radius) ?: return
        if (terrainEntry != null && fraction >= terrainEntry) return
        add(ProjectileContact(fraction, id, target))
    }

    /** First point where segment [from]..[to] enters a closed disc, as a fraction in `[0, 1]`. */
    private fun segmentDiscEntry(from: Vec2, to: Vec2, centre: Vec2, radius: Double): Double? {
        val travel = to - from
        val offset = from - centre
        val c = offset.lengthSquared - radius * radius
        if (c <= 0.0) return 0.0
        val a = travel.lengthSquared
        if (a <= Vec2.EPSILON * Vec2.EPSILON) return null
        val b = offset.x * travel.x + offset.y * travel.y
        val discriminant = b * b - a * c
        if (discriminant < 0.0) return null
        val fraction = (-b - kotlin.math.sqrt(discriminant)) / a
        return fraction.takeIf { it in 0.0..1.0 }
    }

    /** First contact of a swept closed disc with a closed AABB, including its rounded corners. */
    private fun sweptDiscAabbEntry(
        from: Vec2,
        to: Vec2,
        radius: Double,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Double? = listOfNotNull(
        segmentAabbEntry(from, to, left - radius, top, right + radius, bottom),
        segmentAabbEntry(from, to, left, top - radius, right, bottom + radius),
        segmentDiscEntry(from, to, Vec2(left, top), radius),
        segmentDiscEntry(from, to, Vec2(right, top), radius),
        segmentDiscEntry(from, to, Vec2(left, bottom), radius),
        segmentDiscEntry(from, to, Vec2(right, bottom), radius),
    ).minOrNull()

    /** First entry of a segment into a closed axis-aligned box. */
    private fun segmentAabbEntry(
        from: Vec2,
        to: Vec2,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Double? {
        val xWindow = axisEntryWindow(from.x, to.x, left, right) ?: return null
        val yWindow = axisEntryWindow(from.y, to.y, top, bottom) ?: return null
        val entry = maxOf(0.0, xWindow.first, yWindow.first)
        val exit = minOf(1.0, xWindow.second, yWindow.second)
        return entry.takeIf { entry <= exit }
    }

    private fun axisEntryWindow(start: Double, end: Double, minimum: Double, maximum: Double): Pair<Double, Double>? {
        val delta = end - start
        if (kotlin.math.abs(delta) <= Vec2.EPSILON) {
            return if (start in minimum..maximum) {
                Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY
            } else {
                null
            }
        }
        val first = (minimum - start) / delta
        val second = (maximum - start) / delta
        return minOf(first, second) to maxOf(first, second)
    }

    /** Entry into the blocked tile containing [to], or null when the movement piece stays clear. */
    private fun terrainEntryFraction(from: Vec2, to: Vec2): Double? {
        if (!blocked(to)) return null
        val tileX = TileMap.toTile(to.x)
        val tileY = TileMap.toTile(to.y)
        val left = TileMap.toWorld(tileX)
        val top = TileMap.toWorld(tileY)
        val right = left + TILE_SIZE
        val bottom = top + TILE_SIZE
        val travel = to - from

        fun axisEntry(start: Double, delta: Double, minimum: Double, maximum: Double): Double = when {
            start in minimum..<maximum -> 0.0
            delta > 0.0 -> (minimum - start) / delta
            delta < 0.0 -> (maximum - start) / delta
            else -> Double.POSITIVE_INFINITY
        }

        return maxOf(
            axisEntry(from.x, travel.x, left, right),
            axisEntry(from.y, travel.y, top, bottom),
        ).coerceIn(0.0, 1.0)
    }

    /**
     * Reflects a projectile off the terrain it just entered (PROD-074): the axis it crossed into the
     * solid tile along is reversed — both when it entered a corner on both — it is put back where
     * it was before the step, and it keeps [BOUNCE_DAMAGE] of its damage.
     */
    private fun bounce(projectile: LiveProjectile, before: Vec2) {
        val after = projectile.position
        val enteredAlongX = blocked(Vec2(after.x, before.y))
        val enteredAlongY = blocked(Vec2(before.x, after.y))
        val corner = !enteredAlongX && !enteredAlongY
        val v = projectile.velocity
        projectile.velocity = Vec2(
            if (enteredAlongX || corner) -v.x else v.x,
            if (enteredAlongY || corner) -v.y else v.y,
        )
        projectile.position = before
        projectile.damage *= BOUNCE_DAMAGE
        projectile.bouncesLeft--
    }

    private fun steer(projectile: LiveProjectile) {
        val nearest = enemies.filter { it.alive }
            .minByOrNull { (it.centre - projectile.position).lengthSquared } ?: return
        val offset = nearest.centre - projectile.position
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
     * (`specs/enemies.md`). Walkers preview safe pursuit leaps over terrain and hazards; Flyers
     * cross those spans in flight. Arena ownership remains a separate boundary for rank enemies.
     */
    private fun advanceEnemies() {
        val playerCentre = player.centre(Physics.Default)
        enemies.filter { it.alive }.forEach { enemy ->
            advanceEnemy(enemy, playerCentre)
        }
    }

    private fun advanceEnemy(enemy: LiveEnemy, playerCentre: Vec2) {
        updateAwareness(enemy, playerCentre)
        enemy.landingCooldownLeft = (enemy.landingCooldownLeft - TICK_SECONDS).coerceAtLeast(0.0)
        if (!enemy.archetype.ignoresTerrain) {
            if (enemy.leap != null) advanceLeap(enemy) else fall(enemy)
        }
        decayVisuals(enemy)
        if (!enemy.alive) return
        val attackTimerDelta = attackTimerDelta(enemy, playerCentre)
        enemy.cooldownLeft -= if (enemy.cooldownLeft > 0.0) attackTimerDelta else TICK_SECONDS
        if (enemy.stunned) return

        // No attack starts or continues through a committed leap or its landing grace.
        if (enemy.leap != null || enemy.vy != 0.0 || enemy.landingCooldownLeft > 0.0) return

        if (enemy.windingUp) {
            windUp(enemy, playerCentre, attackTimerDelta)
            return
        }

        val speed = ENEMY_SPEED * enemy.speedScale(DamagePipeline.MIN_ENEMY_SPEED_FRACTION) *
            enemy.archetype.speedScale
        if (enemy.engaged) act(enemy, playerCentre, speed) else patrol(enemy, speed)

        if (enemy.cooldownLeft <= 0.0) beginAttack(enemy, playerCentre)
    }

    private fun attackTimerDelta(enemy: LiveEnemy, playerCentre: Vec2): Double {
        if (enemy.archetype.shoots) return TICK_SECONDS
        val swing = EnemyAttacks.swing(enemy.archetype)
        val offset = playerCentre - enemy.centre
        val rate = if (offset.lengthSquared <= swing.reachPx * swing.reachPx) {
            EnemyAttacks.MELEE_ATTACK_RATE_IN_REACH
        } else {
            1.0
        }
        return rate * TICK_SECONDS
    }

    private fun decayVisuals(enemy: LiveEnemy) {
        enemy.hurtSecondsLeft = (enemy.hurtSecondsLeft - TICK_SECONDS).coerceAtLeast(0.0)
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
        val offset = playerCentre - enemy.centre
        if (enemy.archetype.shoots) {
            val shot = EnemyAttacks.SHOT
            if (offset.lengthSquared > shot.rangePx * shot.rangePx) return
            if (!hasLineOfSight(enemy.centre, playerCentre)) return
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

    private fun windUp(enemy: LiveEnemy, playerCentre: Vec2, timerDelta: Double) {
        enemy.windUpLeft -= timerDelta
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
            origin = enemy.centre,
            direction = enemy.attackDirection,
            arcDegrees = swing.arcDegrees,
            reachPx = swing.reachPx,
            secondsLeft = SWING_VISIBLE_SECONDS,
            totalSeconds = SWING_VISIBLE_SECONDS,
        )
        val offset = playerCentre - enemy.centre
        if (offset.lengthSquared > swing.reachPx * swing.reachPx) return
        if (!TrigTable.withinArc(enemy.attackDirection, offset, swing.arcDegrees / 2.0)) return
        if (!enemyDamageAllowed()) return
        hurt(Balance.contactDamage(level.mapIndex) * swing.damageShare, PlayerDamageSource.Melee)
    }

    private fun fire(enemy: LiveEnemy) {
        val shot = EnemyAttacks.SHOT
        enemy.cooldownLeft = shot.cooldownSeconds
        if (projectiles.size >= MAX_PROJECTILES) return
        val origin = enemy.centre
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
     * What a rank-and-file enemy may do to the player: exposed under the committed-span rule and
     * not standing on final-boss ground. Mini-boss ground is ordinary pursuit and combat space
     * (PROD-112); bosses may hurt on either encounter's ground.
     */
    private fun enemyDamageAllowed(): Boolean = playerExposed() && !playerOnMainBossGround()

    /** Boss-owned contact, swings, projectiles and beams are allowed on their fight ground. */
    private fun bossDamageAllowed(): Boolean = playerExposed()

    private fun playerOnMainBossGround(): Boolean {
        val left = TileMap.toTile(player.x)
        val right = TileMap.toTile(player.x + Physics.Default.width - EDGE)
        return (left..right).any { level.isMainBossGround(it, Populator.ARENA_APPROACH_TILES) }
    }

    private fun playerOverCommitted(): Boolean {
        val left = TileMap.toTile(player.x)
        // Any overlap at all counts, down to a fraction of a pixel: `width - 1` left the last
        // pixel of the box out of the test.
        val right = TileMap.toTile(player.x + Physics.Default.width - EDGE)
        return (left..right).any { level.isCommitted(it) }
    }

    private fun advanceExposure() {
        exposedSeconds = when {
            playerOverCommitted() -> 0.0
            player.onGround -> exposedSeconds + TICK_SECONDS
            else -> exposedSeconds
        }
    }

    /** Euclidean and strict at the radius, with hysteresis so an enemy at the edge does not flicker. */
    private fun updateAwareness(enemy: LiveEnemy, playerCentre: Vec2) {
        val distanceSquared = (playerCentre - enemy.centre).lengthSquared
        // Strictly inside, the same predicate auto-aim uses, so the two boundaries agree at equality.
        enemy.engaged = when {
            // Engaged until the distance *exceeds* the radius: equality keeps it.
            enemy.engaged -> distanceSquared <= DISENGAGE_PX * DISENGAGE_PX
            else -> distanceSquared < AWARE_PX * AWARE_PX
        }
    }

    private fun patrol(enemy: LiveEnemy, speed: Double) {
        if (enemy.archetype == io.github.ksean.cyberslop.entity.EnemyArchetype.Turret) return
        if (enemy.position.x > enemy.homeX + enemy.patrolPx) enemy.facing = -1
        if (enemy.position.x < enemy.homeX - enemy.patrolPx) enemy.facing = 1
        if (!walk(enemy, enemy.facing * speed * TICK_SECONDS)) enemy.facing = -enemy.facing
    }

    private fun act(enemy: LiveEnemy, playerCentre: Vec2, speed: Double) {
        val offset = playerCentre - enemy.centre
        val toward = if (offset.x < 0.0) -1 else 1
        enemy.facing = toward
        when {
            enemy.archetype.ignoresTerrain -> fly(enemy, offset, speed)
            enemy.archetype.melee -> {
                if (kotlin.math.abs(offset.x) > CLOSE_ENOUGH_PX) pursue(enemy, toward, speed)
            }
            else -> {
                val distanceSquared = offset.lengthSquared
                when {
                    distanceSquared > SHOOTER_RANGE * SHOOTER_RANGE -> pursue(enemy, toward, speed)
                    distanceSquared < RETREAT_PX * RETREAT_PX -> pursue(enemy, -toward, speed)
                    else -> Unit
                }
            }
        }
    }

    /** Walks in the chosen direction, beginning a fixed-direction leap when its preview is safe. */
    private fun pursue(enemy: LiveEnemy, direction: Int, speed: Double) {
        if (needsLeap(enemy, direction) && beginLeap(enemy, direction)) return
        if (!walk(enemy, direction * speed * TICK_SECONDS)) beginLeap(enemy, direction)
    }

    private fun needsLeap(enemy: LiveEnemy, direction: Int): Boolean {
        val pieces = (EnemyLeap.LOOK_AHEAD_PX / (TILE_SIZE / 2.0)).toInt()
        repeat(pieces) { index ->
            val x = enemy.position.x + direction * (index + 1) * (TILE_SIZE / 2.0)
            if (!canStand(Vec2(x, enemy.position.y))) return true
        }
        return false
    }

    private fun beginLeap(enemy: LiveEnemy, direction: Int): Boolean {
        if (enemy.vy != 0.0 || enemy.leap != null) return false
        val plan = EnemyLeap.plan(
            tiles = level.tiles,
            level = level,
            topLeft = enemy.position,
            width = LiveEnemy.BODY_SIZE,
            height = LiveEnemy.BODY_SIZE,
            feetOffset = LiveEnemy.FEET_OFFSET,
            direction = direction,
            timeSeconds = elapsedTicks * TICK_SECONDS,
            landingAllowed = { columns -> landingAllowed(enemy, columns) },
        ) ?: return false
        enemy.leap = plan
        enemy.vy = EnemyLeap.VY
        enemy.facing = direction
        return true
    }

    /**
     * A voluntary horizontal step under the ledge rule: refused unless the destination footprint is
     * supported by non-lethal ground and nothing solid stands at body height. Returns whether it
     * was taken.
     */
    private fun walk(enemy: LiveEnemy, dx: Double): Boolean {
        if (dx == 0.0) return true
        val next = enemy.position + Vec2(dx, 0.0)
        // Down to the last fraction of a pixel, unlike the ledge test's whole-pixel footprint.
        if (entersMainBossGround(enemy, TileMap.toTile(if (dx > 0) next.x + LiveEnemy.BODY_SIZE - EDGE else next.x))) return false
        if (!canStand(next)) return false
        enemy.position = next
        enemy.stridePx += kotlin.math.abs(dx)
        return true
    }

    private fun canStand(at: Vec2): Boolean {
        if (bodyBlocked(at, LiveEnemy.BODY_SIZE, LiveEnemy.BODY_SIZE)) return false
        if (safeFloorRow(at) == null) return false
        return Hazards.overlapped(level, at.x, at.y, LiveEnemy.BODY_SIZE, LiveEnemy.BODY_SIZE).isEmpty() &&
            !jetOverlap(at, LiveEnemy.BODY_SIZE, LiveEnemy.BODY_SIZE)
    }

    /** Rank-and-file may enter mini-boss ground, but never newly enter final-boss ground. */
    private fun entersMainBossGround(enemy: LiveEnemy, leadingColumn: Int): Boolean {
        if (!level.isMainBossGround(leadingColumn, Populator.ARENA_APPROACH_TILES)) return false
        val here = TileMap.toTile(enemy.position.x)..TileMap.toTile(enemy.position.x + LiveEnemy.BODY_SIZE - 1.0)
        return here.none { level.isMainBossGround(it, Populator.ARENA_APPROACH_TILES) }
    }

    private fun landingAllowed(enemy: LiveEnemy, columns: IntRange): Boolean {
        val here = TileMap.toTile(enemy.position.x)..TileMap.toTile(enemy.position.x + LiveEnemy.BODY_SIZE - EDGE)
        val alreadyInside = here.any { level.isMainBossGround(it, Populator.ARENA_APPROACH_TILES) }
        return alreadyInside || columns.none { level.isMainBossGround(it, Populator.ARENA_APPROACH_TILES) }
    }

    private fun fly(enemy: LiveEnemy, offset: Vec2, speed: Double) {
        val direction = offset.normalisedOr(Vec2.Right)
        val step = direction * (speed * TICK_SECONDS)
        // The whole body stays out, not just its centre: a pod could otherwise hang half a tile
        // over the gap it is forbidden to enter.
        val nextX = enemy.position.x + step.x
        val leading = TileMap.toTile(nextX)
        val trailing = TileMap.toTile(nextX + LiveEnemy.BODY_SIZE - EDGE)
        val blocked = entersMainBossGround(enemy, if (step.x > 0) trailing else leading)
        val horizontal = if (blocked) 0.0 else step.x
        enemy.position += Vec2(horizontal, step.y)
        enemy.stridePx += kotlin.math.abs(horizontal)
    }

    /** Gravity for walkers. Landing on a lethal tile kills; landing on solid ground stops the fall. */
    private fun fall(enemy: LiveEnemy) {
        val physics = Physics.Default
        val feetRow = TileMap.toTile(enemy.position.y + LiveEnemy.FEET_OFFSET)
        val left = TileMap.toTile(enemy.position.x)
        val right = TileMap.toTile(enemy.position.x + LiveEnemy.BODY_SIZE - 1.0)
        val supported = enemy.vy == 0.0 &&
            (level.tiles.blocksMovement(left, feetRow) || level.tiles.blocksMovement(right, feetRow))
        if (supported) return

        enemy.vy = (enemy.vy + physics.gravity * TICK_SECONDS).coerceAtMost(physics.terminalVelocity)
        var travel = enemy.vy * TICK_SECONDS
        while (travel > 0.0) {
            val slice = minOf(travel, TILE_SIZE / 2.0)
            travel -= slice
            val nextY = enemy.position.y + slice
            val row = TileMap.toTile(nextY + LiveEnemy.FEET_OFFSET)
            if (level.tiles.isLethal(left, row) || level.tiles.isLethal(right, row)) {
                enemy.health = 0.0
                enemy.position = Vec2(enemy.position.x, nextY)
                return
            }
            if (level.tiles.blocksMovement(left, row) || level.tiles.blocksMovement(right, row)) {
                enemy.position = Vec2(enemy.position.x, TileMap.toWorld(row) - LiveEnemy.FEET_OFFSET)
                enemy.vy = 0.0
                return
            }
            enemy.position = Vec2(enemy.position.x, nextY)
        }
    }

    /** Moves one committed leap in substeps no longer than half a tile. */
    private fun advanceLeap(enemy: LiveEnemy) {
        val leap = enemy.leap ?: return
        enemy.vy = (enemy.vy + Physics.Default.gravity * TICK_SECONDS)
            .coerceAtMost(Physics.Default.terminalVelocity)
        val travel = Vec2(leap.direction * EnemyLeap.VX * TICK_SECONDS, enemy.vy * TICK_SECONDS)
        val pieces = maxOf(
            1,
            kotlin.math.ceil(maxOf(kotlin.math.abs(travel.x), kotlin.math.abs(travel.y)) / (TILE_SIZE / 2.0)).toInt(),
        )
        repeat(pieces) {
            val next = enemy.position + travel * (1.0 / pieces)
            if (enemyDangerAt(next)) {
                enemy.health = 0.0
                enemy.position = next
                return
            }
            if (bodyBlocked(next, LiveEnemy.BODY_SIZE, LiveEnemy.BODY_SIZE)) {
                if (enemy.vy > 0.0 && land(enemy, next)) return
                enemy.vy = 0.0
                enemy.leap = null
                return
            }
            enemy.position = next
            enemy.stridePx += kotlin.math.abs(travel.x / pieces)
        }
    }

    private fun land(enemy: LiveEnemy, at: Vec2): Boolean {
        val row = safeFloorRow(at) ?: return false
        enemy.position = Vec2(at.x, TileMap.toWorld(row) - LiveEnemy.FEET_OFFSET)
        enemy.vy = 0.0
        enemy.leap = null
        enemy.landingCooldownLeft = EnemyLeap.LANDING_COOLDOWN
        return true
    }

    /** The shared non-lethal support rule for voluntary steps and pursuit-leap landings. */
    private fun safeFloorRow(at: Vec2): Int? {
        val row = TileMap.toTile(at.y + LiveEnemy.FEET_OFFSET)
        val left = TileMap.toTile(at.x)
        val right = TileMap.toTile(at.x + LiveEnemy.BODY_SIZE - EDGE)
        val supported = level.tiles.blocksMovement(left, row) && level.tiles.blocksMovement(right, row)
        val lethal = level.tiles.isLethal(left, row) || level.tiles.isLethal(right, row)
        return row.takeIf { supported && !lethal }
    }

    private fun bodyBlocked(at: Vec2, width: Double, height: Double): Boolean {
        val left = TileMap.toTile(at.x)
        val right = TileMap.toTile(at.x + width - EDGE)
        val top = TileMap.toTile(at.y)
        val bottom = TileMap.toTile(at.y + height - EDGE)
        return (left..right).any { column -> (top..bottom).any { row -> level.tiles.blocksMovement(column, row) } }
    }

    private fun enemyDangerAt(at: Vec2): Boolean {
        val left = TileMap.toTile(at.x)
        val right = TileMap.toTile(at.x + LiveEnemy.BODY_SIZE - EDGE)
        val top = TileMap.toTile(at.y)
        val bottom = TileMap.toTile(at.y + LiveEnemy.BODY_SIZE - EDGE)
        return (left..right).any { column -> (top..bottom).any { row -> level.tiles.isLethal(column, row) } } ||
            Hazards.overlapped(level, at.x, at.y, LiveEnemy.BODY_SIZE, LiveEnemy.BODY_SIZE).isNotEmpty() ||
            jetOverlap(at, LiveEnemy.BODY_SIZE, LiveEnemy.BODY_SIZE)
    }

    private fun jetOverlap(at: Vec2, width: Double, height: Double): Boolean {
        val left = TileMap.toTile(at.x)
        val right = TileMap.toTile(at.x + width - EDGE)
        val top = TileMap.toTile(at.y)
        val bottom = TileMap.toTile(at.y + height - EDGE)
        val time = elapsedTicks * TICK_SECONDS
        return level.jets.any { jet ->
            jet.column in left..right && (top..bottom).any(jet::coversRow) && jet.isOnAt(time)
        }
    }

    private fun advanceBosses() {
        val target = BossTarget(
            centre = player.centre(Physics.Default),
            onGround = player.onGround,
            crouched = player.stance == io.github.ksean.cyberslop.physics.Stance.Crouch,
        )
        listOf(miniboss, boss).forEach { live ->
            if (!live.fight.engaged && (target.centre - live.centre).lengthSquared < AWARE_PX * AWARE_PX) {
                live.fight.engage()
            }
            val damage = live.tick(TICK_SECONDS, target)
            live.events.forEach(::emitBossEvent)
            live.hurtSecondsLeft = (live.hurtSecondsLeft - TICK_SECONDS).coerceAtLeast(0.0)
            if (damage > 0.0 && bossDamageAllowed()) hurt(damage, PlayerDamageSource.Melee)
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
            gainScrap(BOSS_SCRAP)
            openGate()
        }
    }

    private fun emitBossEvent(event: BossAttackEvent) {
        when (event.attack.module) {
            BossModule.Slam, BossModule.Sweep, BossModule.Flurry, BossModule.Rush -> Unit
            BossModule.Bolt -> spawnBossRound(event, speed = 280.0, angle = 0.0)
            BossModule.Burst -> spawnBossRound(event, speed = 300.0, angle = 0.0)
            BossModule.Scatter -> BOSS_SCATTER_ANGLES.forEach { angle ->
                spawnBossRound(event, speed = 320.0, angle = angle)
            }
            BossModule.Laser -> spawnBossBeam(event)
        }
    }

    private fun spawnBossRound(event: BossAttackEvent, speed: Double, angle: Double) {
        if (projectiles.size >= MAX_PROJECTILES) return
        val direction = TrigTable.rotate(event.direction, angle)
        projectiles += LiveProjectile(
            position = event.origin,
            velocity = direction * speed,
            damage = event.attack.damage,
            pierceLeft = 0,
            secondsLeft = bossLevelReach / speed,
            passesTerrain = false,
            fromPlayer = false,
            bossOwned = true,
            bossModule = event.attack.module,
        )
    }

    private fun spawnBossBeam(event: BossAttackEvent) {
        val end = clippedBeamEnd(event.origin, event.direction, bossLevelReach)
        val beam = LiveBossBeam(
            start = event.origin,
            end = end,
            damage = event.attack.damage,
            secondsLeft = event.attack.activeSeconds,
            totalSeconds = event.attack.activeSeconds,
        )
        bossBeams += beam
        hitByBeam(beam)
    }

    private fun advanceBossBeams() {
        bossBeams.forEach { beam ->
            hitByBeam(beam)
            beam.secondsLeft -= TICK_SECONDS
        }
        bossBeams.removeAll { it.secondsLeft <= 0.0 }
    }

    private fun hitByBeam(beam: LiveBossBeam) {
        if (beam.hitPlayer || !beamTouchesPlayer(beam)) return
        beam.hitPlayer = true
        if (bossDamageAllowed()) hurt(beam.damage, PlayerDamageSource.Laser)
    }

    private fun beamTouchesPlayer(beam: LiveBossBeam): Boolean {
        val point = player.centre(Physics.Default)
        val segment = beam.end - beam.start
        val lengthSquared = segment.lengthSquared
        val along = if (lengthSquared <= Vec2.EPSILON) 0.0 else {
            val offset = point - beam.start
            ((offset.x * segment.x + offset.y * segment.y) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val nearest = beam.start + segment * along
        val radius = BOSS_BEAM_HALF_WIDTH + minOf(Physics.Default.width, player.height(Physics.Default)) / 2.0
        return (point - nearest).lengthSquared <= radius * radius
    }

    private fun clippedBeamEnd(origin: Vec2, direction: Vec2, reach: Double): Vec2 {
        val pieces = kotlin.math.ceil(reach / MAX_PROJECTILE_STEP).toInt()
        var end = origin
        repeat(pieces) {
            val next = end + direction * minOf(MAX_PROJECTILE_STEP, reach - (end - origin).length)
            if (outsideLevel(next) || blocked(next)) return end
            end = next
        }
        return end
    }

    /** Longer than the diagonal, so terrain or a level edge always resolves boss fire first. */
    private val bossLevelReach: Double
        get() = kotlin.math.hypot(level.tiles.widthPx, level.tiles.heightPx) + TILE_SIZE

    private fun outsideLevel(point: Vec2): Boolean =
        point.x < 0.0 || point.x >= level.tiles.widthPx ||
            point.y < 0.0 || point.y >= level.tiles.heightPx

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
        // One item, weapon and powerup together: collected as a pair, weapon first, so the
        // powerup is never wiped by the weapon it was awarded with (PROD-070).
        // Rolled whether or not an override replaces them, so the loot stream is the same either way.
        val rolled = DropTable.rollWeapon(rng, level.mapIndex, floor, shifts, unlockedWeapons)
        val pairedRoll = if (powerup) DropTable.rollPowerup(rng, level.mapIndex, runPool, floor = powerupFloor) else null
        val (weapon, paired) = awardOverride?.invoke(rolled, pairedRoll) ?: (rolled to pairedRoll)
        items.add(
            GroundItem.equipment(
                position = deathDropPlacement.place(at, paired = paired != null),
                weapon = weapon,
                powerup = paired,
                guaranteed = true,
            ),
        )
    }

    /**
     * Harness hook: what a guaranteed award becomes, given what was rolled. The loot floor's
     * reference player takes every award at its weakest outcome; the pressure harness sets this so
     * an award is the floor's before it can be collected — which can be the tick it drops.
     */
    internal var awardOverride: ((WeaponSpec, Powerup?) -> Pair<WeaponSpec, Powerup?>)? = null


    private fun onKilled(enemy: LiveEnemy) {
        gainScrap(SCRAP_PER_KILL)
        if (autoFire.weapon.killRefundChance > 0.0 &&
            rng.nextDouble() < autoFire.weapon.killRefundChance
        ) {
            autoFire.clearCooldown()
        }
        if (ramenRng.nextInt(RAMEN_DROP_DENOMINATOR) == 0 && optionalLoot) {
            items += GroundItem.ramen(deathDropPlacement.placeGrounded(enemy.centre))
        }
        if (rng.nextDouble() > DropTable.killDropChance(level.mapIndex)) return
        // Rolled whether or not it is kept: the loot stream also feeds crits and stuns, and a
        // guaranteed-only run has to be the same fight with the loot merely withheld.
        val at = deathDropPlacement.place(enemy.centre, paired = false)
        val drop = if (rng.nextDouble() < DropTable.weaponShare()) {
            GroundItem.equipment(
                position = at,
                weapon = DropTable.rollWeapon(rng, level.mapIndex, unlocked = unlockedWeapons),
            )
        } else {
            GroundItem.equipment(
                position = at,
                powerup = DropTable.rollPowerup(rng, level.mapIndex, runPool),
            )
        }
        if (optionalLoot) items.add(drop)
    }

    private fun collectItems(): List<DiscoveryId> {
        val reach = DeathDropPlacement.PICKUP_REACH
        val centre = player.centre(Physics.Default)
        val taken = items.filter { it.inReachOf(centre, reach) }
        val collected = mutableListOf<DiscoveryId>()
        taken.forEach { item ->
            when (val payload = item.payload) {
                is GroundItem.Equipment -> {
                    // Weapon first, then powerup (PROD-070): a paired award is one item, so its
                    // powerup resolves against the new or preserved matching-weapon build.
                    payload.weapon?.let { weapon ->
                        val (next, outcome) = run.loadout.collect(weapon)
                        run = run.copy(loadout = next)
                        gainScrap(outcome.scrap)
                        collected += DiscoveryId.Weapon(weapon.id)
                    }
                    payload.powerup?.let { powerup ->
                        val (next, outcome) = run.loadout.collect(
                            powerup.id,
                            level.mapIndex,
                            payload.guaranteed,
                        )
                        run = run.copy(loadout = next)
                        // Both losing outcomes pay out: the pickup that lost, or the slot displaced.
                        val scrap = when (outcome) {
                            is Pickup.Scrapped -> outcome.scrap
                            is Pickup.Displaced -> outcome.scrap
                            is Pickup.Applied -> 0
                        }
                        gainScrap(scrap)
                        collected += DiscoveryId.Powerup(powerup.id)
                    }
                }
                GroundItem.Ramen -> {
                    run = run.healed(run.maxHealth * RAMEN_HEAL_FRACTION)
                    playerHealSecondsLeft = HEAL_FLASH_SECONDS
                }
            }
            emittedAudioCues += AudioCue.PickupPulse
            autoFire.rebuild(run.loadout.weapon, run.loadout.slots)
        }
        items.removeAll(taken)
        return collected
    }

    private fun advanceScrapGains() {
        liveScrapGains.indices.forEach { index ->
            val gain = liveScrapGains[index]
            liveScrapGains[index] = gain.copy(
                previousSecondsLeft = gain.secondsLeft,
                secondsLeft = (gain.secondsLeft - TICK_SECONDS).coerceAtLeast(0.0),
            )
        }
        liveScrapGains.removeAll { it.secondsLeft <= 0.0 }
    }

    /** The only active-gameplay boundary which changes Scrap (PROD-086). */
    private fun gainScrap(amount: Int) {
        if (amount <= 0) return
        run = run.copy(scrap = run.scrap + amount)
        val origin = Vec2(player.centre(Physics.Default).x, player.y - SCRAP_GAIN_HEAD_GAP)
        val previous = liveScrapGains.lastOrNull()
        if (previous?.bornTick == elapsedTicks) {
            liveScrapGains[liveScrapGains.lastIndex] = previous.copy(
                amount = previous.amount + amount,
                origin = origin,
            )
        } else {
            liveScrapGains += ScrapGain(
                amount = amount,
                origin = origin,
                previousSecondsLeft = SCRAP_GAIN_SECONDS,
                secondsLeft = SCRAP_GAIN_SECONDS,
                bornTick = elapsedTicks,
            )
        }
    }

    /** Harness hook: hold exactly this loadout, as the loot-floor model assumes. */
    internal fun holdLoadout(loadout: Loadout) {
        run = run.copy(loadout = loadout)
        autoFire.rebuild(loadout.weapon, loadout.slots)
    }

    // ---- helpers ------------------------------------------------------------------------------

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
        val spikeRate = Hazards.spikeRatePerSecond(
            level, player.x, player.y, Physics.Default.width, player.height(Physics.Default),
        )
        if (spikeRate > 0.0) {
            hurt(
                spikeRate * Balance.contactDamage(level.mapIndex) * TICK_SECONDS,
                PlayerDamageSource.Spike,
            )
        }
        val glassRate = Hazards.glassRatePerSecond(
            level, player.x, player.y, Physics.Default.width, player.height(Physics.Default),
        )
        if (glassRate > 0.0) {
            hurt(
                glassRate * Balance.contactDamage(level.mapIndex) * TICK_SECONDS,
                PlayerDamageSource.Glass,
            )
        }
        val fireRate = Hazards.fireRatePerSecond(
            level, player.x, player.y, Physics.Default.width, player.height(Physics.Default),
        )
        if (fireRate > 0.0) {
            hurt(
                fireRate * Balance.contactDamage(level.mapIndex) * TICK_SECONDS,
                PlayerDamageSource.Fire,
            )
        }
    }

    /** Every living enemy body drains by overlap like a hazard (`specs/enemies.md`, PROD-069). */
    private fun drainContact() {
        val normalBodies = if (enemyDamageAllowed()) enemies.count { it.alive && overlapsPlayer(it) } else 0
        val bossBodies = if (bossDamageAllowed()) {
            (if (!miniboss.fight.defeated && overlapsPlayer(miniboss)) 1 else 0) +
                (if (!boss.fight.defeated && overlapsPlayer(boss)) 1 else 0)
        } else {
            0
        }
        val contactRate = (normalBodies + bossBodies * EnemyAttacks.BOSS_CONTACT_MULTIPLIER) *
            EnemyAttacks.CONTACT_DRAIN
        if (contactRate > 0.0) {
            hurt(
                contactRate * Balance.contactDamage(level.mapIndex) * TICK_SECONDS,
                PlayerDamageSource.Contact,
            )
        }
    }

    private fun overlapsPlayer(enemy: LiveEnemy): Boolean {
        val width = Physics.Default.width
        val height = player.height(Physics.Default)
        return enemy.position.x < player.x + width && enemy.position.x + LiveEnemy.BODY_SIZE > player.x &&
            enemy.position.y < player.y + height && enemy.position.y + LiveEnemy.BODY_SIZE > player.y
    }

    private fun overlapsPlayer(boss: LiveBoss): Boolean {
        val width = Physics.Default.width
        val height = player.height(Physics.Default)
        return boss.position.x - boss.halfWidth < player.x + width &&
            boss.position.x + boss.halfWidth > player.x &&
            boss.position.y - boss.height < player.y + height && boss.position.y > player.y
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
        private val COMBAT_TARGET_ORDER =
            compareBy<CombatTargetId> { it.kind.ordinal }.thenBy { it.index }
        const val STARTER_CACHE_TILES = 6
        const val MAX_PROJECTILES = 300
        const val MAX_PIERCE = 8
        const val DEFAULT_PROJECTILE_SPEED = 520.0
        const val DEFAULT_LIFETIME = 2.0
        const val PROJECTILE_RADIUS = 7.0
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
        const val SCRAP_GAIN_SECONDS = 0.90
        const val SCRAP_GAIN_RISE_PX = 20.0
        const val SCRAP_GAIN_HEAD_GAP = 6.0
        /** A static cache draws twice for its tier and keeps the better roll (`specs/combat.md`). */
        const val CACHE_TIER_SHIFTS = 1
        const val BLAST_RADIUS = 36.0
        const val BURN_SECONDS = 3.0
        const val SLOW_SECONDS = 2.0
        const val STUN_SECONDS = 0.5
        const val LIFESTEAL_CAP = 4.0
        const val LIFESTEAL_PER_SECOND = 12.0
        /** What a projectile keeps of its damage at each terrain bounce. */
        const val BOUNCE_DAMAGE = 0.85
        private const val BURST_EPSILON = 1e-9
        private const val VISUAL_EPSILON = 1e-9
        /** A projectile step is walked in pieces no longer than this, so no shot crosses a tile unseen. */
        const val MAX_PROJECTILE_STEP = TILE_SIZE / 2.0
        const val BOSS_BEAM_HALF_WIDTH = 5.0
        val BOSS_SCATTER_ANGLES = doubleArrayOf(-15.0, -7.5, 0.0, 7.5, 15.0)
        const val FULL_CIRCLE = 360.0
        const val SWING_VISIBLE_SECONDS = 0.16
        const val FLASH_VISIBLE_SECONDS = 0.10

        /** How long a hit enemy or boss is drawn red (PROD-076). */
        const val HURT_FLASH_SECONDS = 0.12
        const val HEAL_FLASH_SECONDS = 0.12
        const val RAMEN_HEAL_FRACTION = 0.05
        private const val RAMEN_DROP_DENOMINATOR = 8
        const val EXIT_CLEARANCE = 6
        private const val UPGRADE_DIGEST_TAG = 0x55504752
        private const val DEATH_SEQUENCE_DIGEST_TAG = 0x44454144
        private const val ACTIVE_SWING_DIGEST_TAG = 0x5357494E
    }
}
