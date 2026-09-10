package com.hexvane.titan.crypt;

import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Real ordinary NPC, released volley spawner, model assets and spell ticks; no synthetic weakpoint. */
public final class CryptMissileRuntimeSmoke {
    private CryptMissileRuntimeSmoke() { }
    private record Fixture(Ref<EntityStore> owner,Ref<EntityStore> npc) { }

    /** Needs a loaded empty lane spanning x +/-3, y 0..4, z 0..16 from this position. */
    public static void run(World world,Vector3d position) throws Exception {
        var fixture=on(world,()->create(world,position));
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!on(world,()->TargetUtil.getAllEntitiesInSphere(position,20,world.getEntityStore().getStore()).contains(fixture.npc))) {
                if(System.nanoTime()>deadline) throw new AssertionError("ordinary NPC entered engine spatial index");
                Thread.sleep(10);
            }
            on(world,()->{ exercise(world,position,fixture);return true; });
        } finally {
            on(world,()->{
                var store=world.getEntityStore().getStore();clear(store,fixture.owner);
                if(fixture.owner.isValid()) store.removeEntity(fixture.owner,RemoveReason.REMOVE);
                if(fixture.npc.isValid()) store.removeEntity(fixture.npc,RemoveReason.REMOVE);
                return true;
            });
        }
        System.out.println("[CRYPT RUNTIME SMOKE] visible emerald models / ordinary hostile NPC / touching+1-3+9-15 blocks / strafe+slow tick / thin wall / owner immunity PASS");
    }

    private static Fixture create(World world,Vector3d position) {
        var store=world.getEntityStore().getStore();
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(position,new Rotation3f()));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-.4,0,-.4,.4,1.8,.4)));
        var stats=holder.ensureAndGetComponent(EntityStatMap.getComponentType());stats.update();TitanPartBuilder.applyHealth(stats,1000);
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var owner=store.addEntity(holder,AddReason.SPAWN);
        var npc=NPCPlugin.get().spawnNPC(store,"Skeleton_Soldier",null,new Vector3d(position).add(0,0,3),new Rotation3f());
        check(owner!=null && npc!=null,"ordinary skeleton range fixture spawned");
        return new Fixture(owner,npc.first());
    }

    private static void exercise(World world,Vector3d origin,Fixture fixture) {
        var store=world.getEntityStore().getStore();
        // NPC attitude providers use the actual Player component to classify their target. Attach it
        // only for this queued task: unrelated full-avatar systems never tick this minimal spell owner.
        store.addComponent(fixture.owner,Player.getComponentType(),new Player());
        try {
            check(CryptSpellSystem.enemy(store,fixture.owner,fixture.npc),"native Skeleton_Soldier attitude is hostile to spell owner");
            check(!CryptSpellSystem.enemy(store,fixture.owner,fixture.owner),"spell cannot target its owner");
            check(ModelAsset.getAssetMap().getAsset(CryptStaff.MISSILE_MODEL)!=null,"emerald soul model asset resolved");
            for(double distance:new double[]{.55,1,2,3,9,15})
                fire(store,origin,fixture,distance,.05f,false,false);
            fire(store,origin,fixture,3,.25f,true,false);
            fire(store,origin,fixture,9,.8f,true,false);

            var wall=new ArrayList<Vector3i>();
            try {
                for(int x=-2;x<=2;x++) for(int y=0;y<4;y++) {
                    var p=new Vector3i((int)Math.floor(origin.x)+x,(int)Math.floor(origin.y)+y,(int)Math.floor(origin.z)+2);
                    check(GroundSampler.blockId(world.getChunkStore(),p.x,p.y,p.z)==0,"wall fixture starts in empty lane");
                    block(world,p,"Rock_Basalt");wall.add(p);
                    check(GroundSampler.isSolid(world.getChunkStore(),p.x,p.y,p.z),"written wall is solid in the spell's actual section lookup: "+p);
                }
                fire(store,origin,fixture,4,.25f,false,true);
            } finally { for(var p:wall) block(world,p,"Empty"); }
        } finally {
            clear(store,fixture.owner);
            store.removeComponent(fixture.owner,Player.getComponentType());
        }
    }

    private static void fire(Store<EntityStore> store,Vector3d origin,Fixture f,double distance,float dt,boolean strafe,boolean blocked) {
        clear(store,f.owner);
        var npcStats=store.getComponent(f.npc,EntityStatMap.getComponentType());TitanPartBuilder.applyHealth(npcStats,1000);
        // The real skeleton retains its native max-health modifier (61 - baseHealth) in addition
        // to the test's durability modifier. Measure this fully healed baseline, not an assumed 1000.
        var npcHealth=npcStats.get(DefaultEntityStatTypes.getHealth());
        double before=npcHealth.get();
        check(before==npcHealth.getMax() && before>200,"ordinary NPC starts fully healed: current="+before+", max="+npcHealth.getMax());
        var npcTransform=store.getComponent(f.npc,TransformComponent.getComponentType());
        npcTransform.setPosition(new Vector3d(origin).add(0,0,distance));
        var eye=new Vector3d(origin).add(0,1.6,0);
        if(blocked) {
            var box=store.getComponent(f.npc,BoundingBox.getComponentType()).getBoundingBox();
            check(npcTransform.getPosition().z+box.min.z>Math.floor(origin.z)+3,"NPC body lies completely behind the wall; position="+npcTransform.getPosition()+", box="+box);
            check(!CryptSpellSystem.visible(store,eye,CryptSpellSystem.center(store,f.npc)),"same ray test used by target acquisition sees the blocking wall");
        }
        CryptStaff.launchMissiles(store,f.owner,eye,new Vector3d(0,0,1));
        var missiles=missiles(store,f.owner);check(missiles.size()==5,"actual released volley contains five souls");
        for(var ref:missiles) {
            check(store.getComponent(ref,ModelComponent.getComponentType())!=null && store.getComponent(ref,PersistentModel.getComponentType())!=null
                && store.getComponent(ref,NetworkId.getComponentType())!=null,"each soul has a networked visible model");
        }
        for(double t=0;t<6.1 && missiles.stream().anyMatch(Ref::isValid);t+=dt) {
            if(strafe) npcTransform.setPosition(new Vector3d(origin).add(Math.sin(t*2)*.8,0,distance));
            store.forEachChunk(CryptStaff.spellType,(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) {
                    var spell=chunk.getComponent(i,CryptStaff.spellType);
                    if(spell.kind==CryptSpellComponent.Kind.MISSILE && f.owner.equals(spell.owner))
                        new CryptSpellSystem().tick(dt,i,chunk,store,cb);
                }
            });
        }
        double health=npcStats.get(DefaultEntityStatTypes.getHealth()).get();
        if(blocked) check(health==before,"no soul damages a hostile NPC behind a one-block wall; before="+before+", after="+health+", origin="+origin);
        else check(before-health>=45,"at least three souls hit ordinary NPC at range "+distance+", dt="+dt+"; before="+before+", after="+health);
        check(missiles.stream().noneMatch(Ref::isValid),"range-test missiles impact or expire cleanly");
        check(store.getComponent(f.owner,EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get()==1000,"owner remains unharmed by overlapping souls");
    }

    private static List<Ref<EntityStore>> missiles(Store<EntityStore> store,Ref<EntityStore> owner) {
        var result=new ArrayList<Ref<EntityStore>>();
        store.forEachChunk(CryptStaff.spellType,(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) {var spell=chunk.getComponent(i,CryptStaff.spellType);
                if(spell.kind==CryptSpellComponent.Kind.MISSILE && owner.equals(spell.owner)) result.add(chunk.getReferenceTo(i));}
        });return result;
    }
    private static void clear(Store<EntityStore> store,Ref<EntityStore> owner) {
        for(var ref:missiles(store,owner)) if(ref.isValid()) store.removeEntity(ref,RemoveReason.REMOVE);
    }
    private static void block(World world,Vector3i p,String id) {
        var chunk=world.getChunkStore().getChunkComponent(ChunkUtil.indexChunkFromBlock(p.x,p.z),WorldChunk.getComponentType());
        check(chunk!=null,"wall fixture chunk loaded");chunk.setBlock(p.x,p.y,p.z,id);
    }
    private static <T> T on(World world,Supplier<T> task) throws Exception { return CompletableFuture.supplyAsync(task,world).get(10,TimeUnit.SECONDS); }
    private static void check(boolean okay,String message) { if(!okay) throw new AssertionError(message); }
}
