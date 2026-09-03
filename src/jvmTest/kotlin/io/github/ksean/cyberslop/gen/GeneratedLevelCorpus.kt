package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.verify.Witness
import io.github.ksean.cyberslop.verify.WitnessStep
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.TileMap

/** Memoized JVM-test generation with a fresh mutable level graph for every caller (ENG-036). */
internal class GeneratedLevelCorpus(
    private val generator: (ULong, Int) -> GeneratedLevel = LevelGenerator::generate,
) {
    private data class Key(val seed: ULong, val mapIndex: Int)

    private val cache = mutableMapOf<Key, GeneratedLevel>()

    var generationCount: Int = 0
        private set

    val cachedKeyCount: Int
        get() = synchronized(cache) { cache.size }

    fun generated(seed: ULong, mapIndex: Int): GeneratedLevel = canonical(seed, mapIndex).deepCopy()

    fun level(seed: ULong, mapIndex: Int): Level = canonical(seed, mapIndex).level.deepCopy()

    private fun canonical(seed: ULong, mapIndex: Int): GeneratedLevel =
        synchronized(cache) {
            cache.getOrPut(Key(seed, mapIndex)) {
                generationCount++
                generator(seed, mapIndex)
            }
        }
}

/** Shared by read-only cohort setup; direct generator tests intentionally bypass it. */
internal object GeneratedLevels {
    private val corpus = GeneratedLevelCorpus()

    fun generated(seed: ULong, mapIndex: Int): GeneratedLevel = corpus.generated(seed, mapIndex)

    fun level(seed: ULong, mapIndex: Int): Level = corpus.level(seed, mapIndex)

}

private fun GeneratedLevel.deepCopy(): GeneratedLevel = GeneratedLevel(
    level = level.deepCopy(),
    witness = Witness(
        witness.steps.map { step -> WitnessStep(step.frames.toList()) },
    ),
    report = report.copy(discarded = report.discarded.toList()),
)

private fun Level.deepCopy(): Level = Level(
    mapIndex = mapIndex,
    theme = theme,
    tiles = tiles.deepCopy(),
    floorMask = floorMask.deepCopy(),
    arcMask = arcMask.deepCopy(),
    spawnColumn = spawnColumn,
    spawnRow = spawnRow,
    miniboss = miniboss,
    boss = boss,
    jets = jets.toList(),
    enemies = enemies.toList(),
    pickups = pickups.toList(),
    gateColumn = gateColumn,
    barrels = barrels.toList(),
)

private fun TileMap.deepCopy(): TileMap = TileMap(width, height).also { copy ->
    for (x in 0 until width) for (y in 0 until height) copy[x, y] = this[x, y]
}

private fun Mask.deepCopy(): Mask = Mask(width, height).also { copy ->
    for (x in 0 until width) for (y in 0 until height) copy[x, y] = this[x, y]
}
