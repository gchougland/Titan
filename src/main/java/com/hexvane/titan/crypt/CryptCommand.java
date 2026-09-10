package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.spawn.GlobalSpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;
import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Administrator validation commands; inherits the /titan world-editor permission. */
public final class CryptCommand extends AbstractCommandCollection {
    private record ReturnPoint(String world, Transform transform) { }
    private static final Map<UUID, ReturnPoint> RETURNS = new ConcurrentHashMap<>();
    private static final Set<UUID> BUILDING = ConcurrentHashMap.newKeySet();
    private static final Vector3i TEST_ORIGIN = new Vector3i(0, 159, 0);
    private static final Transform TEST_ENTRANCE = new Transform(new Vector3d(4.5, 160, .5),
        new Rotation3f(0, (float) (Math.PI / 2), 0));

    public CryptCommand() {
        super("crypt", "titan_crypt_dungeon.command.root");
        addSubCommand(new Action("dungeon"));
        addSubCommand(new Action("staff"));
        addSubCommand(new Action("status"));
        addSubCommand(new Action("reset"));
        addSubCommand(new Action("return"));
    }

    private static final class Action extends AbstractPlayerCommand {
        private final String action;
        Action(String action) { super(action, "titan_crypt_dungeon.command." + action); this.action=action; }
        @Override protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                                           @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world) {
            switch (action) {
                case "dungeon" -> dungeon(context, store, ref, player, world);
                case "staff" -> {
                    var result = Player.giveItem(new ItemStack(CryptSiteSystem.STAFF_ID, 1), ref, store);
                    context.sendMessage(Message.raw(ItemStack.isEmpty(result.getRemainder())
                        ? "Staff of the Crypt Keeper added to your inventory." : "Make an empty inventory slot first."));
                }
                case "return" -> {
                    var point = RETURNS.get(player.getUuid());
                    if (point == null) { context.sendMessage(Message.raw("No return point is recorded for this session.")); return; }
                    var target = Universe.get().getWorld(point.world());
                    if (target == null) { context.sendMessage(Message.raw("Your original world is not loaded: " + point.world())); return; }
                    store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(target, point.transform()));
                    RETURNS.remove(player.getUuid());
                }
                default -> inspect(context, store, ref, action.equals("reset"));
            }
        }
    }

    private static void dungeon(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                                 PlayerRef player, World originalWorld) {
        if (!BUILDING.add(player.getUuid())) { context.sendMessage(Message.raw("Your test crypt is already being prepared.")); return; }
        final IPrefabBuffer prefab;
        try {
            var path = PrefabStore.get().findBrowsablePrefabPath("Titan/Crypt/SkeletonDungeon_Site.prefab.json");
            if (path == null) throw new IllegalStateException("The dungeon prefab is not loaded.");
            prefab = PrefabBufferUtil.getCached(path);
        } catch (RuntimeException e) {
            BUILDING.remove(player.getUuid());
            context.sendMessage(Message.raw("The crypt prefab could not be decoded: " + e.getMessage())); return;
        }
        var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) { BUILDING.remove(player.getUuid()); return; }
        var back = new ReturnPoint(originalWorld.getName(), new Transform(transform.getPosition(), transform.getRotation()));
        String name = "crypt-test-" + UUID.randomUUID().toString().substring(0, 8);
        WorldConfig config = new WorldConfig();
        config.setDisplayName("Crypt Keeper Test");
        config.setGameMode(GameMode.Adventure);
        config.setSpawningNPC(false);
        config.setWorldGenProvider(new FlatWorldGenProvider(FlatWorldGenProvider.DEFAULT_TINT,
            new FlatWorldGenProvider.Layer[]{
                new FlatWorldGenProvider.Layer(0, 157, null, "Rock_Basalt"),
                new FlatWorldGenProvider.Layer(157, 159, null, "Soil_Dirt"),
                new FlatWorldGenProvider.Layer(159, 160, null, "Soil_Grass")}));
        config.setSpawnProvider(new GlobalSpawnProvider(TEST_ENTRANCE));
        config.markChanged();
        var universe = Universe.get();
        context.sendMessage(Message.raw("Preparing a fresh underground crypt in " + name + ". Your current world is untouched."));
        universe.makeWorld(name, universe.validateWorldPath(name), config)
            .thenCompose(world -> PrefabUtil.loadPasteRegionAsync(prefab, world, TEST_ORIGIN,
                PrefabRotation.ROTATION_0, 0).thenAcceptAsync(region -> {
                    if (!region.isFullyLoaded()) throw new IllegalStateException("The test dungeon's chunks did not load.");
                    PrefabUtil.paste(prefab, world, new Vector3i(TEST_ORIGIN), Rotation.None, new Random(0),
                        PrefabUtil.Flags.FORCE, SetBlockSettings.NONE, region, ignored -> { }, ignored -> { },
                        world.getEntityStore().getStore());
                    originalWorld.execute(() -> {
                        BUILDING.remove(player.getUuid());
                        if (!ref.isValid()) return;
                        RETURNS.putIfAbsent(player.getUuid(), back);
                        store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(world, TEST_ENTRANCE));
                        player.sendMessage(Message.raw("Crypt ready. Descend the stairs and open the central sarcophagus. "
                            + "Commands: /titan crypt staff, /titan crypt status, /titan crypt reset, /titan crypt return. World: " + name));
                    });
                }, world))
            .exceptionally(error -> {
                BUILDING.remove(player.getUuid());
                player.sendMessage(Message.raw("Test crypt creation failed: " + error.getMessage()));
                return null;
            });
    }

    private static void inspect(CommandContext context, Store<EntityStore> store, Ref<EntityStore> player, boolean reset) {
        var transform = store.getComponent(player, TransformComponent.getComponentType());
        if (transform == null) return;
        var found = new ArrayList<Ref<EntityStore>>(1);
        final double[] distance = {192*192};
        store.forEachChunk(Archetype.of(CryptSiteComponent.getComponentType(), TransformComponent.getComponentType()),
            (chunk, ignored) -> {
                for (int i=0; i<chunk.size(); i++) {
                    var t = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (t == null) continue;
                    double d = t.getPosition().distanceSquared(transform.getPosition());
                    if (d < distance[0]) { distance[0]=d; found.clear(); found.add(chunk.getReferenceTo(i)); }
                }
            });
        if (found.isEmpty()) { context.sendMessage(Message.raw("No loaded crypt within 192 blocks. Use /titan crypt dungeon.")); return; }
        var siteRef = found.getFirst();
        var site = store.getComponent(siteRef, CryptSiteComponent.getComponentType());
        var arena = CryptSiteSystem.arena(store, siteRef);
        boolean defeated = CryptSiteSystem.isDefeated(store, siteRef);
        if (reset && defeated) {
            context.sendMessage(Message.raw("This crypt has already been defeated. Use /titan crypt dungeon for another test encounter.")); return;
        }
        var roots = new ArrayList<Ref<EntityStore>>();
        store.forEachChunk(CryptBossComponent.getComponentType(), (chunk, ignored) -> {
            for (int i=0; i<chunk.size(); i++) {
                var boss = chunk.getComponent(i, CryptBossComponent.getComponentType());
                if (boss != null && siteRef.equals(boss.site)) {
                    roots.add(chunk.getReferenceTo(i));
                    if (!reset) context.sendMessage(Message.raw("Phase " + boss.fight.phase() + "; " + boss.fight.state()
                        + "; crown=" + (int)boss.fight.crown() + "/" + (int)boss.fight.maxCrown + "; bracelets="
                        + (int)boss.fight.bracelet(0) + "," + (int)boss.fight.bracelet(1)
                        + " (max " + (int)boss.fight.maxBracelet + " each); party scale=" + boss.fight.partySize()));
                }
            }
        });
        if (reset) {
            for (var root : roots) {
                var boss = store.getComponent(root, CryptBossComponent.getComponentType());
                if (boss != null) { boss.finishing = true; CryptEncounter.dispose(store, boss); }
                if (root.isValid()) store.removeEntity(root, RemoveReason.REMOVE);
            }
            CryptSiteSystem.reset(store, siteRef);
            context.sendMessage(Message.raw("The encounter despawned and its coffin resealed. No reward was created."));
        } else context.sendMessage(Message.raw("Crypt: " + (defeated ? "permanently defeated" : site.isActive() ? "active" : "sealed")
            + "; reward spawned=" + site.isRewardDeposited() + "; coffin=" + arena.coffinBlock()));
    }
}
