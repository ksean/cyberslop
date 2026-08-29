package io.github.ksean.cyberslop.entity

/** How an attack is avoided. Every attack must be avoidable with the four movement keys. */
enum class Dodge { Jump, Crouch, MoveAside }

enum class BossAttackKind { Melee, Ranged }

/**
 * How an attack's active window is drawn (`specs/enemies.md`, "Drawn as"). Every telegraph is the
 * same wind-up pose; this is what follows it.
 */
enum class AttackVisual(val ranged: Boolean) {
    /** A downward swing and a ground swoosh. */
    GroundSlam(false),
    /** A level swing and swoosh. */
    LevelSweep(false),
    /** Several separately timed level swings. */
    RapidSweep(false),
    /** One narrow muzzle flash. */
    MuzzleBolt(true),
    /** Several flashes along one locked line. */
    MuzzleBurst(true),
    /** A muzzle flash and a fan of projectile dots. */
    MuzzleFan(true),
    /** A charging lens followed by a core-and-bloom beam. */
    LaserBeam(true),
    /** A lunge with a trailing swoosh. */
    Lunge(false),
}

/** The typed module which drives mechanics and the boss's visible hardware. */
enum class BossModule(
    val kind: BossAttackKind,
    val visual: AttackVisual,
    val dodge: Dodge,
    val displayName: String,
) {
    Slam(BossAttackKind.Melee, AttackVisual.GroundSlam, Dodge.Jump, "Slam"),
    Sweep(BossAttackKind.Melee, AttackVisual.LevelSweep, Dodge.Crouch, "Sweep"),
    Flurry(BossAttackKind.Melee, AttackVisual.RapidSweep, Dodge.Crouch, "Flurry"),
    Rush(BossAttackKind.Melee, AttackVisual.Lunge, Dodge.Jump, "Rush"),
    Bolt(BossAttackKind.Ranged, AttackVisual.MuzzleBolt, Dodge.MoveAside, "Bolt"),
    Burst(BossAttackKind.Ranged, AttackVisual.MuzzleBurst, Dodge.MoveAside, "Burst"),
    Scatter(BossAttackKind.Ranged, AttackVisual.MuzzleFan, Dodge.MoveAside, "Scatter"),
    Laser(BossAttackKind.Ranged, AttackVisual.LaserBeam, Dodge.MoveAside, "Laser"),
}

data class BossProfile(
    val primaryMelee: BossModule,
    val primaryRanged: BossModule,
    val signature: BossModule? = null,
) {
    init {
        require(primaryMelee.kind == BossAttackKind.Melee)
        require(primaryRanged.kind == BossAttackKind.Ranged)
        require(signature == null || signature !in primaryPair)
    }

    val primaryPair: List<BossModule> get() = listOf(primaryMelee, primaryRanged)
    val modules: List<BossModule> get() = primaryPair + listOfNotNull(signature)
}

/**
 * One boss attack.
 *
 * [telegraphSeconds] is not decoration: [damageAt] returns nothing until it has elapsed, so the
 * telegraph is behaviour rather than metadata. A test that only read the field would pass for an
 * attack that struck instantly.
 */
data class BossAttack(
    val module: BossModule,
    val name: String,
    val telegraphSeconds: Double,
    val activeSeconds: Double,
    val damage: Double,
    val dodge: Dodge,
    /** How far from the boss's feet the attack can reach a player who is not dodging it. */
    val reachPx: Double,
    val visual: AttackVisual,
    /** Seconds into the active window at which separate damage/projectile events occur. */
    val eventOffsets: List<Double> = listOf(0.0),
) {
    init {
        require(telegraphSeconds >= MIN_TELEGRAPH_SECONDS) {
            "$name telegraphs for only $telegraphSeconds s"
        }
        require(eventOffsets.isNotEmpty() && eventOffsets.all { it in 0.0..activeSeconds })
    }

    fun damageAt(elapsedSeconds: Double): Double = when {
        elapsedSeconds < telegraphSeconds -> 0.0
        elapsedSeconds <= telegraphSeconds + activeSeconds -> damage
        else -> 0.0
    }

    val totalSeconds: Double get() = telegraphSeconds + activeSeconds
    val kind: BossAttackKind get() = module.kind

    /** Event indices crossed by one newly elapsed slice. */
    fun eventsBetween(before: Double, after: Double): List<Int> = eventOffsets.indices.filter { index ->
        val at = telegraphSeconds + eventOffsets[index]
        before < at && after >= at
    }

    companion object {
        /** The fairness floor. It never scales with difficulty. */
        const val MIN_TELEGRAPH_SECONDS = 0.4
    }
}

/** A phase begins when the boss drops to [fromHealthFraction] of its maximum. */
data class BossPhase(val fromHealthFraction: Double, val attacks: List<BossAttack>)

data class BossSpec(
    val name: String,
    val maxHealth: Double,
    val contactDamage: Double,
    val phases: List<BossPhase>,
    val profile: BossProfile,
) {
    fun phaseAt(healthFraction: Double): BossPhase =
        phases.last { healthFraction <= it.fromHealthFraction }
}

/**
 * The boss encounter.
 *
 * Inert and invulnerable until the player comes within the awareness radius; from then on it
 * fights wherever the player stands (`specs/enemies.md`, Activation). Nothing the player or their
 * automatic weapon does can seal an arena: the exit gate is carved with the map and only the
 * boss's death clears it.
 */
class BossFight(private val spec: BossSpec) {
    var health: Double = spec.maxHealth
        private set
    var engaged: Boolean = false
        private set

    val defeated: Boolean get() = health <= 0.0
    val exitOpen: Boolean get() = defeated
    val vulnerable: Boolean get() = engaged

    /** Once noticed, never forgotten. */
    fun engage() {
        engaged = true
    }

    /** Damage is refused entirely before the boss has noticed the player. */
    fun damage(amount: Double): Boolean {
        if (!vulnerable || defeated) return false
        health = (health - amount).coerceAtLeast(0.0)
        return true
    }

    fun currentPhase(): BossPhase = spec.phaseAt(health / spec.maxHealth)
}
