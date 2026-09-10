package com.hexvane.titan.crypt;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.joml.Vector3d;

/** Bounded engine check; call from the smoke worker, not the world thread. No real player is required. */
public final class CryptGrabRuntimeSmoke {
    private CryptGrabRuntimeSmoke() { }

    public static void run(World world,CryptBossComponent original,Ref<EntityStore> root,
                           Ref<EntityStore> attacker) throws Exception {
        CompletableFuture.runAsync(()->exercise(world.getEntityStore().getStore(),original,root,attacker),world)
            .get(10,TimeUnit.SECONDS);
        System.out.println("[CRYPT RUNTIME SMOKE] bracelet pressure / reactive grab / catch / lift / one toss / bystander safety / dodge / cooldown / stun release PASS");
    }

    private static void exercise(Store<EntityStore> store,CryptBossComponent original,Ref<EntityStore> root,
                                 Ref<EntityStore> attacker) {
        var actorTx=store.getComponent(attacker,TransformComponent.getComponentType());
        var rootTx=store.getComponent(root,TransformComponent.getComponentType());
        Vector3d actorPosition=new Vector3d(actorTx.getPosition()),rootPosition=new Vector3d(rootTx.getPosition());
        float rootHealth=health(store,root);
        var probe=new CryptBossComponent();
        probe.arena=original.arena;probe.site=original.site;probe.networkId=original.networkId;
        probe.rig=new CryptRig(probe.arena);probe.emptyTime=-1000;
        probe.braceletRepairPending=true; // The check must not rebuild/remove the actual fight's parts.
        var bystanderHolder=EntityStore.REGISTRY.newHolder();
        bystanderHolder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(actorPosition),new Rotation3f()));
        bystanderHolder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-.4,0,-.4,.4,1.8,.4)));
        var bystanderStats=bystanderHolder.ensureAndGetComponent(EntityStatMap.getComponentType());
        bystanderStats.update();TitanPartBuilder.applyHealth(bystanderStats,100);
        bystanderHolder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var bystander=store.addEntity(bystanderHolder,AddReason.SPAWN);
        check(bystander!=null,"grab bystander fixture spawned");
        try {
            // Swap only inside this one queued task; normal world ticking never observes the probe.
            store.putComponent(root,CryptBossComponent.getComponentType(),probe);
            prepare(store,probe,attacker,0);
            for(int i=0;i<5;i++) { probe.elapsed=100+i*.65;CryptGrab.noteHit(store,probe,attacker,0); }
            CryptGrab.consider(store,probe,List.of(attacker));
            check(probe.fight.state()==CryptFight.State.REST,"five bracelet hits do not trigger a grab");
            probe.elapsed=103.25;CryptGrab.noteHit(store,probe,attacker,0);
            CryptGrab.consider(store,probe,List.of(attacker));
            check(probe.fight.move()==CryptFight.Move.GRAB && probe.attackTarget.equals(attacker),"sustained close bracelet pressure selects its attacker");
            check(probe.grabSide==0 && probe.braceletPressure.isEmpty() && probe.grabCooldownUntil==127.25,"grab records side and 24-second cooldown");
            probe.target.set(actorTx.getPosition());
            var bystanderTx=store.getComponent(bystander,TransformComponent.getComponentType());
            bystanderTx.getPosition().set(probe.target).add(1,0,0); // Inside catch radius, but never selected.
            Vector3d bystanderPosition=new Vector3d(bystanderTx.getPosition());
            var players=List.of(attacker,bystander);
            pose(probe,probe.fight.windup()+.29);
            grabTick(store,root,probe,players);
            check(probe.grabbed==null && health(store,attacker)==100,"grab telegraph causes no early catch or damage");
            pose(probe,probe.fight.windup()+.31);
            grabTick(store,root,probe,players);
            check(attacker.equals(probe.grabbed),"target inside locked catch area is grabbed");
            check(actorTx.getPosition().distance(CryptGrab.grip(probe))<.001,"real Teleport system moves victim into the hand");
            float multiplier=TitanConfig.get().getAttackDamageMultiplier();
            close(100-12*multiplier,health(store,attacker),"catch deals its damage once");
            double catchHeight=actorTx.getPosition().y;
            pose(probe,probe.fight.windup()+1.16);
            grabTick(store,root,probe,players);
            check(actorTx.getPosition().distance(CryptGrab.grip(probe))<.001 && actorTx.getPosition().y>catchHeight+3,
                "held victim follows the raised hand");
            close(100-12*multiplier,health(store,attacker),"holding does not repeat catch damage");
            pose(probe,probe.fight.windup()+1.51);
            grabTick(store,root,probe,players);
            check(probe.grabbed==null && probe.grabThrown,"throw releases the victim reference");
            var knockback=store.getComponent(attacker,KnockbackComponent.getComponentType());
            check(knockback!=null && knockback.getVelocityType()==ChangeVelocityType.Set && knockback.getVelocity().isFinite(),"throw creates an explicit velocity impulse");
            double horizontal=Math.hypot(knockback.getVelocity().x,knockback.getVelocity().z);
            close(11/Math.max(1,DamageSystems.HackKnockbackValues.PLAYER_KNOCKBACK_SCALE),horizontal,"throw horizontal speed accounts for player engine scaling");
            close(5,knockback.getVelocity().y,"throw lifts the victim into an arc");
            close(100-32*multiplier,health(store,attacker),"throw deals one additional damage hit");
            pose(probe,probe.fight.windup()+1.8);grabTick(store,root,probe,players);
            close(100-32*multiplier,health(store,attacker),"throw cannot fire twice");
            check(store.getComponent(attacker,Teleport.getComponentType())==null,"throw leaves no teleport pin");
            check(health(store,bystander)==100 && bystanderTx.getPosition().equals(bystanderPosition) &&
                store.getComponent(bystander,KnockbackComponent.getComponentType())==null,"nearby unselected player is neither hurt nor pushed");

            probe.fight=readyFight();probe.rig.sample(probe);
            actorTx.getPosition().set(probe.rig.palm(0));
            probe.braceletPressure.put(attacker,new CryptGrab.Pressure(probe.elapsed-3.25,probe.elapsed,6,0));
            CryptGrab.consider(store,probe,List.of(attacker));
            check(probe.fight.state()==CryptFight.State.REST,"retaliation cannot repeat during cooldown");

            prepare(store,probe,attacker,1);
            check(probe.fight.requestGrab(),"dodge scenario starts a telegraphed grab");
            actorTx.getPosition().set(probe.arena.point(-24,1,40));
            pose(probe,probe.fight.windup()+.31);grabTick(store,root,probe,List.of(attacker));
            check(probe.grabAttempted && probe.grabbed==null && health(store,attacker)==100,"moving beyond the catch area dodges without damage");
            check(store.getComponent(attacker,KnockbackComponent.getComponentType())==null,"a dodged grab does not throw the target");

            prepare(store,probe,attacker,0);
            check(probe.fight.requestGrab(),"stun-interruption scenario starts grab");
            pose(probe,probe.fight.windup()+.31);grabTick(store,root,probe,List.of(attacker));
            check(attacker.equals(probe.grabbed),"stun-interruption fixture was actually caught");
            Vector3d safeReturn=new Vector3d(probe.grabReturn);
            probe.fight.hit(1,10000,42,1);probe.fight.hit(2,10000,42,2);
            check(probe.fight.state()==CryptFight.State.FALLING,"bracelet breaks interrupt grab with a collapse");
            // Execute production encounter dispatch, including its enter-state abort, through a real buffer.
            store.forEachChunk(CryptBossComponent.getComponentType(),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getReferenceTo(i)))
                    new CryptEncounter.Tick().tick(0,i,chunk,store,cb);
            });
            check(probe.grabbed==null && !probe.grabThrown,"stun releases victim without a throw");
            check(actorTx.getPosition().distance(safeReturn)<.001 && probe.arena.contains(safeReturn),"stun returns victim to safe room floor");
            check(store.getComponent(attacker,KnockbackComponent.getComponentType())==null,"stun interruption adds no throw impulse");
            close(100-12*multiplier,health(store,attacker),"stun interruption adds no throw damage");
        } finally {
            store.putComponent(root,CryptBossComponent.getComponentType(),original);
            rootTx.getPosition().set(rootPosition);setHealth(store,root,rootHealth);
            if(attacker.isValid()) {
                store.tryRemoveComponent(attacker,Teleport.getComponentType());
                store.tryRemoveComponent(attacker,KnockbackComponent.getComponentType());
                actorTx.getPosition().set(actorPosition);setHealth(store,attacker,100);
            }
            if(bystander.isValid()) store.removeEntity(bystander,RemoveReason.REMOVE);
        }
    }

    private static CryptFight readyFight() {
        var f=new CryptFight(905,1);
        while(f.state()==CryptFight.State.INTRO) f.tick(.05);
        for(int i=0;i<24;i++) f.tick(.05);
        return f;
    }
    private static void prepare(Store<EntityStore> store,CryptBossComponent b,Ref<EntityStore> attacker,int side) {
        b.fight=readyFight();b.rig=new CryptRig(b.arena);b.elapsed=100;b.revision=-1;
        b.grabSide=side;b.grabbed=null;b.grabAttempted=false;b.grabThrown=false;b.grabPinTime=0;b.grabCooldownUntil=0;
        b.attackTarget=attacker;b.braceletPressure.clear();b.rig.sample(b);
        var tx=store.getComponent(attacker,TransformComponent.getComponentType());
        tx.getPosition().set(b.rig.palm(side));b.target.set(tx.getPosition());
        store.tryRemoveComponent(attacker,Teleport.getComponentType());
        store.tryRemoveComponent(attacker,KnockbackComponent.getComponentType());
        setHealth(store,attacker,100);
    }
    private static void pose(CryptBossComponent b,double time) {
        while(b.fight.time()<time-1e-7) { double step=Math.min(.05,time-b.fight.time());b.fight.tick(step);b.elapsed+=step; }
        b.rig.sample(b);
    }
    private static void grabTick(Store<EntityStore> store,Ref<EntityStore> root,CryptBossComponent b,List<Ref<EntityStore>> players) {
        buffered(store,root,cb->CryptGrab.tick(cb,root,b,players,false));
    }
    private static void buffered(Store<EntityStore> store,Ref<EntityStore> root,Consumer<CommandBuffer<EntityStore>> work) {
        store.forEachChunk(CryptBossComponent.getComponentType(),(chunk,cb)->{
            for(int i=0;i<chunk.size();i++) if(root.equals(chunk.getReferenceTo(i))) work.accept(cb);
        });
    }
    private static float health(Store<EntityStore> store,Ref<EntityStore> ref) {
        return store.getComponent(ref,EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get();
    }
    private static void setHealth(Store<EntityStore> store,Ref<EntityStore> ref,float value) {
        store.getComponent(ref,EntityStatMap.getComponentType()).setStatValue(DefaultEntityStatTypes.getHealth(),value);
    }
    private static void close(double expected,double actual,String message) { check(Math.abs(expected-actual)<.001,message+": expected "+expected+", got "+actual); }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
