package com.hexvane.titan.system;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rewards bringing the right tool to an ore node.
 *
 * <p>Every pickaxe in the game overrides its damage against entities to a flat one point whatever it is
 * made of, which would leave a node taking a hundred-odd swings; the multiplier undoes that. A mace is
 * already a heavy blunt weapon doing real damage, so it only needs a nudge to come second.
 *
 * <p>Runs in the filter group, where damage is still being adjusted, unlike
 * {@link TitanWeakpointDamageSystem}, which watches the result.
 */
public final class TitanWeakpointDamageBonusSystem extends DamageEventSystem {

    /** Gather type a tool must handle to count as a pickaxe. Matched on purpose, not name, so mods count. */
    private static final String ROCK_GATHER_TYPE = "Rocks";
    private static final String ORE_GATHER_PREFIX = "Ore";

    /** The vanilla tag every mace carries, so modded ones that declare the family are covered too. */
    private static final String MACE_FAMILY_TAG = "Family=Mace";

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(TitanWeakpointComponent.getComponentType());

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

        if (damage.isCancelled() || damage.getAmount() <= 0f) return;

        // Only a swing counts: an arrow also arrives as an entity source naming the archer, whose hand
        // holds the bow that fired it.
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) return;
        if (entitySource instanceof Damage.ProjectileSource) return;

        final Ref<EntityStore> attacker = entitySource.getRef();
        if (!attacker.isValid()) return;

        final ItemStack held = InventoryComponent.getItemInHand(commandBuffer, attacker);
        if (held == null || held.isEmpty()) return;

        final Item item = held.getItem();
        final var config = TitanConfig.get();

        if (isRockBreakingTool(item)) {
            damage.setAmount(damage.getAmount() * config.getPickaxeDamageMultiplier());
        } else if (isMace(item)) {
            damage.setAmount(damage.getAmount() * config.getMaceDamageMultiplier());
        }
    }

    private static boolean isRockBreakingTool(@Nonnull final Item item) {
        final var tool = item.getTool();
        if (tool == null) return false;

        final var specs = tool.getSpecs();
        if (specs == null) return false;

        for (final var spec : specs) {
            final String gatherType = spec.getGatherType();
            if (gatherType == null) continue;
            if (ROCK_GATHER_TYPE.equals(gatherType) || gatherType.startsWith(ORE_GATHER_PREFIX)) return true;
        }
        return false;
    }

    private static boolean isMace(@Nonnull final Item item) {
        if (item.getWeapon() == null) return false;

        final int tag = AssetRegistry.getTagIndex(MACE_FAMILY_TAG);
        if (tag == AssetRegistry.TAG_NOT_FOUND) return false;

        final var data = item.getData();
        return data != null && data.getExpandedTagIndexes().contains(tag);
    }
}
