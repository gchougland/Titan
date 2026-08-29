package com.hexvane.titan.entity;

import javax.annotation.Nonnull;

/**
 * The titan behaviour state machine.
 *
 * <pre>
 * SLEEPING --player nearby--&gt; WAKING --clip ends--&gt; IDLE &lt;--&gt; CHASE --in range--&gt; WINDUP
 *                                                                                    |
 *                                     CHASE &lt;-- RECOVER &lt;-- STUNNED &lt;-- SMASH &lt;------+
 * </pre>
 *
 * Losing all weakpoints jumps straight to {@link #DYING} from anywhere.
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

    public boolean isAttacking() {
        return this == WINDUP || this == SMASH || this == STUNNED || this == RECOVER;
    }

    public boolean isAlive() {
        return this != DYING;
    }
}
