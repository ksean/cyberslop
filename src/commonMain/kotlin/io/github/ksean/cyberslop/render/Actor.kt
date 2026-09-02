package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.Physics
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Poses a figure (PROD-041, PROD-067, ENG-062).
 *
 * Pure: same [Motion] in, same [Pose] out. It reads no clock, and nothing it returns is fed back
 * into the simulation — a pose that could change what the game does would put presentation inside
 * ENG-050's purity claim and stop a recorded witness meaning one thing on replay.
 *
 * The figure is built in **local space with `+x` forward**, then mirrored by facing at the end. That
 * is what makes turning around exact rather than a second set of hand-tuned numbers, and it is why
 * the swing direction is converted into local space on the way in.
 *
 * Every proportion is a fraction of the figure's **standing** height, so the same code poses a 26 px
 * player and a 56 px boss without a second rig — and so a crouch, whose box is shorter, keeps the
 * limbs of the figure that stood up: knees and elbows are solved as two-bone chains of fixed length
 * rather than pushed out from a midpoint, so bending is bending and not shrinking.
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
        // The reference every limb is sized from: the figure standing, whatever it is doing now.
        val ref = physics.standingHeight * motion.scale
        val clip = clipOf(motion)
        val action = actionOf(motion)
        val crouched = clip == Clip.Crouch || clip == Clip.CrouchWalk

        // Forward speed in the direction the figure faces, so a lean is a lean and not a sign error.
        val forward = motion.speedX * facing
        val phase = gaitPhase(motion, clip)

        val hip: Vec2
        val neck: Vec2
        val head: Vec2
        if (crouched) {
            // Hips dropped to about half their standing height and the torso folded forward, so the
            // whole figure sits inside the crouch box without a single length changing.
            hip = Vec2(0.0, -CROUCH_HIP * ref)
            val torso = Vec2(TrigTable.sinDegrees(CROUCH_FOLD), -TrigTable.cosDegrees(CROUCH_FOLD))
            neck = hip + torso * (TORSO * ref)
            head = neck + torso * (HEAD_ABOVE_NECK * ref)
        } else {
            val lean = leanOf(clip, forward, physics) * ref
            hip = Vec2(lean * LEAN_HIP, -HIP * ref)
            neck = Vec2(lean, -NECK * ref)
            head = Vec2(lean * HEAD_LEAD, -HEAD_CENTRE * ref)
        }

        val leadFoot = footAt(phase, ref, clip, motion)
        val rearFoot = footAt(phase + HALF_CYCLE, ref, clip, motion)

        val along = (neck - hip).normalisedOr(Vec2(0.0, -1.0))
        val across = Vec2(-along.y, along.x)
        val leadShoulder = neck - along * (NECK_TO_SHOULDER * ref) + across * (SHOULDER_SPREAD * ref)
        val rearShoulder = neck - along * (NECK_TO_SHOULDER * ref) - across * (SHOULDER_SPREAD * ref)
        // Whichever direction matters is converted into local space here, so the mirror at the end
        // turns it back exactly rather than by a second set of hand-tuned signs.
        val held = motion.weaponAim ?: motion.swingDirection
        val localAim = Vec2(
            (if (action == Action.Swing) motion.swingDirection.x else held.x) * facing,
            if (action == Action.Swing) motion.swingDirection.y else held.y,
        )
        val leadHand = reachable(leadShoulder, leadHandAt(motion, action, leadShoulder, ref, localAim, crouched), ARM * ref)
        val rearHand = reachable(rearShoulder, rearHandAt(phase, rearShoulder, ref, clip), ARM * ref)

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
            headRadius = HEAD_RADIUS * ref,
            leadShoulder = mirror(leadShoulder, facing),
            leadElbow = mirror(joint(leadShoulder, leadHand, ARM * ref, forward = false), facing),
            leadHand = mirror(leadHand, facing),
            rearShoulder = mirror(rearShoulder, facing),
            rearElbow = mirror(joint(rearShoulder, rearHand, ARM * ref, forward = false), facing),
            rearHand = mirror(rearHand, facing),
            leadKnee = mirror(joint(hip, leadFoot, LEG * ref, forward = true), facing),
            leadFoot = mirror(leadFoot, facing),
            rearKnee = mirror(joint(hip, rearFoot, LEG * ref, forward = true), facing),
            rearFoot = mirror(rearFoot, facing),
            weaponAim = mirror(aim, facing),
        )
    }

    /**
     * Lowers a resolved lethal-frame pose into a side-prone pose without changing any bone length.
     * The endpoints and body anchors interpolate; elbows and knees are re-solved from the rig's
     * fixed chains at every progress value rather than interpolated into shrinking limbs.
     */
    fun deathPose(
        start: Pose,
        progress: Double,
        facing: Int,
        physics: Physics = Physics.Default,
    ): Pose {
        val p = progress.coerceIn(0.0, 1.0)
        if (p == 0.0) return start

        val direction = if (facing < 0) -1.0 else 1.0
        val ref = physics.standingHeight
        val targetHip = Vec2(0.02 * ref * direction, -0.12 * ref)
        val targetNeck = Vec2(0.37 * ref * direction, -0.13 * ref)
        val targetHead = Vec2(0.49 * ref * direction, -0.12 * ref)
        val targetLeadShoulder = targetNeck + Vec2(0.0, -0.07 * ref)
        val targetRearShoulder = targetNeck + Vec2(0.0, 0.07 * ref)
        val targetLeadHand = targetNeck + Vec2(0.14 * ref * direction, 0.10 * ref)
        val targetRearHand = targetNeck + Vec2(-0.05 * ref * direction, 0.17 * ref)
        val targetLeadFoot = Vec2(-0.16 * ref * direction, 0.0)
        val targetRearFoot = Vec2(-0.28 * ref * direction, -0.01 * ref)

        val hip = interpolate(start.hip, targetHip, p)
        val neck = interpolate(start.neck, targetNeck, p)
        val leadShoulder = interpolate(start.leadShoulder, targetLeadShoulder, p)
        val rearShoulder = interpolate(start.rearShoulder, targetRearShoulder, p)
        val leadHand = reachable(
            leadShoulder,
            interpolate(start.leadHand, targetLeadHand, p),
            ARM * ref,
        )
        val rearHand = reachable(
            rearShoulder,
            interpolate(start.rearHand, targetRearHand, p),
            ARM * ref,
        )
        val leadFoot = reachable(hip, interpolate(start.leadFoot, targetLeadFoot, p), LEG * ref)
        val rearFoot = reachable(hip, interpolate(start.rearFoot, targetRearFoot, p), LEG * ref)
        val aim = interpolate(start.weaponAim, Vec2(direction, 0.0), p).normalisedOr(Vec2(direction, 0.0))

        return Pose(
            clip = Clip.Idle,
            action = Action.None,
            height = start.height + (physics.standingHeight - start.height) * p,
            hip = hip,
            neck = neck,
            head = interpolate(start.head, targetHead, p),
            headRadius = start.headRadius,
            leadShoulder = leadShoulder,
            leadElbow = joint(leadShoulder, leadHand, ARM * ref, forward = false),
            leadHand = leadHand,
            rearShoulder = rearShoulder,
            rearElbow = joint(rearShoulder, rearHand, ARM * ref, forward = false),
            rearHand = rearHand,
            leadKnee = joint(hip, leadFoot, LEG * ref, forward = true),
            leadFoot = leadFoot,
            rearKnee = joint(hip, rearFoot, LEG * ref, forward = true),
            rearFoot = rearFoot,
            weaponAim = aim,
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
     * compete — and where a weapon does both, the swing is the one that resolved damage. A
     * wind-up ranks under both: the strike it led to is the newer thing to show.
     */
    fun actionOf(motion: Motion): Action = when {
        motion.secondsSinceSwing in 0.0..motion.swingSeconds -> Action.Swing
        motion.secondsSinceShot in 0.0..motion.shotSeconds -> Action.Fire
        motion.windingUp -> Action.WindUp
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
            Clip.JumpRise -> RISE_LEAN
            Clip.JumpFall -> FALL_LEAN
            Clip.Idle, Clip.Crouch, Clip.CrouchWalk -> 0.0
        }
    }

    /**
     * A foot, for the half of the cycle [phase] names.
     *
     * Horizontal travel uses cosine so the two feet are furthest apart at the start of a cycle
     * rather than together — with sine they coincide at phase zero and the figure reads as standing
     * for an instant on every stride.
     */
    private fun footAt(phase: Double, ref: Double, clip: Clip, motion: Motion): Vec2 = when (clip) {
        Clip.Run, Clip.CrouchWalk -> {
            val reach = (if (clip == Clip.Run) RUN_STEP else CROUCH_STEP) * ref
            val lift = (if (clip == Clip.Run) LIFT else CROUCH_LIFT) * ref * maxOf(0.0, TrigTable.sinDegrees(phase))
            Vec2(reach * TrigTable.cosDegrees(phase), -lift)
        }

        // The still poses split the feet by the same phase the walk uses, so the rear foot is a
        // rear foot rather than the front one drawn twice. Both feet landing on one point made a
        // standing figure look one-legged and a falling one look like a plank.
        Clip.JumpRise -> Vec2(
            TUCK_X * ref * TrigTable.cosDegrees(phase),
            -TUCK_Y * ref * (TUCK_EVEN + TUCK_SPLIT * TrigTable.cosDegrees(phase)),
        )

        Clip.JumpFall -> Vec2(REACH_X * ref * TrigTable.cosDegrees(phase), 0.0)
        Clip.Crouch -> Vec2(CROUCH_STANCE * ref * TrigTable.cosDegrees(phase), 0.0)
        Clip.Idle -> Vec2(IDLE_STANCE * ref * TrigTable.cosDegrees(phase), 0.0)
    }

    /**
     * The weapon hand.
     *
     * At rest it is held forward, because the weapon fires without being asked and a hand hanging at
     * the side would never be where the shot came from — and low across the body in a crouch.
     * Firing pulls it back and lets it settle; swinging sweeps it through the arc the hit actually
     * covered (PROD-033); a wind-up draws it back and holds it there.
     */
    private fun leadHandAt(
        motion: Motion,
        action: Action,
        shoulder: Vec2,
        ref: Double,
        localAim: Vec2,
        crouched: Boolean,
    ): Vec2 {
        val aim = motion.weaponAim
        val rest = when {
            crouched -> Vec2(shoulder.x + CROUCH_HOLD_X * ref, shoulder.y + CROUCH_HOLD_Y * ref)
            aim == null -> Vec2(shoulder.x + HOLD_X * ref, shoulder.y + HOLD_Y * ref)
            else -> {
                val held = localAim.normalisedOr(Vec2.Right)
                Vec2(shoulder.x + held.x * HOLD_REACH * ref, shoulder.y + held.y * HOLD_REACH * ref)
            }
        }
        return when (action) {
            Action.None -> rest

            // Drawn back behind the shoulder and raised, and held there: a telegraph is a pose the
            // player has time to read, not a motion.
            Action.WindUp -> Vec2(shoulder.x - WINDUP_BACK * ref, shoulder.y - WINDUP_UP * ref)

            Action.Fire -> {
                // One at the shot, easing to zero: the arm snaps back and returns.
                val recoil = 1.0 - (motion.secondsSinceShot / motion.shotSeconds).coerceIn(0.0, 1.0)
                Vec2(
                    rest.x - RECOIL_X * ref * recoil,
                    rest.y - RECOIL_Y * ref * recoil,
                )
            }

            Action.Swing -> {
                val progress = motion.swingProgress
                    ?: (motion.secondsSinceSwing / motion.swingSeconds).coerceIn(0.0, 1.0)
                val offset = motion.swingArcDegrees?.let { -it / 2.0 + it * progress }
                    ?: (SWING_ARC / 2.0 - SWING_ARC * progress)
                val direction = TrigTable.rotate(localAim.normalisedOr(Vec2.Right), offset)
                Vec2(
                    shoulder.x + direction.x * ARM_REACH * ref,
                    shoulder.y + direction.y * ARM_REACH * ref,
                )
            }
        }
    }

    private fun rearHandAt(phase: Double, shoulder: Vec2, ref: Double, clip: Clip): Vec2 {
        val swing = when (clip) {
            Clip.Run -> ARM_SWING
            Clip.CrouchWalk -> ARM_SWING / 2.0
            else -> 0.0
        }
        val hang = if (clip == Clip.Crouch || clip == Clip.CrouchWalk) CROUCH_HANG else HANG
        // Opposite the lead leg, which is what a walk does.
        return Vec2(
            shoulder.x - swing * ref * TrigTable.cosDegrees(phase),
            shoulder.y + hang * ref,
        )
    }

    /** A target the whole chain can reach: pulled back along its line when it would overstretch. */
    private fun reachable(root: Vec2, target: Vec2, chain: Double): Vec2 {
        val offset = target - root
        val distance = offset.length
        if (distance <= chain * STRETCH) return target
        return root + offset * (chain * STRETCH / distance)
    }

    /**
     * The middle joint of a two-bone chain of total length [chain] with equal bones, from [root] to
     * [end]: the one point at bone length from both, on the forward or backward side of the line.
     * That is what keeps a thigh a thigh's length in every pose. Solving it costs one square root.
     */
    private fun joint(root: Vec2, end: Vec2, chain: Double, forward: Boolean): Vec2 {
        val bone = chain / 2.0
        val offset = end - root
        val distance = offset.length
        val along = offset.normalisedOr(Vec2(0.0, 1.0))
        val half = minOf(distance / 2.0, bone)
        val out = sqrt(maxOf(0.0, bone * bone - half * half))
        // Forward is +x in local space. For a limb hanging down, "forward" of the line is +x; the
        // perpendicular is chosen by which side of the line has the larger x.
        val perpendicular = Vec2(-along.y, along.x).let { if ((it.x > 0.0) == forward) it else it * -1.0 }
        return root + along * half + perpendicular * out
    }

    private fun mirror(point: Vec2, facing: Double): Vec2 = Vec2(point.x * facing, point.y)

    private fun interpolate(from: Vec2, to: Vec2, progress: Double): Vec2 =
        from + (to - from) * progress

    // Proportions, all as a fraction of the figure's standing height.
    private const val HIP = 0.46
    private const val NECK = 0.80
    private const val NECK_TO_SHOULDER = 0.04
    private const val TORSO = NECK - HIP
    private const val HEAD_CENTRE = 0.89
    private const val HEAD_ABOVE_NECK = HEAD_CENTRE - NECK
    private const val HEAD_RADIUS = 0.135
    private const val HEAD_LEAD = 1.6
    private const val LEAN_HIP = -0.4
    private const val SHOULDER_SPREAD = 0.11
    /** Whole-chain lengths: two equal bones each. */
    private const val LEG = 0.60
    private const val ARM = 0.44
    /** How far past the chain a target may sit before it is pulled in; slack for straight limbs. */
    private const val STRETCH = 0.999
    private const val RUN_STEP = 0.30
    private const val CROUCH_STEP = 0.09
    private const val LIFT = 0.15
    private const val CROUCH_LIFT = 0.05
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
    private const val CROUCH_HOLD_X = 0.30
    private const val CROUCH_HOLD_Y = 0.16
    private const val CROUCH_HANG = 0.16
    private const val RECOIL_X = 0.14
    private const val RECOIL_Y = 0.06
    private const val ARM_REACH = 0.40
    private const val WINDUP_BACK = 0.22
    private const val WINDUP_UP = 0.18
    private const val ARM_SWING = 0.18
    private const val HANG = 0.30
    private const val RUN_LEAN = 0.10
    private const val RISE_LEAN = 0.08
    private const val FALL_LEAN = -0.06
    /** The crouch: hips at this height and the torso folded this far forward from vertical. */
    private const val CROUCH_HIP = 0.22
    private const val CROUCH_FOLD = 70.0

    private const val FULL_CYCLE = 360.0
    private const val HALF_CYCLE = 180.0
    private const val SWING_ARC = 150.0
}
