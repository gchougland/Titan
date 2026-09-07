package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Periodic damage to players overlapping the Dunewyrm's body while it is above ground.
 */
public final class DunewyrmContactDamageSystem extends EntityTickingSystem<EntityStore> {

    /**
     * Players that already had knockback applied this world tick. Shared across every snake so a split
     * pair cannot both {@code put}/{@code ensure} KnockbackComponent in the same flush.
     */
    @Nonnull
    private static final IntOpenHashSet KNOCKBACK_THIS_TICK = new IntOpenHashSet();
    private static long knockbackTickKey = Long.MIN_VALUE;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        DunewyrmComponent.getComponentType(),
        TransformComponent.getComponentType());

    @Nonnull
    private final Vector3d push = new Vector3d();
    @Nonnull
    private final IntOpenHashSet hitPlayers = new IntOpenHashSet();

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

        final var worm = archetypeChunk.getComponent(index, DunewyrmComponent.getComponentType());
        final Ref<EntityStore> root = archetypeChunk.getReferenceTo(index);
        if (worm == null) return;
        if (worm.getState() == DunewyrmState.TUNNEL || worm.getState() == DunewyrmState.DYING) return;

        // World ticks are single-threaded; a monotonic key per system pass is enough to share the set.
        final long tickKey = store.getExternalData().getWorld().getTick();
        if (tickKey != knockbackTickKey) {
            knockbackTickKey = tickKey;
            KNOCKBACK_THIS_TICK.clear();
        }

        worm.setContactTimer(worm.getContactTimer() - dt);
        if (worm.getContactTimer() > 0f) return;
        worm.setContactTimer(DunewyrmTuning.CONTACT_INTERVAL);

        final var variant = worm.getVariant();
        final float damageAmount = variant != null
            ? Math.max(DunewyrmTuning.CONTACT_DAMAGE, variant.getAttackDamage() * 0.35f)
            : DunewyrmTuning.CONTACT_DAMAGE;
        final float scaled = damageAmount * TitanConfig.get().getAttackDamageMultiplier();
        final int cause = DamageCause.getAssetMap().getIndex("Physical");
        boolean hitAnyone = false;
        hitPlayers.clear();

        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY
                && segment.getRole() != DunewyrmSegmentRole.HEAD) {
                continue;
            }
            for (final Ref<EntityStore> victim : TargetUtil.getAllEntitiesInCylinder(
                segment.getPosition(), DunewyrmTuning.CONTACT_RADIUS, DunewyrmTuning.CONTACT_RADIUS, store)) {
                if (store.getComponent(victim, Player.getComponentType()) == null) continue;
                final int victimIndex = victim.getIndex();
                if (!hitPlayers.add(victimIndex)) continue;

                DamageSystems.executeDamage(victim, commandBuffer,
                    new Damage(new Damage.EntitySource(root), cause, scaled));
                hitAnyone = true;
                applyKnockback(store, commandBuffer, victim, segment.getPosition(), worm.getYaw());
            }
        }

        if (hitAnyone && worm.getState() == DunewyrmState.CHARGE) {
            TitanSound.play(commandBuffer,
                variant != null ? variant.getImpactSound() : DunewyrmTuning.BREAK_SOUND,
                worm.getHeadPosition());
        }
    }

    private void applyKnockback(@Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final Ref<EntityStore> victim,
                                @Nonnull final Vector3d from,
                                final float yaw) {

        final int victimIndex = victim.getIndex();
        if (!KNOCKBACK_THIS_TICK.add(victimIndex)) return;

        final var victimTransform = store.getComponent(victim, TransformComponent.getComponentType());
        if (victimTransform == null) return;

        push.set(victimTransform.getPosition()).sub(from);
        push.y = 0;
        if (push.lengthSquared() < 1e-4) {
            push.set(DunewyrmAiSystem.forwardX(yaw), 0, DunewyrmAiSystem.forwardZ(yaw));
        } else {
            push.normalize();
        }
        final float strength = DunewyrmTuning.CONTACT_KNOCKBACK
            * TitanConfig.get().getAttackKnockbackMultiplier();
        push.mul(strength);
        push.y = Math.min(0.12f, strength * 0.35f);
        final float hack = DamageSystems.HackKnockbackValues.PLAYER_KNOCKBACK_SCALE;
        if (hack > 1f) {
            push.x /= hack;
            push.z /= hack;
        }

        // Never ensureAndGetComponent — a second queued add in the same flush crashes. Prefer an existing
        // component; otherwise put a fresh one once (guarded by KNOCKBACK_THIS_TICK above).
        KnockbackComponent knockback = commandBuffer.getComponent(victim, KnockbackComponent.getComponentType());
        if (knockback == null) {
            knockback = new KnockbackComponent();
            commandBuffer.putComponent(victim, KnockbackComponent.getComponentType(), knockback);
        }
        knockback.setVelocity(push);
        knockback.setVelocityType(ChangeVelocityType.Set);
        knockback.setDuration(0f);
        knockback.setTimer(0f);
    }
}
