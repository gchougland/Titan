package com.hexvane.titan.entity;

import javax.annotation.Nonnull;

/**
 * The titan behaviour state machine.
 *
 * <pre>
 * SLEEPING --player nearby--&gt; WAKING --clip ends--&gt; IDLE &lt;--&gt; CHASE --in range--+
 *                                                                               |
 *                    CHASE &lt;-- RECOVER &lt;-- STUNNED &lt;-- SMASH &lt;-- WINDUP &lt;-------+
 *                    CHASE &lt;-- RISING  &lt;-- PRONE   &lt;-- SLAM  &lt;-- SLAM_WINDUP &lt;--+
 * </pre>
 *
 * <p>The two attacks are both a hit followed by a window where the titan cannot defend itself. The arm
 * smash buries one fist and leaves that arm as a ramp; the body slam puts the whole creature on the floor,
 * which is slower to recover from and drops the back within reach.
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
    /** Falling apart. */
    DYING("Death");

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

    public boolean isAttacking() {
        return isArmSmash() || isBodySlam();
    }

    public boolean isAlive() {
        return this != DYING;
    }
}
