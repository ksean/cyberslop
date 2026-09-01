package io.github.ksean.cyberslop.game

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.render.Camera
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.GameplayViewport
import io.github.ksean.cyberslop.sim.TickReport

/** Converts browser-owned framing into the immutable common rule input for one fixed tick. */
internal fun GameSimulation.tick(input: InputFrame, camera: Camera): TickReport = tick(
    input,
    GameplayViewport(
        left = camera.x,
        top = camera.y,
        right = camera.x + camera.viewWidth,
        bottom = camera.y + camera.viewHeight,
    ),
)
