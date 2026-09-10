package com.hexvane.titan.crypt;

import com.hexvane.titan.combat.TitanImpulse;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import org.joml.Vector3d;

/** A dodgeable response to sustained close-range bracelet attacks, never a random unavoidable grab. */
final class CryptGrab {
    record Pressure(double first, double last, int hits, int side) { }
    static final double CATCH_TIME=.3, THROW_TIME=1.5;
    static final double THROW_SPEED=30, THROW_LIFT=9;
    private CryptGrab() { }

    static boolean ready(Pressure p,double now) {
        return p.hits()>=6 && p.last()-p.first()>=3 && now-p.last()<=4;
    }

    static void noteHit(ComponentAccessor<EntityStore> a,CryptBossComponent b,Ref<EntityStore> attacker,int side) {
        var tx=a.getComponent(attacker,TransformComponent.getComponentType());
        if(tx==null || tx.getPosition().distanceSquared(b.rig.palm(side))>13*13) return;
        var old=b.braceletPressure.get(attacker);
        var next=old==null || b.elapsed-old.last()>2.5 ? new Pressure(b.elapsed,b.elapsed,1,side)
            : new Pressure(old.first(),b.elapsed,old.hits()+1,side);
        b.braceletPressure.put(attacker,next);
    }

    static void consider(ComponentAccessor<EntityStore> a,CryptBossComponent b,List<Ref<EntityStore>> players) {
        b.braceletPressure.entrySet().removeIf(e->!e.getKey().isValid() || b.elapsed-e.getValue().last()>6);
        if(b.fight.state()!=CryptFight.State.REST || b.fight.time()<1.1 || b.elapsed<b.grabCooldownUntil) return;
        for(var player:players) {
            var pressure=b.braceletPressure.get(player);
            if(pressure==null || !ready(pressure,b.elapsed)) continue;
            var tx=a.getComponent(player,TransformComponent.getComponentType());
            if(tx==null || tx.getPosition().distanceSquared(b.rig.palm(pressure.side()))>13*13) continue;
            if(!b.fight.requestGrab()) return;
            b.attackTarget=player; b.grabSide=pressure.side();
            b.grabCooldownUntil=b.elapsed+24; b.braceletPressure.clear();
            return;
        }
    }

    static Vector3d returnPoint(CryptArena arena,Vector3d position) {
        var local=CryptRig.local(arena,position);
        double x=Math.clamp(local.x,-29,29), z=Math.clamp(local.z,18,46);
        if(Math.abs(x)<7 && Math.abs(z-29)<7) x=x<0 ? -10 : 10;
        return arena.point(x,.35,z);
    }

    static void tick(CommandBuffer<EntityStore> cb,Ref<EntityStore> root,CryptBossComponent b,
                     List<Ref<EntityStore>> players,boolean fx) {
        double active=b.fight.time()-b.fight.windup();
        if(active<CATCH_TIME) return;
        if(!b.grabAttempted) {
            b.grabAttempted=true;
            var victim=b.attackTarget;
            if(victim!=null && players.contains(victim) && victim.isValid()) {
                var tx=cb.getComponent(victim,TransformComponent.getComponentType());
                if(tx!=null && CryptEncounter.near(tx.getPosition(),b.target,CryptAttackGeometry.GRAB_RADIUS,5)) {
                    b.grabbed=victim; b.grabReturn.set(returnPoint(b.arena,tx.getPosition()));
                    CryptEncounter.hit(cb,root,victim,12,"Physical");
                    CryptFx.burst(cb,CryptFx.GRAB_CATCH,b.rig.palm(b.grabSide),1);
                    CryptFx.sound(cb,"SFX_Crypt_Bracelet_Regen",b.rig.palm(b.grabSide));
                }
            }
        }
        if(b.grabbed==null) return;
        if(!b.grabbed.isValid() || !players.contains(b.grabbed)) { abort(cb,b); return; }
        var tx=cb.getComponent(b.grabbed,TransformComponent.getComponentType());
        if(tx==null) { b.grabbed=null; return; }
        if(active<THROW_TIME) {
            if(b.elapsed>=b.grabPinTime) {
                b.grabPinTime=b.elapsed+.08;
                cb.putComponent(b.grabbed,Teleport.getComponentType(),new Teleport(grip(b),tx.getRotation()));
            }
        } else if(!b.grabThrown) {
            b.grabThrown=true;
            var victim=b.grabbed; b.grabbed=null;
            // Throw down an open side aisle, past the podium into the far half of the room.
            Vector3d at=grip(b), destination=b.arena.point(b.grabSide==0 ? -14 : 14,0,44);
            Vector3d velocity=new Vector3d(destination).sub(at); velocity.y=0;
            if(velocity.lengthSquared()<1) velocity.set(b.arena.point(0,0,1)).sub(b.arena.point(0,0,0));
            velocity.normalize(THROW_SPEED); velocity.y=THROW_LIFT;
            cb.tryRemoveComponent(victim,Teleport.getComponentType());
            TitanImpulse.set(cb,victim,velocity);
            CryptEncounter.hit(cb,root,victim,20,"Physical");
            CryptFx.directed(cb,CryptFx.GRAB_TOSS,at,destination,1);
            CryptFx.sound(cb,"SFX_Crypt_Sweep",at);
        }
    }

    static Vector3d grip(CryptBossComponent b) { return b.rig.palm(b.grabSide).add(0,2,0); }

    static void abort(CommandBuffer<EntityStore> cb,CryptBossComponent b) {
        var victim=b.grabbed; b.grabbed=null;
        if(victim==null || !victim.isValid()) return;
        var tx=cb.getComponent(victim,TransformComponent.getComponentType());
        if(tx!=null) cb.putComponent(victim,Teleport.getComponentType(),new Teleport(b.grabReturn,tx.getRotation()));
    }
    static void abort(Store<EntityStore> store,CryptBossComponent b) {
        var victim=b.grabbed; b.grabbed=null;
        if(victim==null || !victim.isValid()) return;
        var tx=store.getComponent(victim,TransformComponent.getComponentType());
        if(tx!=null) store.putComponent(victim,Teleport.getComponentType(),new Teleport(b.grabReturn,tx.getRotation()));
    }
}
