package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.input.BrowserInput
import io.github.ksean.cyberslop.loop.RafLoop
import io.github.ksean.cyberslop.physics.IntentFilter
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.render.Camera
import io.github.ksean.cyberslop.render.CanvasRenderer
import io.github.ksean.cyberslop.render.HudModel
import io.github.ksean.cyberslop.render.Scene
import io.github.ksean.cyberslop.run.MetaProgression
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.save.LocalStorageSaveStore
import io.github.ksean.cyberslop.screen.ScreenEvent
import io.github.ksean.cyberslop.screen.ScreenRouter
import io.github.ksean.cyberslop.screen.ScreenState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.title.TitleScreenAction
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/**
 * Owns the running game and its lifecycle.
 *
 * It holds no rules: movement, firing, targeting, screens and framing all live in `commonMain`
 * behind [GameSimulation], where they are tested without a browser. What lives here is the wiring
 * and the three transitions that only make sense in a browser — starting, dying, and winning.
 */
class GameHost(
    private val root: HTMLElement,
    private val canvas: HTMLCanvasElement,
    private val saves: LocalStorageSaveStore,
) {
    private val input = BrowserInput(canvas)
    private var filter = IntentFilter()

    private var screen: ScreenState = ScreenState.Title
    private var meta: MetaProgression = MetaProgression()
    private var simulation: GameSimulation? = null
    private var renderer: CanvasRenderer? = null
    private var camera = Camera(0.0, 0.0, 1.0, 1.0)
    private var loop: RafLoop? = null
    private var announced = ""

    fun start(action: TitleScreenAction) {
        val context = canvas.getContext("2d") as? CanvasRenderingContext2D ?: return
        renderer = CanvasRenderer(canvas, context)

        val restored = if (action == TitleScreenAction.ContinueGame) saves.load() else null
        meta = restored?.second ?: saves.loadMeta()

        // The screen must be told which map is being resumed, or it starts at one while the run is
        // on four — and the next boss death then rewrites the run's map index backwards.
        screen = ScreenRouter.next(
            ScreenState.Title,
            when (action) {
                TitleScreenAction.ContinueGame -> ScreenEvent.ContinueGame
                TitleScreenAction.NewGame -> ScreenEvent.NewGame
            },
            resumeAt = restored?.first?.mapIndex ?: 1,
        )
        val playing = screen as? ScreenState.Playing ?: return

        root.style.display = "none"
        canvas.style.display = "block"
        renderer?.resizeToDisplay()
        canvas.focus()

        enter(restored?.first ?: RunState.begin(freshSeed()), playing.mapIndex)
        input.detach()
        input.attach()

        loop = RafLoop(
            step = { step() },
            render = { alpha -> render(alpha) },
            isPaused = { input.paused },
        ).also { it.start() }
    }

    private fun enter(run: RunState, mapIndex: Int) {
        val entering = run.copy(mapIndex = mapIndex)
        val generated = LevelGenerator.generate(entering.seed, mapIndex)
        val sim = GameSimulation(generated.level, entering, entering.seed)
        simulation = sim
        // Assist state belongs to one simulation: a jump held into a map change is not a jump on
        // the next map, and a buffered press does not carry over.
        filter = IntentFilter()
        // The view is measured in world units, so the zoom is a *smaller* view rather than a
        // transform. Nothing about following or clamping to the level changes.
        camera = Camera(
            0.0, 0.0,
            canvas.width.toDouble() / Scene.ZOOM,
            canvas.height.toDouble() / Scene.ZOOM,
        )
        renderer?.enterLevel(sim, entering.seed)
        saves.save(entering, meta)
    }

    private fun step() {
        val sim = simulation ?: return
        if (screen !is ScreenState.Playing) return

        val player = sim.player
        val standingBlocked =
            player.stance == Stance.Crouch && !MovementModel.canStand(player, sim.level.tiles)
        val report = sim.tick(filter.next(input.keys(), player.onGround, standingBlocked))

        if (report.playerDied) {
            endRun(sim.run.scrap, ScreenEvent.PlayerDied(sim.run.scrap))
            return
        }

        // Clearing a map means walking out of it, not the boss's last hit point. The exit opens on
        // its death; the player still has to reach it.
        if (report.mapCleared) {
            screen = ScreenRouter.next(screen, ScreenEvent.BossDefeated(sim.run.scrap))
            when (val next = screen) {
                is ScreenState.Playing -> enter(sim.run.advanced(), next.mapIndex)
                is ScreenState.Victory -> endRun(sim.run.scrap, null)
                else -> Unit
            }
        }
    }

    /**
     * Ends a run once and stops.
     *
     * The save is cleared and **not** rewritten: an earlier version cleared it and immediately
     * entered a fresh run, which saved again, so reloading after death offered `Continue game` for
     * a run that was over. Stopping the loop also keeps a finished run from ticking on — victory
     * used to re-bank the same Scrap on every frame.
     */
    private fun endRun(scrap: Int, event: ScreenEvent?) {
        event?.let { screen = ScreenRouter.next(screen, it) }
        loop?.stop()
        loop = null
        simulation = null
        saves.clearRun()
        meta = meta.banking(scrap)
        saves.saveMeta(meta)
        showEndScreen(scrap)
    }

    private fun showEndScreen(scrap: Int) {
        canvas.style.display = "none"
        root.style.display = "flex"
        root.textContent = ""

        val heading = document.createElement("h1") as HTMLElement
        heading.textContent = if (screen is ScreenState.Victory) "Run complete" else "You died"
        root.appendChild(heading)

        val summary = document.createElement("p") as HTMLElement
        summary.textContent = "Scrap banked: $scrap. Total: ${meta.scrap}."
        root.appendChild(summary)

        val again = document.createElement("button") as org.w3c.dom.HTMLButtonElement
        again.type = "button"
        again.textContent = "New game"
        again.onclick = { _ ->
            screen = ScreenState.Title
            start(TitleScreenAction.NewGame)
        }
        root.appendChild(again)
        again.focus()

        announce(heading.textContent.orEmpty() + ". " + summary.textContent.orEmpty())
    }

    private fun render(alpha: Double) {
        val sim = simulation ?: return
        val active = renderer ?: return
        active.resizeToDisplay()

        // A point that a stance change cannot move. Interpolating the box's corner moved the camera
        // twelve world pixels on a crouch; following the body's centre still moved it six.
        val follow = Scene.drawnFollow(sim, alpha)
        val x = follow.x
        val y = follow.y

        camera = Camera.following(
            camera.copy(
                viewWidth = canvas.width.toDouble() / Scene.ZOOM,
                viewHeight = canvas.height.toDouble() / Scene.ZOOM,
            ),
            x, y, sim.facing, sim.level,
        )
        active.draw(sim, camera, sim.elapsedTicks * TICK_SECONDS, alpha)
        // The same model the bar is drawn from, so the two cannot disagree (PROD-004).
        announce(HudModel.of(sim).announcement)
    }

    /**
     * Reports run state as text. A canvas announces nothing on its own, so without this the game is
     * opaque to assistive technology (PROD-004).
     */
    private fun announce(text: String) {
        if (text == announced) return
        announced = text
        (document.getElementById("game-status") as? HTMLElement)?.textContent = text
    }

    private fun freshSeed(): ULong = (window.performance.now() * 1000.0).toLong().toULong() or 1uL
}
