package com.hexvane.titan.combat;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Riding a climbable titan/Dunewyrm platform teleports the player with the collider. Sudden drops read as
 * freefall (and kills), and sudden rises launch them. Cancel environmental damage while anchored, and soft
 * damp extreme vertical launches each tick.
 */
public final class TitanClimbFallGuardSystem {

    private TitanClimbFallGuardSystem() {
    }

    /** Apply the same rider protection to Titan's direct impulses, leaving ordinary movement alone. */
    static void softenImpulse(Store<EntityStore> store, Ref<EntityStore> player, Vector3d velocity) {
        if (store.getComponent(player, Player.getComponentType()) != null
            && TitanStandingOn.isOnClimbable(store, player)) SoftLand.soften(velocity);
    }

    /** Refuse fall / other non-entity damage while standing on climbable titan geometry. */
    public static final class FallFilter extends DamageEventSystem {

        @Nonnull
        private final Query<EntityStore> query = Archetype.of(
            Player.getComponentType(),
            TransformComponent.getComponentType());

        @Nullable
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getFilterDamageGroup();
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void handle(final int index,
                           @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull final Damage damage) {

            if (damage.isCancelled()) return;

            final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);
            if (!TitanStandingOn.isOnClimbable(store, self)) return;

            // Entity hits (contact, charge) still apply; freefall / environment does not.
            if (damage.getSource() instanceof Damage.EntitySource) return;
            damage.setCancelled(true);
        }
    }

    /**
     * Kill residual upward velocity while anchored so a jerky platform step cannot fling the player into
     * lethal freefall on the way down.
     */
    public static final class SoftLand extends com.hypixel.hytale.component.system.tick.EntityTickingSystem<EntityStore> {

        private static final float MAX_UPWARD = 0.35f;

        @Nonnull
        private final Query<EntityStore> query = Archetype.of(
            Player.getComponentType(),
            TransformComponent.getComponentType());

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void tick(final float dt,
                         final int index,
                         @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                         @Nonnull final Store<EntityStore> store,
                         @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

            final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);
            if (!TitanStandingOn.isOnClimbable(store, self)) return;

            KnockbackComponent knockback = commandBuffer.getComponent(self, KnockbackComponent.getComponentType());
            if (knockback == null) return;

            final Vector3d v = knockback.getVelocity();
            if (!soften(v)) return;
            knockback.setVelocityType(ChangeVelocityType.Set);
            knockback.setDuration(0f);
            knockback.setTimer(0f);
        }

        private static boolean soften(Vector3d velocity) {
            if (velocity.y <= MAX_UPWARD && velocity.y >= -0.05f) return false;
            velocity.set(velocity.x * 0.35,
                Math.min(MAX_UPWARD, Math.max(0.0, velocity.y * 0.15)), velocity.z * 0.35);
            return true;
        }
    }
}
