package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.builtin.worldgen.modifier.content.Content;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.prefab.PrefabCopyableComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Dungeon lifetime and the single persistent reward. All mutations run on the world thread. */
public final class CryptSiteSystem {
    public static final String COFFIN_ID = "Titan_Crypt_Coffin";
    public static final String STAFF_ID = "Titan_Staff_Crypt_Keeper";
    static final String EMPTY_COFFIN_MESSAGE = "The coffin lies empty.";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static EncounterStarter starter;
    private static ResourceType<EntityStore, CryptVictoryMemory> victoryMemoryType;

    private CryptSiteSystem() { }

    @FunctionalInterface
    public interface EncounterStarter {
        boolean start(Store<EntityStore> store, Ref<EntityStore> siteRef,
                      CryptArena arena, Ref<EntityStore> playerRef);
    }

    public static void setEncounterStarter(@Nonnull EncounterStarter value) { starter = value; }

    public static void register(@Nonnull PluginBase plugin) {
        var registry = plugin.getEntityStoreRegistry();
        CryptSiteComponent.register(registry);
        victoryMemoryType = registry.registerResource(CryptVictoryMemory.class, CryptVictoryMemory.ID, CryptVictoryMemory.CODEC);
        plugin.getCodecRegistry(Content.TYPE_CODEC).register(CryptPrefabContent.ID, CryptPrefabContent.class, CryptPrefabContent.CODEC);
        Interaction.CODEC.register(CryptCoffinInteraction.TYPE, CryptCoffinInteraction.class, CryptCoffinInteraction.CODEC);
        registry.registerSystem(new EnsureComponents());
        registry.registerSystem(new RestoreOnAdd());
        registry.registerSystem(new Maintain());
    }

    private static Query<EntityStore> query() {
        return Archetype.of(CryptSiteComponent.getComponentType(), TransformComponent.getComponentType());
    }

    @Nullable
    public static CryptArena arena(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) return null;
        var transform = store.getComponent(ref, TransformComponent.getComponentType());
        return transform == null ? null : new CryptArena(transform.getPosition(), transform.getRotation().yaw());
    }

    @Nullable
    public static Ref<EntityStore> findSite(@Nonnull Store<EntityStore> store, @Nonnull Vector3i coffin) {
        @SuppressWarnings("unchecked") Ref<EntityStore>[] found = new Ref[1];
        store.forEachChunk(query(), (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                var transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) continue;
                var arena = new CryptArena(transform.getPosition(), transform.getRotation().yaw());
                if (arena.coffinBlock().equals(coffin)) { found[0] = chunk.getReferenceTo(i); return; }
            }
        });
        return found[0];
    }

    /** Durable guard shared by coffin activation, direct encounter starts, and administrator actions. */
    public static boolean canStart(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> siteRef,
                                    @Nonnull CryptArena requestedArena) {
        if (siteRef == null || !siteRef.isValid() || siteRef.getStore() != store) return false;
        var site = store.getComponent(siteRef, CryptSiteComponent.getComponentType());
        var actualArena = arena(store, siteRef);
        if (site == null || actualArena == null || actualArena.center().distanceSquared(requestedArena.center()) > .01
            || !actualArena.coffinBlock().equals(requestedArena.coffinBlock())) return false;
        // activate() reserves active/pending before invoking its starter; those flags are not victories.
        return !site.isDefeated() && !store.getResource(victoryMemoryType).hasVictory(actualArena.coffinBlock());
    }

    static boolean isDefeated(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> siteRef) {
        if (!siteRef.isValid()) return false;
        var site = store.getComponent(siteRef, CryptSiteComponent.getComponentType());
        var arena = arena(store, siteRef);
        return site != null && (site.isDefeated()
            || arena != null && store.getResource(victoryMemoryType).hasVictory(arena.coffinBlock()));
    }

    /** The interaction validates distance through SimpleBlockInteraction before reaching this method. */
    static void activate(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> siteRef,
                         @Nonnull Ref<EntityStore> playerRef) {
        if (!siteRef.isValid() || !playerRef.isValid()) return;
        var site = store.getComponent(siteRef, CryptSiteComponent.getComponentType());
        var arena = arena(store, siteRef);
        var playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (site == null || arena == null || playerTransform == null || !arena.contains(playerTransform.getPosition())) return;
        if (site.isDefeated()) {
            message(store, playerRef, EMPTY_COFFIN_MESSAGE);
            return;
        }
        reconcile(store, siteRef);
        if (isDefeated(store, siteRef)) {
            message(store, playerRef, EMPTY_COFFIN_MESSAGE);
            return;
        }
        if (!canStart(store, siteRef, arena)) return;
        if (site.isActive() || site.isPending()) return;
        if (adoptLiveEncounter(store, siteRef, site, arena)) return;
        site.setPending(true);
        try {
            if (starter == null) {
                message(store, playerRef, "The crypt encounter is unavailable.");
                return;
            }
            site.setActive(true);
            if (starter.start(store, siteRef, arena, playerRef)) {
                setCoffinState(store.getExternalData().getWorld(), arena, "Open");
            } else {
                site.setActive(false);
                setCoffinState(store.getExternalData().getWorld(), arena, "Closed");
                message(store, playerRef, "The crypt could not awaken. Try again in a moment.");
            }
        } catch (RuntimeException exception) {
            site.setActive(false);
            LOGGER.atWarning().withCause(exception).log("Crypt encounter could not start at %s", arena.center());
            message(store, playerRef, "The crypt could not awaken. Try again in a moment.");
        } finally {
            site.setPending(false);
        }
    }

    /** Commit the final blow before playing the death sequence, including when the marker unloaded. */
    public static void beginVictory(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> siteRef,
                                    @Nonnull CryptArena arena) {
        var memory = store.getResource(victoryMemoryType);
        if (memory.recordVictory(arena.coffinBlock())) saveMemory(store, memory);
        var live = liveSite(store, siteRef, arena);
        if (live == null) return;
        var site = store.getComponent(live, CryptSiteComponent.getComponentType());
        if (site != null && !site.isCleared()) site.setVictoryPending(true);
    }

    /** Call after the dramatic death sequence. One persistent staff materializes atop the coffin. */
    public static void victory(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> siteRef) {
        if (siteRef == null || !siteRef.isValid()) return;
        var arena = arena(store, siteRef);
        if (arena != null) victory(store, siteRef, arena);
    }

    public static void victory(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> siteRef,
                                @Nonnull CryptArena arena) {
        beginVictory(store, siteRef, arena);
        siteRef = liveSite(store, siteRef, arena);
        if (siteRef == null) return; // The saved journal completes delivery when this marker loads.
        var site = store.getComponent(siteRef, CryptSiteComponent.getComponentType());
        if (site == null) return;
        site.setCleared(true);
        site.setVictoryPending(false);
        site.setActive(false);
        site.setPending(false);
        reconcile(store, siteRef);
    }

    /** Wipes are despawns and never invoke victory or generate a reward. */
    public static void reset(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> siteRef) {
        if (siteRef == null || !siteRef.isValid()) return;
        var site = store.getComponent(siteRef, CryptSiteComponent.getComponentType());
        if (site == null || site.isCleared()) return;
        var arena = arena(store, siteRef);
        if (arena == null) return;
        if (isDefeated(store, siteRef)) {
            reconcile(store, siteRef);
            return;
        }
        site.setActive(false);
        site.setPending(false);
        setCoffinState(store.getExternalData().getWorld(), arena, "Closed");
    }

    static void message(Store<EntityStore> store, Ref<EntityStore> player, String value) {
        var ref = store.getComponent(player, PlayerRef.getComponentType());
        if (ref != null) ref.sendMessage(Message.raw(value));
    }

    private static void reconcile(Store<EntityStore> store, Ref<EntityStore> siteRef) {
        if (!siteRef.isValid()) return;
        var site = store.getComponent(siteRef, CryptSiteComponent.getComponentType());
        var arena = arena(store, siteRef);
        if (site == null || arena == null) return;
        var memory = store.getResource(victoryMemoryType);
        boolean changed = false;
        if (site.isDefeated()) changed = memory.recordVictory(arena.coffinBlock());
        if (site.isRewardDeposited()) changed |= memory.recordDeposit(arena.coffinBlock());
        if (changed) saveMemory(store, memory);
        if (memory.hasDeposit(arena.coffinBlock())) site.setRewardDeposited(true);
        if (!site.isCleared() && memory.hasVictory(arena.coffinBlock())) {
            site.setVictoryPending(true);
            if (adoptLiveEncounter(store, siteRef, site, arena)) return;
            site.setCleared(true);
            site.setVictoryPending(false);
            site.setActive(false);
            site.setPending(false);
        }
        if (site.isActive() || site.isPending()) return;
        if (!site.isCleared() && adoptLiveEncounter(store, siteRef, site, arena)) return;
        var world = store.getExternalData().getWorld();
        if (site.isCleared()) {
            setCoffinState(world, arena, "Reward");
            boolean spawned = !site.isRewardDeposited() && spawnReward(store, arena, site);
            if (site.isRewardDeposited() && memory.recordDeposit(arena.coffinBlock())) saveMemory(store, memory);
            if (spawned) {
                CryptFx.burst(store, "Crypt_Loot", arena.rewardPoint(), 1);
                CryptFx.sound(store, "SFX_Crypt_Loot", arena.rewardPoint());
            }
        } else setCoffinState(world, arena, "Closed");
    }

    @Nullable
    private static Ref<EntityStore> liveSite(Store<EntityStore> store, Ref<EntityStore> ref, CryptArena arena) {
        if (ref != null && ref.isValid() && store.getComponent(ref, CryptSiteComponent.getComponentType()) != null) return ref;
        return findSite(store, arena.coffinBlock());
    }

    private static void saveMemory(Store<EntityStore> store, CryptVictoryMemory memory) {
        // Rare final-blow/delivery writes finish in order before the world can unload.
        // The engine writer uses a shared temporary path, so overlapping saves are unsafe.
        try {
            store.getResourceStorage().save(store, store.getRegistry().getData(), victoryMemoryType, memory.clone()).join();
        } catch (java.util.concurrent.CompletionException error) {
            LOGGER.atSevere().withCause(error).log("Could not save Crypt Keeper victory journal");
        }
    }

    /** Rebind an encounter if its marker unloaded while another room chunk stayed active. */
    private static boolean adoptLiveEncounter(Store<EntityStore> store, Ref<EntityStore> siteRef,
                                              CryptSiteComponent site, CryptArena arena) {
        if (CryptBossComponent.getComponentType() == null) return false;
        boolean[] found = {false};
        store.forEachChunk(CryptBossComponent.getComponentType(), (chunk, ignored) -> {
            for (int i=0; i<chunk.size(); i++) {
                var boss = chunk.getComponent(i, CryptBossComponent.getComponentType());
                if (boss == null || boss.finishing || boss.arena == null) continue;
                if (boss.arena.center().distanceSquared(arena.center()) > .01) continue;
                boss.site = siteRef;
                site.setActive(true);
                found[0] = true;
            }
        });
        return found[0];
    }

    private static boolean spawnReward(Store<EntityStore> store, CryptArena arena, CryptSiteComponent site) {
        Vector3i p = arena.coffinBlock();
        var chunks = store.getExternalData().getWorld().getChunkStore();
        var sectionRef = chunks.getChunkSectionReferenceAtBlock(p.x, p.y, p.z);
        if (sectionRef == null || !sectionRef.isValid()) return false; // Retry when the coffin is loaded.
        var section = chunks.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return false;
        var block = BlockType.getAssetMap().getAsset(section.get(p.x, p.y, p.z));
        if (block == null || !block.getId().contains(COFFIN_ID)) return false;
        var point = arena.rewardPoint();
        var itemSection = chunks.getChunkSectionReferenceAtBlock((int)Math.floor(point.x),
            (int)Math.floor(point.y), (int)Math.floor(point.z));
        if (itemSection == null || !itemSection.isValid()) return false;
        var holder = ItemComponent.generateItemDrop(store, new ItemStack(STAFF_ID, 1), point,
            Rotation3f.IDENTITY, 0, 0, 0);
        if (holder == null) return false;
        // A unique boss reward waits for its owner and uses ordinary saved item physics/pickup.
        holder.removeComponent(DespawnComponent.getComponentType());
        holder.getComponent(ItemComponent.getComponentType()).setPickupDelay(1.25f);
        var drop = store.addEntity(holder, AddReason.SPAWN);
        if (drop == null || !drop.isValid()) return false;
        // Keep the existing codec key as a backwards-compatible delivery receipt.
        site.setRewardDeposited(true);
        return true;
    }

    private static void setCoffinState(World world, CryptArena arena, String state) {
        Vector3i p = arena.coffinBlock();
        var chunks = world.getChunkStore();
        var sectionRef = chunks.getChunkSectionReferenceAtBlock(p.x, p.y, p.z);
        if (sectionRef == null || !sectionRef.isValid()) return;
        var section = chunks.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return;
        var block = BlockType.getAssetMap().getAsset(section.get(p.x, p.y, p.z));
        if (block == null || !block.getId().contains(COFFIN_ID)) return;
        BlockOperations.setBlockInteractionState(chunks, sectionRef, p.x, p.y, p.z, block, state, false);
    }

    public static final class EnsureComponents extends HolderSystem<EntityStore> {
        @Nonnull @Override public Query<EntityStore> getQuery() { return CryptSiteComponent.getComponentType(); }
        @Override public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason,
                                          @Nonnull Store<EntityStore> store) {
            if (holder.getComponent(NetworkId.getComponentType()) == null)
                holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(Intangible.getComponentType());
            holder.ensureComponent(PrefabCopyableComponent.getComponentType());
        }
        @Override public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
                                              @Nonnull Store<EntityStore> store) { }
    }

    public static final class RestoreOnAdd extends RefSystem<EntityStore> {
        @Nonnull @Override public Query<EntityStore> getQuery() { return query(); }
        @Override public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            store.getExternalData().getWorld().execute(() -> reconcile(store, ref));
        }
        @Override public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull CommandBuffer<EntityStore> commandBuffer) { }
    }

    /** Retries reward delivery across chunk loading and closes abandoned coffins after server restart. */
    public static final class Maintain extends EntityTickingSystem<EntityStore> {
        @Nonnull @Override public Query<EntityStore> getQuery() { return query(); }
        @Override public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            var site = chunk.getComponent(index, CryptSiteComponent.getComponentType());
            if (site == null || site.isActive() || site.isPending()) return;
            site.setTimer(site.getTimer() - dt);
            if (site.getTimer() > 0) return;
            site.setTimer(2f);
            var ref = chunk.getReferenceTo(index);
            store.getExternalData().getWorld().execute(() -> reconcile(store, ref));
        }
    }
}
