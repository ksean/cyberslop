package io.github.ksean.cyberslop.render

/**
 * The fixed colours a drop is drawn in (PROD-050, PROD-051).
 *
 * Red and blue do not move with the sub-theme: a red-outlined thing is a weapon on all ten maps, or
 * the rule teaches a player nothing. What that costs, and why the halo is not optional, is measured
 * in `plan.md` §16.3 — the red outline alone is worth **2.0** of luminance separation against
 * `ArcologyVault.tileBody`, and a near-black halo alone **0.1** against `ReactorCore.sky`. Drawn as a
 * pair so that at least one line always separates, the worst case over all ten palettes is 45.8.
 */
object IconStyles {
    const val WEAPON_OUTLINE = "#ff2f2f"
    const val POWERUP_OUTLINE = "#3d8bff"

    /** Under every line, wider, so the outline is never read against the terrain directly. */
    const val HALO = "#05060a"

    fun outlineOf(weapon: Boolean): String = if (weapon) WEAPON_OUTLINE else POWERUP_OUTLINE

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
     * onto **three** distinct widths per weight, so the ground layer opens nine outline batches and
     * nine halo batches whatever is on screen — a bigger constant than the plan estimated, still a
     * constant, and `IconBatchBoundTest` counts it rather than believing it.
     */
    fun widthOf(weight: StrokeWeight, scale: Double): Double =
        Scene.strokeWidth(weight.fraction * scale)

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
}
