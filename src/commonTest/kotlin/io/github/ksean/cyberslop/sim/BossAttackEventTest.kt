package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.BossAttackKind
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossPhase
import io.github.ksean.cyberslop.entity.BossProfile
import io.github.ksean.cyberslop.entity.BossSpec
import io.github.ksean.cyberslop.entity.Bosses
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each module resolves the separately timed events promised by its one readable telegraph. */
class BossAttackEventTest {
    @Test
    fun `single modules emit one event while flurry and burst emit three`() {
        val expected = mapOf(
            BossModule.Slam to 1,
            BossModule.Sweep to 1,
            BossModule.Flurry to 3,
            BossModule.Rush to 1,
            BossModule.Bolt to 1,
            BossModule.Burst to 3,
            BossModule.Scatter to 1,
            BossModule.Laser to 1,
        )
        expected.forEach { (module, count) ->
            assertEquals(count, emittedBy(module), "$module emitted the wrong event count")
        }
    }

    private fun emittedBy(module: BossModule): Int {
        val map = when (module) {
            BossModule.Slam, BossModule.Sweep, BossModule.Bolt -> 1
            BossModule.Flurry, BossModule.Burst -> 4
            BossModule.Scatter -> 5
            BossModule.Rush, BossModule.Laser -> 10
        }
        val attack = Bosses.attack(module, map, mainBoss = true)
        val profile = if (module.kind == BossAttackKind.Melee) {
            BossProfile(module, Bosses.rangedModulesFor(map).first())
        } else {
            BossProfile(Bosses.meleeModulesFor(map).first(), module)
        }
        val spec = BossSpec("fixture", 1_000.0, 0.0, listOf(BossPhase(1.0, listOf(attack))), profile, map)
        val sim = TestLevels.simulation()
        val live = LiveBoss(spec, sim.level.boss, sim.level.tiles)
        live.fight.engage()
        val target = BossTarget(live.centre.copy(x = live.centre.x - 40.0), onGround = true, crouched = false)
        var count = 0
        repeat(240) {
            live.tick(TICK_SECONDS, target)
            count += live.events.size
            if (count > 0 && live.currentAttack == null) return count
        }
        return count
    }
}
