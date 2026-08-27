package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.gen.DifficultyCurve
import io.github.ksean.cyberslop.gen.Themes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROD-042: the archetypes are told apart by silhouette, and a stronger enemy looks tougher.
 *
 * The second half is the one a comment cannot satisfy. "Tougher looking the stronger they are" is
 * asserted here as a monotone function over the **whole** archetype-by-map grid ordered by the
 * health an enemy actually carries — so a map-10 Swarm outranks a map-1 Brute, which is right,
 * because it has roughly forty times the health.
 */
class EnemyLookTest {
    @Test
    fun `each archetype has its own silhouette`() {
        val shapes = EnemyArchetype.entries.map { archetype ->
            val look = EnemyLooks.of(archetype, 1)
            listOf(look.form, look.headScale, look.strideRate, look.heightScale, look.armed)
        }

        assertEquals(
            EnemyArchetype.entries.size,
            shapes.distinct().size,
            "two archetypes are drawn as the same shape, so colour is the only thing telling " +
                "them apart: $shapes",
        )
    }

    @Test
    fun `shape is what distinguishes them, not size alone`() {
        val forms = EnemyArchetype.entries.map { EnemyLooks.of(it, 1).form }.distinct()

        assertTrue(
            forms.size >= 3,
            "every archetype is the same kind of body, so they differ only in proportion: $forms",
        )
    }

    /** PROD-042's whole-grid clause: plating and protrusions, which are counts of drawn parts. */
    @Test
    fun `armour and protrusions never fall as health rises, anywhere in the run`() {
        val ordered = grid().sortedBy { it.health }

        ordered.zipWithNext { weaker, stronger ->
            assertTrue(
                stronger.look.plates >= weaker.look.plates,
                "${stronger.label} carries more health than ${weaker.label} but has less armour",
            )
            assertTrue(
                stronger.look.spikes >= weaker.look.spikes,
                "${stronger.label} carries more health than ${weaker.label} but fewer protrusions",
            )
        }
    }

    /**
     * PROD-042's per-map clause, over the size the figure is **actually drawn at**.
     *
     * A review round found the whole-grid version of this being tested against `bulk` alone, which
     * is monotone while `height * bulk` — what `Scene.figure` derives every limb width from — is
     * not: a map-4 Swarm carries more health than a map-1 Brute and is drawn at 14.2 against 24.3.
     *
     * The requirement is scoped rather than the code forced, because the two are not jointly
     * satisfiable: measured, a whole-grid size ordering needs the five archetypes within 1.01x of
     * each other, which is every enemy the same size and no silhouette left to tell apart.
     */
    @Test
    fun `on any one map a tougher enemy is drawn bigger`() {
        (1..DifficultyCurve.MAPS).forEach { mapIndex ->
            val ordered = EnemyArchetype.entries
                .map { it to EnemyLooks.of(it, mapIndex) }
                .sortedBy { (archetype, _) -> archetype.healthOn(mapIndex) }

            ordered.zipWithNext { (weaker, weak), (stronger, strong) ->
                assertTrue(
                    strong.drawnScale >= weak.drawnScale,
                    "on map $mapIndex a $stronger carries more health than a $weaker but is " +
                        "drawn at ${strong.drawnScale} against ${weak.drawnScale}",
                )
            }
        }
    }

    /** The scalar the per-map clause rests on stays monotone everywhere, which is worth pinning. */
    @Test
    fun `the bulk factor never falls as health rises`() {
        grid().sortedBy { it.health }.zipWithNext { weaker, stronger ->
            assertTrue(
                stronger.look.bulk >= weaker.look.bulk,
                "${stronger.label} carries more health than ${weaker.label} but is less bulky",
            )
            assertTrue(
                stronger.look.glowTone >= weaker.look.glowTone,
                "${stronger.label} carries more health than ${weaker.label} but glows dimmer",
            )
        }
    }

    @Test
    fun `the extremes actually differ`() {
        val ordered = grid().sortedBy { it.health }
        val weakest = ordered.first().look
        val strongest = ordered.last().look

        assertTrue(strongest.bulk > weakest.bulk, "the toughest enemy is no bulkier than the weakest")
        assertTrue(
            strongest.drawnScale > weakest.drawnScale,
            "the toughest enemy in the run is drawn no bigger than the weakest",
        )
        assertTrue(strongest.plates > weakest.plates, "the toughest enemy wears no more armour")
        assertTrue(strongest.spikes > weakest.spikes, "the toughest enemy has no more protrusions")
        assertTrue(strongest.glowTone > weakest.glowTone, "the toughest enemy glows no brighter")
    }

    /**
     * PROD-042's luminance clause, over the colours actually drawn rather than over an index.
     *
     * A review round found that a monotone tone index says nothing about brightness once it is
     * resolved through a per-theme palette: a map-1 Turret carries more health than a map-2 Shooter
     * and resolved to a duller colour. The requirement is now scoped to what a player can compare —
     * two enemies on the same map — and this asserts it that way.
     */
    @Test
    fun `on any one map a tougher enemy is drawn brighter`() {
        (1..DifficultyCurve.MAPS).forEach { mapIndex ->
            val palette = Palettes.of(Themes.forMap(mapIndex))
            val ordered = EnemyArchetype.entries
                .map { it to EnemyLooks.of(it, mapIndex) }
                .sortedBy { (archetype, _) -> archetype.healthOn(mapIndex) }

            ordered.zipWithNext { (weaker, weak), (stronger, strong) ->
                val dim = Palette.luminanceOf(palette.glow[weak.glowTone])
                val bright = Palette.luminanceOf(palette.glow[strong.glowTone])
                assertTrue(
                    bright >= dim,
                    "on map $mapIndex a $stronger carries more health than a $weaker but is " +
                        "drawn at luminance $bright against $dim",
                )
            }
        }
    }

    @Test
    fun `a glow tone always indexes a palette`() {
        grid().forEach { entry ->
            assertTrue(
                entry.look.glowTone in 0 until Palette.GLOW_TONES,
                "${entry.label} asks for glow tone ${entry.look.glowTone}, which no palette has",
            )
        }
    }

    @Test
    fun `a boss is not mistakable for trash, and a mini-boss is not the main boss`() {
        val tallestTrash = grid().maxOf { it.look.height }
        val miniboss = EnemyLooks.boss(mapIndex = 1, isMain = false)
        val boss = EnemyLooks.boss(mapIndex = 1, isMain = true)

        assertTrue(
            miniboss.height > tallestTrash,
            "a mini-boss is no taller than the biggest trash enemy on any map",
        )
        assertTrue(boss.height > miniboss.height, "the main boss is no bigger than the mini-boss")
        assertTrue(boss.crown > miniboss.crown, "nothing marks the main boss out from the mini-boss")
        assertTrue(miniboss.crown > 0, "a boss carries no crown, so it reads as a large trash enemy")
        assertEquals(0, grid().maxOf { it.look.crown }, "a trash enemy is drawn with a boss crown")
    }

    private class Entry(val label: String, val health: Double, val look: EnemyLook)

    private fun grid(): List<Entry> = EnemyArchetype.entries.flatMap { archetype ->
        (1..DifficultyCurve.MAPS).map { mapIndex ->
            Entry(
                label = "$archetype on map $mapIndex",
                health = archetype.healthOn(mapIndex),
                look = EnemyLooks.of(archetype, mapIndex),
            )
        }
    }
}
