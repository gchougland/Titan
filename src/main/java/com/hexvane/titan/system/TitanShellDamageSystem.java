package com.hexvane.titan.system;

import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanShellComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Turns hits on a shell voxel into damage against the whole shell.
 *
 * <p>The voxel itself is left untouched. Every one of them carries health so that the engine will let a
 * swing land on it at all, but the hit is cancelled before it can be applied and counted against
 * {@link TitanShellComponent} instead, so the shell drains as one target and no part of it can be knocked
 * off early. Sitting in the filter group is what makes that possible: it is the last point at which damage
 * can still be refused.
 *
 * <p>Cancelling also skips {@link TitanWeakpointDamageSystem}, which is what normally tells a titan who is
 * hitting it, so the attacker is reported from here as well. For an egg that matters more than it does for
 * a boss: whoever is landing these hits is who the hatchling will belong to.
 *
 * <p>Ore nodes are not affected. A titan without a shell has no {@link TitanShellComponent} on its root,
 * and its nodes go on breaking one at a time.
 */
public final class TitanShellDamageSystem extends DamageEventSystem {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanWeakpointComponent.getComponentType(),
        TransformComponent.getComponentType());

    /**
     * After the tool bonus, which shares this group and this query. It is what makes a pickaxe worth
     * bringing, and reading the amount before it had been applied would charge the shell for a bare swing.
     */
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies =
        Set.of(new SystemDependency<>(Order.AFTER, TitanWeakpointDamageBonusSystem.class));

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

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void handle(final int index,
                       @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull final Store<EntityStore> store,
                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull final Damage damage) {

        if (damage.isCancelled()) return;

        final var weakpoint = archetypeChunk.getComponent(index, TitanWeakpointComponent.getComponentType());
        if (weakpoint == null) return;

        final Ref<EntityStore> owner = weakpoint.getOwner();
        if (owner == null || !owner.isValid()) return;

        final var shell = commandBuffer.getComponent(owner, TitanShellComponent.getComponentType());
        if (shell == null) return;

        // Refused whatever the amount, including a hit that would have done nothing anyway. The one rule
        // for a shell voxel is that nothing removes it before the shell breaks.
        damage.setCancelled(true);

        final var titan = commandBuffer.getComponent(owner, TitanComponent.getComponentType());
        if (titan == null) return;

        // ProjectileSource extends EntitySource and reports the shooter, so an egg cracked with arrows
        // still knows whose it is.
        if (damage.getSource() instanceof Damage.EntitySource entitySource && entitySource.getRef().isValid()) {
            titan.reportAttacker(entitySource.getRef());
        }

        final float amount = damage.getAmount();
        if (amount <= 0f) return;

        shell.absorb(amount);
        knock(archetypeChunk, index, commandBuffer, titan);
    }

    /**
     * Sounds and dust at the block that was hit.
     *
     * <p>The shell shows nothing for a hit on its own: the voxel does not lose health, does not flash, and
     * for a pet there is no bar anywhere to move. Without this a player would be swinging at something with
     * no sign of getting anywhere, and the only feedback would be the shell finally coming apart.
     */
    private static void knock(@Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                              final int index,
                              @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull final TitanComponent titan) {

        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        final var variant = titan.getVariant();
        if (transform == null || variant == null) return;

        final String particle = variant.getImpactParticle();
        if (particle != null && !particle.isEmpty()) {
            ParticleUtil.spawnParticleEffect(particle, transform.getPosition(), commandBuffer);
        }

        TitanSound.play(commandBuffer, variant.getImpactSound(), transform.getPosition());
    }
}
