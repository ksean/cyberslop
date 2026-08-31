package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2

/**
 * What the figure's legs are doing. One of these is always selected (PROD-041).
 *
 * Deliberately separate from [Action]: the weapon fires on its own cooldown and the player never
 * presses anything to use it, so a figure that stopped moving to shoot would stop constantly. The
 * two axes compose.
 */
enum class Clip { Idle, Run, JumpRise, JumpFall, Crouch, CrouchWalk }

/** What the figure's arms are doing, over whatever its legs are doing. */
enum class Action { None, WindUp, Fire, Swing }

/**
 * Everything animation needs to know about an actor this frame.
 *
 * Elapsed *distance* rather than elapsed *time* drives the gait. Time with a speed-dependent rate
 * makes the cycle jump whenever speed changes, and the feet visibly skate; distance plants them by
 * construction and is deterministic besides (ENG-062).
 */
data class Motion(
    val speedX: Double = 0.0,
    val verticalSpeed: Double = 0.0,
    val onGround: Boolean = true,
    val crouched: Boolean = false,
    val facing: Int = 1,
    val stridePx: Double = 0.0,
    /** Seconds since the weapon last fired. [Double.MAX_VALUE] when it never has. */
    val secondsSinceShot: Double = Double.MAX_VALUE,
    val secondsSinceSwing: Double = Double.MAX_VALUE,
    /** An attack is telegraphing: the arm is drawn back and held there until it resolves (PROD-063). */
    val windingUp: Boolean = false,
    /**
     * How long each action lasts, taken from the simulation's own visual rather than duplicated.
     *
     * The simulation decides how long a swing or a muzzle flash stays visible, and the animation has
     * to end on the same tick. Two independently-written constants drifted apart immediately: the
     * arm's sweep ran 0.18 s against a swing the simulation cleared at 0.16 s, so the arm snapped
     * back to rest at 89% of its arc, every swing.
     */
    val shotSeconds: Double = Actor.FIRE_SECONDS,
    val swingSeconds: Double = Actor.SWING_SECONDS,
    /** Where the swing went, so the arm sweeps the way the hit resolved (PROD-033). */
    val swingDirection: Vec2 = Vec2.Right,
    /** Player `ArcSwing` geometry; null leaves enemy and boss attack-owned motion unchanged. */
    val swingArcDegrees: Double? = null,
    val swingProgress: Double? = null,
    /**
     * Where a held weapon points when nothing else is happening.
     *
     * Null holds it level. An armed enemy tracking the player computes the full direction and used
     * to throw away everything but its sign, so one firing upward or downward drew a horizontal
     * barrel while its projectile left on the diagonal.
     */
    val weaponAim: Vec2? = null,
    /** Scales the whole figure. One for the player; larger for a boss. */
    val scale: Double = 1.0,
)

/**
 * A posed figure, in pixels relative to the point between its feet, with `y` increasing downward as
 * everywhere else in this project. The renderer adds a world position and draws; it does no
 * trigonometry and makes no decision.
 *
 * The points are `Vec2` rather than a flat array. That is an allocation per joint per actor per
 * frame, and it is affordable here for a reason worth stating: a map holds at most 60 enemies and
 * only the handful on screen are posed, so this is tens of allocations a frame against the roughly
 * 3,600 tile rectangles the same frame writes into reused buffers.
 */
data class Pose(
    val clip: Clip,
    val action: Action,
    val height: Double,
    val hip: Vec2,
    val neck: Vec2,
    val head: Vec2,
    val headRadius: Double,
    val leadShoulder: Vec2,
    val leadElbow: Vec2,
    val leadHand: Vec2,
    val rearShoulder: Vec2,
    val rearElbow: Vec2,
    val rearHand: Vec2,
    val leadKnee: Vec2,
    val leadFoot: Vec2,
    val rearKnee: Vec2,
    val rearFoot: Vec2,
    /** Where a weapon points from [leadHand], as a unit vector. */
    val weaponAim: Vec2,
)
