package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property 27 and property 29: the authored registries (PROD-049, PROD-050).
 *
 * Totality runs over `entries` rather than over a hand-written list, so a twenty-seventh weapon added
 * later fails here rather than drawing nothing on the floor of a map.
 *
 * What these cannot assert is the thing the owner actually asked for — that a shotgun looks like a
 * shotgun. That is a human judgement, made against the sheet `IconSheetTest` writes (`plan.md`
 * §16.7). What they do assert is everything that would make the sheet a lie: an icon that is missing,
 * empty, duplicated, or wearing the wrong kind's casing.
 */
class IconRegistryTest {
    @Test
    fun `every weapon and every powerup has an icon`() {
        WeaponId.entries.forEach { WeaponIcons.of(it) }
        PowerupId.entries.forEach { PowerupIcons.of(it) }

        assertEquals(
            WeaponId.entries.size,
            Weapons.all.size,
            "the weapon registry and the icon registry are keyed by the same enum, so these must agree",
        )
        assertEquals(PowerupId.entries.size, Powerups.all.size)
    }

    @Test
    fun `no two icons are the same geometry`() {
        val all = allIcons()
        val seen = mutableMapOf<List<IconOp>, String>()

        all.forEach { (name, icon) ->
            val clash = seen.put(icon.ops, name)
            assertTrue(clash == null, "$name and $clash are drawn identically")
        }
        assertEquals(WeaponId.entries.size + PowerupId.entries.size, seen.size)
    }

    @Test
    fun `every icon carries enough ink to be seen`() {
        allIcons().forEach { (name, icon) ->
            assertTrue(
                icon.strokes.size >= MIN_STROKES,
                "$name has ${icon.strokes.size} strokes; below $MIN_STROKES nothing reads as an object",
            )
            assertTrue(
                icon.span >= MIN_SPAN,
                "$name spans ${icon.span} of its box, under $MIN_SPAN — it would draw as a speck in " +
                    "the middle of the space its rarity size claims",
            )
        }
    }

    @Test
    fun `a powerup wears the casing and a weapon does not`() {
        PowerupId.entries.forEach {
            assertTrue(
                PowerupIcons.of(it).cased,
                "$it has no casing, so with colour removed it is indistinguishable from a weapon",
            )
        }
        WeaponId.entries.forEach {
            assertTrue(!WeaponIcons.of(it).cased, "$it is drawn inside a powerup's casing")
        }
    }

    @Test
    fun `the two outline colours differ in hue and in luminance`() {
        val weapon = IconStyles.WEAPON_OUTLINE
        val powerup = IconStyles.POWERUP_OUTLINE

        assertTrue(
            rgbDistance(weapon, powerup) >= MIN_HUE_DISTANCE,
            "the two outlines are $weapon and $powerup, too close to tell apart",
        )
        val gap = Palette.luminanceOf(powerup) - Palette.luminanceOf(weapon)
        assertTrue(
            (if (gap < 0) -gap else gap) >= MIN_LUMINANCE_GAP,
            "the outlines have the same luminance, so greyscale loses the kind entirely",
        )
    }

    private fun allIcons(): List<Pair<String, Icon>> =
        WeaponId.entries.map { it.name to WeaponIcons.of(it) } +
            PowerupId.entries.map { it.name to PowerupIcons.of(it) }

    private fun rgbDistance(first: String, second: String): Double {
        fun channel(hex: String, at: Int) = hex.substring(at, at + 2).toInt(16).toDouble()
        var sum = 0.0
        listOf(1, 3, 5).forEach { at ->
            val delta = channel(first, at) - channel(second, at)
            sum += delta * delta
        }
        return kotlin.math.sqrt(sum)
    }

    private companion object {
        const val MIN_STROKES = 3
        const val MIN_SPAN = 1.2
        const val MIN_HUE_DISTANCE = 120.0
        const val MIN_LUMINANCE_GAP = 20.0
    }
}
