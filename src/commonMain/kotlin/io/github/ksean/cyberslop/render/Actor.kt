package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.Physics
import kotlin.math.abs

/**
 * Poses a figure (PROD-041, ENG-062).
 *
 * Pure: same [Motion] in, same [Pose] out. It reads no clock, and nothing it returns is fed back
 * into the simulation — a pose that could change what the game does would put presentation inside
 * ENG-050's purity claim and stop a recorded witness meaning one thing on replay.
 *
 * The figure is built in **local space with `+x` forward**, then mirrored by facing at the end. That
 * is what makes turning around exact rather than a second set of hand-tuned numbers, and it is why
 * the swing direction is converted into local space on the way in.
 *
 * Every proportion is a fraction of the figure's own height, so the same code poses a 26 px player
 * and a 56 px boss without a second rig.
 */
object Actor {
    /** How far the figure walks for one full gait cycle. */
    const val STRIDE_PX = 34.0

    /**
     * Default action windows, used when a [Motion] does not carry the simulation's own.
     *
     * The running game always carries them: `Scene.motionOf` reads them off the swing and the muzzle
     * flash, so what is drawn ends exactly when what it depicts does.
     */
    const val FIRE_SECONDS = 0.10
    const val SWING_SECONDS = 0.16

    /** Below this the figure is standing, not walking. */
    private const val MOVING_PX_PER_SECOND = 4.0

    fun pose(motion: Motion, physics: Physics = Physics.Default): Pose {
        val facing = if (motion.facing < 0) -1.0 else 1.0
        val height = bodyHeight(motion, physics)
        val clip = clipOf(motion)
        val action = actionOf(motion)

        // Forward speed in the direction the figure faces, so a lean is a lean and not a sign error.
        val forward = motion.speedX * facing
        val phase = gaitPhase(motion, clip)

        val hipY = -HIP * height
        val shoulderY = -SHOULDER * height
        val lean = leanOf(clip, forward, physics) * height

        val hip = Vec2(lean * LEAN_HIP, hipY)
        val neck = Vec2(lean, -NECK * height)
        val head = Vec2(lean * HEAD_LEAD, -HEAD_CENTRE * height)

        val leadHipJoint = Vec2(hip.x + HIP_SPREAD * height, hipY)
        val rearHipJoint = Vec2(hip.x - HIP_SPREAD * height, hipY)
        val leadFoot = footAt(phase, height, clip, motion)
        val rearFoot = footAt(phase + HALF_CYCLE, height, clip, motion)

        val leadShoulder = Vec2(neck.x + SHOULDER_SPREAD * height, shoulderY)
        val rearShoulder = Vec2(neck.x - SHOULDER_SPREAD * height, shoulderY)
        // Whichever direction matters is converted into local space here, so the mirror at the end
        // turns it back exactly rather than by a second set of hand-tuned signs.
        val held = motion.weaponAim ?: motion.swingDirection
        val localAim = Vec2(
            (if (action == Action.Swing) motion.swingDirection.x else held.x) * facing,
            if (action == Action.Swing) motion.swingDirection.y else held.y,
        )
        val leadHand = leadHandAt(motion, action, leadShoulder, height, localAim)
        val rearHand = rearHandAt(phase, rearShoulder, height, clip)

        val aim = when {
            action == Action.Swing -> (leadHand - leadShoulder).normalisedOr(Vec2.Right)
            motion.weaponAim != null -> localAim.normalisedOr(Vec2.Right)
            else -> Vec2.Right
        }

        return Pose(
            clip = clip,
            action = action,
            height = height,
            hip = mirror(hip, facing),
            neck = mirror(neck, facing),
            head = mirror(head, facing),
            headRadius = HEAD_RADIUS * height,
            leadShoulder = mirror(leadShoulder, facing),
            leadElbow = mirror(bend(leadShoulder, leadHand, height, ELBOW_BEND), facing),
            leadHand = mirror(leadHand, facing),
            rearShoulder = mirror(rearShoulder, facing),
            rearElbow = mirror(bend(rearShoulder, rearHand, height, ELBOW_BEND), facing),
            rearHand = mirror(rearHand, facing),
            leadKnee = mirror(bend(leadHipJoint, leadFoot, height, KNEE_BEND), facing),
            leadFoot = mirror(leadFoot, facing),
            rearKnee = mirror(bend(rearHipJoint, rearFoot, height, KNEE_BEND), facing),
            rearFoot = mirror(rearFoot, facing),
            weaponAim = mirror(aim, facing),
        )
    }

    fun clipOf(motion: Motion): Clip = when {
        !motion.onGround -> if (motion.verticalSpeed < 0.0) Clip.JumpRise else Clip.JumpFall
        motion.crouched -> if (isMoving(motion)) Clip.CrouchWalk else Clip.Crouch
        isMoving(motion) -> Clip.Run
        else -> Clip.Idle
    }

    /**
     * A swing outranks a shot because a melee weapon does not shoot, so the two never really
     * compete — and where a weapon does both, the swing is the one that resolved damage.
     */
    fun actionOf(motion: Motion): Action = when {
        motion.secondsSinceSwing in 0.0..motion.swingSeconds -> Action.Swing
        motion.secondsSinceShot in 0.0..motion.shotSeconds -> Action.Fire
        else -> Action.None
    }

    private fun isMoving(motion: Motion): Boolean = abs(motion.speedX) > MOVING_PX_PER_SECOND

    private fun bodyHeight(motion: Motion, physics: Physics): Double =
        (if (motion.crouched) physics.crouchingHeight else physics.standingHeight) * motion.scale

    /**
     * Where in the walk cycle the figure is, in degrees.
     *
     * Airborne and idle figures hold a fixed pose rather than continuing to walk on air, which is
     * what reading the phase unconditionally would give.
     */
    private fun gaitPhase(motion: Motion, clip: Clip): Double = when (clip) {
        Clip.Run, Clip.CrouchWalk -> motion.stridePx / STRIDE_PX * FULL_CYCLE
        // Zero, which cosine reads as "lead foot forward, rear foot back" — a stance, held.
        else -> 0.0
    }

    private fun leanOf(clip: Clip, forward: Double, physics: Physics): Double {
        val fraction = (forward / physics.maxRunSpeed).coerceIn(-1.0, 1.0)
        return when (clip) {
            Clip.Run -> RUN_LEAN * fraction
            Clip.CrouchWalk, Clip.Crouch -> CROUCH_LEAN
            Clip.JumpRise -> RISE_LEAN
            Clip.JumpFall -> FALL_LEAN
            Clip.Idle -> 0.0
        }
    }

    /**
     * A foot, for the half of the cycle [phase] names.
     *
     * Horizontal travel uses cosine so the two feet are furthest apart at the start of a cycle
     * rather than together — with sine they coincide at phase zero and the figure reads as standing
     * for an instant on every stride.
     */
    private fun footAt(phase: Double, height: Double, clip: Clip, motion: Motion): Vec2 = when (clip) {
        Clip.Run, Clip.CrouchWalk -> {
            val reach = (if (clip == Clip.Run) RUN_STEP else CROUCH_STEP) * height
            val lift = LIFT * height * maxOf(0.0, TrigTable.sinDegrees(phase))
            Vec2(reach * TrigTable.cosDegrees(phase), -lift)
        }

        // The still poses split the feet by the same phase the walk uses, so the rear foot is a
        // rear foot rather than the front one drawn twice. Both feet landing on one point made a
        // standing figure look one-legged and a falling one look like a plank.
        Clip.JumpRise -> Vec2(
            TUCK_X * height * TrigTable.cosDegrees(phase),
            -TUCK_Y * height * (TUCK_EVEN + TUCK_SPLIT * TrigTable.cosDegrees(phase)),
        )

        Clip.JumpFall -> Vec2(REACH_X * height * TrigTable.cosDegrees(phase), 0.0)
        Clip.Crouch -> Vec2(CROUCH_STANCE * height * TrigTable.cosDegrees(phase), 0.0)
        Clip.Idle -> Vec2(IDLE_STANCE * height * TrigTable.cosDegrees(phase), 0.0)
    }

    /**
     * The weapon hand.
     *
     * At rest it is held forward, because the weapon fires without being asked and a hand hanging at
     * the side would never be where the shot came from. Firing pulls it back and lets it settle;
     * swinging sweeps it through the arc the hit actually covered (PROD-033).
     */
    private fun leadHandAt(
        motion: Motion,
        action: Action,
        shoulder: Vec2,
        height: Double,
        localAim: Vec2,
    ): Vec2 {
        val aim = motion.weaponAim
        val rest = if (aim == null) {
            Vec2(shoulder.x + HOLD_X * height, shoulder.y + HOLD_Y * height)
        } else {
            val held = localAim.normalisedOr(Vec2.Right)
            Vec2(shoulder.x + held.x * HOLD_REACH * height, shoulder.y + held.y * HOLD_REACH * height)
        }
        return when (action) {
            Action.None -> rest

            Action.Fire -> {
                // One at the shot, easing to zero: the arm snaps back and returns.
                val recoil = 1.0 - (motion.secondsSinceShot / motion.shotSeconds).coerceIn(0.0, 1.0)
                Vec2(
                    rest.x - RECOIL_X * height * recoil,
                    rest.y - RECOIL_Y * height * recoil,
                )
            }

            Action.Swing -> {
                val progress = (motion.secondsSinceSwing / motion.swingSeconds).coerceIn(0.0, 1.0)
                val offset = SWING_ARC / 2.0 - SWING_ARC * progress
                val direction = TrigTable.rotate(localAim.normalisedOr(Vec2.Right), offset)
                Vec2(
                    shoulder.x + direction.x * ARM_REACH * height,
                    shoulder.y + direction.y * ARM_REACH * height,
                )
            }
        }
    }

    private fun rearHandAt(phase: Double, shoulder: Vec2, height: Double, clip: Clip): Vec2 {
        val swing = when (clip) {
            Clip.Run -> ARM_SWING
            Clip.CrouchWalk -> ARM_SWING / 2.0
            else -> 0.0
        }
        // Opposite the lead leg, which is what a walk does.
        return Vec2(
            shoulder.x - swing * height * TrigTable.cosDegrees(phase),
            shoulder.y + HANG * height,
        )
    }

    /**
     * A joint between two points, pushed out so the limb reads as bent.
     *
     * A two-bone solve would be exact and is not worth it: the figure is 26 px tall, and a
     * perpendicular offset from the midpoint is indistinguishable at that size while costing one
     * `sqrt` instead of two and no angle at all.
     */
    private fun bend(from: Vec2, to: Vec2, height: Double, amount: Double): Vec2 {
        val mid = Vec2((from.x + to.x) / 2.0, (from.y + to.y) / 2.0)
        val along = (to - from).normalisedOr(Vec2.Right)
        // The perpendicular, taken forward rather than backward, so knees and elbows agree.
        return Vec2(mid.x - along.y * amount * height, mid.y + along.x * amount * height)
    }

    private fun mirror(point: Vec2, facing: Double): Vec2 = Vec2(point.x * facing, point.y)

    // Proportions, all as a fraction of the figure's own height.
    private const val HIP = 0.46
    private const val NECK = 0.80
    private const val SHOULDER = 0.76
    private const val HEAD_CENTRE = 0.89
    private const val HEAD_RADIUS = 0.135
    private const val HEAD_LEAD = 1.6
    private const val LEAN_HIP = -0.4
    private const val HIP_SPREAD = 0.07
    private const val SHOULDER_SPREAD = 0.11
    private const val RUN_STEP = 0.30
    private const val CROUCH_STEP = 0.16
    private const val LIFT = 0.15
    private const val TUCK_X = 0.14
    private const val TUCK_Y = 0.24
    private const val TUCK_EVEN = 0.72
    private const val TUCK_SPLIT = 0.28
    private const val REACH_X = 0.26
    private const val CROUCH_STANCE = 0.16
    private const val IDLE_STANCE = 0.11
    private const val HOLD_X = 0.34
    private const val HOLD_REACH = 0.36
    private const val HOLD_Y = 0.10
    private const val RECOIL_X = 0.14
    private const val RECOIL_Y = 0.06
    private const val ARM_REACH = 0.40
    private const val ARM_SWING = 0.18
    private const val HANG = 0.30
    private const val KNEE_BEND = 0.05
    private const val ELBOW_BEND = 0.04
    private const val RUN_LEAN = 0.10
    private const val CROUCH_LEAN = 0.12
    private const val RISE_LEAN = 0.08
    private const val FALL_LEAN = -0.06

    private const val FULL_CYCLE = 360.0
    private const val HALF_CYCLE = 180.0
    private const val SWING_ARC = 150.0
}
