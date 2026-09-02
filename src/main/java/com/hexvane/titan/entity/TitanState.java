package com.hexvane.titan.entity;

import javax.annotation.Nonnull;

/**
 * The titan behaviour state machine.
 *
 * <pre>
 * SLEEPING --player nearby--&gt; WAKING --clip ends--&gt; IDLE &lt;--&gt; CHASE --in range--+
 *                                                                               |
 *                    CHASE &lt;-- RECOVER      &lt;-- STUNNED       &lt;-- SMASH &lt;-- WINDUP &lt;-------+
 *                    CHASE &lt;-- RISING       &lt;-- PRONE         &lt;-- SLAM  &lt;-- SLAM_WINDUP &lt;--+
 *                    CHASE &lt;-- POUND_RECOVER &lt;-- POUND_STUNNED &lt;-- POUND &lt;-- POUND_WINDUP &lt;-+
 *                    CHASE &lt;-- HURL_RECOVER                    &lt;-- HURL  &lt;-- HURL_WINDUP &lt;-+
 *                    CHASE &lt;-- PLOW_RECOVER                    &lt;-- PLOW  &lt;-- PLOW_WINDUP &lt;-+
 *                    CHASE &lt;-- STOMP_RECOVER                   &lt;-- STOMP &lt;-- STOMP_WINDUP &lt;+
 * </pre>
 *
 * <p>Every attack is a hit followed by a window where the titan cannot defend itself, and each one opens a
 * different way in. The arm smash buries one fist and leaves that arm as a ramp; the ground pound buries
 * both and offers two; the body slam and the head plow put the whole creature on the floor with its back
 * within jumping range. Only the boulder throw is answered from a distance, and its recovery is short to
 * match — there is nothing to climb when the titan never came near you. The leg stomp is the exception
 * that offers nothing: a titan too large to climb has no reason to hand out a way up.
 *
 * <p>Losing all weakpoints jumps straight to {@link #DYING} from anywhere.
 */
public enum TitanState {
    /** Curled into a boulder. No clip plays and no transforms are sent. */
    SLEEPING("Sleep"),
    /** One-shot stand-up. */
    WAKING("Wake"),
    /** Awake but with nothing to chase. */
    IDLE("Idle"),
    /** Walking towards the target with IK feet. */
    CHASE("Walk"),
    /** Arm raised, committed to the smash. */
    WINDUP("Idle"),
    /** Arm driving down; the impact fires partway through. */
    SMASH("Idle"),
    /** Hand embedded in the ground — the climbing window. */
    STUNNED("Stunned"),
    /** Pulling the arm free. */
    RECOVER("Idle"),
    /** Reared back on the hind legs, about to throw the whole body forward. */
    SLAM_WINDUP("Slam_Windup"),
    /** Body pitching down onto the ground; the impact fires partway through. */
    SLAM("Slam"),
    /** Chest down, both forearms out front — the long climbing window. */
    PRONE("Prone"),
    /** Pushing back up off the floor. */
    RISING("Rise"),

    /** Both fists hauled overhead, about to come down together. */
    POUND_WINDUP("Pound_Windup"),
    /** Both fists driving into the ground; the launch fires partway through. */
    POUND("Pound"),
    /** Both fists embedded — two arm ramps at once, and the widest way up there is. */
    POUND_STUNNED("Stunned"),
    /** Hauling both arms back out of the ground. */
    POUND_RECOVER("Idle"),

    /** One arm reaching into the ground for a boulder, then tearing it loose. */
    HURL_WINDUP("Hurl_Windup"),
    /** Throwing it. The boulder leaves the hand partway through. */
    HURL("Hurl"),
    /** Following through after the throw. Short: the titan never left its feet. */
    HURL_RECOVER("Idle"),

    /** Rearing up and pitching the slab down, arms sweeping back. */
    PLOW_WINDUP("Plow_Windup"),
    /** Grinding forward with the front edge buried, shovelling whatever is in the way. */
    PLOW("Plow"),
    /** Beached at the end of the run, front down and back low. */
    PLOW_RECOVER("Plow_Recover"),

    /** One leg hauled up off the ground with the spot under it marked. */
    STOMP_WINDUP("Idle"),
    /** That leg driving back down; the impact fires as it lands. */
    STOMP("Idle"),
    /** Leg planted, weight still on it, before the gait takes it back. */
    STOMP_RECOVER("Idle"),

    /** Falling apart. */
    DYING("Death"),
    /**
     * Standing still playing whatever clip was handed to it, with the AI and the IK both stood down.
     *
     * <p>Nothing enters this on its own; it exists for commands that want a clip shown exactly as authored,
     * without planted feet dragging the legs back onto the terrain.
     */
    EMOTING("Idle");

    @Nonnull
    private final String defaultClip;

    TitanState(@Nonnull final String defaultClip) {
        this.defaultClip = defaultClip;
    }

    /**
     * Clip played on entering this state. Attack states resolve to a side-specific clip instead and
     * ignore this value.
     */
    @Nonnull
    public String getDefaultClip() {
        return defaultClip;
    }

    /** Whether one arm is under IK, driving a fist to the impact point. */
    public boolean isArmSmash() {
        return this == WINDUP || this == SMASH || this == STUNNED || this == RECOVER;
    }

    /** Whether both arms are under IK, bracing the body against the floor. */
    public boolean isBodySlam() {
        return this == SLAM_WINDUP || this == SLAM || this == PRONE || this == RISING;
    }

    /** Whether both arms are under IK, driving a pair of fists at one point. */
    public boolean isGroundPound() {
        return this == POUND_WINDUP || this == POUND || this == POUND_STUNNED || this == POUND_RECOVER;
    }

    /** Whether one arm is under IK, working a boulder out of the ground and throwing it. */
    public boolean isBoulderThrow() {
        return this == HURL_WINDUP || this == HURL || this == HURL_RECOVER;
    }

    /** Whether both arms are under IK, swept back out of the way of the plough. */
    public boolean isHeadPlow() {
        return this == PLOW_WINDUP || this == PLOW || this == PLOW_RECOVER;
    }

    /**
     * Whether one leg is under IK, lifted clear of the gait and aimed at the impact point.
     *
     * <p>The odd one out among the attacks: everything else borrows an arm, which the animation system
     * treats as an override on top of the clip. A stomp takes a foot away from the walk planner instead,
     * so the other legs have to keep holding the body up while it happens.
     */
    public boolean isLegStomp() {
        return this == STOMP_WINDUP || this == STOMP || this == STOMP_RECOVER;
    }

    /**
     * Whether one arm is doing the work and the other should fade back to its clip pose. Both the smash and
     * the boulder throw are single-armed, and both aim the working hand at {@code attackPoint}.
     */
    public boolean isOneArmed() {
        return isArmSmash() || isBoulderThrow();
    }

    /** Whether both arms are pinned to IK goals rather than following the clip. */
    public boolean isTwoArmed() {
        return isBodySlam() || isGroundPound() || isHeadPlow();
    }

    public boolean isAttacking() {
        return isOneArmed() || isTwoArmed() || isLegStomp();
    }

    /** Whether this is a windup, and so the point at which the attack should be telegraphed. */
    public boolean isWindup() {
        return this == WINDUP || this == SLAM_WINDUP || this == POUND_WINDUP
            || this == HURL_WINDUP || this == PLOW_WINDUP || this == STOMP_WINDUP;
    }

    public boolean isAlive() {
        return this != DYING;
    }
}
