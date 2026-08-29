package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossProfile
import io.github.ksean.cyberslop.gen.DifficultyCurve

/** What kind of body an enemy has. This, not colour, is what tells two archetypes apart. */
enum class EnemyForm {
    /** Legs and a gait. */
    Biped,

    /** No legs; thruster plumes and a hover bob. */
    Hover,

    /** A cannon pod on articulated legs, folded before engagement and mobile afterwards. */
    Crawler,
}

/** A colour-independent piece of hardware which advertises one boss attack module. */
enum class BossMarker {
    WeightedForearm,
    LongBlade,
    PairedBlades,
    RamPlate,
    NarrowBarrel,
    BurstMagazine,
    ScatterPorts,
    LaserLens,
}

/** The mount is part of the silhouette: primaries and a folded signature cannot look identical. */
enum class BossMount { LeadArm, RearShoulder, HighBack }

data class BossHardware(
    val module: BossModule,
    val marker: BossMarker,
    val mount: BossMount,
    val folded: Boolean,
)

data class BossLook(val body: EnemyLook, val hardware: List<BossHardware>)

/** Composes the body shared by a boss rank with every module assigned to that encounter. */
object BossLooks {
    fun of(profile: BossProfile, mapIndex: Int, isMain: Boolean): BossLook = BossLook(
        body = EnemyLooks.boss(mapIndex, isMain),
        hardware = buildList {
            add(BossHardware(profile.primaryMelee, markerOf(profile.primaryMelee), BossMount.LeadArm, folded = false))
            add(BossHardware(profile.primaryRanged, markerOf(profile.primaryRanged), BossMount.RearShoulder, folded = false))
            profile.signature?.let { add(BossHardware(it, markerOf(it), BossMount.HighBack, folded = true)) }
        },
    )

    fun markerOf(module: BossModule): BossMarker = when (module) {
        BossModule.Slam -> BossMarker.WeightedForearm
        BossModule.Sweep -> BossMarker.LongBlade
        BossModule.Flurry -> BossMarker.PairedBlades
        BossModule.Rush -> BossMarker.RamPlate
        BossModule.Bolt -> BossMarker.NarrowBarrel
        BossModule.Burst -> BossMarker.BurstMagazine
        BossModule.Scatter -> BossMarker.ScatterPorts
        BossModule.Laser -> BossMarker.LaserLens
    }
}

/**
 * How an enemy is drawn (PROD-042).
 *
 * Two independent axes, and keeping them independent is the whole design. **Shape** — [form],
 * [headScale], [heightScale], [strideRate], [armed] — belongs to the archetype and never moves.
 * **Menace** — [bulk], [plates], [spikes], [glowTone] — is a function of the health the enemy
 * actually carries and nothing else, which is what makes it monotone across the whole grid rather
 * than only within one archetype.
 *
 * [glowTone] is an index into a bounded set of palette tones rather than a colour of its own. A
 * continuous luminance would give nearly every enemy its own draw batch, which is exactly what
 * ENG-061 forbids.
 */
data class EnemyLook(
    val form: EnemyForm,
    val height: Double,
    val heightScale: Double,
    val headScale: Double,
    val strideRate: Double,
    val armed: Boolean,
    val bulk: Double,
    val plates: Int,
    val spikes: Int,
    val glowTone: Int,
    /** Zero for anything that is not a boss. */
    val crown: Int = 0,
) {
    /**
     * How thick the figure is actually drawn, which is [height] and [bulk] together.
     *
     * `Scene.figure` derives every limb and torso width from `pose.height * bulk`, and a pose's
     * height *is* this look's height — so this is the quantity a player sees, and it is the one
     * PROD-042's size clause is held to. A review round found the requirement being tested against
     * [bulk] alone, which is monotone while the product is not: a map-4 Swarm carries more health
     * than a map-1 Brute and is drawn at 14.2 against 24.3.
     */
    val drawnScale: Double get() = height * bulk
}

object EnemyLooks {
    fun of(archetype: EnemyArchetype, mapIndex: Int): EnemyLook {
        val shape = shapes.getValue(archetype)
        val menace = menaceOf(archetype, mapIndex)
        return EnemyLook(
            form = shape.form,
            height = TRASH_HEIGHT * shape.heightScale * (1.0 + HEIGHT_MENACE * menace),
            heightScale = shape.heightScale,
            headScale = shape.headScale,
            strideRate = shape.strideRate,
            armed = shape.armed,
            bulk = BULK_BASE + BULK_RANGE * menace,
            plates = (menace * MAX_PLATES).toInt(),
            spikes = (menace * MAX_SPIKES).toInt(),
            glowTone = (menace * Palette.GLOW_TONES).toInt()
                .coerceAtMost(Palette.GLOW_TONES - 1),
        )
    }

    /** A boss reuses the rig at scale, plus a crown of plating nothing else carries. */
    fun boss(mapIndex: Int, isMain: Boolean): EnemyLook {
        val scale = if (isMain) BOSS_SCALE else MINIBOSS_SCALE
        val depth = (mapIndex - 1).toDouble() / (DifficultyCurve.MAPS - 1)
        return EnemyLook(
            form = EnemyForm.Biped,
            height = TRASH_HEIGHT * scale * (1.0 + HEIGHT_MENACE * depth),
            heightScale = scale,
            headScale = BOSS_HEAD,
            strideRate = BOSS_STRIDE,
            armed = true,
            bulk = BULK_BASE + BULK_RANGE,
            plates = MAX_PLATES,
            spikes = MAX_SPIKES,
            glowTone = Palette.GLOW_TONES - 1,
            crown = if (isMain) MAIN_CROWN else MINI_CROWN,
        )
    }

    /**
     * Where an enemy sits in the run's whole population, ordered by the health it carries.
     *
     * A **rank** rather than a ratio, deliberately. Health spans roughly 7 to 1,900 across the grid,
     * so any direct scaling either flattens the early maps to nothing or saturates the late ones —
     * and the obvious fix, a logarithm, is a transcendental this project keeps out of shared code
     * (ENG-054). A rank needs none, and is monotone in health by construction rather than by
     * argument.
     */
    fun menaceOf(archetype: EnemyArchetype, mapIndex: Int): Double =
        ranks.getValue(archetype to mapIndex)

    private class Shape(
        val form: EnemyForm,
        val heightScale: Double,
        val headScale: Double,
        val strideRate: Double,
        val armed: Boolean,
    )

    /**
     * Height scales are ordered by the archetype's own health multiplier — 0.6, 0.7, 0.8, 1.5, 2.2 —
     * so that on any one map a tougher enemy is never drawn smaller. They were not, and a review
     * round found a Turret drawn shorter than a Shooter while carrying nearly twice its health.
     */
    private val shapes = mapOf(
        // Small, hunched, oversized head, twitchy. Reads as something that arrives in numbers.
        EnemyArchetype.Swarm to Shape(EnemyForm.Biped, 0.72, 1.60, 1.9, armed = false),
        // A legless pod. Nothing else in the game has no ground contact.
        EnemyArchetype.Flyer to Shape(EnemyForm.Hover, 0.80, 1.20, 0.0, armed = false),
        // Upright and deliberate, with one long weapon arm.
        EnemyArchetype.Shooter to Shape(EnemyForm.Biped, 0.88, 1.00, 1.0, armed = true),
        // A wide cannon pod on short articulated legs; it folds until the player is noticed.
        EnemyArchetype.Turret to Shape(EnemyForm.Crawler, 1.02, 1.35, 0.45, armed = true),
        // The broadest thing on the map, with a small sunken head and a slow heavy gait.
        EnemyArchetype.Brute to Shape(EnemyForm.Biped, 1.28, 0.70, 0.55, armed = false),
    )

    private val ranks: Map<Pair<EnemyArchetype, Int>, Double> = buildMap {
        val ordered = EnemyArchetype.entries
            .flatMap { archetype -> (1..DifficultyCurve.MAPS).map { archetype to it } }
            .sortedBy { (archetype, mapIndex) -> archetype.healthOn(mapIndex) }
        ordered.forEachIndexed { index, key ->
            put(key, index.toDouble() / (ordered.size - 1))
        }
    }

    /**
     * The height of a mid-grid trash enemy, before its archetype and its menace scale it.
     *
     * Tuned against rendered frames. At 21 px a map-nine Brute stood half again as tall as the
     * 26 px player and read as a boss; at 18 it tops the player by a head, which is what "the
     * broadest thing on the map" should look like next to them.
     */
    private const val TRASH_HEIGHT = 18.0
    private const val HEIGHT_MENACE = 0.25
    private const val BULK_BASE = 0.85
    private const val BULK_RANGE = 0.75
    private const val MAX_PLATES = 4
    private const val MAX_SPIKES = 3
    private const val MINIBOSS_SCALE = 2.6
    private const val BOSS_SCALE = 3.7
    private const val BOSS_HEAD = 0.85
    private const val BOSS_STRIDE = 0.7
    private const val MINI_CROWN = 2
    private const val MAIN_CROWN = 4
}
