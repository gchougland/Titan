package com.hexvane.titan.crypt;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.builtin.audio.systems.ForcedMusicSystems;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.CameraKeyframe;
import com.hypixel.hytale.protocol.CameraSequenceFlags;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.packets.camera.PlayCameraSequence;
import com.hypixel.hytale.protocol.packets.interface_.UpdateBossBar;
import com.hypixel.hytale.protocol.packets.interface_.ServerMessage;
import com.hypixel.hytale.protocol.packets.world.UpdateForcedMusic;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.modules.camera.CameraSequencePacketHandler;
import com.hypixel.hytale.server.core.modules.camera.CameraSequenceSource;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.joml.Vector3d;

/** Real world roster and native outbound protocol, without external sockets or a client avatar. */
public final class CryptMultiplayerRuntimeSmoke {
    private CryptMultiplayerRuntimeSmoke() { }
    private record Track(double seconds,byte flags,int frames) { }
    private record Member(Ref<EntityStore> ref,PlayerRef player,Capture packets) { }

    private static final class Capture extends GenericPacketHandler implements IPacketHandler {
        private PlayerRef player;
        final List<PlayCameraSequence> cameras=new ArrayList<>();
        final List<UpdateBossBar> bars=new ArrayList<>();
        final List<Integer> music=new ArrayList<>();
        final List<Track> tracks=new ArrayList<>();
        final List<String> messages=new ArrayList<>();
        Capture() {
            super(sink(),new ProtocolVersion(0));
            var camera=new CameraSequencePacketHandler(this) {
                @Override public void begin(CameraSequenceSource source) {
                    var frames=new CameraKeyframe[source.keyframeCount()];
                    source.writeKeyframes(0,frames.length,frames);
                    double seconds=0;
                    for(var frame:frames) { check(frame.duration>0,"positive camera keyframe duration");seconds+=frame.duration; }
                    tracks.add(new Track(seconds,source.flags(),frames.length));
                    super.begin(source); // Capture the actual native PlayCameraSequence packet too.
                }
            };
            registerSubPacketHandler(camera);camera.registerHandlers();
        }
        @Override public PlayerRef getPlayerRef() { return player; }
        @Override public String getIdentifier() { return "Crypt multiplayer smoke"; }
        @Override public boolean writePacket(ToClientPacket packet,boolean cache) {
            if(packet instanceof PlayCameraSequence camera) cameras.add(new PlayCameraSequence(camera));
            if(packet instanceof UpdateBossBar bar) bars.add(new UpdateBossBar(bar));
            if(packet instanceof UpdateForcedMusic song) music.add(song.containerIndex);
            if(packet instanceof ServerMessage message && message.message!=null) messages.add(message.message.rawText);
            return true;
        }
        @Override public void write(ToClientPacket... packets) { for(var packet:packets) writePacket(packet,false); }
        @Override public void write(ToClientPacket[] packets,ToClientPacket finalPacket) { write(packets);writePacket(finalPacket,false); }
        Track track() {
            check(!tracks.isEmpty() && tracks.size()==cameras.size(),"native camera source and emitted packet agree");
            var track=tracks.getLast();var packet=cameras.getLast();
            check(packet.flags==track.flags && packet.totalKeyframes==track.frames,"native camera packet preserves timeline flags/count");
            return track;
        }
    }

    /** Call before the existing single-player/headless fight, while this site is still uncleared. */
    public static void run(World world,Ref<EntityStore> site,CryptArena arena) throws Exception {
        CompletableFuture.runAsync(()->exercise(world,site,arena),world).get(10,TimeUnit.SECONDS);
        System.out.println("[CRYPT RUNTIME SMOKE] real multiplayer roster / shared root / 2-to-3 scaling / late intro+death / camera+music+bar packets / defeated coffin feedback / all corner retention / partial death / all-player wipe PASS");
    }

    private static void exercise(World world,Ref<EntityStore> site,CryptArena arena) {
        var store=world.getEntityStore().getStore();
        var members=new ArrayList<Member>();
        Ref<EntityStore> root=null;
        CryptBossComponent boss=null;
        Throwable primary=null;
        try {
            var one=create(store,arena.point(-12,1,30));members.add(one);
            var two=create(store,arena.point(12,1,30));members.add(two);
            var outside=create(store,arena.entrance());members.add(outside);
            var spectator=create(store,arena.point(0,1,30));members.add(spectator);
            store.addComponent(spectator.ref,Spectating.getComponentType(),new Spectating());
            check(world.getPlayerRefs().containsAll(List.of(one.player,two.player,outside.player,spectator.player)),"fixture registered real world membership");
            check(CryptEncounter.living(store,arena).size()==2,"room roster excludes outside player and living spectator");
            var feedbackSite=store.getComponent(site,CryptSiteComponent.getComponentType());
            int feedbackMessages=one.packets.messages.size();
            feedbackSite.setVictoryPending(true);
            try {
                CryptSiteSystem.activate(store,site,one.ref);
                check(one.packets.messages.size()==feedbackMessages+1
                    && "The coffin lies empty.".equals(one.packets.messages.getLast()),"defeated coffin sends the exact requested message");
                check(roots(store).isEmpty() && !feedbackSite.isActive(),"defeated coffin feedback cannot start an encounter");
            } finally { feedbackSite.setVictoryPending(false); }
            check(CryptSiteSystem.canStart(store,site,arena),"read-only coffin feedback did not persist a test victory");
            check(CryptEncounter.start(store,site,arena,one.ref),"two-player coffin encounter starts");
            store.getComponent(site,CryptSiteComponent.getComponentType()).setActive(true);
            CryptSiteSystem.activate(store,site,two.ref);
            check(!CryptEncounter.start(store,site,arena,two.ref),"second participant cannot directly spawn a duplicate boss");
            var roots=roots(store);check(roots.size()==1,"all players share exactly one encounter root");
            root=roots.getFirst();boss=store.getComponent(root,CryptBossComponent.getComponentType());
            float config=TitanConfig.get().getWeakpointHealthMultiplier();
            close(2600*config*1.55,boss.fight.maxCrown,"initial two-player crown scaling");
            close(340*config*1.55,boss.fight.maxBracelet,"initial two-player bracelet scaling");
            tick(store,root,boss,0);sendMusic(store);
            cinematic(one,13.71);cinematic(two,13.71);
            guarded(store,one,outside);guarded(store,two,outside);
            check(outside.packets.cameras.isEmpty() && outside.packets.bars.isEmpty() && outside.packets.music.isEmpty(),"outsider receives no encounter presentation");
            check(spectator.packets.cameras.isEmpty() && spectator.packets.bars.isEmpty() && spectator.packets.music.isEmpty(),"spectator receives no encounter presentation");
            shown(one,boss.networkId);shown(two,boss.networkId);
            checkMusic(store,one,true);checkMusic(store,two,true);

            advance(boss.fight,6);
            var three=create(store,arena.point(22,1,33));members.add(three);
            boss.uiTime=0;tick(store,root,boss,0);sendMusic(store);
            cinematic(three,7.71);
            guarded(store,three,outside);
            check(one.packets.cameras.size()==1 && two.packets.cameras.size()==1,"late entry does not restart existing players' introduction");
            close(2600*config*2.1,boss.fight.maxCrown,"third concurrent player raises crown maximum");
            close(340*config*2.1,boss.fight.maxBracelet,"third concurrent player raises bracelet maximum");
            var rootStats=store.getComponent(root,EntityStatMap.getComponentType());
            close(boss.fight.maxCrown,rootStats.get(DefaultEntityStatTypes.getHealth()).getMax(),"shared root entity stat maximum scales with party");
            close(boss.fight.crown(),rootStats.get(DefaultEntityStatTypes.getHealth()).get(),"shared root current health follows authoritative pool");
            check(roots(store).size()==1 && CryptEncounter.living(store,arena).size()==3,"third player joins the same boss");
            shown(three,boss.networkId);checkMusic(store,three,true);

            advance(boss.fight,8.1);boss.uiTime=0;tick(store,root,boss,0);sendMusic(store);
            unlocked(one);unlocked(two);unlocked(three);
            check(boss.fight.hit(0,boss.fight.maxCrown*.2f,42,1),"multiplayer crown damage accepted");
            boss.fight.hit(1,boss.fight.maxBracelet,42,2);
            float crown=boss.fight.crown(),max=boss.fight.maxCrown;
            health(store,one,0);boss.uiTime=0;tick(store,root,boss,.25f);sendMusic(store);
            check(root.isValid() && !boss.finishing && CryptEncounter.living(store,arena).size()==2,"one fallen player cannot wipe living teammates");
            close(max,boss.fight.maxCrown,"losing one player never lowers encounter health maximum");
            close(crown,boss.fight.crown(),"losing one player never heals or damages the crown");
            check(boss.fight.bracelet(0)==0,"broken bracelet stays broken when a player falls");
            checkMusic(store,one,false);hidden(one,boss.networkId);

            // Exercise death presentation without committing victory for the main smoke's reusable site.
            // Skip only DYING's persistence entry; run the actual per-player cinematic synchronization.
            health(store,one,100);move(store,three,arena.entrance());
            boss.uiTime=0;tick(store,root,boss,0);sendMusic(store);
            var ongoing=boss.fight;
            var death=new CryptFight(904,config,3);advance(death,14.1);death.hit(0,death.maxCrown,77,1);
            boss.fight=death;boss.revision=death.revision();boss.cinematics.clear();
            boss.uiTime=0;tick(store,root,boss,0);sendMusic(store);
            cinematic(one,9.51);cinematic(two,9.51);
            guarded(store,one,outside);guarded(store,two,outside);
            int oneDeathCount=one.packets.cameras.size(),twoDeathCount=two.packets.cameras.size();
            advance(death,4);move(store,three,arena.point(22,1,33));
            boss.uiTime=0;tick(store,root,boss,0);sendMusic(store);
            cinematic(three,5.51);
            guarded(store,three,outside);
            check(one.packets.cameras.size()==oneDeathCount && two.packets.cameras.size()==twoDeathCount,
                "late entry does not restart existing players' death tracks");
            move(store,two,arena.entrance());boss.uiTime=0;tick(store,root,boss,0);sendMusic(store);
            unlocked(two);checkMusic(store,two,false);hidden(two,boss.networkId);
            check(outside.packets.cameras.isEmpty() && outside.packets.bars.isEmpty(),"outside player never receives introduction or death camera");

            boss.fight=ongoing;boss.revision=-1;boss.uiTime=0;tick(store,root,boss,0);sendMusic(store);
            // Retain one living participant beyond the previous room limits for longer than
            // the two-second empty-room timeout. The outsider and spectator remain alive.
            health(store,one,100);health(store,two,0);health(store,three,0);
            int[][] roomCorners={{-65,9,-40},{-65,5,36},{4,18,-40},{4,18,36},
                {-66,19,-34},{-59,19,-41},{-59,19,37},{7,19,-7}};
            for(var corner:roomCorners) {
                move(store,one,arena.sourcePoint(corner[0]+.5,corner[1],corner[2]+.5));
                check(CryptEncounter.living(store,arena).equals(List.of(one.ref)),"only the corner participant counts as living");
                for(int frame=0;frame<10;frame++) {
                    health(store,one,100); // This check isolates room membership from attack balance.
                    tick(store,root,boss,.25f);
                    check(root.isValid() && !boss.finishing && boss.emptyTime==0,
                        "sole player in authored corner/alcove cannot trigger a wipe");
                }
            }
            for(var member:List.of(one,two,three)) health(store,member,0);
            boss.emptyTime=0;
            for(int i=0;i<9 && root.isValid();i++) tick(store,root,boss,.25f);
            sendMusic(store);
            check(!root.isValid(),"all dead encounter players trigger despawn even with a living outsider and spectator");
            var state=store.getComponent(site,CryptSiteComponent.getComponentType());
            check(!state.isActive() && !state.isCleared() && !state.isRewardDeposited() && !state.isVictoryPending(),"multiplayer wipe leaves coffin reset without victory or reward");
            for(var member:List.of(one,two,three)) { unlocked(member);checkMusic(store,member,false);hidden(member,boss.networkId); }
            check(boss.cinematics.isEmpty() && boss.viewers.isEmpty() && boss.parts.isEmpty(),"wipe clears all shared presentation and body ownership");
            check(CryptCinematic.play(store,one.ref,arena,false),"transfer fixture owns an active locked camera");
            cinematic(one,13.71);
            CryptBossBars.show(one.player,boss.networkId,Message.raw("Crypt transfer fixture").getFormattedMessage());
            int beforeTransfer=one.packets.bars.size();
            CryptMusic.apply(store,one.ref);sendMusic(store);
            store.removeEntity(one.ref,RemoveReason.UNLOAD);
            check(!one.ref.isValid() && one.player.getReference()==null,"transferred player's old entity ref is invalid");
            unlocked(one);
            check(one.packets.bars.size()==beforeTransfer+1 && one.packets.bars.getLast().entityNetworkId==boss.networkId
                && one.packets.bars.getLast().hide,"actual holder removal hides only its owned Crypt boss bar");
            check(one.packets.music.getLast()==0,"actual holder removal sends stopped music");
            int twoBars=two.packets.bars.size(),outsideBars=outside.packets.bars.size(),outsideCameras=outside.packets.cameras.size();
            store.removeEntity(two.ref,RemoveReason.UNLOAD);store.removeEntity(outside.ref,RemoveReason.UNLOAD);
            check(two.packets.bars.size()==twoBars,"removing a normally cleared bar owner sends no duplicate hide");
            check(outside.packets.bars.size()==outsideBars && outside.packets.cameras.size()==outsideCameras,"removing an unowned outsider sends no unrelated bar or camera cleanup");
        } catch(RuntimeException | Error failure) {
            primary=failure;
            throw failure;
        } finally {
            var failures=new ArrayList<Throwable>();
            try { if(root!=null && root.isValid()) {
                // A failed assertion must not leave a test encounter affecting the next checks.
                if(boss!=null) { boss.finishing=true;CryptEncounter.dispose(store,boss); }
                store.removeEntity(root,RemoveReason.REMOVE);
                CryptSiteSystem.reset(store,site);
            } } catch(RuntimeException | Error cleanup) { failures.add(cleanup); }
            for(var member:members) try {
                if(member.ref.isValid()) store.removeEntity(member.ref,RemoveReason.REMOVE);
            } catch(RuntimeException | Error cleanup) { failures.add(cleanup);world.untrackPlayerRef(member.player); }
            for(var member:members) try {
                check(world.getPlayerRefs().stream().noneMatch(p->p.getUuid().equals(member.player.getUuid())),"engine removal untracks every test player UUID from world");
            } catch(RuntimeException | Error cleanup) { failures.add(cleanup);world.untrackPlayerRef(member.player); }
            if(!failures.isEmpty()) {
                var cleanup=new AssertionError("Multiplayer fixture cleanup failed");failures.forEach(cleanup::addSuppressed);
                if(primary!=null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    private static Member create(Store<EntityStore> store,Vector3d position) {
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(position,new Rotation3f()));
        holder.addComponent(HeadRotation.getComponentType(),new HeadRotation(new Rotation3f()));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-.4,0,-.4,.4,1.8,.4)));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(ForcedMusicTracker.getComponentType(),new ForcedMusicTracker());
        holder.ensureComponent(PlayerInput.getComponentType()); // Native player removal unconditionally clears input.
        var stats=holder.ensureAndGetComponent(EntityStatMap.getComponentType());stats.update();TitanPartBuilder.applyHealth(stats,100);
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var actor=store.addEntity(holder,AddReason.SPAWN);check(actor!=null,"player roster fixture created");
        var packets=new Capture();
        var player=new PlayerRef(holder,UUID.randomUUID(),"CryptSmoke","en-US",packets,new ChunkTracker());
        packets.player=player;
        // Full client-login HolderSystems require an avatar. Use the same public binding APIs as
        // PlayerRefAddedSystem after attaching the component to this minimal network endpoint.
        store.addComponent(actor,PlayerRef.getComponentType(),player);
        player.addedToStore(actor);
        store.getExternalData().getWorld().trackPlayerRef(player);
        check(actor.equals(player.getReference()),"fixture binds a real player actor reference");
        return new Member(actor,player,packets);
    }
    private static void tick(Store<EntityStore> store,Ref<EntityStore> root,CryptBossComponent boss,float seconds) {
        if(!root.isValid()) return;
        store.forEachChunk(CryptBossComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getReferenceTo(i))) new CryptEncounter.Tick().tick(seconds,i,chunk,store,cb);
        });
    }
    private static void sendMusic(Store<EntityStore> store) {
        var system=new ForcedMusicSystems.Tick(Player.getComponentType(),PlayerRef.getComponentType(),ForcedMusicTracker.getComponentType());
        // Its production tick reads exactly these two components; real client avatars additionally have Player.
        store.forEachChunk(Archetype.of(PlayerRef.getComponentType(),ForcedMusicTracker.getComponentType()),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) system.tick(0,i,chunk,store,cb);
        });
    }
    private static List<Ref<EntityStore>> roots(Store<EntityStore> store) {
        var found=new ArrayList<Ref<EntityStore>>();
        store.forEachChunk(CryptBossComponent.getComponentType(),(chunk,cb)->{for(int i=0;i<chunk.size();i++) found.add(chunk.getReferenceTo(i));});
        return found;
    }
    private static void advance(CryptFight fight,double seconds) { for(double t=0;t<seconds;t+=.1) fight.tick(Math.min(.1,seconds-t)); }
    private static void health(Store<EntityStore> store,Member m,float value) { store.getComponent(m.ref,EntityStatMap.getComponentType()).setStatValue(DefaultEntityStatTypes.getHealth(),value); }
    private static void move(Store<EntityStore> store,Member m,Vector3d p) { store.getComponent(m.ref,TransformComponent.getComponentType()).getPosition().set(p); }
    private static void guarded(Store<EntityStore> store,Member victim,Member attacker) {
        float before=store.getComponent(victim.ref,EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get();
        var damage=new Damage(new Damage.EntitySource(attacker.ref),DamageCause.getAssetMap().getIndex("Physical"),50);
        DamageSystems.executeDamage(victim.ref,store,damage);
        check(damage.isCancelled(),"actual damage pipeline protects every cinematic participant");
        close(before,store.getComponent(victim.ref,EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get(),"locked cinematic player takes no damage");
    }
    private static void cinematic(Member m,double duration) {
        var track=m.packets.track();
        check((track.flags&CameraSequenceFlags.LockInput)!=0 && (track.flags&CameraSequenceFlags.ReturnToGameplayCameraOnEnd)!=0,"shared cinematic locks and returns control");
        close(duration,track.seconds,"late-join cinematic has only remaining timeline");
    }
    private static void unlocked(Member m) {
        var track=m.packets.track();
        check((track.flags&CameraSequenceFlags.LockInput)==0 && (track.flags&CameraSequenceFlags.ReturnToGameplayCameraOnEnd)!=0 && track.seconds<=.1,
            "leaving/finished/wiped player receives an immediate gameplay-camera release");
    }
    private static void shown(Member m,int root) { check(m.packets.bars.stream().anyMatch(p->p.entityNetworkId==root&&!p.hide),"player receives shared boss bar"); }
    private static void hidden(Member m,int root) { check(m.packets.bars.stream().anyMatch(p->p.entityNetworkId==root&&p.hide),"player's shared boss bar is removed"); }
    private static void checkMusic(Store<EntityStore> store,Member m,boolean playing) {
        int current=store.getComponent(m.ref,ForcedMusicTracker.getComponentType()).getCurrentContainerIndex();
        check((current>0)==playing && !m.packets.music.isEmpty() && m.packets.music.getLast()==current,"actual forced-music packet matches encounter ownership");
    }
    private static ChannelConnection sink() {
        return (ChannelConnection)Proxy.newProxyInstance(ChannelConnection.class.getClassLoader(),new Class<?>[]{ChannelConnection.class},(proxy,method,args)->switch(method.getName()) {
            case "isActive","isWritable" -> true;
            case "isFromSameOrigin","equals" -> args!=null && args[0]==proxy;
            case "hashCode" -> System.identityHashCode(proxy);
            case "remoteAddress" -> new InetSocketAddress("127.0.0.1",0);
            case "formatRemoteAddress","toString" -> "crypt-smoke-loopback";
            case "execute" -> { ((Runnable)args[0]).run();yield null; }
            case "setupAuxiliaryChannels" -> CompletableFuture.completedFuture(null);
            default -> null;
        });
    }
    private static void close(double expected,double actual,String message) { check(Math.abs(expected-actual)<.03,message+": expected "+expected+", got "+actual); }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
