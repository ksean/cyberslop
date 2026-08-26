package io.github.ksean.cyberslop.entity

/** How an attack is avoided. Every attack must be avoidable with the four movement keys. */
enum class Dodge { Jump, Crouch, MoveAside }

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
 * The gate closes when the player crosses a **commit line** inside the arena, and the boss cannot be
 * damaged before it does. Making the lock trigger on entry, or on first damage, both fail the same
 * way: weapons fire automatically and can be aimed by an accessibility setting, so the player can
 * land a hit without ever choosing to fight. Crossing a line is unambiguously the player's act.
 */
class BossFight(private val spec: BossSpec, private val commitColumn: Int) {
    var health: Double = spec.maxHealth
        private set
    var committed: Boolean = false
        private set

    val gateClosed: Boolean get() = committed && !defeated
    val defeated: Boolean get() = health <= 0.0
    val exitOpen: Boolean get() = defeated

    val vulnerable: Boolean get() = committed

    fun playerMoved(column: Int) {
        if (column >= commitColumn) committed = true
    }

    /** Damage is refused entirely before the player commits. */
    fun damage(amount: Double): Boolean {
        if (!vulnerable || defeated) return false
        health = (health - amount).coerceAtLeast(0.0)
        return true
    }

    fun currentPhase(): BossPhase = spec.phaseAt(health / spec.maxHealth)
}
