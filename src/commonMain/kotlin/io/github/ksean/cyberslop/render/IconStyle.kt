package io.github.ksean.cyberslop.render

/**
 * The fixed colours a drop is drawn in (PROD-050, PROD-051, PROD-078).
 *
 * Tier and kind colours do not move with the sub-theme: a white ring means a T1 weapon on all ten
 * maps, or the rule teaches a player nothing. The ring is the *drop's*, not the icon's: the icon
 * inside it is drawn in its materials ([Material]), the same on the ground, in the hand and in the
 * HUD, and only the ground adds the ring (`Scene.pickup`).
 *
 * Why the halo is not optional is measured in `specs/presentation.md` — a coloured line alone is
 * worth **2.0** of luminance separation against `ArcologyVault.tileBody`, and a near-black halo alone
 * **0.1** against `ReactorCore.sky`. Drawn as a pair so that at least one line always separates.
 */
object IconStyles {
    const val T1_WEAPON_RING = "#f4f4f4"
    const val T2_WEAPON_RING = "#39d353"
    const val T3_WEAPON_RING = "#ffd45a"
    const val T4_WEAPON_RING = "#b45cff"
    const val T5_WEAPON_RING = "#ff2f2f"

    const val POWERUP_RING = "#3d8bff"

    /** Under every line, wider, so a material is never read against the terrain directly. */
    const val HALO = "#05060a"

    fun ringOf(look: PickupLook): String =
        if (look.weapon) weaponRing(look.tierOrdinal) else POWERUP_RING

    fun weaponRing(tierOrdinal: Int): String = WEAPON_RINGS[tierOrdinal]

    /** A coloured edge outside the ordinary dark ring halo, only for T4 and T5 weapons. */
    fun bloomWidthOf(look: PickupLook, scale: Double): Double? = when {
        !look.weapon -> null
        look.tierOrdinal == T4_ORDINAL -> Scene.strokeWidth(T4_BLOOM_FRACTION * scale)
        look.tierOrdinal == T5_ORDINAL -> Scene.strokeWidth(T5_BLOOM_FRACTION * scale)
        else -> null
    }

    /** The ring's radius as a multiple of the icon's scale (its half-extent in pixels). */
    const val KIND_RING = 1.35

    /**
     * How wide a stroke of this weight is on an icon drawn at [scale], snapped to `Scene`'s ladder.
     *
     * **Proportional to the icon rather than fixed, and the first rendered sheet is what decided
     * it.** Fixed pixel widths were chosen to hold the batch count down — five tiers times three
     * weights looked like fifteen batches where the design budgeted three. Drawn, they were wrong in
     * a way arithmetic did not show: a weight that does not scale makes a Street drop and an
     * Ascended drop two different designs rather than one object at two sizes, and a `Slab` heavy
     * enough to read as a bottle's body at 28 px is more than half the height of the icon at 53 px.
     *
     * The ladder makes the cost far smaller than fifteen anyway. Snapping collapses the five tiers
     * onto at most **four** distinct widths per weight, so the item layers open a constant
     * vocabulary of batches whatever is on screen, and `PickupIconTest` derives the bound from the
     * ladder and counts against it rather than believing it.
     */
    fun widthOf(weight: StrokeWeight, scale: Double): Double =
        Scene.strokeWidth(weight.fraction * scale)

    /**
     * The width of the weathering streak along a stroke of this weight: one weight lighter
     * (`specs/presentation.md`, Materials), or nothing where the ladder does not give the streak a
     * width strictly under its stroke's — a `Hair` at any scale, since the ladder's floor is
     * 1.5 px, and a `Line` on a small held weapon. A streak as wide as its line replaces rather
     * than weathers it (review rounds 1 and 2).
     */
    fun streakWidthOf(weight: StrokeWeight, scale: Double): Double? {
        if (weight == StrokeWeight.Hair) return null
        val streak = widthOf(weight.lighter, scale)
        return if (streak < widthOf(weight, scale)) streak else null
    }

    /**
     * The halo under a stroke of this weight.
     *
     * Per weight rather than one width for the whole icon: a single halo wide enough to edge a
     * `Slab` swallows every `Hair` line on the same icon, which on the first sheet turned a broken
     * bottle's three spikes into one dark smear.
     */
    fun haloWidthOf(weight: StrokeWeight, scale: Double): Double =
        Scene.strokeWidth((weight.fraction + HALO_FRACTION) * scale)

    /** How much wider the halo is than the line it backs, as a fraction of the icon's half-extent. */
    const val HALO_FRACTION = 0.09

    /** Where along a stroke its weathering streak starts and ends, as fractions of its length. */
    const val STREAK_FROM = 0.55
    const val STREAK_TO = 0.95

    private val WEAPON_RINGS = listOf(
        T1_WEAPON_RING,
        T2_WEAPON_RING,
        T3_WEAPON_RING,
        T4_WEAPON_RING,
        T5_WEAPON_RING,
    )
    private const val T4_ORDINAL = 3
    private const val T5_ORDINAL = 4
    private const val T4_BLOOM_FRACTION = 0.25
    private const val T5_BLOOM_FRACTION = 0.34
}
