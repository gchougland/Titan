package com.hexvane.titan.npc;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanIntent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.spawn.TitanTrio;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared helpers for Role Actions that drive a linked titan. */
public final class TitanNpcSupport {

    private TitanNpcSupport() {
    }

    @Nullable
    public static TitanComponent titanOf(@Nonnull final Ref<EntityStore> brainRef,
                                         @Nonnull final Store<EntityStore> store) {
        return TitanTrio.linkedTitan(brainRef, store);
    }

    /** Whether the titan is free to start a new attack (idle/chase, cooldown clear). */
    public static boolean canStartAttack(@Nonnull final TitanComponent titan) {
        if (titan.getAttackCooldown() > 0f) return false;
        final TitanState state = titan.getState();
        return state == TitanState.IDLE || state == TitanState.CHASE || state == TitanState.SLEEPING
            || state == TitanState.WAKING;
    }

    public static void applyTarget(@Nonnull final TitanComponent titan,
                                   @Nullable final InfoProvider sensorInfo) {
        if (sensorInfo == null) return;
        final var positions = sensorInfo.getPositionProvider();
        if (positions == null) return;
        final Ref<EntityStore> target = positions.getTarget();
        if (target != null && target.isValid()) {
            titan.setTarget(target);
        }
    }

    public static boolean request(@Nonnull final TitanComponent titan,
                                  @Nonnull final TitanIntent intent,
                                  @Nullable final InfoProvider sensorInfo) {
        applyTarget(titan, sensorInfo);
        titan.setIntent(intent);
        return true;
    }
}
