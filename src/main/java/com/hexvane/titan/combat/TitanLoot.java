package com.hexvane.titan.combat;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns a dead titan into a pile of ore.
 *
 * <p>A variant can use a drop list, a flat item id, or both; the fixed item is what makes a Mithril talus
 * reliably worth killing while the list adds variety on top.
 */
public final class TitanLoot {

    /** Cap on stacks spawned at once, so a misconfigured drop list cannot flood the world with entities. */
    private static final int MAX_STACKS = 64;

    private TitanLoot() {
    }

    public static void drop(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final TitanVariantAsset variant,
                            @Nonnull final Vector3d position) {

        final List<ItemStack> stacks = new ArrayList<>();

        final String dropItem = variant.getDropItem();
        if (dropItem != null && !dropItem.isEmpty()) {
            final int min = Math.max(0, variant.getDropCountMin());
            final int max = Math.max(min, variant.getDropCountMax());
            final int count = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            if (count > 0) stacks.add(new ItemStack(dropItem, count));
        }

        final String dropList = variant.getDropList();
        final var itemModule = ItemModule.get();
        if (dropList != null && !dropList.isEmpty() && itemModule.isEnabled()) {
            stacks.addAll(itemModule.getRandomItemDrops(dropList));
        }

        if (stacks.isEmpty()) return;
        if (stacks.size() > MAX_STACKS) stacks.subList(MAX_STACKS, stacks.size()).clear();

        final var dropPosition = new Vector3d(position).add(0, 1, 0);
        commandBuffer.addEntities(
            ItemComponent.generateItemDrops(commandBuffer, stacks, dropPosition, Rotation3f.IDENTITY),
            AddReason.SPAWN);
    }
}
