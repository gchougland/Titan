package com.hexvane.titan.crypt;

import com.hypixel.hytale.Main;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.shape.Box;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.ShutdownReason;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.SingleTrackMusicContainer;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.RespondToHit;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.spawn.GlobalSpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Standalone real-engine smoke; run with the disposable smoke world's server launch arguments. */
public final class CryptRuntimeSmoke {
    private static long deadline;
    private static final Vector3i ORIGIN = new Vector3i(0,159,0);
    private static final Vector3i COFFIN = new Vector3i(-86,105,0);
    private static volatile Throwable failure;

    public static void main(String[] args) {
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        Thread worker = new Thread(() -> {
            try { run(); System.out.println("[CRYPT RUNTIME SMOKE] PASS"); }
            catch (Throwable error) {
                failure=error;
                System.err.println("[CRYPT RUNTIME SMOKE] FAIL: " + error);
                error.printStackTrace();
            } finally {
                var server = HytaleServer.get();
                if (server != null) server.shutdownServer(failure == null ? ShutdownReason.SHUTDOWN : ShutdownReason.VERIFY_ERROR);
            }
        }, "CryptRuntimeSmoke");
        worker.start();
        Main.main(args);
    }

    private static void run() throws Exception {
        waitFor(() -> HytaleServer.get() != null && HytaleServer.get().isBooted()
            && Universe.get() != null && CryptBossComponent.getComponentType() != null, "server boot");
        var universe = Universe.get();
        com.hexvane.titan.compat.TitanReloadRuntimeSmoke.run();
        String name="crypt-runtime-"+UUID.randomUUID().toString().substring(0,8);
        WorldConfig config=new WorldConfig();
        config.setSpawningNPC(false);
        config.setCanUnloadChunks(false); // The headless smoke has no player chunk tickets.
        config.setWorldGenProvider(new FlatWorldGenProvider(FlatWorldGenProvider.DEFAULT_TINT,
            new FlatWorldGenProvider.Layer[]{new FlatWorldGenProvider.Layer(0,157,null,"Rock_Basalt"),
                new FlatWorldGenProvider.Layer(157,159,null,"Soil_Dirt"),
                new FlatWorldGenProvider.Layer(159,160,null,"Soil_Grass")}));
        config.setSpawnProvider(new GlobalSpawnProvider(new Transform(new Vector3d(4.5,160,.5),new Rotation3f())));
        config.markChanged();
        World world=await(universe.makeWorld(name,universe.validateWorldPath(name),config));
        var path=PrefabStore.get().findBrowsablePrefabPath("Titan/Crypt/SkeletonDungeon_Site.prefab.json");
        check(path!=null,"prefab path resolves");
        var prefab=PrefabBufferUtil.getCached(path);
        var region=await(PrefabUtil.loadPasteRegionAsync(prefab,world,ORIGIN,PrefabRotation.ROTATION_0,GetChunkFlags.SET_TICKING));
        check(region.isFullyLoaded(),"all dungeon chunks loaded");
        await(on(world,()->{
            PrefabUtil.paste(prefab,world,new Vector3i(ORIGIN),Rotation.None,new Random(0),
                PrefabUtil.Flags.FORCE,SetBlockSettings.NONE,region,ignored->{},ignored->{},world.getEntityStore().getStore());
            return true;
        }));
        var store=world.getEntityStore().getStore();
        Ref<EntityStore> site=await(on(world,()->CryptSiteSystem.findSite(store,COFFIN)));
        check(site!=null && site.isValid(),"invisible dungeon marker loaded");
        CryptArena arena=await(on(world,()->CryptSiteSystem.arena(store,site)));
        check(arena.center().distance(new Vector3d(-114.5,103,-.5))<.001,"marker world transform");
        com.hexvane.titan.npc.TitanCombatRuntimeSmoke.run(world,new Vector3d(-80,160,20));
        CryptStaffInputSmoke.run(world,arena.point(12,0,34));
        CryptMissileRuntimeSmoke.run(world,arena.point(0,5,20));
        await(on(world, () -> {
            com.hexvane.titan.compat.TitanMinionRuntimeSmoke.run(store, arena.point(18, 1, 38));
            return true;
        }));
        CryptMultiplayerRuntimeSmoke.run(world,site,arena);
        await(on(world,()->{
            check(block(world,COFFIN).contains(CryptSiteSystem.COFFIN_ID),"custom coffin base block");
            check(rewardItems(store,arena).isEmpty(),"sealed coffin has no world-item reward");
            check(block(world,new Vector3i(-104,103,-14)).equals("Empty"),"blue marker removed");
            check(block(world,new Vector3i(-101,103,-27)).equals("Empty"),"flame marker removed");
            check(block(world,new Vector3i(-114,110,0)).equals("Empty"),"underground interior air carved");
            check(block(world,new Vector3i(-39,132,0)).equals("Empty"),"omitted stair vault air carved");
            check(block(world,new Vector3i(-58,109,-34)).equals("Empty"),"omitted wall alcove air carved");
            check(block(world,new Vector3i(-94,131,-33)).equals("Empty"),"omitted ceiling interior air carved");
            System.out.println("[CRYPT WALL SMOKE] back bone="+block(world,new Vector3i(-131,105,-32))
                +" backing="+block(world,new Vector3i(-132,105,-32))
                +"; right bone="+block(world,new Vector3i(-123,105,-40))
                +" backing="+block(world,new Vector3i(-123,105,-41)));
            check(block(world,new Vector3i(-131,105,-32)).equals("Deco_Bone_Skulls_Wall"),"clipped back recess bone wall restored");
            check(block(world,new Vector3i(-132,105,-32)).equals("Rock_Basalt_Cobble"),"clipped back recess basalt backing restored");
            check(block(world,new Vector3i(-123,105,-40)).equals("Deco_Bone_Skulls_Wall"),"clipped right recess bone wall restored");
            check(block(world,new Vector3i(-123,105,-41)).equals("Rock_Basalt_Cobble"),"clipped right recess basalt backing restored");
            check(block(world,new Vector3i(-130,105,-32)).equals("Empty"),"back recess remains open in front of bone panel");
            check(block(world,new Vector3i(-123,105,-39)).equals("Empty"),"right recess remains open in front of bone panel");
            check(block(world,new Vector3i(-101,105,-39)).equals("Empty"),"omitted right recess window clears terrain");
            check(block(world,new Vector3i(-14,170,0)).equals("Empty"),"entrance vault interior remains open");
            check(block(world,new Vector3i(-58,131,-5)).equals("Rock_Basalt"),"exterior roof rim terrain preserved");
            check(block(world,new Vector3i(-94,101,40)).equals("Rock_Basalt"),"exterior wall terrain preserved");
            check(!block(world,new Vector3i(0,159,0)).equals("Empty"),"entrance aligns with ground");
            return true;
        }));
        var wipedRef=startBoss(world,site,arena,1.99);
        waitFor(()->!wipedRef.isValid(),"all-player wipe cleanup");
        await(on(world,()->{
            var state=store.getComponent(site,CryptSiteComponent.getComponentType());
            check(!state.isCleared() && !state.isActive() && !state.isRewardDeposited(),"wipe resets persistent site");
            check(rewardItems(store,arena).isEmpty(),"wipe creates no world-item reward");
            check(block(world,COFFIN).contains("Closed"),"wipe closes coffin");
            return true;
        }));
        System.out.println("[CRYPT RUNTIME SMOKE] wipe / no reward / coffin reset PASS");
        var bossRef=startBoss(world,site,arena,-1000);
        System.out.println("[CRYPT RUNTIME SMOKE] dungeon / coffin / boss assembly PASS");
        final var boss=await(on(world,()->store.getComponent(bossRef,CryptBossComponent.getComponentType())));
        final Vector3d before=await(on(world,()->new Vector3d(store.getComponent(boss.parts.getFirst(),TransformComponent.getComponentType()).getPosition())));
        advance(world,boss,8);
        awaitFrame(world,boss);
        await(on(world,()->{
            var after=store.getComponent(boss.parts.getFirst(),TransformComponent.getComponentType()).getPosition();
            check(after.isFinite() && after.distance(before)>.01,"actual part transforms animate");
            return true;
        }));
        advance(world,boss,7);
        awaitFrame(world,boss);
        final var attacker = await(on(world,()->{
            var holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(arena.podium(),new Rotation3f()));
            holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-.4,0,-.4,.4,1.8,.4)));
            var stats=holder.ensureAndGetComponent(EntityStatMap.getComponentType());
            stats.update(); TitanPartBuilder.applyHealth(stats,100);
            holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
            return store.addEntity(holder,AddReason.SPAWN);
        }));
        exerciseMusic(world,attacker);
        final var originalBracelets=await(on(world,()->bracelets(store,boss)));
        check(originalBracelets.size()==32,"two authored 16-voxel bracelets initially exist");
        await(on(world,()->{
            check(boss.fight.damageable(),"intro returns to combat");
            float crown = boss.fight.crown();
            damagePool(store,boss,attacker,-1,100000);
            check(boss.fight.crown()==crown && boss.fight.bracelet(0)==boss.fight.maxBracelet,"body damage intercepted without pool damage");
            damagePool(store,boss,attacker,0,55);
            check(boss.fight.crown()==crown-55,"engine damage reaches crown pool");
            damagePool(store,boss,attacker,1,100000);
            damagePool(store,boss,attacker,2,100000);
            check(Math.abs(boss.fight.crown()-(crown-55-boss.fight.maxCrown*.06f))<.01,
                "two actual bracelet breaks chip six percent from crown");
            check(boss.fight.state()==CryptFight.State.FALLING,"both bracelets trigger collapse");
            return true;
        }));
        awaitFrame(world,boss);
        advance(world,boss,2.3);
        awaitFrame(world,boss);
        await(on(world,()->{
            check(boss.fight.state()==CryptFight.State.STUNNED,"stun reached");
            check(boss.fight.bracelet(0)==0 && boss.fight.bracelet(1)==0,"bracelets destroyed through stun");
            check(originalBracelets.stream().noneMatch(Ref::isValid),"broken bracelet entities were removed instead of hidden");
            check(bracelets(store,boss).isEmpty(),"no broken bracelet remains in the live entity store");
            return true;
        }));
        exerciseStaff(world,boss,attacker);
        advance(world,boss,12.1);
        awaitFrame(world,boss);
        advance(world,boss,3.1);
        awaitFrame(world,boss);
        await(on(world,()->{
            check(boss.fight.bracelet(0)==boss.fight.maxBracelet && boss.fight.bracelet(1)==boss.fight.maxBracelet,"bracelets regenerate");
            return true;
        }));
        awaitBracelets(world,boss,attacker,"first recovery");
        exerciseBraceletLifecycle(world,bossRef,boss,attacker);
        CryptGrabRuntimeSmoke.run(world,boss,bossRef,attacker);
        exerciseEveryAttack(world,boss);
        await(on(world,()->{
            damagePool(store,boss,attacker,0,boss.fight.crown()-boss.fight.maxCrown*.045f);
            damagePool(store,boss,attacker,1,100000);
            check(boss.fight.crown()>0 && boss.fight.state()!=CryptFight.State.DYING,"first near-death bracelet break leaves crown alive");
            damagePool(store,boss,attacker,2,100000);
            check(boss.fight.state()==CryptFight.State.DYING,"lethal second bracelet chip starts death instead of stun");
            return true;
        }));
        awaitFrame(world,boss);
        final var savedMarker = await(on(world,()->{
            var state = store.getComponent(site,CryptSiteComponent.getComponentType());
            check(state.isVictoryPending() && !state.isCleared(),"final blow persisted before death animation ends");
            CryptSiteSystem.reset(store,site);
            check(state.isVictoryPending(),"wipe cannot erase pending final blow");
            var holder = store.removeEntity(site,RemoveReason.UNLOAD);
            var info = ExtraInfo.THREAD_LOCAL.get();
            holder.putComponent(CryptSiteComponent.getComponentType(),CryptSiteComponent.CODEC.decode(
                CryptSiteComponent.CODEC.encode(state,info),info));
            return holder;
        }));
        check(!site.isValid(),"marker genuinely unloaded during death");
        advance(world,boss,10.2);
        waitFor(()->!bossRef.isValid(),"death cleanup");
        final var restoredSite = await(on(world,()->store.addEntity(savedMarker,AddReason.LOAD)));
        while(!await(on(world,()->store.getComponent(restoredSite,CryptSiteComponent.getComponentType()).isRewardDeposited()))) {
            checkTime("deferred victory delivery after marker reload"); Thread.sleep(20);
        }
        final var rewardHolder=await(on(world,()->{
            var state=store.getComponent(restoredSite,CryptSiteComponent.getComponentType());
            check(state.isCleared() && state.isRewardDeposited(),"persistent completion and delivery receipt");
            check(!state.isVictoryPending(),"pending victory finalized after reload");
            var rewards=rewardItems(store,arena);
            check(rewards.size()==1,"one staff world item is on top of the coffin");
            var reward=rewards.getFirst();
            var item=store.getComponent(reward,ItemComponent.getComponentType());
            check(item.getItemStack().getQuantity()==1,"reward is exactly one staff");
            var position=store.getComponent(reward,TransformComponent.getComponentType()).getPosition();
            check(position.distance(arena.rewardPoint())<1,"reward spawns at the coffin-top point");
            check(!item.canPickUp(),"brief pickup delay lets the victory drop be seen");
            check(store.getComponent(reward,DespawnComponent.getComponentType())==null,"unclaimed staff has no despawn timer");
            check(store.getComponent(reward,EntityStore.REGISTRY.getNonSerializedComponentType())==null,"staff is a saved world item");
            CryptSiteSystem.victory(store,restoredSite);
            CryptSiteSystem.victory(store,restoredSite);
            check(rewardItems(store,arena).size()==1 && item.getItemStack().getQuantity()==1,"repeated completion does not duplicate staff");
            CryptSiteSystem.reset(store,restoredSite);
            check(state.isCleared(),"reset cannot erase victory");
            check(boss.parts.isEmpty() && boss.minions.isEmpty(),"part/minion cleanup");
            if(attacker.isValid()) store.removeEntity(attacker,RemoveReason.REMOVE);
            var holder=store.removeEntity(reward,RemoveReason.UNLOAD);
            var info=ExtraInfo.THREAD_LOCAL.get();
            holder.putComponent(ItemComponent.getComponentType(),ItemComponent.CODEC.decode(ItemComponent.CODEC.encode(item,info),info));
            return holder;
        }));
        final var reloadedReward=await(on(world,()->store.addEntity(rewardHolder,AddReason.LOAD)));
        while(!await(on(world,()->store.getComponent(reloadedReward,ItemComponent.getComponentType()).canPickUp()))) {
            checkTime("world staff pickup delay");Thread.sleep(20);
        }
        await(on(world,()->{
            var item=store.getComponent(reloadedReward,ItemComponent.getComponentType());
            check(item.getItemStack().getQuantity()==1 && rewardItems(store,arena).size()==1,"one collectible staff survives item reload");
            check(store.getComponent(reloadedReward,DespawnComponent.getComponentType())==null,"item reload preserves no-despawn reward");
            // Headless test has no player inventory; emulate the removal receipt produced by pickup.
            item.setRemovedByPlayerPickup(true);
            store.removeEntity(reloadedReward,RemoveReason.REMOVE);
            CryptSiteSystem.victory(store,restoredSite);
            CryptSiteSystem.reset(store,restoredSite);
            check(rewardItems(store,arena).isEmpty(),"claimed world reward is not recreated by reconciliation");
            return true;
        }));
        System.out.println("[CRYPT RUNTIME SMOKE] damage / bracelet crown chip / stun / regeneration / bracelet-triggered death / pending victory reload / single coffin-top world reward / item reload / pickup receipt PASS");
        CryptPermanentVictorySmoke.run(world, restoredSite, arena);
    }

    private static void damagePool(Store<EntityStore> store,CryptBossComponent boss,Ref<EntityStore> attacker,int pool,float amount) {
        var part = boss.parts.stream().filter(Ref::isValid)
            .filter(ref->store.getComponent(ref,CryptPartComponent.getComponentType()).pool==pool).findFirst().orElseThrow();
        var damage = new Damage(new Damage.EntitySource(attacker),DamageCause.getAssetMap().getIndex("Physical"),amount);
        DamageSystems.executeDamage(part,store,damage);
        check(damage.isCancelled(),"voxel health protected from direct damage for pool "+pool);
    }

    private static void exerciseMusic(World world,Ref<EntityStore> actor) throws Exception {
        await(on(world,()->{
            var store=world.getEntityStore().getStore();
            var tracker=new ForcedMusicTracker();
            store.addComponent(actor,ForcedMusicTracker.getComponentType(),tracker);
            int index=MusicContainer.getAssetMap().getIndex(CryptMusic.TRACK);
            check(index>0,"dedicated crypt battle music container is loaded");
            var container=MusicContainer.getAssetMap().getAsset(CryptMusic.TRACK);
            check(container instanceof SingleTrackMusicContainer,"crypt music is a single track");
            var track=(SingleTrackMusicContainer)container;
            check(track.getLoopCount()==0,"crypt music loops throughout encounter");
            check("Music/Titan/Crypt_Keeper_Battle.ogg".equals(track.getTrack()) &&
                CommonAssetRegistry.hasCommonAsset(track.getTrack()),"crypt battle audio resolves in loaded common assets");
            CryptMusic.apply(store,actor);
            check(tracker.getCurrentContainerIndex()==index,"crypt encounter applies dedicated forced music");
            CryptMusic.stop(store,actor);
            check(tracker.getCurrentContainerIndex()==0,"crypt encounter releases forced music on cleanup");
            var movingHolder=EntityStore.REGISTRY.newHolder();
            movingHolder.addComponent(ForcedMusicTracker.getComponentType(),tracker);
            tracker.setCurrentContainerIndex(index); tracker.setLastSentContainerIndex(index);
            CryptMusic.resetOnRemoval(movingHolder);
            check(tracker.getCurrentContainerIndex()==0 && tracker.getLastSentContainerIndex()==0,
                "world transfer clears crypt playback and stale packet state");
            CryptMusic.apply(store,actor);
            check(tracker.getCurrentContainerIndex()==index && tracker.getLastSentContainerIndex()!=index,
                "returning to crypt requires a fresh music start packet");
            tracker.setLastSentContainerIndex(index);
            CryptMusic.stop(store,actor);
            CryptMusic.resetOnRemoval(movingHolder);
            check(tracker.getCurrentContainerIndex()==0 && tracker.getLastSentContainerIndex()==0,
                "transfer during pending music stop cannot suppress the next start");
            tracker.setCurrentContainerIndex(index+1); tracker.setLastSentContainerIndex(index+1);
            CryptMusic.stop(store,actor); CryptMusic.resetOnRemoval(movingHolder);
            check(tracker.getCurrentContainerIndex()==index+1 && tracker.getLastSentContainerIndex()==index+1,
                "crypt cleanup preserves another track's ownership");
            tracker.setCurrentContainerIndex(0); tracker.setLastSentContainerIndex(0);
            return true;
        }));
        System.out.println("[CRYPT RUNTIME SMOKE] dedicated music / packaged audio / infinite loop / apply / stop / transfer state / track ownership PASS");
    }

    private static List<Ref<EntityStore>> bracelets(Store<EntityStore> store,CryptBossComponent boss) {
        return boss.parts.stream().filter(Ref::isValid)
            .filter(ref->store.getComponent(ref,CryptPartComponent.getComponentType()).pool>0).toList();
    }

    private static void awaitBracelets(World world,CryptBossComponent boss,Ref<EntityStore> attacker,String cycle) throws Exception {
        var store=world.getEntityStore().getStore();
        while(!await(on(world,()->bracelets(store,boss).size()==32))) {
            checkTime(cycle+" restores all 32 bracelet voxels");Thread.sleep(20);
        }
        awaitFrame(world,boss);
        await(on(world,()->{
            int[] pools=new int[2];
            var occupied=new HashSet<String>();
            for(var ref:bracelets(store,boss)) {
                var part=store.getComponent(ref,CryptPartComponent.getComponentType());
                pools[part.pool-1]++;
                check(occupied.add(part.bone+":"+part.offset),cycle+" has no duplicate bracelet voxel");
                var bone=boss.rig.bones.get(part.bone);
                var expected=new Vector3d(part.offset);expected.z*=bone.lengthScale;
                bone.rotation.transform(expected).add(bone.position);
                var position=store.getComponent(ref,TransformComponent.getComponentType()).getPosition();
                check(position.isFinite() && position.distance(expected)<.08,cycle+" bracelet is attached to its current wrist pose");
                // Visible is a transient viewer set, removed by Hytale when no client is nearby.
                // The headless world checks the stable components used to render a discovered entity.
                var blockEntity=store.getComponent(ref,BlockEntity.getComponentType());
                var scale=store.getComponent(ref,EntityScaleComponent.getComponentType());
                check(store.getComponent(ref,NetworkId.getComponentType())!=null && blockEntity!=null &&
                    BlockType.getAssetMap().getIndex(blockEntity.getBlockTypeKey())>0 && scale!=null &&
                    Float.isFinite(scale.getScale()) && scale.getScale()>0 &&
                    store.getComponent(ref,BoundingBox.getComponentType())!=null,cycle+" bracelet has renderable network block");
                check(store.getComponent(ref,RespondToHit.getComponentType())!=null && health(store,ref)>0 &&
                    CryptSpellSystem.enemy(store,attacker,ref),cycle+" bracelet is a living damageable target");
            }
            check(pools[0]==16 && pools[1]==16,cycle+" has 16 real voxels on each wrist");
            return true;
        }));
    }

    private static void exerciseBraceletLifecycle(World world,Ref<EntityStore> root,CryptBossComponent boss,
                                                  Ref<EntityStore> attacker) throws Exception {
        var store=world.getEntityStore().getStore();
        // Match the engine's section-unload removal: NonSerialized parts are discarded, not saved.
        var unloaded=await(on(world,()->{
            var refs=bracelets(store,boss);
            for(var ref:refs) {
                var holder=store.removeEntity(ref,RemoveReason.UNLOAD);
                check(!holder.hasSerializableComponents(EntityStore.REGISTRY.getData()),"unloaded bracelet is discarded by section lifecycle");
            }
            return refs;
        }));
        check(unloaded.stream().noneMatch(Ref::isValid),"section unload genuinely invalidates all original bracelet refs");
        awaitBracelets(world,boss,attacker,"unload repair");
        var secondBracelets=await(on(world,()->{
            // Repeated repair must preserve the surviving refs, rather than generating extra rings.
            var refs=bracelets(store,boss);
            CryptEncounter.reconcileBracelets(store,root,boss);
            CryptEncounter.reconcileBracelets(store,root,boss);
            check(bracelets(store,boss).equals(refs),"idempotent repair retains the 32 living bracelet refs");
            float left=boss.fight.bracelet(0),right=boss.fight.bracelet(1);
            damagePool(store,boss,attacker,1,1);
            damagePool(store,boss,attacker,2,1);
            check(boss.fight.bracelet(0)==left-1 && boss.fight.bracelet(1)==right-1,"regenerated bracelet entities route real damage to both pools");
            return refs;
        }));
        awaitFrame(world,boss); // The real damage deduplicator resets between attack ticks.
        await(on(world,()->{
            damagePool(store,boss,attacker,1,100000);
            damagePool(store,boss,attacker,2,100000);
            check(boss.fight.state()==CryptFight.State.FALLING,"second pair of real bracelet breaks triggers a second collapse");
            return true;
        }));
        awaitFrame(world,boss);
        advance(world,boss,2.3);
        awaitFrame(world,boss);
        await(on(world,()->{
            check(boss.fight.state()==CryptFight.State.STUNNED,"second stun reached");
            check(secondBracelets.stream().noneMatch(Ref::isValid) && bracelets(store,boss).isEmpty(),"second broken pair removes every rendered voxel");
            return true;
        }));
        advance(world,boss,12.1);
        awaitFrame(world,boss);
        advance(world,boss,3.1);
        awaitFrame(world,boss);
        awaitBracelets(world,boss,attacker,"second recovery");
        await(on(world,()->{
            check(boss.fight.bracelet(0)==boss.fight.maxBracelet && boss.fight.bracelet(1)==boss.fight.maxBracelet,"second recovery restores both health pools");
            damagePool(store,boss,attacker,1,1);
            damagePool(store,boss,attacker,2,1);
            check(boss.fight.bracelet(0)==boss.fight.maxBracelet-1 && boss.fight.bracelet(1)==boss.fight.maxBracelet-1,"second reconstructed pair still accepts real engine damage");
            return true;
        }));
        System.out.println("[CRYPT RUNTIME SMOKE] two stun cycles / all 32 bracelet voxels / wrist transforms / real damage / section unload repair PASS");
    }

    private record SpellProbe(Ref<EntityStore> root,CryptSpellComponent spell,float crown,float ownerHealth) { }

    /** Real spell systems tick against the actual stunned crown; no player packets or cast fixture. */
    private static void exerciseStaff(World world,CryptBossComponent boss,Ref<EntityStore> owner) throws Exception {
        var store=world.getEntityStore().getStore();
        var missile=await(on(world,()->{
            check(CryptSpellSystem.alive(store,owner),"staff smoke owner has real living health");
            var crown=highestCrown(store,boss);
            check(CryptSpellSystem.enemy(store,owner,crown),"stunned crown accepted by live homing target filter");
            Vector3d target=CryptSpellSystem.center(store,crown);
            Vector3d launch=new Vector3d(target).add(.8,3.5,0);
            check(CryptSpellSystem.visible(store,launch,target),"missile test path is clear above actual crown");
            var spell=new CryptSpellComponent(owner,CryptSpellComponent.Kind.MISSILE);
            spell.velocity.set(0,-7,0); // Offset launch requires actual homing to turn toward the crown.
            spell.anchor.set(launch);
            return new SpellProbe(CryptStaff.spawn(store,spell,launch),spell,boss.fight.crown(),health(store,owner));
        }));
        boolean[] observed={false,false};
        while(!await(on(world,()->{
            if(missile.root().isValid()) {
                observed[0]|=missile.spell().target!=null && missile.spell().target.isValid();
                observed[1]|=Math.abs(missile.spell().velocity.x)>.01 || Math.abs(missile.spell().velocity.z)>.01;
            }
            return !missile.root().isValid();
        }))) {checkTime("real homing missile impact");Thread.sleep(20);}
        await(on(world,()->{
            check(observed[0] && observed[1],"missile acquired a target and curved during real engine ticks");
            check(boss.fight.crown()==missile.crown()-15,"one missile deals exactly 15 crown damage before despawning");
            check(health(store,owner)==missile.ownerHealth(),"missile owner is unharmed");
            return true;
        }));
        System.out.println("[CRYPT RUNTIME SMOKE] staff missile target / homing / real crown damage / cleanup PASS");

        var arm=await(on(world,()->{
            Vector3d target=CryptSpellSystem.center(store,highestCrown(store,boss)).add(0,-.5,0);
            // Put the synthetic owner inside the damage radius to prove the exclusion is meaningful.
            store.getComponent(owner,TransformComponent.getComponentType()).setPosition(new Vector3d(target));
            check(boss.arena.contains(target),"signature owner remains inside encounter");
            var spell=new CryptSpellComponent(owner,CryptSpellComponent.Kind.ARM);
            spell.anchor.set(target);
            var root=CryptStaff.spawn(store,spell,new Vector3d(target).add(0,7,0));
            CryptStaffArm.build(store,root,target,0);
            return new SpellProbe(root,spell,boss.fight.crown(),health(store,owner));
        }));
        var parts=await(on(world,()->{
            var refs=new ArrayList<Ref<EntityStore>>();
            store.forEachChunk(CryptStaff.spellType,(chunk,ignored)->{
                for(int i=0;i<chunk.size();i++) {
                    var spell=chunk.getComponent(i,CryptStaff.spellType);
                    if(spell.kind==CryptSpellComponent.Kind.PART && arm.root().equals(spell.root)) refs.add(chunk.getReferenceTo(i));
                }
            });
            check(refs.size()>130,"signature assembled supplied arm, palm, bracelet and articulated digits");
            return refs;
        }));
        while(!await(on(world,()->!arm.root().isValid() && parts.stream().noneMatch(Ref::isValid)))) {
            checkTime("real signature impact and part cleanup");Thread.sleep(20);
        }
        await(on(world,()->{
            check(arm.spell().impacted,"signature reached its real timed impact");
            check(boss.fight.crown()==arm.crown()-110,"signature damages crown pool once, independent of voxel count");
            check(health(store,owner)==arm.ownerHealth(),"signature spares owner inside its impact radius");
            return true;
        }));
        System.out.println("[CRYPT RUNTIME SMOKE] staff signature actual arm / single pool hit / owner immunity / part cleanup PASS");
    }

    private static Ref<EntityStore> highestCrown(Store<EntityStore> store,CryptBossComponent boss) {
        return boss.parts.stream().filter(Ref::isValid)
            .filter(ref->store.getComponent(ref,CryptPartComponent.getComponentType()).pool==0)
            .max(Comparator.comparingDouble(ref->CryptSpellSystem.center(store,ref).y)).orElseThrow();
    }
    private static float health(Store<EntityStore> store,Ref<EntityStore> ref) {
        return store.getComponent(ref,EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get();
    }

    private static Ref<EntityStore> startBoss(World world,Ref<EntityStore> site,CryptArena arena,double emptyTime) throws Exception {
        return await(on(world,()->{
            var store=world.getEntityStore().getStore();
            check(CryptEncounter.start(store,site,arena,null),"boss starts in real entity store");
            store.getComponent(site,CryptSiteComponent.getComponentType()).setActive(true);
            var roots=new ArrayList<Ref<EntityStore>>();
            store.forEachChunk(CryptBossComponent.getComponentType(),(chunk,ignored)->{
                for(int i=0;i<chunk.size();i++) roots.add(chunk.getReferenceTo(i));
            });
            check(roots.size()==1,"exactly one boss root");
            var boss=store.getComponent(roots.getFirst(),CryptBossComponent.getComponentType());
            boss.emptyTime=emptyTime;
            check(boss.parts.size()>500,"block skeleton assembled");
            return roots.getFirst();
        }));
    }

    private static void exerciseEveryAttack(World world,CryptBossComponent boss) throws Exception {
        // Fix the test's RNG after the real intro/stun/regeneration scenario. The
        // production engine still performs every enter, windup, impact and pose.
        await(on(world,()->{
            boss.fight=new CryptFight(0xC2F17L,1);
            while(boss.fight.state()==CryptFight.State.INTRO) boss.fight.tick(.25);
            boss.fight.hit(0,boss.fight.maxCrown*.8f,42,80);
            boss.revision=-1;
            check(boss.fight.phase()==3,"phase three reached through crown health");
            return true;
        }));
        awaitFrame(world,boss);
        var seen=EnumSet.noneOf(CryptFight.Move.class);
        int regularMoves=CryptFight.Move.values().length-1; // GRAB is a separate reactive retaliation test.
        for(int attempt=0;attempt<70 && seen.size()<regularMoves;attempt++) {
            await(on(world,()->{
                while(boss.fight.state()!=CryptFight.State.ATTACK) boss.fight.tick(.25);
                return true;
            }));
            awaitFrame(world,boss);
            var move=await(on(world,()->boss.fight.move()));
            advance(world,boss,boss.fight.windup()*.45);
            awaitFrame(world,boss);
            advance(world,boss,boss.fight.windup()*.55+.02);
            awaitFrame(world,boss);
            await(on(world,()->{
                check(boss.fired,"actual attack activated: "+move);
                if(move==CryptFight.Move.MINIONS) check(!boss.minions.isEmpty(),"real skeletal minions spawned");
                if(move==CryptFight.Move.BLUE_FIRE) check(boss.hazards.stream().anyMatch(h->!h.poison()),"blue fire hazards created");
                if(move==CryptFight.Move.POISON) check(boss.hazards.stream().anyMatch(CryptBossComponent.Hazard::poison),"poison hazard created");
                return true;
            }));
            advance(world,boss,move==CryptFight.Move.SLAM ? .3 : boss.fight.activeDuration()*.5);
            awaitFrame(world,boss);
            await(on(world,()->{
                var store=world.getEntityStore().getStore();
                for(var part:boss.parts) if(part.isValid()) {
                    var transform=store.getComponent(part,TransformComponent.getComponentType());
                    check(transform!=null && transform.getPosition().isFinite(),"finite part pose during "+move);
                    var rotation=transform.getRotation();
                    check(Float.isFinite(rotation.pitch()) && Float.isFinite(rotation.yaw()) && Float.isFinite(rotation.roll()),"finite part rotation during "+move);
                }
                while(boss.fight.state()==CryptFight.State.ATTACK) boss.fight.tick(.25);
                return true;
            }));
            awaitFrame(world,boss);
            if(seen.add(move)) System.out.println("[CRYPT RUNTIME SMOKE] attack "+move+" enter / telegraph / active pose PASS");
        }
        check(seen.size()==regularMoves,"all seven regular move variants exercised");
    }

    private static String block(World world,Vector3i p) {
        var chunks=world.getChunkStore();
        var ref=chunks.getChunkSectionReferenceAtBlock(p.x,p.y,p.z);
        check(ref!=null && ref.isValid(),"section loaded at "+p);
        var section=chunks.getStore().getComponent(ref,BlockSection.getComponentType());
        var type=BlockType.getAssetMap().getAsset(section.get(p.x,p.y,p.z));
        return type==null ? "UNKNOWN" : type.getId();
    }
    private static List<Ref<EntityStore>> rewardItems(Store<EntityStore> store,CryptArena arena) {
        var items=new ArrayList<Ref<EntityStore>>();
        store.forEachChunk(Archetype.of(ItemComponent.getComponentType(),TransformComponent.getComponentType()),(chunk,ignored)->{
            for(int i=0;i<chunk.size();i++) {
                var item=chunk.getComponent(i,ItemComponent.getComponentType());
                var transform=chunk.getComponent(i,TransformComponent.getComponentType());
                if(item.getItemStack()!=null && CryptSiteSystem.STAFF_ID.equals(item.getItemStack().getItemId()) &&
                    transform.getPosition().distanceSquared(arena.rewardPoint())<16) items.add(chunk.getReferenceTo(i));
            }
        });
        return items;
    }
    private static void advance(World world,CryptBossComponent boss,double seconds) throws Exception {
        await(on(world,()->{for(double time=0;time<seconds;time+=.25) boss.fight.tick(Math.min(.25,seconds-time));return true;}));
    }
    private static void awaitFrame(World world,CryptBossComponent boss) throws Exception {
        double elapsed=await(on(world,()->boss.elapsed));
        while(await(on(world,()->boss.elapsed<=elapsed || boss.revision!=boss.fight.revision()))) {
            checkTime("engine frame");Thread.sleep(20);
        }
    }
    private static <T> CompletableFuture<T> on(World world,Supplier<T> work) {
        return CompletableFuture.supplyAsync(work,world);
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        long remaining=deadline-System.nanoTime();
        if(remaining<=0) throw new TimeoutException("90-second smoke deadline");
        return future.get(remaining,TimeUnit.NANOSECONDS);
    }
    private static void waitFor(BooleanSupplier condition,String operation) throws Exception {
        while(!condition.getAsBoolean()) {checkTime(operation);Thread.sleep(50);}
    }
    private static void checkTime(String operation) throws TimeoutException {
        if(System.nanoTime()>deadline) throw new TimeoutException("90-second smoke deadline: "+operation);
    }
    private static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
}


