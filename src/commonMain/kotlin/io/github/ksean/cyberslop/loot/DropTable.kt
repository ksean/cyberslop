package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.gen.DifficultyCurve

/**
 * How rarity is decided.
 *
 * Weight is **strictly decreasing in tier at every map index**, which is what "stronger things are
 * rarer" has to mean if it is to mean anything. Later maps do not achieve their better loot by
 * making rare things common — the whole distribution flattens, so the strongest tier's share rises
 * from 1% to 7% while still being the least likely thing to find.
 *
 * Only the two endpoint rows are authoritative; everything between is interpolated. Writing out
 * intermediate rows by hand is how an earlier version ended up with a table that was not the
 * interpolation it claimed to be, and was not monotone either.
 */
object DropTable {
    private val FIRST_MAP = doubleArrayOf(62.0, 25.0, 9.0, 3.0, 1.0)
    private val LAST_MAP = doubleArrayOf(34.0, 26.0, 20.0, 13.0, 7.0)

    /** Normalised tier weights for [mapIndex], strongest tier last. */
    fun weights(mapIndex: Int): DoubleArray {
        val d = (mapIndex - 1) / (DifficultyCurve.MAPS - 1).toDouble()
        val raw = DoubleArray(FIRST_MAP.size) { i ->
            FIRST_MAP[i] + (LAST_MAP[i] - FIRST_MAP[i]) * d
        }
        val total = raw.sum()
        return DoubleArray(raw.size) { i -> raw[i] / total }
    }

    /**
     * The chance a slain enemy drops anything (PROD-046).
     *
     * Flat, and deliberately not a function of [mapIndex] beyond taking it: the parameter is kept so
     * that a future curve is a change here rather than a change at every call site, and the test
     * asserts it does not vary. It replaces two ramps that disagreed with each other — `plan.md`
     * §6.7 published 1.5%-to-3% while the simulation ran 3%-to-6%.
     */
    @Suppress("UNUSED_PARAMETER")
    fun killDropChance(mapIndex: Int): Double = KILL_DROP_CHANCE

    /**
     * How much of a drop is a weapon rather than a powerup (PROD-046).
     *
     * Here beside the rate it splits, rather than as a private constant in the simulation, because a
     * review round found it normative in `specs/` and untested: changing it left every loot test
     * green, since they all measured the total.
     */
    fun weaponShare(): Double = WEAPON_SHARE

    fun rollTier(rng: Rng, mapIndex: Int, floor: Tier? = null, shifts: Int = 0): Tier {
        var best = drawTier(rng, mapIndex)
        repeat(shifts) {
            val again = drawTier(rng, mapIndex)
            if (again.ordinal > best.ordinal) best = again
        }
        if (floor != null && best.ordinal < floor.ordinal) best = floor
        return best
    }

    /**
     * [unlocked] is how many weapons this account has opened up. Scrap widens the pool, and without
     * this parameter it bought nothing: every roll drew from the whole registry regardless.
     */
    fun rollWeapon(
        rng: Rng,
        mapIndex: Int,
        floor: Tier? = null,
        shifts: Int = 0,
        unlocked: Int = Weapons.all.size,
    ): WeaponSpec {
        val available = Weapons.all.take(unlocked.coerceIn(1, Weapons.all.size))
        val tier = rollTier(rng, mapIndex, floor, shifts)
        val candidates = available.filter { it.tier == tier }
            .ifEmpty { available.filter { it.tier.ordinal <= tier.ordinal } }
            .ifEmpty { available }
        return candidates[rng.nextInt(candidates.size)]
    }

    /**
     * [shifts] draws again and keeps the rarer result, the same way [rollTier] does for weapons — so
     * a cache can hold something better than a corpse does on both branches rather than only on the
     * weapon one, which is what PROD-047 now requires.
     */
    fun rollPowerup(rng: Rng, mapIndex: Int, pool: List<Powerup>, shifts: Int = 0): Powerup {
        var best = drawPowerup(rng, mapIndex, pool)
        repeat(shifts) {
            val again = drawPowerup(rng, mapIndex, pool)
            if (again.tier.ordinal > best.tier.ordinal) best = again
        }
        return best
    }

    private fun drawPowerup(rng: Rng, mapIndex: Int, pool: List<Powerup>): Powerup {
        val weights = weights(mapIndex)
        val weighted = pool.map { weights[it.tier.ordinal] }
        val total = weighted.sum()
        var draw = rng.nextDouble() * total
        pool.forEachIndexed { index, powerup ->
            draw -= weighted[index]
            if (draw <= 0.0) return powerup
        }
        return pool.last()
    }

    /** The subset of powerup types a single run can draw from, so duplicates stack often enough. */
    fun runPool(rng: Rng, mapIndex: Int, size: Int = RUN_POOL_SIZE): List<Powerup> {
        val remaining = Powerups.all.toMutableList()
        val pool = mutableListOf<Powerup>()
        repeat(size.coerceAtMost(remaining.size)) {
            val picked = rollPowerup(rng, mapIndex, remaining)
            pool.add(picked)
            remaining.remove(picked)
        }
        return pool
    }

    private fun drawTier(rng: Rng, mapIndex: Int): Tier {
        val weights = weights(mapIndex)
        var draw = rng.nextDouble()
        Tier.entries.forEachIndexed { index, tier ->
            draw -= weights[index]
            if (draw <= 0.0) return tier
        }
        return Tier.entries.last()
    }

    const val RUN_POOL_SIZE = 8

    /** One in five (PROD-046). */
    private const val KILL_DROP_CHANCE = 0.20

    /** Three in ten of those (PROD-046). */
    private const val WEAPON_SHARE = 0.30
}
