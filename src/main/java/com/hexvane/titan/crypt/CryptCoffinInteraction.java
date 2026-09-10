package com.hexvane.titan.crypt;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Awakens the encounter. The coffin never opens an inventory or other player window. */
public final class CryptCoffinInteraction extends SimpleBlockInteraction {
    public static final String TYPE = "CryptCoffin";
    public static final BuilderCodec<CryptCoffinInteraction> CODEC = BuilderCodec
        .builder(CryptCoffinInteraction.class, CryptCoffinInteraction::new, SimpleBlockInteraction.CODEC).build();

    @Override protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commands,
                                               @Nonnull InteractionType type, @Nonnull InteractionContext context,
                                               @Nullable ItemStack held, @Nonnull Vector3i position,
                                               @Nonnull CooldownHandler cooldown) {
        var player = context.getEntity();
        var store = player.getStore();
        var siteRef = CryptSiteSystem.findSite(store, position);
        if (siteRef == null || !siteRef.isValid()) {
            CryptSiteSystem.message(store, player, "The crypt is still settling. Step closer and try again.");
            return;
        }
        var site = commands.getComponent(siteRef, CryptSiteComponent.getComponentType());
        if (site == null) return;
        if (site.isDefeated() || !site.isActive() && !site.isPending()) {
            world.execute(() -> CryptSiteSystem.activate(store, siteRef, player));
        }
    }

    @Override protected void simulateInteractWithBlock(@Nonnull InteractionType type,
                                                        @Nonnull InteractionContext context,
                                                        @Nullable ItemStack held,
                                                        @Nonnull World world,
                                                        @Nonnull Vector3i position) { }
}
