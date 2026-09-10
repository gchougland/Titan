package com.hexvane.titan.crypt;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Persistent encounter completion and delivery receipt; combat always restarts fresh after unload. */
public final class CryptSiteComponent implements Component<EntityStore> {
    public static final String ID = "CryptSite";
    public static final BuilderCodec<CryptSiteComponent> CODEC = BuilderCodec
        .builder(CryptSiteComponent.class, CryptSiteComponent::new)
        .append(new KeyedCodec<>("Cleared", Codec.BOOLEAN), (c,v) -> c.cleared=v, c -> c.cleared).add()
        .append(new KeyedCodec<>("VictoryPending", Codec.BOOLEAN), (c,v) -> c.victoryPending=v,
            c -> c.victoryPending).add()
        .append(new KeyedCodec<>("RewardDeposited", Codec.BOOLEAN), (c,v) -> c.rewardDeposited=v,
            c -> c.rewardDeposited).add().build();
    private static ComponentType<EntityStore, CryptSiteComponent> type;
    private boolean cleared;
    private boolean victoryPending;
    private boolean rewardDeposited;
    private transient boolean active;
    private transient boolean pending;
    private transient float timer;

    static void register(IComponentRegistry<EntityStore> registry) {
        type = registry.registerComponent(CryptSiteComponent.class, ID, CODEC);
    }
    @Nonnull public static ComponentType<EntityStore, CryptSiteComponent> getComponentType() {
        if (type == null) throw new IllegalStateException("CryptSite has not been registered");
        return type;
    }
    public boolean isCleared() { return cleared; }
    public void setCleared(boolean value) { cleared = value; }
    public boolean isVictoryPending() { return victoryPending; }
    public void setVictoryPending(boolean value) { victoryPending = value; }
    public boolean isRewardDeposited() { return rewardDeposited; }
    public void setRewardDeposited(boolean value) { rewardDeposited = value; }
    /** Any persisted victory receipt permanently retires this dungeon's boss. */
    public boolean isDefeated() { return cleared || victoryPending || rewardDeposited; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public boolean isPending() { return pending; }
    public void setPending(boolean value) { pending = value; }
    float getTimer() { return timer; }
    void setTimer(float value) { timer = value; }

    @Nonnull @Override public Component<EntityStore> clone() {
        var copy = new CryptSiteComponent();
        copy.cleared = cleared;
        copy.victoryPending = victoryPending;
        copy.rewardDeposited = rewardDeposited;
        return copy;
    }
}
