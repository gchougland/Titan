package com.hexvane.titan.npc;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.entity.*;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hexvane.titan.spawn.TitanTrio;
import com.hexvane.titan.system.TitanAiSystem;
import com.hexvane.titan.system.TitanAnimationSystem;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.system.PlayerSpatialSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.PositionCache;
import com.hypixel.hytale.server.npc.systems.PositionCacheSystems;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.joml.Vector3d;

/** The real authored NPC instruction tree drives real titan AI and animation, without render voxels. */
public final class TitanCombatRuntimeSmoke {
    private TitanCombatRuntimeSmoke() { }
    private record Visitor(Ref<EntityStore> ref,PlayerRef player) { }
    private record Boss(Ref<EntityStore> root,Ref<EntityStore> brain,TitanComponent titan) { }
    private static final class Packets extends GenericPacketHandler {
        Packets() { super(sink(),new ProtocolVersion(0)); }
        @Override public String getIdentifier() { return "Titan combat smoke"; }
        @Override public boolean writePacket(ToClientPacket packet,boolean cache) { return true; }
        @Override public void write(ToClientPacket... packets) { }
        @Override public void write(ToClientPacket[] packets,ToClientPacket last) { }
    }

    /** Call from the smoke worker with loaded level terrain, before or after the Crypt encounter. */
    public static void run(World world,Vector3d ground) throws Exception {
        CompletableFuture.runAsync(()->exercise(world,ground),world).get(15,TimeUnit.SECONDS);
        System.out.println("[TITAN COMBAT SMOKE] native PlayerRef/spatial sensor / full role / wake preserved / Talus chase+attack / passive Temple retaliation+stomp / disabled HURL PASS");
    }
    private static void exercise(World world,Vector3d ground) {
        var store=world.getEntityStore().getStore();
        var visitor=visitor(store,new Vector3d(ground).add(0,0,-4));
        try {
            check(world.getPlayerRefs().contains(visitor.player),"real PlayerRef world membership");
            var talus=boss(store,"Stone_Talus_Copper",ground);
            try {
                check(talus.titan.getState()==TitanState.SLEEPING,"Talus begins sleeping");
                // A naturally sleeping root has already received its animation pass before the
                // Encounter swaps the brain role. This fixture starts directly in the combat role.
                animate(store,talus);
                check(!talus.titan.getAnimator().isFinished(),"sleeping loop is active before native wake request");
                role(store,talus);
                check(visitor.ref.equals(talus.titan.getTarget()),"full role reaches its player-target branch");
                check(talus.titan.getIntent()==TitanIntent.WAKE,"close melee candidate cannot overwrite initial wake");
                ai(store,talus);
                check(talus.titan.getState()==TitanState.WAKING,"executor consumes native role wake; state="+talus.titan.getState()+", clip="+talus.titan.getAnimator().getCurrentName());
                move(store,visitor,new Vector3d(ground).add(0,0,-10));
                for(int i=0;i<150 && talus.titan.getState()==TitanState.WAKING;i++) {
                    role(store,talus);
                    check(talus.titan.getIntent()==TitanIntent.NONE || talus.titan.getIntent()==TitanIntent.CHASE,"waking cannot queue attacks");
                    ai(store,talus);
                }
                check(talus.titan.getState()==TitanState.IDLE,"real waking clip finishes");
                var start=new Vector3d(store.getComponent(talus.root,TransformComponent.getComponentType()).getPosition());
                boolean chased=false;
                for(int i=0;i<100 && !attacking(talus.titan.getState());i++) {
                    role(store,talus);ai(store,talus);
                    chased|=talus.titan.getState()==TitanState.CHASE && talus.titan.getVelocity().lengthSquared()>.01;
                }
                check(chased && store.getComponent(talus.root,TransformComponent.getComponentType()).getPosition().distance(start)>.2,"Talus walks toward acquired player");
                check(attacking(talus.titan.getState()),"Talus enters an actual attack windup from full role");
            } finally { remove(store,talus); }

            move(store,visitor,new Vector3d(ground).add(0,0,-4));
            var temple=boss(store,"Roaming_Temple",ground);
            try {
                for(int i=0;i<3;i++) { role(store,temple);ai(store,temple); }
                check(temple.titan.getTarget()==null && !attacking(temple.titan.getState()),"unprovoked Temple stays passive");
                move(store,visitor,new Vector3d(ground).add(0,0,-30));
                temple.titan.reportAttacker(visitor.ref);ai(store,temple);
                var held=new Vector3d(store.getComponent(temple.root,TransformComponent.getComponentType()).getPosition());
                for(int i=0;i<5;i++) {
                    role(store,temple);
                    check(temple.titan.getIntent()!=TitanIntent.HURL,"shared role cannot queue Temple's disabled hurl");
                    ai(store,temple);
                }
                check(visitor.ref.equals(temple.titan.getTarget()) && temple.titan.getState()==TitanState.CHASE,"provoked Temple acquires attacker");
                check(store.getComponent(temple.root,TransformComponent.getComponentType()).getPosition().distance(held)<.01,"Temple holds ground per its authored no-chase behavior");
                move(store,visitor,new Vector3d(ground).add(0,0,-18));
                for(int i=0;i<5 && temple.titan.getState()!=TitanState.STOMP_WINDUP;i++) { role(store,temple);ai(store,temple); }
                check(temple.titan.getState()==TitanState.STOMP_WINDUP,"Temple native role chooses its enabled stomp");
                check(temple.titan.getStompFoot()>=0,"stomp commits an actual authored skeleton foot");
            } finally { remove(store,temple); }
        } finally {
            // Partial avatar exists only inside this task, so unrelated player systems never tick it.
            store.removeComponent(visitor.ref,Player.getComponentType());
            store.removeEntity(visitor.ref,RemoveReason.REMOVE);
            rebuildPlayers(store);
            check(world.getPlayerRefs().stream().noneMatch(p->p.getUuid().equals(visitor.player.getUuid())),"fixture PlayerRef fully untracked");
        }
    }

    private static Boss boss(Store<EntityStore> store,String id,Vector3d position) {
        var variant=TitanVariantAsset.find(id);check(variant!=null,"variant loaded: "+id);
        var skeleton=TitanSkeletonAsset.find(variant.getSkeleton());check(skeleton!=null,"skeleton loaded");
        var titan=new TitanComponent(variant,skeleton);titan.getHome().set(position);
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TitanComponent.getComponentType(),titan);
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(position,new Rotation3f()));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-1,0,-1,1,2,1)));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureAndGetComponent(EntityStatMap.getComponentType()).update();
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var root=store.addEntity(holder,AddReason.SPAWN);check(root!=null,"test root exists");
        // Zero socket tally intentionally disables missing-render-voxel auditing in this behavior fixture.
        var npc=NPCPlugin.get().spawnEntity(store,NPCPlugin.get().getIndex(TitanTrio.ROLE_COMBAT),new Vector3d(position),new Rotation3f(),null,
            (entity,h,s)->{h.addComponent(TitanBrainComponent.getComponentType(),new TitanBrainComponent(root));h.ensureComponent(Intangible.getComponentType());},null);
        check(npc!=null,"native combat brain builds");titan.setBrainDriven(true);titan.setBrainRef(npc.first());
        return new Boss(root,npc.first(),titan);
    }
    private static void role(Store<EntityStore> store,Boss boss) {
        var position=store.getComponent(boss.root,TransformComponent.getComponentType()).getPosition();
        store.getComponent(boss.brain,TransformComponent.getComponentType()).setPosition(position);
        rebuildPlayers(store);
        var update=new PositionCacheSystems.UpdateSystem(NPCPlugin.get().getNpcSpatialResource());
        store.forEachChunk(PositionCache.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(boss.brain.equals(chunk.getReferenceTo(i))) {
                chunk.getComponent(i,PositionCache.getComponentType()).getPlayers().clear();
                update.steppedTick(1,i,chunk,store,cb);
            }
        });
        store.getComponent(boss.brain,NPCEntity.getComponentType()).getRole().tick(boss.brain,.1f,store);
    }
    private static void ai(Store<EntityStore> store,Boss boss) {
        var ai=new TitanAiSystem(null);var animation=new TitanAnimationSystem();
        store.forEachChunk(TitanComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(boss.root.equals(chunk.getReferenceTo(i))) {
                ai.tick(.1f,i,chunk,store,cb);animation.tick(.1f,i,chunk,store,cb);
            }
        });
    }
    private static void animate(Store<EntityStore> store,Boss boss) {
        var animation=new TitanAnimationSystem();
        store.forEachChunk(TitanComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(boss.root.equals(chunk.getReferenceTo(i))) animation.tick(.1f,i,chunk,store,cb);
        });
    }
    private static void rebuildPlayers(Store<EntityStore> store) {
        var resource=store.getResource(EntityModule.get().getPlayerSpatialResourceType());
        var data=resource.getSpatialData();data.clear();
        store.forEachChunk(PlayerSpatialSystem.QUERY,(chunk,cb)->{
            data.addCapacity(chunk.size());for(int i=0;i<chunk.size();i++) data.append(chunk.getComponent(i,TransformComponent.getComponentType()).getPosition(),chunk.getReferenceTo(i));
        });resource.getSpatialStructure().rebuild(data);
    }
    private static Visitor visitor(Store<EntityStore> store,Vector3d position) {
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(position,new Rotation3f()));
        holder.addComponent(HeadRotation.getComponentType(),new HeadRotation());
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-.4,0,-.4,.4,1.8,.4)));
        holder.addComponent(ForcedMusicTracker.getComponentType(),new ForcedMusicTracker());holder.ensureComponent(PlayerInput.getComponentType());
        var stats=holder.ensureAndGetComponent(EntityStatMap.getComponentType());stats.update();TitanPartBuilder.applyHealth(stats,1000);
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var ref=store.addEntity(holder,AddReason.SPAWN);check(ref!=null,"test player exists");
        var player=new PlayerRef(holder,UUID.randomUUID(),"TitanCombatSmoke","en-US",new Packets(),new ChunkTracker());
        store.addComponent(ref,PlayerRef.getComponentType(),player);player.addedToStore(ref);
        store.getExternalData().getWorld().trackPlayerRef(player);store.addComponent(ref,Player.getComponentType(),new Player());
        return new Visitor(ref,player);
    }
    private static void move(Store<EntityStore> store,Visitor v,Vector3d point) { store.getComponent(v.ref,TransformComponent.getComponentType()).setPosition(point); }
    private static void remove(Store<EntityStore> store,Boss b) {
        b.titan.setBrainDriven(false);b.titan.setBrainRef(null);
        if(b.brain.isValid()) store.removeEntity(b.brain,RemoveReason.REMOVE);
        if(b.root.isValid()) store.removeEntity(b.root,RemoveReason.REMOVE);
    }
    private static boolean attacking(TitanState state) { return state==TitanState.WINDUP || state==TitanState.SLAM_WINDUP || state==TitanState.POUND_WINDUP || state==TitanState.HURL_WINDUP || state==TitanState.STOMP_WINDUP; }
    private static ChannelConnection sink() {
        return (ChannelConnection)Proxy.newProxyInstance(ChannelConnection.class.getClassLoader(),new Class<?>[]{ChannelConnection.class},(proxy,method,args)->switch(method.getName()) {
            case "isActive","isWritable" -> true;
            case "equals","isFromSameOrigin" -> args!=null && args[0]==proxy;
            case "hashCode" -> System.identityHashCode(proxy);
            case "remoteAddress" -> new InetSocketAddress("127.0.0.1",0);
            case "formatRemoteAddress","toString" -> "titan-smoke-loopback";
            case "execute" -> {((Runnable)args[0]).run();yield null;}
            case "setupAuxiliaryChannels" -> CompletableFuture.completedFuture(null);
            default -> null;
        });
    }
    private static void check(boolean okay,String message) { if(!okay) throw new AssertionError(message); }
}
