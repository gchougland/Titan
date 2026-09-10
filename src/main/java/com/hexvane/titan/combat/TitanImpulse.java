package com.hexvane.titan.combat;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/** One-shot movement in blocks per second, delivered through the engine's velocity instruction system. */
public final class TitanImpulse {
    private TitanImpulse() { }

    public static void set(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull Ref<EntityStore> victim, @Nonnull Vector3d impulse) {
        if (!victim.isValid()) return;
        var velocity = commandBuffer.getComponent(victim, Velocity.getComponentType());
        if (velocity == null) {
            velocity = new Velocity();
            commandBuffer.putComponent(victim, Velocity.getComponentType(), velocity);
        }
        // Instructions retain their vector. Attack systems reuse scratch vectors between victims.
        var copy = new Vector3d(impulse);
        TitanClimbFallGuardSystem.softenImpulse(commandBuffer.getStore(), victim, copy);
        velocity.addInstruction(copy, null, ChangeVelocityType.Set);
    }
}
