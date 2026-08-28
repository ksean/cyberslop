package io.github.ksean.cyberslop.entity

/** How an attack is avoided. Every attack must be avoidable with the four movement keys. */
enum class Dodge { Jump, Crouch, MoveAside }

/**
 * How an attack's active window is drawn (`specs/enemies.md`, "Drawn as"). Every telegraph is the
 * same wind-up pose; this is what follows it.
 */
enum class AttackVisual {
    /** A downward swing and a ground swoosh. */
    GroundSlam,
    /** A level swing and swoosh. */
    LevelSweep,
    /** A muzzle flash and a fan of projectile dots. */
    MuzzleFan,
    /** A lunge with a trailing swoosh. */
    Lunge,
    ;

    /** Whether the window is a shot rather than a swing. */
    val ranged: Boolean get() = this == MuzzleFan
}

/**
 * One boss attack.
 *
 * [telegraphSeconds] is not decoration: [damageAt] returns nothing until it has elapsed, so the
 * telegraph is behaviour rather than metadata. A test that only read the field would pass for an
 * attack that struck instantly.
 */
data class BossAttack(
    val name: String,
    val telegraphSeconds: Double,
    val activeSeconds: Double,
    val damage: Double,
    val dodge: Dodge,
    /** How far from the boss's feet the attack can reach a player who is not dodging it. */
    val reachPx: Double,
    val visual: AttackVisual,
) {
    init {
        require(telegraphSeconds >= MIN_TELEGRAPH_SECONDS) {
            "$name telegraphs for only $telegraphSeconds s"
        }
    }

    fun damageAt(elapsedSeconds: Double): Double = when {
        elapsedSeconds < telegraphSeconds -> 0.0
        elapsedSeconds <= telegraphSeconds + activeSeconds -> damage
        else -> 0.0
    }

    val totalSeconds: Double get() = telegraphSeconds + activeSeconds

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
