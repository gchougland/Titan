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
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
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
        final java.util.List<ChangeVelocity> impulses = new java.util.ArrayList<>();
        Packets() { super(sink(),new ProtocolVersion(0)); }
        @Override public String getIdentifier() { return "Titan combat smoke"; }
        @Override public boolean writePacket(ToClientPacket packet,boolean cache) {
            if(packet instanceof ChangeVelocity velocity) impulses.add(velocity);
            return true;
        }
        @Override public void write(ToClientPacket... packets) { }
        @Override public void write(ToClientPacket[] packets,ToClientPacket last) { }
    }

    /** Call from the smoke worker with loaded level terrain, before or after the Crypt encounter. */
    public static void run(World world,Vector3d ground) throws Exception {
        var removedStones = new java.util.ArrayList<Ref<EntityStore>>();
        CompletableFuture.runAsync(()->exercise(world,ground,removedStones),world).get(15,TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> check(!removedStones.isEmpty() && removedStones.stream().noneMatch(Ref::isValid),
            "deferred root-removal cleanup removes every falling stone"), world).get(5,TimeUnit.SECONDS);
        System.out.println("[TITAN COMBAT SMOKE] native PlayerRef/spatial sensor / full role / wake preserved / Talus chase+attack / passive Temple retaliation+stomp / disabled HURL PASS");
    }
    private static void exercise(World world,Vector3d ground,java.util.List<Ref<EntityStore>> removedStones) {
        var store=world.getEntityStore().getStore();
        var visitor=visitor(store,new Vector3d(ground).add(0,0,-4));
        try {
            com.hexvane.titan.combat.TitanApiRuntimeSmoke.run(store,visitor.ref,
                ((Packets)visitor.player.getPacketHandler()).impulses,ground);
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

            testTempleCores(store, visitor, ground, removedStones);
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
                for(int i=0;i<5 && !temple.titan.getState().isAttacking();i++) { role(store,temple);ai(store,temple); }
                check(temple.titan.getState()==TitanState.STOMP_WINDUP || temple.titan.getState()==TitanState.BOULDER_FORMATION,"Temple native role chooses an enabled move");
                if(temple.titan.getState()==TitanState.STOMP_WINDUP) check(temple.titan.getStompFoot()>=0,"stomp commits an actual authored skeleton foot");
            } finally { remove(store,temple); }
        } finally {
            // Partial avatar exists only inside this task, so unrelated player systems never tick it.
            store.removeComponent(visitor.ref,Player.getComponentType());
            store.removeEntity(visitor.ref,RemoveReason.REMOVE);
            rebuildPlayers(store);
            check(world.getPlayerRefs().stream().noneMatch(p->p.getUuid().equals(visitor.player.getUuid())),"fixture PlayerRef fully untracked");
        }
    }

    private static void testTempleCores(Store<EntityStore> store, Visitor visitor, Vector3d position, java.util.List<Ref<EntityStore>> removedStones) {
        var result = com.hexvane.titan.spawn.TitanSpawner.spawn(store, "Roaming_Temple", new Vector3d(position), .6f,
            com.hexvane.titan.spawn.ColliderMode.DEFAULT, 73, false);
        check(result.ok(), "full Temple prefab spawns: " + result.error());
        var root = result.root();
        var titan = store.getComponent(root, TitanComponent.getComponentType());
        try {
            check(titan.getSolidCores().stream().map(c -> c.bone()).distinct().count() == 12,
                "all four thighs, calves and feet have solid cores");
            check(titan.getSolidCores().size() == 20, "thigh cores follow all three steps in the taper");
            for (var core : titan.getSolidCores()) {
                var trapped = titan.getPose().getWorld(core.bone()).transformPosition(new Vector3d(core.center()));
                move(store, visitor, trapped);
                store.forEachChunk(TitanComponent.getComponentType(), (chunk, cb) -> {
                    for (int i = 0; i < chunk.size(); i++) if (root.equals(chunk.getReferenceTo(i)))
                        com.hexvane.titan.combat.TitanCoreSafety.rescue(titan, cb);
                });
                var rescued = store.getComponent(visitor.ref, TransformComponent.getComponentType()).getPosition();
                check(rescued.distance(trapped) > .1, "real Teleport releases trapped player from limb " + core.bone());
                for (var other : titan.getSolidCores())
                    check(!com.hexvane.titan.combat.TitanCoreSafety.overlaps(rescued, .4, 1.8,
                        titan.getPose().getWorld(other.bone()), other), "escape is outside every Temple limb");
                check(Math.abs(rescued.y - position.y - .15) < .01, "escape lands on nearby ground, not another limb");
                var settled = new Vector3d(rescued);
                store.forEachChunk(TitanComponent.getComponentType(), (chunk, cb) -> {
                    for (int i = 0; i < chunk.size(); i++) if (root.equals(chunk.getReferenceTo(i)))
                        com.hexvane.titan.combat.TitanCoreSafety.rescue(titan, cb);
                });
                check(settled.equals(store.getComponent(visitor.ref, TransformComponent.getComponentType()).getPosition()),
                    "safe player is not moved again on the following recovery pass");
            }
            testTempleRollingBoulders(store, visitor, root, titan, position);
            testTempleRain(store, visitor, root, titan, position);
            withTemple(store, root, cb -> check(com.hexvane.titan.ai.TitanRollingBoulders.begin(store, cb, titan, titan.getVariant()), "unload fixture leaves a forming boulder volley"));
            System.out.println("[Titan limb smoke] all twelve limb sections / twenty tapered cores / rotated-limb recovery / clear ground outside all limbs / no repeat teleport PASS");
        } finally {
            var falling = new java.util.ArrayList<>(titan.stalactites.stones.stream().map(stone -> stone.entity).toList());
            falling.addAll(titan.rollingBoulders.rocks.stream().map(rock -> rock.entity).toList());
            TitanTrio.detach(store, titan);
            if (root.isValid()) store.removeEntity(root, RemoveReason.REMOVE);
            removedStones.addAll(falling);
        }
    }
    private static void testTempleRollingBoulders(Store<EntityStore> store, Visitor visitor, Ref<EntityStore> root,
                                                   TitanComponent titan, Vector3d ground) {
        var partner = visitor(store, new Vector3d(ground).add(7,0,-28));
        try {
            move(store, visitor, new Vector3d(ground).add(-7,0,-28));
            rebuildPlayers(store); titan.setTarget(visitor.ref);
            withTemple(store,root,cb -> check(com.hexvane.titan.ai.TitanRollingBoulders.begin(store,cb,titan,titan.getVariant()),"Temple starts rolling-boulder formation"));
            var rocks = new java.util.ArrayList<>(titan.rollingBoulders.rocks);
            check(rocks.size()==2 && !rocks.get(0).target.equals(rocks.get(1).target),"two boulders distribute across encounter players");
            for(var rock:rocks) {
                check(store.getComponent(rock.entity,ModelComponent.getComponentType()).getModel().getAnimationSetMap().containsKey("Form_0"), "animated model retains assembly clips");
                check("Form_0".equals(store.getComponent(rock.entity,ActiveAnimationComponent.getComponentType()).getActiveAnimations()[com.hypixel.hytale.protocol.AnimationSlot.Movement.ordinal()]), "spawn carries animation for newly visible players");
            }
            float before=health(store,visitor.ref);
            for(var rock:rocks) check(rock.entity.isValid() && store.getComponent(rock.entity,ModelComponent.getComponentType())!=null,"assembly uses a real visible model");
            rollingTick(store,root,titan,1);
            check(rocks.stream().allMatch(rock -> rock.stage==com.hexvane.titan.ai.TitanRollingBoulders.Stage.FORMING),"stones assemble before release");
            check(health(store,visitor.ref)==before,"formation cannot damage players");
            check("Form_5".equals(store.getComponent(rocks.getFirst().entity,ActiveAnimationComponent.getComponentType()).getActiveAnimations()[com.hypixel.hytale.protocol.AnimationSlot.Movement.ordinal()]), "late viewer gets remaining assembly, not a full restart");
            for(int i=0;i<45 && rocks.stream().anyMatch(rock -> rock.stage!=com.hexvane.titan.ai.TitanRollingBoulders.Stage.ROLLING);i++) rollingTick(store,root,titan,.1f);
            check(rocks.stream().allMatch(rock -> rock.stage==com.hexvane.titan.ai.TitanRollingBoulders.Stage.ROLLING),"assembled stones fall and begin rolling on real terrain");
            for(var rock:rocks) check("Complete".equals(store.getComponent(rock.entity,ActiveAnimationComponent.getComponentType()).getActiveAnimations()[com.hypixel.hytale.protocol.AnimationSlot.Movement.ordinal()]), "released boulders stay assembled for new viewers");
            var first=rocks.getFirst();double speed=first.speed;double heading=first.heading;var start=new Vector3d(first.position);
            // Continue the hazard while the boss is in another move, as the real AI does each tick.
            titan.setState(TitanState.STOMP_WINDUP);
            rollingTick(store,root,titan,.4f);
            check(first.position.distance(start)>1 && first.speed>speed,"boulder accelerates and keeps moving during another boss attack");
            check(Math.abs(Math.atan2(Math.sin(first.heading-heading),Math.cos(first.heading-heading)))<=.72*.4+.001,"homing respects maximum turning speed");
            withTemple(store,root,cb -> com.hexvane.titan.ai.TitanRollingBoulders.clear(titan,cb));
            check(rocks.stream().noneMatch(rock -> rock.entity.isValid()),"explicit cancellation removes both models");

            // A slow frame must still hit a player crossed between its beginning and end.
            titan.setState(TitanState.IDLE);move(store,visitor,new Vector3d(ground).add(0,0,-5));
            move(store,partner,new Vector3d(ground).add(25,0,-25));rebuildPlayers(store);
            withTemple(store,root,cb -> check(com.hexvane.titan.ai.TitanRollingBoulders.begin(store,cb,titan,titan.getVariant()),"collision volley spawns"));
            var hit=titan.rollingBoulders.rocks.getFirst();var spare=titan.rollingBoulders.rocks.getLast();
            withTemple(store,root,cb -> cb.removeEntity(spare.entity,RemoveReason.REMOVE));
            hit.stage=com.hexvane.titan.ai.TitanRollingBoulders.Stage.ROLLING;hit.age=4;hit.position.set(ground).add(0,1.65,0);hit.heading=-Math.PI/2;hit.speed=13.5;hit.target=visitor.ref;
            float damageBefore=health(store,visitor.ref);rollingTick(store,root,titan,.8f);
            float expected=titan.getVariant().getRollingBoulderDamage()*com.hexvane.titan.config.TitanConfig.get().getAttackDamageMultiplier()*titan.getLevelDamageMultiplier();
            check(Math.abs(damageBefore-health(store,visitor.ref)-expected)<.02,"swept boulder hits once with level/config scaled damage");
            rollingTick(store,root,titan,.8f);check(Math.abs(damageBefore-health(store,visitor.ref)-expected)<.02,"shattered stone cannot damage twice");

            // Baiting a boulder into a leg safely breaks it without damaging the player or crystals.
            move(store,visitor,new Vector3d(ground).add(0,0,-28));rebuildPlayers(store);titan.setState(TitanState.IDLE);
            withTemple(store,root,cb -> check(com.hexvane.titan.ai.TitanRollingBoulders.begin(store,cb,titan,titan.getVariant()),"leg collision volley spawns"));
            var leg=titan.getSolidCores().getFirst();var legPoint=titan.getPose().getWorld(leg.bone()).transformPosition(new Vector3d(leg.center()));
            check(com.hexvane.titan.ai.TitanRollingBoulders.hitsLeg(titan,legPoint),"rotated Temple leg is a real boulder obstacle");
            for(var rock:titan.rollingBoulders.rocks) {rock.stage=com.hexvane.titan.ai.TitanRollingBoulders.Stage.ROLLING;rock.position.set(legPoint.x,ground.y+1.65,legPoint.z);rock.age=4;}
            rollingTick(store,root,titan,.1f);check(titan.rollingBoulders.rocks.isEmpty(),"rolling into a leg shatters the stones");

            testBoulderWall(store,visitor,root,titan,ground);

            // An exhausted hazard expires rather than pursuing forever.
            titan.setState(TitanState.IDLE);
            withTemple(store,root,cb -> check(com.hexvane.titan.ai.TitanRollingBoulders.begin(store,cb,titan,titan.getVariant()),"expiry volley spawns"));
            for(var rock:titan.rollingBoulders.rocks)rock.age=30;
            rollingTick(store,root,titan,.1f);check(titan.rollingBoulders.rocks.isEmpty(),"boulders have a finite lifetime");
            titan.setState(TitanState.IDLE);
            System.out.println("[TEMPLE BOULDER SMOKE] real assembly models / party targets / fall / terrain rolling / acceleration / bounded homing / attack overlap / slow-frame hit / one damage / leg and wall counterplay / expiry PASS");
        } finally {
            withTemple(store,root,cb -> com.hexvane.titan.ai.TitanRollingBoulders.clear(titan,cb));
            store.removeComponent(partner.ref,Player.getComponentType());store.removeEntity(partner.ref,RemoveReason.REMOVE);rebuildPlayers(store);
        }
    }
    private static void testBoulderWall(Store<EntityStore> store,Visitor visitor,Ref<EntityStore> root,TitanComponent titan,Vector3d ground) {
        var chunks=store.getExternalData().getWorld().getChunkStore();
        var saved=new java.util.ArrayList<int[]>();
        int stone=com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getIndex("Rock_Basalt");
        try {
            for(int dx=-3;dx<=3;dx++) for(int dy=0;dy<5;dy++) {
                int x=(int)ground.x+dx,y=(int)ground.y+dy,z=(int)ground.z-4;
                var section=chunks.getChunkSectionReferenceAtBlock(x,y,z);
                var blocks=chunks.getStore().getComponent(section,com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
                saved.add(new int[]{x,y,z,blocks.get(x,y,z),blocks.getRotationIndex(x,y,z),blocks.getFiller(x,y,z)});
                setBoulderTestBlock(chunks,x,y,z,stone,0,0);
            }
            titan.setState(TitanState.IDLE);move(store,visitor,new Vector3d(ground).add(0,0,-8));rebuildPlayers(store);
            withTemple(store,root,cb -> check(com.hexvane.titan.ai.TitanRollingBoulders.begin(store,cb,titan,titan.getVariant()),"wall volley spawns"));
            float before=health(store,visitor.ref);
            for(var rock:titan.rollingBoulders.rocks) {
                rock.stage=com.hexvane.titan.ai.TitanRollingBoulders.Stage.ROLLING;rock.position.set(ground).add(0,1.65,0);
                rock.age=4;rock.heading=-Math.PI/2;rock.speed=13.5;rock.target=visitor.ref;
            }
            rollingTick(store,root,titan,1);
            check(titan.rollingBoulders.rocks.isEmpty() && health(store,visitor.ref)==before,"substeps shatter both rocks against a wall without hurting the player behind it");
        } finally {
            for(var b:saved)setBoulderTestBlock(chunks,b[0],b[1],b[2],b[3],b[4],b[5]);
            withTemple(store,root,cb -> com.hexvane.titan.ai.TitanRollingBoulders.clear(titan,cb));
        }
    }
    private static void setBoulderTestBlock(com.hypixel.hytale.server.core.universe.world.storage.ChunkStore chunks,int x,int y,int z,int id,int rotation,int filler) {
        com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations.setBlock(chunks,chunks.getChunkSectionReferenceAtBlock(x,y,z),x,y,z,id,
            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(id),rotation,filler,
            com.hypixel.hytale.server.core.universe.world.SetBlockSettings.NONE);
    }
    private static void rollingTick(Store<EntityStore> store,Ref<EntityStore> root,TitanComponent titan,float dt) {
        titan.addStateTime(dt);
        withTemple(store,root,cb -> com.hexvane.titan.ai.TitanRollingBoulders.tick(store,cb,root,titan,dt));
    }
    private static void testTempleRain(Store<EntityStore> store, Visitor visitor, Ref<EntityStore> root,
                                       TitanComponent titan, Vector3d ground) {
        var partner = visitor(store, new Vector3d(ground).add(8, 0, 0));
        try {
            move(store, visitor, new Vector3d(ground));
            titan.setTarget(visitor.ref);
            rebuildPlayers(store);
            var variant = titan.getVariant();
            final boolean[] started = {false};
            withTemple(store, root, cb -> started[0] = com.hexvane.titan.ai.TitanStalactiteAttack.begin(store, cb, titan, variant));
            check(started[0] && titan.getState() == TitanState.STALACTITES, "Temple starts its enabled stalactite attack");
            var stones = new java.util.ArrayList<>(titan.stalactites.stones);
            check(stones.size() >= 2 && stones.size() <= 18, "party gets a bounded stone volley");
            check(stones.stream().anyMatch(stone -> stone.landing.distance(ground) < .01), "one locked ring targets first player");
            check(stones.stream().anyMatch(stone -> stone.landing.distance(new Vector3d(ground).add(8,0,0)) < .01), "second player gets their own locked ring");
            for (var stone : stones) {
                check(stone.entity.isValid() && store.getComponent(stone.entity, ModelComponent.getComponentType()) != null, "falling spike has a visible model");
                check(stone.from.y > ground.y + 4 && stone.from.y < ground.y + 25, "stone originates below actual island surface");
                check(com.hexvane.titan.ai.TitanStalactiteAttack.position(stone, stone.release - .1f).equals(stone.from), "stone hangs throughout warning");
            }
            float before = health(store, visitor.ref), partnerBefore = health(store, partner.ref);
            for (int i=0; i<20; i++) rainTick(store, root, titan, .1f);
            animate(store, new Boss(root, null, titan));
            int body = titan.getSkeleton().indexOfBone("Body_Mesh");
            check(Math.abs(titan.getPose().getLocalTranslation(body).y - titan.getSkeleton().getBones()[body].getOffset().y) > .001,
                "rumble visibly moves the island during windup");
            check(health(store, visitor.ref) == before && health(store, partner.ref) == partnerBefore, "rumble and telegraph deal no early damage");
            move(store, partner, new Vector3d(ground).add(26,0,0));
            rebuildPlayers(store);
            for (int i=0; i<90 && titan.getState()==TitanState.STALACTITES; i++) rainTick(store, root, titan, .1f);
            float expected = variant.getStalactiteDamage() * com.hexvane.titan.config.TitanConfig.get().getAttackDamageMultiplier() * titan.getLevelDamageMultiplier();
            check(Math.abs(before - health(store, visitor.ref) - expected) < .02, "standing inside ring receives exactly one scaled impact");
            check(health(store, partner.ref) == partnerBefore, "leaving the locked ring dodges the attack");
            check(stones.stream().allMatch(stone -> stone.landed && stone.entity == null), "every impact removes its spike once");
            check(titan.getState()==TitanState.IDLE && titan.getAttackCooldown()>0, "rain recovers into ordinary cooldown");
            withTemple(store, root, cb -> check(!com.hexvane.titan.ai.TitanStalactiteAttack.tryBegin(store, cb, titan, variant), "rain cannot repeat back to back"));
            withTemple(store, root, cb -> check(com.hexvane.titan.ai.TitanStalactiteAttack.begin(store, cb, titan, variant), "rain can start a later volley"));
            var cancelled = titan.stalactites.stones.stream().map(stone -> stone.entity).toList();
            titan.setState(TitanState.DYING);
            var ai = new TitanAiSystem(null);
            store.forEachChunk(TitanComponent.getComponentType(), (chunk, cb) -> {
                for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getReferenceTo(i))) ai.tick(.1f,i,chunk,store,cb);
            });
            check(cancelled.stream().noneMatch(Ref::isValid) && titan.stalactites.stones.isEmpty(), "death cancels falling hazards");
            check(health(store, visitor.ref) == before - expected, "cancelled volley adds no damage");
            // Leave a live volley for the enclosing fixture's real root-removal cleanup check.
            titan.setState(TitanState.IDLE);
            withTemple(store, root, cb -> check(com.hexvane.titan.ai.TitanStalactiteAttack.begin(store, cb, titan, variant), "cleanup fixture has active stones"));
            System.out.println("[TEMPLE RAIN SMOKE] native models / actual underside / multiplayer targets / fixed warnings / one scaled hit / dodge / recovery / death cancellation PASS");
        } finally {
            store.removeComponent(partner.ref, Player.getComponentType());
            store.removeEntity(partner.ref, RemoveReason.REMOVE);
            rebuildPlayers(store);
        }
    }
    private static void withTemple(Store<EntityStore> store, Ref<EntityStore> root, java.util.function.Consumer<CommandBuffer<EntityStore>> action) {
        store.forEachChunk(TitanComponent.getComponentType(), (chunk, cb) -> {
            for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getReferenceTo(i))) action.accept(cb);
        });
    }
    private static void rainTick(Store<EntityStore> store, Ref<EntityStore> root, TitanComponent titan, float dt) {
        titan.addStateTime(dt);
        withTemple(store, root, cb -> com.hexvane.titan.ai.TitanStalactiteAttack.tick(store, cb, root, titan, titan.getVariant(), dt));
    }
    private static float health(Store<EntityStore> store, Ref<EntityStore> ref) {
        return store.getComponent(ref, EntityStatMap.getComponentType()).get(com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth()).get();
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
