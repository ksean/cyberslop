package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.input.BrowserInput
import io.github.ksean.cyberslop.loop.RafLoop
import io.github.ksean.cyberslop.physics.IntentFilter
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.progression.PlayerProfile
import io.github.ksean.cyberslop.render.Camera
import io.github.ksean.cyberslop.render.CanvasRenderer
import io.github.ksean.cyberslop.render.HudModel
import io.github.ksean.cyberslop.render.Scene
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.save.LocalStorageSaveStore
import io.github.ksean.cyberslop.screen.ScreenEvent
import io.github.ksean.cyberslop.screen.ScreenRouter
import io.github.ksean.cyberslop.screen.ScreenState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TickReport
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
 * and the lifecycle transitions that only make sense in a browser — starting, pausing, ending and
 * returning to the title.
 */
class GameHost(
    private val root: HTMLElement,
    private val canvas: HTMLCanvasElement,
    private val saves: LocalStorageSaveStore,
    private val onReturnToTitle: () -> Unit = {},
) {
    private val input = BrowserInput(canvas, onEscape = ::toggleManualPause)
    private var filter = IntentFilter()

    private var screen: ScreenState = ScreenState.Title
    private var profile: PlayerProfile = PlayerProfile()
    private var simulation: GameSimulation? = null
    private var renderer: CanvasRenderer? = null
    private var camera = Camera(0.0, 0.0, 1.0, 1.0)
    private var loop: RafLoop? = null
    private var announced = ""
    private var deferredReport: TickReport? = null
    private var lastActivityRevision = 0
    private var manualPaused = false
    private val discovery = DiscoverySession(
        record = saves::recordDiscoveries,
        clearInput = ::clearGameplayInput,
        announce = ::announce,
    )

    fun start(action: TitleScreenAction) {
        if (action == TitleScreenAction.Shop) return
        val context = canvas.getContext("2d") as? CanvasRenderingContext2D ?: return
        renderer = CanvasRenderer(canvas, context)

        val restored = if (action == TitleScreenAction.ContinueGame) saves.load() else null
        profile = restored?.second ?: saves.loadProfile()

        // The screen must be told which map is being resumed, or it starts at one while the run is
        // on four — and the next boss death then rewrites the run's map index backwards.
        screen = ScreenRouter.next(
            ScreenState.Title,
            when (action) {
                TitleScreenAction.ContinueGame -> ScreenEvent.ContinueGame
                TitleScreenAction.NewGame -> ScreenEvent.NewGame
                TitleScreenAction.Shop -> return
            },
            resumeAt = restored?.first?.mapIndex ?: 1,
        )
        val playing = screen as? ScreenState.Playing ?: return

        root.style.display = "none"
        canvas.style.display = "block"
        renderer?.resizeToDisplay()
        canvas.focus()
        manualPaused = false
        discovery.clear()
        deferredReport = null

        enter(restored?.first ?: RunState.begin(freshSeed(), profile.upgrades), playing.mapIndex)
        input.detach()
        input.attach()
        lastActivityRevision = input.activityRevision

        loop = RafLoop(
            step = { step() },
            render = { alpha, deltaSeconds -> render(alpha, deltaSeconds) },
            isPaused = { input.paused || discovery.paused || manualPaused },
        ).also { it.start() }
    }

    private fun enter(run: RunState, mapIndex: Int) {
        // A purchase made while Continue was available applies now without rewriting current HP.
        val entering = run.copy(mapIndex = mapIndex, upgrades = profile.upgrades)
        val generated = LevelGenerator.generate(entering.seed, mapIndex)
        val sim = GameSimulation(generated.level, entering, entering.seed, profile.unlockedWeapons)
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
        saves.save(entering)
    }

    private fun step() {
        val sim = simulation ?: return
        if (screen !is ScreenState.Playing) return

        val player = sim.player
        val standingBlocked =
            player.stance == Stance.Crouch && !MovementModel.canStand(player, sim.level.tiles)
        val report = sim.tick(filter.next(input.keys(), player.onGround, standingBlocked))
        if (report.collectedDiscoveries.isNotEmpty()) {
            profile = discovery.collect(report.collectedDiscoveries).profile
        }

        if (discovery.paused) {
            if (report.playerDied || report.mapCleared) deferredReport = report
            return
        }

        handleReport(report)
    }

    private fun handleReport(report: TickReport) {
        val sim = simulation ?: return

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

    private fun clearGameplayInput() {
        input.clear()
        filter = IntentFilter()
    }

    private fun toggleManualPause() {
        if (screen !is ScreenState.Playing || simulation == null) return
        if (manualPaused) resumeFromPause() else openPauseMenu()
    }

    private fun openPauseMenu() {
        manualPaused = true
        clearGameplayInput()
        root.style.display = "flex"
        announce(
            renderPauseMenu(
                root,
                onResume = ::resumeFromPause,
                onReturnToTitle = ::returnToTitleFromPause,
            ),
        )
    }

    private fun resumeFromPause() {
        if (!manualPaused) return
        manualPaused = false
        root.textContent = ""
        root.className = ""
        root.style.display = "none"
        clearGameplayInput()
        canvas.focus()
    }

    private fun returnToTitleFromPause() {
        val scrap = simulation?.run?.scrap ?: return
        if (!finishRun(scrap)) return
        screen = ScreenRouter.next(screen, ScreenEvent.ReturnToTitle)
        canvas.style.display = "none"
        root.style.display = "flex"
        onReturnToTitle()
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
        if (!finishRun(scrap)) return
        showEndScreen(scrap)
    }

    /** Returns false after the first call so repeated UI activation cannot bank a run twice. */
    private fun finishRun(scrap: Int): Boolean {
        if (simulation == null) return false
        loop?.stop()
        loop = null
        simulation = null
        deferredReport = null
        manualPaused = false
        discovery.clear()
        input.detach()
        saves.clearRun()
        profile = profile.banking(scrap)
        saves.saveProfile(profile)
        return true
    }

    private fun showEndScreen(scrap: Int) {
        canvas.style.display = "none"
        root.style.display = "flex"
        announce(
            renderRunEndedScreen(
                root,
                victory = screen is ScreenState.Victory,
                scrapBanked = scrap,
                profile = profile,
                onReturnToTitle = {
                    screen = ScreenRouter.next(screen, ScreenEvent.ReturnToTitle)
                    onReturnToTitle()
                },
                onNewGame = {
                    screen = ScreenState.Title
                    start(TitleScreenAction.NewGame)
                },
            ),
        )
    }

    private fun render(alpha: Double, frameDeltaSeconds: Double) {
        val currentRevision = input.activityRevision
        val uninterruptedActiveFrame =
            !manualPaused && !input.paused && currentRevision == lastActivityRevision
        lastActivityRevision = currentRevision
        if (discovery.advance(frameDeltaSeconds, uninterruptedActiveFrame)) {
            val report = deferredReport
            deferredReport = null
            if (report != null) handleReport(report)
        }

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
        active.draw(
            sim,
            camera,
            sim.elapsedTicks * TICK_SECONDS,
            alpha,
            discovery = discovery.active,
        )
        // The same model the bar is drawn from, so the two cannot disagree (PROD-004).
        when {
            manualPaused -> announce("Paused")
            discovery.active != null -> announce(requireNotNull(discovery.active).announcement)
            else -> announce(HudModel.of(sim).announcement)
        }
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
