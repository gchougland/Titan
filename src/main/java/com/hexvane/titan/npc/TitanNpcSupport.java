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
        return state == TitanState.IDLE || state == TitanState.CHASE;
    }

    /** Shared role branches may propose a move that this variant has deliberately disabled. */
    public static boolean canRequest(@Nonnull final TitanComponent titan, @Nonnull final TitanIntent intent) {
        if (intent == TitanIntent.WAKE) return titan.getState() == TitanState.SLEEPING;
        if (intent == TitanIntent.CHASE) return titan.getState() != TitanState.DYING;
        if (!canStartAttack(titan)) return false;
        final var variant = titan.getVariant();
        if (variant == null || variant.isPet() || variant.isPassive() && !titan.isProvoked()) return false;
        final boolean stomp = variant.getStompChance() > 0 && titan.getFeet().length > 0;
        return switch (intent) {
            case MELEE -> variant.getSmashChance() > 0 || variant.getSlamChance() > 0 || variant.getPoundChance() > 0 || stomp;
            case SLAM -> variant.getSlamChance() > 0;
            case POUND -> variant.getPoundChance() > 0;
            case HURL -> variant.getHurlChance() > 0;
            case PLOW -> variant.getPlowChance() > 0;
            case STOMP -> stomp;
            default -> false;
        };
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
