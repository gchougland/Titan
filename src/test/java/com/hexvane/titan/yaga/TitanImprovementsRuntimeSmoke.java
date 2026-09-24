package com.hexvane.titan.yaga;

import com.hexvane.titan.ai.TitanTreeClearing;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.*;
import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.spawn.*;
import com.hexvane.titan.system.*;
import com.hypixel.hytale.Main;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.ShutdownReason;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.server.core.universe.world.*;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.FlatWorldGenProvider;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.*;

/** Real engine checks; deliberately isolated from the user's worlds and running server. */
public final class TitanImprovementsRuntimeSmoke {
    private static volatile Throwable failure;
    public static void main(String[] args) {
        new Thread(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                while (HytaleServer.get() == null || !HytaleServer.get().isBooted()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("boot timeout");
                    Thread.sleep(100);
                }
                run();
                System.out.println("[TITAN IMPROVEMENTS SMOKE] PASS");
            } catch (Throwable error) {
                failure = error; error.printStackTrace();
                System.err.println("[TITAN IMPROVEMENTS SMOKE] FAIL");
            } finally {
                if (HytaleServer.get() != null) HytaleServer.get().shutdownServer(
                    failure == null ? ShutdownReason.SHUTDOWN : ShutdownReason.VERIFY_ERROR);
            }
        }, "TitanImprovementsSmoke").start();
        Main.main(args);
    }

    private static void run() throws Exception {
        var config = new WorldConfig();
        config.setSpawningNPC(false); config.setCanUnloadChunks(false);
        config.setWorldGenProvider(new FlatWorldGenProvider(FlatWorldGenProvider.DEFAULT_TINT,
            new FlatWorldGenProvider.Layer[]{new FlatWorldGenProvider.Layer(0,79,null,"Rock_Basalt"),
                new FlatWorldGenProvider.Layer(79,80,null,"Soil_Grass")}));
        var universe = Universe.get();
        String name = "titan-improvements-" + UUID.randomUUID().toString().substring(0,8);
        var world = universe.makeWorld(name, universe.validateWorldPath(name), config).get(20, TimeUnit.SECONDS);
        var loads = new ArrayList<CompletableFuture<?>>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            loads.add(world.getChunkAsync(ChunkUtil.indexChunk(x,z)));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).get(20,TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> exercise(world), world).get(30,TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> exerciseTalus(world), world).get(30,TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> exerciseCombinedModels(world), world).get(60,TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> exercisePoison(world), world).get(30,TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> exerciseWormTerrain(world), world).get(30,TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> TitanInteractionRuntimeSmoke.arrows(world.getEntityStore().getStore()), world).get(30,TimeUnit.SECONDS);
        if (Boolean.getBoolean("titan.memoryProfile"))
            CompletableFuture.runAsync(() -> profileMemory(world), world).get(60,TimeUnit.SECONDS);
    }

    private static void exerciseWormTerrain(World world) {
        var store=world.getEntityStore().getStore();var chunks=world.getChunkStore();
        for(String particle:List.of(com.hexvane.titan.dunewyrm.DunewyrmTuning.SLITHER_PARTICLE,
                com.hexvane.titan.dunewyrm.DunewyrmTuning.BREAK_PARTICLE,TitanVariantAsset.find("Dunewyrm").getImpactParticle()))
            check(com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem.getAssetMap().getAsset(particle)!=null,"sand particle exists: "+particle);
        for(int x=-42;x<=-34;x++)for(int z=-42;z<=-34;z++)block(chunks,x,94,z,"Rock_Basalt");
        var spawned=com.hexvane.titan.dunewyrm.DunewyrmSpawner.spawn(store,new Vector3d(-38,80,-38),0,32,false);
        check(spawned.ok(),"buried Dunewyrm test spawn");
        var worm=store.getComponent(spawned.root(),com.hexvane.titan.dunewyrm.DunewyrmComponent.getComponentType());
        var ai=new com.hexvane.titan.dunewyrm.DunewyrmAiSystem();
        var targetHolder=com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.newHolder();
        targetHolder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(-6,80,-16),new com.hypixel.hytale.math.vector.Rotation3f()));
        var treeTarget=store.addEntity(targetHolder,AddReason.SPAWN);
        try {
            store.forEachChunk(ai.getQuery(),(chunk,cb)->{for(int i=0;i<chunk.size();i++)if(spawned.root().equals(chunk.getReferenceTo(i)))ai.tick(.05f,i,chunk,store,cb);});
            check(worm.getHeadPosition().y>=95,"buried head recovers above the roof without excavating");
            var head=worm.findRole(com.hexvane.titan.dunewyrm.DunewyrmSegmentRole.HEAD);
            var tongue=worm.findRole(com.hexvane.titan.dunewyrm.DunewyrmSegmentRole.TONGUE);
            check(tongue.getPosition().y>head.getPosition().y+.95,"tongue sits one block higher at rest");
            worm.setFleeTimer(10);
            for(int step=0;step<60;step++)store.forEachChunk(ai.getQuery(),(chunk,cb)->{for(int i=0;i<chunk.size();i++)if(spawned.root().equals(chunk.getReferenceTo(i)))ai.tick(.05f,i,chunk,store,cb);});
            check(worm.getHeadPosition().distance(new Vector3d(-38,95,-38))>1,"worm continues moving on roof");
            for(int x=-42;x<=-34;x++)for(int z=-42;z<=-34;z++)check(GroundSampler.blockId(chunks,x,94,z)==BlockType.getAssetMap().getIndex("Rock_Basalt"),"roof survives movement");
            // Real one-block terraces across the head's width. The former clearHead gate
            // rejected the neighboring higher ground before the center reached each step.
            for(int x=-28;x<=-14;x++)for(int z=-42;z<=-18;z++) {
                int height=z<-28?2:z<-23?1:0;
                for(int dy=0;dy<height;dy++)block(chunks,x,80+dy,z,"Rock_Basalt");
            }
            worm.getHeadPosition().set(-21,80,-19);worm.setYaw(0);worm.getPath().clear();worm.setFleeTimer(10);
            for(int step=0;step<80;step++)store.forEachChunk(ai.getQuery(),(chunk,cb)->{for(int i=0;i<chunk.size();i++)if(spawned.root().equals(chunk.getReferenceTo(i)))ai.tick(.05f,i,chunk,store,cb);});
            check(worm.getHeadPosition().z<-29,"worm crosses both one-block terraces instead of stopping: "+worm.getHeadPosition());
            for(int x=-28;x<=-14;x++)for(int z=-42;z<=-18;z++) {
                int height=z<-28?2:z<-23?1:0;
                for(int dy=0;dy<height;dy++)check(GroundSampler.blockId(chunks,x,80+dy,z)==BlockType.getAssetMap().getIndex("Rock_Basalt"),"steps survive movement");
            }
            check(!GroundSampler.isValid(com.hexvane.titan.dunewyrm.DunewyrmTerrain.surface(chunks,1000000,1000000)),"unloaded terrain remains unloaded");
            // Reproduce a head already overlapping a tree. Forward movement is
            // blocked, but a tiny outward step must work before the head clears it.
            for(int y=80;y<88;y++)block(chunks,-6,y,-6,"Wood_Oak_Trunk");
            check(com.hexvane.titan.dunewyrm.DunewyrmTerrain.obstruction(chunks,-6.5,80,-4.5)>0,"tree contact reproduces overlap");
            check(GroundSampler.isValid(com.hexvane.titan.dunewyrm.DunewyrmTerrain.movementHeight(chunks,-6.5,80,-4.5,-6.5,-4.4,true)),"small retreat from tree is allowed");
            check(!GroundSampler.isValid(com.hexvane.titan.dunewyrm.DunewyrmTerrain.movementHeight(chunks,-6.5,80,-4.5,-6.4,-4.6,true)),"deeper tree penetration is rejected");
            check(!GroundSampler.isValid(com.hexvane.titan.dunewyrm.DunewyrmTerrain.movementHeight(chunks,-6.5,80,-1,-6.5,-11,true)),"fast movement cannot cross a trunk");
            for(var mode:List.of("SLITHER","FLEE","CHARGE","FLAIL")) {
                worm.getHeadPosition().set(-6.5,80,-4.5);worm.setYaw(0);worm.setChargeYaw(0);worm.getPath().clear();worm.setObstacleTurnCooldown(0);
                worm.setState(com.hexvane.titan.dunewyrm.DunewyrmState.valueOf(mode.equals("FLEE")?"SLITHER":mode));
                worm.setFleeTimer(mode.equals("FLEE")?10:0);worm.setTarget(treeTarget);worm.setAttackCooldown(100);
                for(int step=0;step<80;step++)store.forEachChunk(ai.getQuery(),(chunk,cb)->{for(int i=0;i<chunk.size();i++)if(spawned.root().equals(chunk.getReferenceTo(i)))ai.tick(.05f,i,chunk,store,cb);});
                check(worm.getHeadPosition().distance(new Vector3d(-6.5,80,-4.5))>2,"worm escapes tree contact during "+mode+": "+worm.getHeadPosition());
                check(com.hexvane.titan.dunewyrm.DunewyrmTerrain.obstruction(chunks,worm.getHeadPosition().x,worm.getHeadPosition().y,worm.getHeadPosition().z)==0,"head leaves tree during "+mode+": "+worm.getHeadPosition());
            }
            for(int y=80;y<88;y++)check(GroundSampler.blockId(chunks,-6,y,-6)==BlockType.getAssetMap().getIndex("Wood_Oak_Trunk"),"tree remains intact");
            System.out.println("[DUNE TERRAIN] Roof recovery, steps, tree overlap escape, swept trunk collision and raised tongue PASS");
        } finally {
            if(treeTarget.isValid())store.removeEntity(treeTarget,RemoveReason.REMOVE);
            for(var segment:worm.getSegments())for(var ref:segment.getVoxels())if(ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);
            if(spawned.root().isValid())store.removeEntity(spawned.root(),RemoveReason.REMOVE);
            for(int x=-42;x<=-34;x++)for(int z=-42;z<=-34;z++)block(chunks,x,94,z,"Empty");
            for(int x=-28;x<=-14;x++)for(int z=-42;z<=-18;z++)for(int dy=0;dy<2;dy++)block(chunks,x,80+dy,z,"Empty");
            for(int y=80;y<88;y++)block(chunks,-6,y,-6,"Empty");
        }
    }

    private static void exerciseTalus(World world) {
        var store = world.getEntityStore().getStore();
        for (String variant : List.of("Copper","Iron","Cobalt","Thorium","Adamantite","Mithril")) {
            var result = TitanSpawner.spawn(store,"Stone_Talus_"+variant,new Vector3d(0,80,0),.7f,ColliderMode.AUTO,456,false);
            check(result.ok(),"Talus spawn "+variant);
            var titan = store.getComponent(result.root(),TitanComponent.getComponentType());
            int original = 0;
            for (var bone : titan.getSkeleton().getBones()) {
                var voxels = PrefabVoxelReader.read(bone.getPrefab(),titan.getVariant().getRockType(),bone.getSliceMinY(),bone.getSliceMaxY(),bone.getPrefabRotation());
                original += voxels.size();
                var pieces = TitanMeshBatches.merge(bone, voxels, key -> {
                    var b = BlockType.getAssetMap().getAsset(key);
                    return b != null && b.isCubeDrawType() && b.getOpacity()==Opacity.Solid;
                });
                for (var piece : pieces) if (!piece.originals().isEmpty() && bone.getColliderStride()>0 && !bone.isColliderAllFaces()) {
                    boolean standable = piece.originals().getFirst().standable();
                    check(piece.originals().stream().allMatch(v -> v.standable()==standable),"Talus retains exact top collider selection");
                }
            }
            check(result.parts()<original*.4,"Talus combining saves at least 60 percent of parts: "+variant+" "+result.parts()+"/"+original);
            check(result.weakpoints()>0,"Talus weakpoints preserved");
            System.out.println("[TALUS GEOMETRY] "+variant+" "+original+" -> "+result.parts()+" rendered parts");
            titan.setState(TitanState.DYING);
            var sync = new TitanPartSyncSystem();
            store.forEachChunk(sync.getQuery(),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) if(result.root().equals(chunk.getComponent(i,TitanPartComponent.getComponentType()).getOwner()))
                    sync.tick(.1f,i,chunk,store,cb);
            });
            int[] count = {0};
            store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) {
                    var part=chunk.getComponent(i,TitanPartComponent.getComponentType());
                    if(!result.root().equals(part.getOwner())) continue;
                    check(part.isDetached() && !part.hasDebrisVoxels(),"Talus debris splits exactly once");
                    count[0]++;
                }
            });
            check(count[0]==original,"Talus original blocks restored, including mirrored limbs");
            removeTestTitan(store,result.root());
        }
    }

    private static List<String> contactSnapshot(Store<EntityStore> store,Ref<EntityStore> root) {
        var result=new ArrayList<String>();
        store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) {
                var p=chunk.getComponent(i,TitanPartComponent.getComponentType());
                if(!root.equals(p.getOwner()) || p.isCombinedVisual())continue;
                boolean collision=chunk.getComponent(i,com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision.getComponentType())!=null;
                boolean usable=chunk.getComponent(i,Interactable.getComponentType())!=null;
                boolean weak=chunk.getComponent(i,TitanWeakpointComponent.getComponentType())!=null;
                boolean hit=chunk.getComponent(i,RespondToHit.getComponentType())!=null;
                if(!collision && !usable && !weak && !hit)continue;
                var bounds=chunk.getComponent(i,BoundingBox.getComponentType());
                // Also exercise later rotation, including the original distinction
                // between rotating cuboids and nonrotating single-block hit boxes.
                bounds.applyRotation(.3f,.7f,.2f);
                var b=bounds.getBoundingBox();
                result.add(p.getBoneIndex()+":"+p.getLocalOffset()+":"+p.getBlockRotation()+":"+b.min+":"+b.max+":"+collision+":"+usable+":"+weak+":"+hit);
            }
        });
        Collections.sort(result);return result;
    }

    private static int renderedParts(Store<EntityStore> store,Ref<EntityStore> root) {
        int[] n={0};
        store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) {
                var p=chunk.getComponent(i,TitanPartComponent.getComponentType());
                if(root.equals(p.getOwner()) && !p.isCollisionOnly() && (chunk.getComponent(i,ModelComponent.getComponentType())!=null
                    || chunk.getComponent(i,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType())!=null))n[0]++;
            }
        });return n[0];
    }

    private static List<String> deathBlocks(Store<EntityStore> store,Ref<EntityStore> root) {
        store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getComponent(i,TitanPartComponent.getComponentType()).getOwner()))
                cb.tryRemoveComponent(chunk.getReferenceTo(i),TitanSpawnFxComponent.getComponentType());
        });
        store.getComponent(root,TitanComponent.getComponentType()).setState(TitanState.DYING);
        var sync=new TitanPartSyncSystem();
        store.forEachChunk(sync.getQuery(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getComponent(i,TitanPartComponent.getComponentType()).getOwner()))sync.tick(.1f,i,chunk,store,cb);
        });
        var result=new ArrayList<String>();
        store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) {
                var p=chunk.getComponent(i,TitanPartComponent.getComponentType());if(!root.equals(p.getOwner()))continue;
                var block=chunk.getComponent(i,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType());
                if(block==null && chunk.getComponent(i,ModelComponent.getComponentType())==null)continue;
                check(block!=null && p.isDetached(),"combined death restores native blocks");result.add(block.getBlockTypeKey());
            }
        });Collections.sort(result);return result;
    }

    private static void exerciseCombinedModels(World world) {
        var saved=TitanConfig.get();var store=world.getEntityStore().getStore();
        try {
            for(String variant:List.of("Roaming_Temple","Stone_Talus_Copper","Stone_Talus_Iron","Stone_Talus_Cobalt",
                "Stone_Talus_Thorium","Stone_Talus_Adamantite","Stone_Talus_Mithril","Yaga_Baba","Yaga_Baby","Yaga_Egg")) {
                var previous=new TitanConfig();set(previous,"combinePartModels",false);TitanConfig.setActive(previous);
                var before=TitanSpawner.spawn(store,variant,new Vector3d(0,90,0),.7f,ColliderMode.AUTO,42,false);
                check(before.ok(),"baseline spawn "+variant);
                var contacts=contactSnapshot(store,before.root());int oldDraws=renderedParts(store,before.root());
                var death=deathBlocks(store,before.root());removeTestTitan(store,before.root());
                TitanConfig.setActive(new TitanConfig());
                var after=TitanSpawner.spawn(store,variant,new Vector3d(0,90,0),.7f,ColliderMode.AUTO,42,false);
                check(after.ok(),"combined spawn "+variant);
                var afterContacts=contactSnapshot(store,after.root());
                if(!contacts.equals(afterContacts)) {
                    System.out.println("[CONTACT DIFF BEFORE] "+contacts.stream().filter(c -> !afterContacts.contains(c)).limit(8).toList());
                    System.out.println("[CONTACT DIFF AFTER] "+afterContacts.stream().filter(c -> !contacts.contains(c)).limit(8).toList());
                }
                check(contacts.equals(afterContacts),"exact collider/use/hit bounds preserved: "+variant);
                int newDraws=renderedParts(store,after.root());check(newDraws<oldDraws,"fewer visual parts: "+variant);
                verifyCombinedFrames(store,after.root());
                if (variant.startsWith("Yaga_") && !variant.equals("Yaga_Egg")) TitanInteractionRuntimeSmoke.verifyYaga(store,after.root());
                check(death.equals(deathBlocks(store,after.root())),"exact death block materials/counts preserved: "+variant);
                removeTestTitan(store,after.root());
                System.out.println("[ALL TITAN MODELS] "+variant+" "+oldDraws+" -> "+newDraws+" visuals; collision, use, hit targets and death blocks unchanged");
            }
            exerciseCombinedWorm(store);
        } finally { TitanConfig.setActive(saved); }
    }

    private static void exerciseCombinedWorm(Store<EntityStore> store) {
        List<String> baseline=null;int baselineDraws=0;
        for(boolean combined:List.of(false,true)) {
            var config=new TitanConfig();set(config,"combinePartModels",combined);TitanConfig.setActive(config);
            var result=com.hexvane.titan.dunewyrm.DunewyrmSpawner.spawn(store,new Vector3d(0,90,0),.7f,42,false);
            check(result.ok(),"Dunewyrm combined spawn");
            var worm=store.getComponent(result.root(),com.hexvane.titan.dunewyrm.DunewyrmComponent.getComponentType());
            var contacts=new ArrayList<String>();int draws=0,expectedDebris=0;
            for(var segment:worm.getSegments()) for(var ref:segment.getVoxels()) {
                var part=store.getComponent(ref,com.hexvane.titan.dunewyrm.DunewyrmPartComponent.getComponentType());
                if(part==null)continue;
                if(part.isCombinedVisual()){draws++;continue;}
                if(part.getOriginalBlock()==null && store.getComponent(ref,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType())!=null)draws++;
                var b=store.getComponent(ref,BoundingBox.getComponentType()).getBoundingBox();
                contacts.add(part.getSegmentIndex()+":"+part.getLocalOffset()+":"+b.min+":"+b.max+":"+part.isClimbable()+":"
                    +(store.getComponent(ref,com.hexvane.titan.dunewyrm.DunewyrmHitComponent.getComponentType())!=null));
                if(part.getOriginalBlock()!=null || store.getComponent(ref,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType())!=null)expectedDebris++;
            }
            Collections.sort(contacts);
            if(!combined){baseline=contacts;baselineDraws=draws;}
            else {check(baseline.equals(contacts),"Dunewyrm collision and hit targets unchanged");check(draws<baselineDraws,"Dunewyrm model combining active");}
            if (combined) {
                verifyWormFrames(store,result.root(),worm);
                com.hexvane.titan.crypt.CryptMissileRuntimeSmoke.dunewyrm(store,result.root(),worm);
            }
            var oldRefs=new ArrayList<Ref<EntityStore>>();for(var segment:worm.getSegments())oldRefs.addAll(segment.getVoxels());
            com.hexvane.titan.dunewyrm.DunewyrmSpawner.releaseAllAsDebris(store,worm);
            int debris=0;
            for(var ref:oldRefs)if(ref.isValid()) {
                if(store.getComponent(ref,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType())!=null)debris++;
                store.removeEntity(ref,RemoveReason.REMOVE);
            }
            check(debris==expectedDebris,"Dunewyrm restored its original block visuals on death");
            if(result.root().isValid())store.removeEntity(result.root(),RemoveReason.REMOVE);
            if(combined)System.out.println("[ALL TITAN MODELS] Dunewyrm "+baselineDraws+" -> "+draws+" visuals; collision and death blocks unchanged");
        }
    }

    private static org.bson.BsonDocument assetJson(String path) {
        try(var stream=TitanImprovementsRuntimeSmoke.class.getResourceAsStream("/"+path)) {
            return org.bson.BsonDocument.parse(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        } catch(java.io.IOException e) { throw new RuntimeException(e); }
    }

    /** Check actual client mesh coordinates, not just the separate collider entities. */
    private static void verifyMeshFrame(TransformComponent transform,
            com.hypixel.hytale.server.core.asset.type.model.config.Model model,
            org.joml.Matrix4d boneWorld, Vector3d pivot, double mirror, double boneScale,
            TitanPartModels.Group group) {
        var decoded=transform.getRotation().getQuaternion(new org.joml.Quaterniond()).rotateY(Math.PI);
        var mesh=assetJson("Common/"+model.getModel());
        var blockRoot=group.block()==null?null:assetJson("Common/"+BlockType.getAssetMap().getAsset(group.block()).getCustomModel())
            .getArray("nodes").getFirst().asDocument();
        int nodeIndex=0;
        for(var value:mesh.getArray("nodes").getFirst().asDocument().getArray("children")) {
            var pos=value.asDocument().getDocument("position");
            var point=new Vector3d(pos.getNumber("x").doubleValue(),pos.getNumber("y").doubleValue(),pos.getNumber("z").doubleValue()).div(64);
            var renderedPoint=new Vector3d(point);
            if(blockRoot!=null) {
                var blockPos=blockRoot.getArray("children").get(nodeIndex).asDocument().getDocument("position");
                var rootPos=blockRoot.getDocument("position");
                renderedPoint.set(blockPos.getNumber("x").doubleValue()+rootPos.getNumber("x").doubleValue(),
                    blockPos.getNumber("y").doubleValue()+rootPos.getNumber("y").doubleValue()-16,
                    blockPos.getNumber("z").doubleValue()+rootPos.getNumber("z").doubleValue()).mul(group.blockScale()/32.0);
            }
            nodeIndex++;
            // Noncentral samples catch pitch, roll and facing even for symmetric boxes.
            for(var delta:List.of(new Vector3d(),new Vector3d(.3,.7,-.2))) {
                var sample=new Vector3d(point).add(delta);
                var actual=decoded.transform(new Vector3d(renderedPoint).add(delta).mul(model.getScale())).add(transform.getPosition());
                var expected=boneWorld.transformPosition(sample.add(group.originX()-pivot.x*mirror,
                    group.originY()-pivot.y,group.originZ()-pivot.z).mul(boneScale));
                check(actual.distance(expected)<.0002,"rendered geometry follows original bone: "+group.model()+" "+actual+" != "+expected);
            }
        }
    }

    private static void verifyCombinedFrames(Store<EntityStore> store,Ref<EntityStore> root) {
        var titan=store.getComponent(root,TitanComponent.getComponentType());
        var pose=titan.getPose();var sync=new TitanPartSyncSystem();
        var fx=new TitanSpawnFxSystem();
        store.forEachChunk(fx.getQuery(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++)if(root.equals(chunk.getComponent(i,TitanPartComponent.getComponentType()).getOwner()))
                fx.tick(10f,i,chunk,store,cb);
        });
        // Spawn orientation and arbitrary walking/resting turns both retain the mesh basis.
        for(int frame=0;frame<2;frame++) {
            if(frame==1) {
                for(int i=0;i<pose.getBoneCount();i++)pose.getWorld(i).rotateXYZ(.28,-.37,.19);
                pose.captureMotion();titan.setPoseDirty(true);
                store.forEachChunk(sync.getQuery(),(chunk,cb)->{
                    for(int i=0;i<chunk.size();i++)if(root.equals(chunk.getComponent(i,TitanPartComponent.getComponentType()).getOwner()))
                        sync.tick(.1f,i,chunk,store,cb);
                });
            }
            store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) {
                    var part=chunk.getComponent(i,TitanPartComponent.getComponentType());
                    if(!root.equals(part.getOwner()) || !part.isCombinedVisual())continue;
                    var bone=titan.getSkeleton().getBones()[part.getBoneIndex()];
                    var voxels=PrefabVoxelReader.read(bone.getPrefab(),titan.getVariant().getRockType(),bone.getSliceMinY(),bone.getSliceMaxY(),bone.getPrefabRotation());
                    var pivot=bone.getPivot()!=null?new Vector3d(bone.getPivot()):voxels.defaultPivot();
                    var modelComponent=chunk.getComponent(i,ModelComponent.getComponentType());
                    var block=chunk.getComponent(i,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType());
                    var group=TitanPartModels.find(voxels,bone.isMirrorX(),bone.isHollow(),1f).groups().stream()
                        .filter(g->block!=null?block.getBlockTypeKey().equals(g.block()):g.model().equals(modelComponent.getModel().getModelAssetId())).findFirst().orElseThrow();
                    var model=block==null?modelComponent.getModel():com.hypixel.hytale.server.core.asset.type.model.config.Model.createStaticScaledModel(
                        com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset.getAssetMap().getAsset(group.model()),
                        chunk.getComponent(i,EntityScaleComponent.getComponentType()).getScale()/group.blockScale());
                    if(bone.isUsable())check(block!=null && modelComponent==null,"Yaga uses only the native block renderer");
                    verifyMeshFrame(chunk.getComponent(i,TransformComponent.getComponentType()),model,pose.getWorld(part.getBoneIndex()),
                        pivot,bone.isMirrorX()?-1:1,bone.getScale(),group);
                    check((chunk.getComponent(i,Interactable.getComponentType())!=null)==bone.isUsable(),"usable bone interaction belongs to its visible artwork");
                    check((chunk.getComponent(i,Intangible.getComponentType())==null)==bone.isUsable(),"usable artwork is tangible; scenery remains intangible");
                }
            });
        }
    }

    private static void verifyWormFrames(Store<EntityStore> store,Ref<EntityStore> root,
            com.hexvane.titan.dunewyrm.DunewyrmComponent worm) {
        var sync=new com.hexvane.titan.dunewyrm.DunewyrmPartSyncSystem();
        for(int frame=0;frame<2;frame++) {
            if(frame==1) {
                for(var segment:worm.getSegments()) {segment.setPitch(.53f);segment.setYaw(-.82f);}
                store.forEachChunk(sync.getQuery(),(chunk,cb)->{
                    for(int i=0;i<chunk.size();i++)if(root.equals(chunk.getComponent(i,com.hexvane.titan.dunewyrm.DunewyrmPartComponent.getComponentType()).getOwner()))
                        sync.tick(.1f,i,chunk,store,cb);
                });
            }
            for(var segment:worm.getSegments())for(var ref:segment.getVoxels()) {
                var part=store.getComponent(ref,com.hexvane.titan.dunewyrm.DunewyrmPartComponent.getComponentType());
                if(part==null || !part.isCombinedVisual())continue;
                var voxels=PrefabVoxelReader.read(segment.getPrefabKey());
                var pivot=voxels.center();
                if(segment.getRole()!=com.hexvane.titan.dunewyrm.DunewyrmSegmentRole.TONGUE)pivot.y=voxels.defaultPivot().y;
                float scale=switch(segment.getRole()){case HEAD,JAW,TONGUE -> com.hexvane.titan.dunewyrm.DunewyrmTuning.HEAD_SCALE;default -> 1f;};
                var model=store.getComponent(ref,ModelComponent.getComponentType()).getModel();
                var group=TitanPartModels.find(voxels,segment.isMirrored(),true,scale).groups().stream()
                    .filter(g->g.model().equals(model.getModelAssetId())).findFirst().orElseThrow();
                // Native voxel spawn uses yaw only; its first sync applies the
                // pitch from the laid-out chain. Compare the same two stages.
                var world=new org.joml.Matrix4d().translation(segment.getPosition()).rotateY(segment.getYaw())
                    .rotateX(frame==0?0:segment.getPitch());
                verifyMeshFrame(store.getComponent(ref,TransformComponent.getComponentType()),model,world,pivot,segment.isMirrored()?-1:1,1,group);
            }
        }
    }

    private static void exercisePoison(World world) {
        var store=world.getEntityStore().getStore();
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(0,90,0),new Rotation3f()));
        holder.addComponent(com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent.getComponentType(),
            new com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent());
        holder.ensureComponent(EntityStatMap.getComponentType());
        var ref=store.addEntity(holder,AddReason.SPAWN);
        try {
            var controller=store.getComponent(ref,com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent.getComponentType());
            var system=new com.hypixel.hytale.server.core.modules.entity.livingentity.LivingEntityEffectSystem();
            for(var id:List.of("Crypt_Poison","Dunewyrm_Poison")) {
                var map=com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.getAssetMap();
                var effect=map.getAsset(id);check(effect!=null && !effect.isInfinite(),"finite poison asset "+id);
                for(int i=0;i<200;i++)controller.addEffect(ref,effect,store);
                var active=controller.getActiveEffects().get(map.getIndex(id));
                check(active.getRemainingDuration()<=4 && !active.isInfinite(),"repeated poison exposure never accumulates duration");
                for(int i=0;i<45;i++)tick(store,ref,system);
                check(!controller.getActiveEffects().containsKey(map.getIndex(id)),"poison expires after leaving cloud");
            }
            System.out.println("[TITAN POISON] repeated exposure capped at 4 seconds; both effects expire PASS");
        } finally {if(ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);}
    }

    private static void removeTestTitan(Store<EntityStore> store, Ref<EntityStore> root) {
        store.forEachChunk((chunk,cb)->{
            for(int i=0;i<chunk.size();i++) {
                var part=chunk.getComponent(i,TitanPartComponent.getComponentType());
                var weak=chunk.getComponent(i,TitanWeakpointComponent.getComponentType());
                if((part!=null && root.equals(part.getOwner())) || (weak!=null && root.equals(weak.getOwner())))
                    cb.removeEntity(chunk.getReferenceTo(i),RemoveReason.REMOVE);
            }
        });
        if(root.isValid()) store.removeEntity(root,RemoveReason.REMOVE);
    }

    /** Runs only in this disposable world when explicitly requested by the smoke task. */
    private static void profileMemory(World world) {
        var store=world.getEntityStore().getStore();
        store.forEachChunk((chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(chunk.getComponent(i,TitanPartComponent.getComponentType())!=null
                || chunk.getComponent(i,TitanWeakpointComponent.getComponentType())!=null)
                cb.removeEntity(chunk.getReferenceTo(i),RemoveReason.REMOVE);
        });
        long baseline=retainedHeap();
        var samples=new ArrayList<String>();
        for(int cycle=0;cycle<4;cycle++) {
            var roots=new ArrayList<Ref<EntityStore>>();
            for(int i=0;i<3;i++) {
                var result=TitanSpawner.spawn(store,"Roaming_Temple",new Vector3d(i*8,80,0),0,ColliderMode.AUTO,123+i,false);
                check(result.ok(),"memory profile spawn"); roots.add(result.root());
            }
            long loaded=retainedHeap();
            for(var root:roots) removeTestTitan(store,root);
            roots.clear();
            long removed=retainedHeap();
            samples.add("{\"cycle\":"+cycle+",\"threeTemplesUsedBytes\":"+loaded+",\"afterRemovalUsedBytes\":"+removed+"}");
            System.out.println("[TITAN MEMORY] cycle="+cycle+" baselineMiB="+(baseline/1048576.0)
                +" threeTemplesMiB="+(loaded/1048576.0)+" removedMiB="+(removed/1048576.0));
        }
        long unmerged = 0;
        var saved = TitanConfig.get();
        var legacy = new TitanConfig(); set(legacy,"mergeSolidVoxels",false);
        TitanConfig.setActive(legacy);
        try {
            var roots = new ArrayList<Ref<EntityStore>>();
            for(int i=0;i<3;i++) {
                var result=TitanSpawner.spawn(store,"Roaming_Temple",new Vector3d(i*8,80,0),0,ColliderMode.AUTO,123+i,false);
                check(result.ok(),"unmerged comparison spawn"); roots.add(result.root());
            }
            unmerged=retainedHeap();
            for(var root:roots) removeTestTitan(store,root);
            roots.clear();
            System.out.println("[TITAN MEMORY] threeUnmergedTemplesMiB="+(unmerged/1048576.0)+" afterRemovalMiB="+(retainedHeap()/1048576.0));
        } finally { TitanConfig.setActive(saved); }
        var heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        String report="{\"baselineUsedBytes\":"+baseline+",\"heapCommittedBytes\":"+heap.getCommitted()
            +",\"heapMaxBytes\":"+heap.getMax()+",\"threeUnmergedTemplesUsedBytes\":"+unmerged+",\"cycles\":["+String.join(",",samples)+"]}";
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("titan-memory-profile.json"),report); }
        catch(java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static long retainedHeap() {
        System.gc(); // Never in production code or on a user's server.
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void exercise(World world) {
        var store = world.getEntityStore().getStore();
        var chunks = world.getChunkStore();
        var saved = TitanConfig.get();
        var config = new TitanConfig(); set(config,"combinePartModels",false); TitanConfig.setActive(config);
        try {
            var temple = TitanSpawner.spawn(store,"Roaming_Temple",new Vector3d(0,80,0),0,ColliderMode.AUTO,123,false);
            check(temple.ok(), "temple spawn: " + temple.error());
            var titan = store.getComponent(temple.root(),TitanComponent.getComponentType());
            int original = 0, merged = 0;
            for (var bone : titan.getSkeleton().getBones()) {
                var voxels = PrefabVoxelReader.read(bone.getPrefab(), null, bone.getSliceMinY(),bone.getSliceMaxY(),bone.getPrefabRotation());
                original += bone.isHollow() ? voxels.surfaceSize() : voxels.size();
                var pieces = com.hexvane.titan.spawn.TitanMeshBatches.merge(bone, voxels, key -> {
                    var type = BlockType.getAssetMap().getAsset(key);
                    return type != null && type.isCubeDrawType() && type.getOpacity() == Opacity.Solid;
                });
                merged += (int) pieces.stream().filter(p -> !bone.isHollow() || p.voxel().surface()).count();
                if ("Titan/Temple/Temple_Body".equals(bone.getPrefab())) {
                    for (var piece : pieces) {
                        var v = piece.voxel();
                        check(!v.blockKey().equals("Plant_Vine_Jungle") && !v.blockKey().equals("Plant_Vine_Wall_Winter"),
                            "side vines use the corrected rendering path throughout the body");
                        if (!v.surface() || v.y() > 14) continue;
                        var type = BlockType.getAssetMap().getAsset(v.blockKey());
                        check(type == null || !type.isCubeDrawType() || type.getOpacity() != Opacity.Solid,
                            "every exposed solid block through the middle uses a generated model: " + v);
                    }
                    var changed = new com.hexvane.titan.spawn.PrefabVoxels(
                        voxels.getVoxels().subList(1, voxels.size()), voxels.minX(), voxels.minY(), voxels.minZ(),
                        voxels.maxX(), voxels.maxY(), voxels.maxZ());
                    var fallback = com.hexvane.titan.spawn.TitanMeshBatches.merge(bone, changed, ignored -> false);
                    check(TitanPartModels.find(changed,bone.isMirrorX(),bone.isHollow(),1f)==null,"edited prefab cannot use stale whole-part art");
                    check(fallback.size() == changed.size() && fallback.stream().noneMatch(v ->
                        v.voxel().blockKey().startsWith("Titan_Temple_Body_")), "edited prefabs discard stale baked geometry");
                    var interactive = com.hexvane.titan.spawn.TitanMeshBatches.merge(bone, voxels, ignored -> false);
                    check(interactive.size() == voxels.size(), "ineligible materials/fixtures are never swallowed by batches");
                }
            }
            int visualGroups = com.hexvane.titan.spawn.TitanMeshBatches.templeBodyVisuals().size();
            check(temple.parts() == merged+visualGroups, "spawn adds bounded body models beside the original collision pieces");
            check(merged < original * .30, "temple batching removes at least 70% of render entities");
            System.out.println("[TITAN GEOMETRY] Temple " + original + " -> " + merged + " rendered parts; weakpoints=" + temple.weakpoints());
            check(temple.weakpoints() > 0, "weakpoints retained");
            for (String key : List.of("Plant_Vine_Jungle","Plant_Vine_Wall_Winter")) {
                var type=BlockType.getAssetMap().getAsset("Titan_Temple_Detail_"+key);
                check(type!=null,"middle-section vine asset exists");
                var modelBounds=com.hypixel.hytale.server.core.asset.type.model.BlockyModelBoundsParser.computeBounds(type.getCustomModel());
                check(modelBounds!=null && modelBounds.min.x>=-.5 && modelBounds.max.x<=.5
                    && modelBounds.min.y>=0 && modelBounds.max.y<=1 && modelBounds.min.z>=-.5 && modelBounds.max.z<=.5,
                    "side vines fit the same unit envelope as the fixed solid meshes");
            }
            var moss = BlockType.getAssetMap().getAsset("Plant_Moss_Block_Green");
            check(moss.getTintUp() != null && moss.getTintUp().length > 0, "native moss keeps its green tint");
            var large = new ArrayList<Ref<EntityStore>>();
            int[] bodyModels = {0,0};
            int[] visibleParts = {0};
            store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) {
                    var part=chunk.getComponent(i,TitanPartComponent.getComponentType());
                    if (temple.root().equals(part.getOwner())) {
                        var entityModel = chunk.getComponent(i, ModelComponent.getComponentType());
                        var blockVisual = chunk.getComponent(i, com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType());
                        if (!part.isCollisionOnly() && (entityModel != null || blockVisual != null)) visibleParts[0]++;
                        if (entityModel != null) {
                            bodyModels[part.isCollisionOnly() ? 1 : 0]++;
                            check(chunk.getComponent(i, com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType()) == null,
                                "body uses only the entity renderer");
                            check(chunk.getComponent(i, EntityScaleComponent.getComponentType()) == null,
                                "body geometry has no second block scale");
                            var modelBounds = entityModel.getModel().getBoundingBox();
                            var serverBounds = chunk.getComponent(i, BoundingBox.getComponentType()).getBoundingBox();
                            check(Math.abs(modelBounds.height()-serverBounds.height())<1e-6,
                                "body model sent to client matches the full collision height");
                            check(modelBounds.getMaximumThickness() >= 1,"body client bounds describe full size");
                            if (part.isCollisionOnly()) {
                                check(!part.hasDebrisVoxels(),"collision pieces do not duplicate death blocks");
                                check(chunk.getComponent(i, com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision.getComponentType()) != null,
                                    "invisible body pieces retain climbable collision");
                            } else {
                                check(com.hexvane.titan.spawn.TitanMeshBatches.templeBodyVisuals().stream()
                                    .anyMatch(group -> group.model().equals(entityModel.getModel().getModelAssetId())),"bounded island model asset");
                                check(part.hasDebrisVoxels(),"body visual owns the original blocks for death");
                                check(chunk.getComponent(i, com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision.getComponentType()) == null,
                                    "whole visual bounds must not fill the island's gaps with collision");
                            }
                        }
                        var block = chunk.getComponent(i, com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType());
                        if (block != null && block.getBlockTypeKey().startsWith("Titan_Temple_")) {
                            var type = BlockType.getAssetMap().getAsset(block.getBlockTypeKey());
                            var box = com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes.getAssetMap()
                                .getAsset(type.getHitboxTypeIndex()).get(0).getBoundingBox();
                            check(box.min.x >= 0 && box.min.y >= 0 && box.min.z >= 0
                                && box.max.x <= 1 && box.max.y <= 1 && box.max.z <= 1,
                                "client mesh hitboxes stay within the scaled block envelope");
                            var server = chunk.getComponent(i, BoundingBox.getComponentType()).getBoundingBox();
                            // At spawn the bone has only yaw, which preserves the Y extent.
                            check(Math.abs((box.max.y-box.min.y)*part.getScale()
                                -(server.max.y-server.min.y)) < 1e-5, "client and server vertical bounds agree");
                        }
                    }
                    if(temple.root().equals(part.getOwner()) && part.getScale() > 1.01f) large.add(chunk.getReferenceTo(i));
                }
            });
            check(!large.isEmpty(), "merged entities exist");
            check(bodyModels[0] == visualGroups && bodyModels[1] == 685,"bounded visible body models with 685 invisible collision pieces");
            check(visibleParts[0] == 767+visualGroups,"rendered pieces include every bounded island model");
            System.out.println("[TITAN WHOLE BODY] "+visualGroups+" bounded island models; 685 invisible collision pieces; "+visibleParts[0]+" rendered pieces across the temple");
            for (var entity : large) {
                boolean block = store.getComponent(entity,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType()) != null;
                boolean model = store.getComponent(entity,ModelComponent.getComponentType()) != null;
                check(block != model,"each part uses exactly one renderer");
            }
            var part=store.getComponent(large.getFirst(),TitanPartComponent.getComponentType());
            var bounds=store.getComponent(large.getFirst(),BoundingBox.getComponentType()).getBoundingBox();
            check(bounds.max.x-bounds.min.x>=1,"merged collider retains its full size");

            // A tree in the actual posed mesh, alongside a worked wood block that must survive.
            var p=store.getComponent(large.getFirst(),TransformComponent.getComponentType()).getPosition();
            int tx=(int)Math.floor(p.x),ty=(int)Math.floor(p.y),tz=(int)Math.floor(p.z);
            block(chunks,tx,ty,tz,"Wood_Oak_Trunk");
            titan.getVelocity().set(0,0,-.9);
            for(int i=0;i<40;i++) TitanTreeClearing.tick(titan,chunks);
            check(GroundSampler.blockId(chunks,tx,ty,tz)==BlockType.getAssetMap().getIndex("Wood_Oak_Trunk"),"tree destruction defaults off");
            set(config,"roamingTempleDestroysTrees",true);
            for(int i=0;i<40;i++) TitanTreeClearing.tick(titan,chunks);
            check(GroundSampler.blockId(chunks,tx,ty,tz)==BlockType.EMPTY_ID,"walking contact clears trees");
            block(chunks,tx,ty,tz,"Wood_Deadwood_Planks");
            for(int i=0;i<40;i++) TitanTreeClearing.tick(titan,chunks);
            check(GroundSampler.blockId(chunks,tx,ty,tz)==BlockType.getAssetMap().getIndex("Wood_Deadwood_Planks"),"worked wood survives");

            // Sync a damaged node through both real systems, preserving the root's death floor.
            var node=titan.getWeakpoints().getFirst();
            var stats=store.getComponent(node,EntityStatMap.getComponentType());
            stats.setStatValue(DefaultEntityStatTypes.getHealth(),1);
            tick(store,temple.root(),new TitanBossBarSystem());
            checkScratchCleared(TitanBossBarSystem.class,"nodes","engaged");
            check(store.getComponent(temple.root(),EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get()<titan.getTotalHealth(),"pooled health reflects node damage");
            tick(store,temple.root(),new TitanHealthSyncSystem());
            checkScratchCleared(TitanHealthSyncSystem.class,"nodes");

            for(int y=80;y<104;y++) block(chunks,5,y,5,"Wood_Oak_Trunk");
            block(chunks,5,104,5,"Plant_Leaves_Oak");
            check(GroundSampler.sample(chunks,5,104,5,0,48)==80,"ground sampler ignores trees");
            check(TitanTerrainProbe.surfaceY(chunks,5,5)==79,"natural spawn ground ignores trees");
            for(int x=0;x<=20;x++) for(int z=0;z<=20;z++) for(int y=80;y<120;y++) water(chunks,x,y,z);
            for(int y=80;y<84;y++) water(chunks,25,y,25);
            var owner = owner(store);
            for(var stage : new YagaComponent.Stage[]{YagaComponent.Stage.BABY,YagaComponent.Stage.BABA}) {
                var spawned=YagaSpawn.spawn(store,stage,new Vector3d(10.5,80,10.5),0,null);
                check(spawned.ok(),"Yaga spawn");
                var root=spawned.root();
                var pet=store.getComponent(root,TitanComponent.getComponentType());
                for (var chain : pet.getSkeleton().getIkChains()) check(chain.isPreserveFacing(), "Yaga legs preserve facing");
                if (stage == YagaComponent.Stage.BABA) {
                    check(pet.getVariant().getMoveSpeed() == 3.125f && pet.getVariant().getWandSpeed() == 6.25,
                        "Baba following and wand speeds increase another 25 percent");
                    var body = java.util.Arrays.stream(pet.getSkeleton().getBones()).filter(TitanMeshBatches::isYagaHouse).findFirst().orElseThrow();
                    var bodyVoxels = PrefabVoxelReader.read(body.getPrefab(),null,body.getSliceMinY(),body.getSliceMaxY(),body.getPrefabRotation());
                    int[] bodyCounts = new int[3];
                    store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
                        for (int i=0;i<chunk.size();i++) {
                            var piece = chunk.getComponent(i,TitanPartComponent.getComponentType());
                            if (!root.equals(piece.getOwner()) || piece.getBoneIndex()!=body.getIndex()) continue;
                            var block = chunk.getComponent(i,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType());
                            if (block == null) continue;
                            bodyCounts[0]++;
                            if (block.getBlockTypeKey().startsWith("Titan_Temple_Yaga_Baba_")) {
                                bodyCounts[1]++;
                                check(piece.hasDebrisVoxels(),"combined house retains original debris blocks");
                                check(chunk.getComponent(i,Interactable.getComponentType()) != null,"combined house can still be clicked");
                            }
                            if (chunk.getComponent(i,TitanFixtureComponent.getComponentType()) != null) bodyCounts[2]++;
                        }
                    });
                    check(bodyCounts[0] < bodyVoxels.surfaceSize()*.6 && bodyCounts[1] > 0,"Baba house is actually batched");
                    check(bodyCounts[2] == 5,"both chests, bed, furnace and workbench stay separate and usable");
                    System.out.println("[YAGA GEOMETRY] Baba house "+bodyVoxels.surfaceSize()+" -> "+bodyCounts[0]+" rendered parts; fixtures="+bodyCounts[2]);
                    var inventories = store.getComponent(root,YagaComponent.getComponentType()).getInventories();
                    check(inventories.length == 2, "Baba has exactly two chests");
                    for (var chest : inventories) check(chest.getCapacity() == 36, "both Baba chests have four rows");
                    var old = new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer((short)63);
                    for (short slot=0; slot<63; slot++) old.setItemStackForSlot(slot,
                        new com.hypixel.hytale.server.core.inventory.ItemStack("Rock_Stone",100));
                    var restored = YagaInventory.restore(new com.hypixel.hytale.server.core.inventory.container.ItemContainer[]{old,old},inventories);
                    check(restored[0].getCapacity()==36 && restored[1].getCapacity()==36, "migration keeps both visible chests at four rows");
                    check(java.util.Arrays.stream(restored).mapToInt(YagaInventory::count).sum()==126,"overflow items survive resizing");
                    restored[0].removeItemStackFromSlot((short)0);
                    YagaInventory.refillRecovered(restored,2);
                    check(restored[0].getItemStack((short)0)!=null,"overflow becomes accessible when a slot is freed");
                    check(java.util.Arrays.stream(restored).mapToInt(YagaInventory::count).sum()==125,"recovery does not duplicate items");
                    var reloaded = YagaInventory.restore(restored,new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer[]{
                        new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer((short)36),
                        new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer((short)36)});
                    check(java.util.Arrays.stream(reloaded).mapToInt(YagaInventory::count).sum()==125,"recovery survives another reload");
                }
                var transform=store.getComponent(root,TransformComponent.getComponentType());
                double height=pet.getVariant().getFloatPlatformHeight()*pet.getScale();
                var driver=new YagaPetSystem(); var animation=new TitanAnimationSystem();
                for(int i=0;i<100;i++) { tick(store,root,driver); tick(store,root,animation); }
                check(pet.isSwimming(),stage+" swims");
                check(Math.abs(transform.getPosition().y+height-120.15)<.001,stage+" floor clearance");
                var foot=new Vector3d(pet.getFeet()[0].current);
                tick(store,root,animation);
                check(foot.distance(pet.getFeet()[0].current)>.01,stage+" paddling legs");
                var yaga=store.getComponent(root,YagaComponent.getComponentType());
                yaga.setOwnerUuid(owner.getUuid());
                var start=new Vector3d(transform.getPosition());
                tick(store,root,driver);
                check(transform.getPosition().z<start.z && pet.isSwimming(),stage+" follows owner while afloat");
                transform.getPosition().set(start);
                var inventory=new SimpleItemContainer((short)1);
                inventory.setItemStackForSlot((short)0,new ItemStack(YagaWand.ITEM,1));
                store.putComponent(owner.getReference(),InventoryComponent.Hotbar.getComponentType(),new InventoryComponent.Hotbar(inventory,(byte)0));
                YagaWand.point(owner.getUuid());
                tick(store,root,driver);
                check(start.z-transform.getPosition().z>.3 && pet.isSwimming(),stage+" follows wand at normal speed while afloat");
                YagaWand.release(owner.getUuid());
                yaga.setOwnerUuid(null);
                transform.getPosition().set(start);
                yaga.leap(Math.sqrt(2*42*pet.getVariant().getLeapHeight()),0);
                for(int i=0;i<40 && yaga.isLeaping();i++) tick(store,root,driver);
                check(!yaga.isLeaping() && pet.isSwimming(),stage+" lands on water after leap");
                check(Math.abs(transform.getPosition().y+height-120.15)<.001,stage+" leap landing floor clearance");
                transform.getPosition().set(start);
                store.getComponent(root,YagaComponent.getComponentType()).setMode(YagaComponent.Mode.RESTING);
                for(int i=0;i<40;i++) tick(store,root,driver);
                check(Math.abs(transform.getPosition().y+height-120.15)<.001,stage+" resting cannot sink floor");
                var shallow=YagaBuoyancy.support(chunks,new Vector3d(25.5,80,25.5),height,0);
                check(!shallow.swimming()&&shallow.rootY()==80,stage+" walks in shallow water");
                transform.getPosition().set(30.5,80,30.5);
                store.getComponent(root,YagaComponent.getComponentType()).setMode(YagaComponent.Mode.FOLLOW);
                tick(store,root,driver);tick(store,root,animation);
                check(!pet.isSwimming(),stage+" stops paddling on land");
                store.removeEntity(root,RemoveReason.REMOVE);
            }
            YagaWand.forget(owner.getUuid());
            world.untrackPlayerRef(owner);
            store.removeEntity(owner.getReference(),RemoveReason.REMOVE);
            // Death expands only the visible originals, not the buried fill volume.
            titan.setState(TitanState.DYING);
            var sync = new TitanPartSyncSystem();
            store.forEachChunk(sync.getQuery(),(chunk,cb)->{
                for (int i=0;i<chunk.size();i++) {
                    var piece=chunk.getComponent(i,TitanPartComponent.getComponentType());
                    if(temple.root().equals(piece.getOwner())) sync.tick(.1f,i,chunk,store,cb);
                }
            });
            int[] debris = {0};
            store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) {
                    var piece=chunk.getComponent(i,TitanPartComponent.getComponentType());
                    if(!temple.root().equals(piece.getOwner())) continue;
                    var block=chunk.getComponent(i,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType());
                    if(block==null) continue;
                    check(!block.getBlockTypeKey().startsWith("Titan_Temple_"),"combined models are removed on death");
                    check(piece.isDetached() && piece.getDebrisVoxels().isEmpty(),"debris is independent and cannot split again");
                    debris[0]++;
                }
            });
            check(debris[0]==original,"death restores exactly the original visible blocks without duplicates: expected "+original+", got "+debris[0]);
            System.out.println("[TITAN DEBRIS] "+merged+" combined/native parts -> "+debris[0]+" original blocks PASS");
            store.removeEntity(temple.root(),RemoveReason.REMOVE);
            System.out.println("[TITAN TERRAIN/WATER] tree contact/default/preserved planks, true ground, both Yagas/depth/floor/paddles/follow/wand/leap/rest/shore PASS");
        } finally { TitanConfig.setActive(saved); }
    }

    private static void tick(Store<EntityStore> store,Ref<EntityStore> root,com.hypixel.hytale.component.system.tick.EntityTickingSystem<EntityStore> system) {
        store.forEachChunk(system.getQuery(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getReferenceTo(i))) system.tick(.1f,i,chunk,store,cb);
        });
    }
    private static void block(ChunkStore chunks,int x,int y,int z,String key) {
        var ref=chunks.getChunkSectionReferenceAtBlock(x,y,z);
        check(ref!=null,"loaded block section");
        var type=BlockType.getAssetMap().getAsset(key);
        BlockOperations.setBlock(chunks,ref,x,y,z,BlockType.getAssetMap().getIndex(key),type,RotationTuple.NONE_INDEX,0,SetBlockSettings.NONE);
    }
    private static void water(ChunkStore chunks,int x,int y,int z) {
        var ref=chunks.getChunkSectionReferenceAtBlock(x,y,z);
        check(ref!=null,"loaded fluid section");
        var section=chunks.getStore().ensureAndGetComponent(ref,FluidSection.getComponentType());
        var fluid=Fluid.getAssetMap().getAsset("Water_Source");
        section.setFluid(x,y,z,fluid,(byte)fluid.getMaxFluidLevel());
    }
    private static void set(Object object,String key,Object value) {
        try { var field=object.getClass().getDeclaredField(key);field.setAccessible(true);field.set(object,value); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void checkScratchCleared(Class<?> system, String... names) {
        try {
            var field=system.getDeclaredField("SCRATCH"); field.setAccessible(true);
            var scratch=((ThreadLocal<?>)field.get(null)).get();
            for(var name:names) {
                var list=scratch.getClass().getDeclaredField(name); list.setAccessible(true);
                check(((List<?>)list.get(scratch)).isEmpty(),system.getSimpleName()+" releases entity references after each tick");
            }
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static PlayerRef owner(Store<EntityStore> store) {
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(10.5,120,-20),new Rotation3f()));
        holder.addComponent(HeadRotation.getComponentType(),new HeadRotation());
        holder.ensureComponent(com.hypixel.hytale.server.core.modules.entity.player.PlayerInput.getComponentType());
        holder.addComponent(com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker.getComponentType(),
            new com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var ref=store.addEntity(holder,AddReason.SPAWN);
        var connection=(ChannelConnection)java.lang.reflect.Proxy.newProxyInstance(ChannelConnection.class.getClassLoader(),
            new Class<?>[]{ChannelConnection.class},(proxy,method,args)->switch(method.getName()) {
                case "isActive","isWritable" -> true;
                case "equals","isFromSameOrigin" -> args!=null && args[0]==proxy;
                case "hashCode" -> System.identityHashCode(proxy);
                case "remoteAddress" -> new java.net.InetSocketAddress("127.0.0.1",0);
                case "formatRemoteAddress","toString" -> "titan-water-smoke";
                case "execute" -> { ((Runnable)args[0]).run(); yield null; }
                default -> null;
            });
        var packets=new GenericPacketHandler(connection,new ProtocolVersion(0)) {
            @Override public String getIdentifier() { return "Titan water smoke"; }
            @Override public boolean writePacket(ToClientPacket packet,boolean cache) { return true; }
            @Override public void write(ToClientPacket... packets) { }
            @Override public void write(ToClientPacket[] packets,ToClientPacket last) { }
        };
        var player=new PlayerRef(holder,UUID.randomUUID(),"TitanWaterSmoke","en-US",packets,new ChunkTracker());
        store.addComponent(ref,PlayerRef.getComponentType(),player); player.addedToStore(ref);
        store.getExternalData().getWorld().trackPlayerRef(player);
        return player;
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
