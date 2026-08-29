package com.hexvane.titan.ik;

import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Per-limb gait state. One instance lives on the titan for each IK chain marked as a foot.
 */
public final class FootState {

    /** Where the foot currently is, in world space. This is what the IK solver aims at. */
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

    public boolean stepping;
    /** Progress through the current step, in {@code [0,1]}. */
    public float stepProgress;
    /** Alternating group so diagonally opposite limbs swing together. */
    public int gaitGroup;
    public boolean initialised;

    public void beginStep() {
        stepOrigin.set(current);
        stepping = true;
        stepProgress = 0f;
    }

    public void finishStep() {
        planted.set(stepTarget);
        current.set(stepTarget);
        stepping = false;
        stepProgress = 0f;
    }
}
