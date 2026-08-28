package io.github.ksean.cyberslop.combat

enum class WeaponClass { Melee, Ranged, Psychic }

/** Rarity, and nothing else: drop weight and Scrap value. A weapon on the ground is always taken (PROD-070). */
enum class Tier { Street, Scav, Chromed, Blacksite, Ascended }

/** Where a pattern originates. Psychic weapons anchor to the cursor; most others to the player. */
enum class Anchor { Self, Cursor }

sealed interface FirePattern {
    data class ArcSwing(val arcDegrees: Double, val lingerSeconds: Double) : FirePattern
    data class Projectile(val gravity: Double, val lifetimeSeconds: Double) : FirePattern
    data class Blast(val radius: Double, val falloff: Double) : FirePattern
    data class Chain(val jumps: Int, val jumpRange: Double, val decay: Double) : FirePattern
    data class Orbit(val radius: Double, val revolutionsPerMinute: Double) : FirePattern
    data class Beam(val widthPx: Double, val tickHz: Double) : FirePattern
    data class Strike(val delaySeconds: Double, val radius: Double) : FirePattern
    data class Pull(val force: Double, val radius: Double) : FirePattern
}

/** Damage falloff with distance. */
sealed interface Falloff {
    data object None : Falloff
    data class Linear(val startPx: Double, val endPx: Double, val minimum: Double) : Falloff
}

sealed interface Homing {
    data object None : Homing
    data class Seek(val turnDegreesPerSecond: Double, val radiusPx: Double) : Homing
}

/** Something a hit does beyond damage. */
sealed interface HitEffect {
    data class Bleed(val perSecond: Double, val seconds: Double) : HitEffect
    data class Ignite(val fractionPerSecond: Double, val seconds: Double) : HitEffect
    data class Stun(val chance: Double, val seconds: Double) : HitEffect
    data class Slow(val fraction: Double, val seconds: Double) : HitEffect
    data class Execute(val healthFraction: Double) : HitEffect
    data class Shock(val extraTargets: Int) : HitEffect
    data class BlastOnHit(val radius: Double, val damageFraction: Double) : HitEffect
}

/** Something firing does to the weapon's owner's *attack*, never to their position (ENG-051). */
sealed interface FireEffect {
    data class DashStrike(val reachPx: Double, val invulnerableSeconds: Double) : FireEffect
    data class FreeRecast(val chance: Double) : FireEffect
}

/**
 * One weapon.
 *
 * Every field the registry needs is here rather than described in a comment: an earlier schema could
 * not express bleed, stun, execute thresholds, wind-up or falloff, all of which its own table
 * claimed. `anchor` is independent of [cls] because a ranged weapon can strike at the cursor and
 * most psychic weapons do not.
 */
data class WeaponSpec(
    val id: WeaponId,
    val name: String,
    val cls: WeaponClass,
    val tier: Tier,
    val damage: Double,
    val cooldown: Double,
    val rangePx: Double,
    val projectileSpeed: Double,
    val projectileCount: Int = 1,
    val spreadDegrees: Double = 0.0,
    /**
     * Seconds between the rounds of one activation (PROD-075). Positive makes a machine gun: the
     * trigger fires one round and the rest follow in a straight line; zero fires them all at once
     * across [spreadDegrees].
     */
    val burstIntervalSeconds: Double = 0.0,
    val pierce: Int = 0,
    val knockback: Double = 0.0,
    val critChance: Double = DEFAULT_CRIT_CHANCE,
    val anchor: Anchor = Anchor.Self,
    val windUpSeconds: Double = 0.0,
    val falloff: Falloff = Falloff.None,
    val homing: Homing = Homing.None,
    val onHit: List<HitEffect> = emptyList(),
    val onFire: List<FireEffect> = emptyList(),
    val pattern: FirePattern,
) {
    /** Single target, unmodified, crit excluded — the number the tier bands are drawn against. */
    val baseDps: Double get() = damage * projectileCount / cooldown

    companion object {
        const val DEFAULT_CRIT_CHANCE = 0.05
    }
}

enum class WeaponId {
    BrokenBottle, RustlineMachete, CorpoRiotBaton, ChromeFang, StaticLash, GutterjackCleaver,
    KillSwitchKatana, ChromewreckMaul, MeatgrinderHalo,
    ScraplineZipPistol, TenementNailgun, GanglordSmg, RiotbreakerShotgun, VultureRailCarbine,
    AshfallGrenadeLobber, SableCorpRailgun, DebtCollectorMinigun, KesslerOrbitalUplink,
    NeuralSpike, MigraineLoop, WetwareScreamer, GhostwireTether, BlackboxChorus,
    SynapseHemorrhage, NullEgoSingularity, VoiceOfTheDeadNet,
}
