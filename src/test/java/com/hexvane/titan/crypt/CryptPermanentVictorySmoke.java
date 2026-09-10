package com.hexvane.titan.crypt;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Real disk journal and stale-marker regression, run after the first staff has been picked up. */
public final class CryptPermanentVictorySmoke {
    private CryptPermanentVictorySmoke() { }

    public static void run(World world, Ref<EntityStore> completedSite, CryptArena completedArena) throws Exception {
        var store = world.getEntityStore().getStore();
        var restoredSite = on(world, () -> {
            @SuppressWarnings("unchecked")
            var memoryType = (ResourceType<EntityStore, CryptVictoryMemory>)
                store.getRegistry().getData().getResourceType(CryptVictoryMemory.ID);
            check(memoryType != null, "world victory journal is registered");
            // Read the file written by the actual final-blow/reward path, not a synthetic codec value.
            var reloaded = store.getResourceStorage().load(store, store.getRegistry().getData(), memoryType).join();
            check(reloaded.hasVictory(completedArena.coffinBlock()) && reloaded.hasDeposit(completedArena.coffinBlock()),
                "disk journal preserves the defeated dungeon after reward pickup");
            store.replaceResource(memoryType, reloaded);
            check(!CryptSiteSystem.canStart(store, completedSite, completedArena), "completed site cannot start again");
            check(!CryptEncounter.start(store, completedSite, completedArena, null), "direct encounter API respects saved defeat");
            var holder = store.removeEntity(completedSite, RemoveReason.UNLOAD);
            holder.putComponent(CryptSiteComponent.getComponentType(), CryptSiteComponent.CODEC.decode(
                BsonDocument.parse("{}"), ExtraInfo.THREAD_LOCAL.get()));
            var staleSite = store.addEntity(holder, AddReason.LOAD);
            check(staleSite != null && staleSite.isValid(), "blank stale marker reloads in the live engine");
            check(!store.getComponent(staleSite, CryptSiteComponent.getComponentType()).isDefeated(),
                "start guard runs before queued marker reconciliation");
            check(!CryptSiteSystem.canStart(store, staleSite, completedArena), "journal alone rejects a blank recreated marker");
            check(!CryptEncounter.start(store, staleSite, completedArena, null), "direct start cannot bypass marker reload timing");
            var actor = actor(store, completedArena.podium());
            try {
                CryptSiteSystem.activate(store, staleSite, actor);
                CryptSiteSystem.reset(store, staleSite);
                var state = store.getComponent(staleSite, CryptSiteComponent.getComponentType());
                check(state.isCleared() && state.isRewardDeposited() && !state.isActive(),
                    "coffin activation and reset restore permanent defeat from journal");
                check(roots(store, completedArena).isEmpty(), "completed dungeon never recreates a boss");
                check(rewards(store, completedArena) == 0, "claimed staff is never recreated by stale marker activation");
                return staleSite;
            } finally {
                if (actor.isValid()) store.removeEntity(actor, RemoveReason.REMOVE);
            }
        });

        // A real second prefab proves completion is scoped to this dungeon, rather than the whole world.
        Vector3d oldOrigin = completedArena.prefabOrigin();
        Vector3i origin = new Vector3i((int) Math.round(oldOrigin.x) + 512,
            (int) Math.round(oldOrigin.y), (int) Math.round(oldOrigin.z));
        var path = PrefabStore.get().findBrowsablePrefabPath("Titan/Crypt/SkeletonDungeon_Site.prefab.json");
        check(path != null, "second dungeon prefab resolves");
        var prefab = PrefabBufferUtil.getCached(path);
        var region = PrefabUtil.loadPasteRegionAsync(prefab, world, origin, PrefabRotation.ROTATION_0,
            GetChunkFlags.SET_TICKING).get(15, TimeUnit.SECONDS);
        check(region.isFullyLoaded(), "second dungeon footprint is loaded");
        on(world, () -> {
            PrefabUtil.paste(prefab, world, origin, Rotation.None, new Random(1), PrefabUtil.Flags.FORCE,
                SetBlockSettings.NONE, region, ignored -> { }, ignored -> { }, store);
            var secondSite = CryptSiteSystem.findSite(store, new Vector3i(completedArena.coffinBlock()).add(512, 0, 0));
            check(secondSite != null && secondSite.isValid(), "another dungeon has an independent marker");
            var secondArena = CryptSiteSystem.arena(store, secondSite);
            check(CryptSiteSystem.canStart(store, secondSite, secondArena), "unbeaten second dungeon remains available");
            check(!CryptSiteSystem.canStart(store, restoredSite, secondArena), "site identity cannot borrow another arena to evade defeat");
            var actor = actor(store, secondArena.podium());
            try {
                CryptSiteSystem.activate(store, secondSite, actor);
                var roots = roots(store, secondArena);
                check(roots.size() == 1, "opening a different coffin starts exactly one new encounter");
                check(store.getComponent(secondSite, CryptSiteComponent.getComponentType()).isActive(),
                    "second dungeon activation is active");
                for (var root : roots) {
                    var boss = store.getComponent(root, CryptBossComponent.getComponentType());
                    boss.finishing = true;
                    CryptEncounter.dispose(store, boss);
                    if (root.isValid()) store.removeEntity(root, RemoveReason.REMOVE);
                }
                CryptSiteSystem.reset(store, secondSite);
                check(CryptSiteSystem.canStart(store, secondSite, secondArena), "unfinished second dungeon can retry after a wipe");
                check(!CryptSiteSystem.canStart(store, restoredSite, completedArena), "first dungeon remains permanently dead");
                check(rewards(store, secondArena) == 0 && rewards(store, completedArena) == 0,
                    "neither an unfinished second fight nor claimed first victory grants another staff");
                return true;
            } finally {
                if (actor.isValid()) store.removeEntity(actor, RemoveReason.REMOVE);
            }
        });
        System.out.println("[CRYPT RUNTIME SMOKE] permanent defeat / disk journal reload / blank marker reload / coffin and direct-start guards / independent second dungeon PASS");
    }

    private static Ref<EntityStore> actor(Store<EntityStore> store, Vector3d position) {
        var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(position, new Rotation3f()));
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var ref = store.addEntity(holder, AddReason.SPAWN);
        check(ref != null && ref.isValid(), "coffin activation fixture exists");
        return ref;
    }

    private static ArrayList<Ref<EntityStore>> roots(Store<EntityStore> store, CryptArena arena) {
        var result = new ArrayList<Ref<EntityStore>>();
        store.forEachChunk(CryptBossComponent.getComponentType(), (chunk, ignored) -> {
            for (int index = 0; index < chunk.size(); index++) {
                var boss = chunk.getComponent(index, CryptBossComponent.getComponentType());
                if (boss != null && boss.arena.center().distanceSquared(arena.center()) < .01) result.add(chunk.getReferenceTo(index));
            }
        });
        return result;
    }

    private static int rewards(Store<EntityStore> store, CryptArena arena) {
        int[] count = {0};
        store.forEachChunk(Archetype.of(ItemComponent.getComponentType(), TransformComponent.getComponentType()), (chunk, ignored) -> {
            for (int index = 0; index < chunk.size(); index++) {
                var item = chunk.getComponent(index, ItemComponent.getComponentType());
                var transform = chunk.getComponent(index, TransformComponent.getComponentType());
                if (item != null && item.getItemStack() != null && CryptSiteSystem.STAFF_ID.equals(item.getItemStack().getItemId())
                    && transform.getPosition().distanceSquared(arena.rewardPoint()) < 16) count[0] += item.getItemStack().getQuantity();
            }
        });
        return count[0];
    }

    private static <T> T on(World world, Supplier<T> action) throws Exception {
        return CompletableFuture.supplyAsync(action, world).get(15, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
