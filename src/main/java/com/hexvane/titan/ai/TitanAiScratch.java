package com.hexvane.titan.ai;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * The working values one titan needs for a tick of AI, held in one place so the hot loop allocates nothing.
 *
 * <p>Owned by {@code TitanAiSystem} and passed down to the attack handlers. The AI runs on the world thread
 * and only ever ticks one titan at a time, so a single instance per system is enough.
 */
public final class TitanAiScratch {

    /** Position of the titan's current target, valid only while target resolution reported one. */
    @Nonnull
    public final Vector3d targetPosition = new Vector3d();

    /** General-purpose vector for direction and velocity maths. */
    @Nonnull
    public final Vector3d direction = new Vector3d();

    /** Holds a candidate world point while it is being tested or built. */
    @Nonnull
    public final Vector3d point = new Vector3d();

    /** Velocity handed to riders thrown off the back. */
    @Nonnull
    public final Vector3d impulse = new Vector3d();

    /** Reused by the rider search, which runs at most once per titan per tick. */
    @Nonnull
    public final List<Ref<EntityStore>> riders = new ArrayList<>();
}
