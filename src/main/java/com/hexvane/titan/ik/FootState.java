package com.hexvane.titan.ik;

import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Per-limb gait state. One instance lives on the titan for each IK chain marked as a foot.
 */
public final class FootState {

    /** Current world-space foot position; the goal the IK solver aims at. */
    @Nonnull
    public final Vector3d current = new Vector3d();
    /** Contact point the foot is locked to while grounded. */
    @Nonnull
    public final Vector3d planted = new Vector3d();
    /** Contact point the in-flight step is heading for. */
    @Nonnull
    public final Vector3d stepTarget = new Vector3d();
    /** Contact point the in-flight step left from. */
    @Nonnull
    public final Vector3d stepOrigin = new Vector3d();

    /** Whether the foot is off the ground, arcing towards {@link #stepTarget}. */
    public boolean stepping;
    /** Progress through the current step, in {@code [0,1]}. */
    public float stepProgress;
    /** Alternating group so diagonally opposite limbs swing together. */
    public int gaitGroup;
    /** Cleared until the first tick has snapped the foot onto the terrain. */
    public boolean initialised;

    /** Lifts the foot off its current position and starts a step towards {@link #stepTarget}. */
    public void beginStep() {
        stepOrigin.set(current);
        stepping = true;
        stepProgress = 0f;
    }

    /** Plants the foot at the step target and ends the step. */
    public void finishStep() {
        planted.set(stepTarget);
        current.set(stepTarget);
        stepping = false;
        stepProgress = 0f;
    }
}
