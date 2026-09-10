package com.hexvane.titan.crypt;

import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.InteractionSimulationHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.joml.Vector3d;

/** Call from a smoke worker after boot, with a loaded open position. Uses real inventory/input chains. */
public final class CryptStaffInputSmoke {
    private CryptStaffInputSmoke() { }

    private static final class Input extends InteractionSimulationHandler {
        float heldSeconds;
        @Override public boolean isCharging(boolean firstRun,float time,InteractionType type,InteractionContext context,
                                            Ref<EntityStore> ref,CooldownHandler cooldown) {
            heldSeconds=Math.max(heldSeconds,time);
            return super.isCharging(firstRun,time,type,context,ref,cooldown);
        }
    }
    private record Cast(Ref<EntityStore> actor,Input input,InteractionChain chain) { }

    public static void run(World world,Vector3d position) throws Exception {
        var store=world.getEntityStore().getStore();
        Cast full=create(world,position,10);
        try {
            until(world,()->full.input().heldSeconds>=.8f,"Primary charges through real interaction manager");
            on(world,()->{
                check(missiles(world,full.actor()).isEmpty(),"holding a full charge does not fire before release");
                check(stamina(world,full.actor())==10,"holding does not spend stamina");
                check(store.getComponent(full.actor(),EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getMana()).getMax()==0,
                    "input fixture retains base zero-maximum mana");
                full.input().setState(InteractionType.Primary,false);
                return true;
            });
            until(world,()->!missiles(world,full.actor()).isEmpty(),"charged Primary release reaches CryptMissiles");
            on(world,()->{
                check(missiles(world,full.actor()).size()==5,"equipped Primary chain launches exactly five missiles");
                check(stamina(world,full.actor())==5,"release spends exactly five stamina once");
                return true;
            });
            until(world,()->full.chain().getServerState()!=InteractionState.NotFinished,"charged primary chain completes");
        } finally { clean(world,full); }

        Cast tap=create(world,position,10);
        try {
            until(world,()->tap.input().heldSeconds>=.1f,"early tap reaches charge interaction");
            on(world,()->{tap.input().setState(InteractionType.Primary,false);return true;});
            until(world,()->tap.chain().getServerState()!=InteractionState.NotFinished,"early release completes");
            on(world,()->{
                check(missiles(world,tap.actor()).isEmpty(),"early release launches no missiles");
                check(stamina(world,tap.actor())==10,"early release spends no stamina");
                return true;
            });
        } finally { clean(world,tap); }

        Cast tired=create(world,position,0);
        try {
            until(world,()->tired.chain().getServerState()!=InteractionState.NotFinished,"exhausted primary resolves failure branch");
            on(world,()->{
                check(tired.input().heldSeconds==0,"exhausted user never enters the charge");
                check(missiles(world,tired.actor()).isEmpty(),"exhausted primary launches nothing");
                return true;
            });
        } finally { clean(world,tired); }
        System.out.println("[CRYPT RUNTIME SMOKE] equipped Primary / native charge / held input / released volley / exact stamina / early cancel / exhausted gate PASS");
    }

    private static Cast create(World world,Vector3d position,float stamina) throws Exception {
        return on(world,()->{
            var store=world.getEntityStore().getStore();
            var holder=EntityStore.REGISTRY.newHolder();
            var rotation=new Rotation3f();
            holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(position),rotation));
            holder.addComponent(HeadRotation.getComponentType(),new HeadRotation(rotation));
            holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(new Box(-.4,0,-.4,.4,1.8,.4)));
            holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(DamageDataComponent.getComponentType());
            var inventory=new SimpleItemContainer((short)1);
            inventory.setItemStackForSlot((short)0,new ItemStack(CryptStaff.ITEM,1));
            holder.addComponent(InventoryComponent.Hotbar.getComponentType(),new InventoryComponent.Hotbar(inventory,(byte)0));
            var stats=holder.ensureAndGetComponent(EntityStatMap.getComponentType());
            stats.update();TitanPartBuilder.applyHealth(stats,100);
            stats.setStatValue(DefaultEntityStatTypes.getStamina(),stamina);
            stats.setStatValue(EntityStatType.getAssetMap().getIndex("StaminaRegenDelay"),-10);
            var input=new Input();input.setState(InteractionType.Primary,true);
            var manager=new InteractionManager(null,input);
            holder.addComponent(InteractionModule.get().getInteractionManagerComponent(),manager);
            holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
            var actor=store.addEntity(holder,AddReason.SPAWN);
            check(actor!=null,"input actor spawned");
            var context=InteractionContext.forInteraction(manager,actor,InteractionType.Primary,store);
            check(context.getHeldItem()!=null && CryptStaff.ITEM.equals(context.getHeldItem().getItemId()),"Primary selected equipped reward item");
            String rootId=context.getRootInteractionId(InteractionType.Primary);
            check("Root_Crypt_Missiles".equals(rootId),"Primary resolves item's actual root interaction");
            var root=RootInteraction.getAssetMap().getAsset(rootId);
            check(root!=null,"equipped primary root is loaded");
            var chain=manager.initChain(InteractionType.Primary,context,root,false);
            manager.queueExecuteChain(chain);
            return new Cast(actor,input,chain);
        });
    }

    private static List<Ref<EntityStore>> missiles(World world,Ref<EntityStore> owner) {
        var refs=new ArrayList<Ref<EntityStore>>();
        world.getEntityStore().getStore().forEachChunk(CryptStaff.spellType,(chunk,ignored)->{
            for(int i=0;i<chunk.size();i++) {
                var spell=chunk.getComponent(i,CryptStaff.spellType);
                if(spell.kind==CryptSpellComponent.Kind.MISSILE && owner.equals(spell.owner)) refs.add(chunk.getReferenceTo(i));
            }
        });
        return refs;
    }
    private static float stamina(World world,Ref<EntityStore> actor) {
        check(actor.isValid(),"input actor remains alive during interaction execution");
        return world.getEntityStore().getStore().getComponent(actor,EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getStamina()).get();
    }
    private static void clean(World world,Cast cast) throws Exception {
        on(world,()->{
            var store=world.getEntityStore().getStore();
            for(var ref:missiles(world,cast.actor())) if(ref.isValid()) store.removeEntity(ref,RemoveReason.REMOVE);
            if(cast.actor().isValid()) store.removeEntity(cast.actor(),RemoveReason.REMOVE);
            return true;
        });
    }
    private static <T> T on(World world,Supplier<T> work) throws Exception {
        return CompletableFuture.supplyAsync(work,world).get(5,TimeUnit.SECONDS);
    }
    private static void until(World world,Supplier<Boolean> condition,String operation) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!on(world,condition)) {
            if(System.nanoTime()>deadline) throw new AssertionError("Timed out: "+operation);
            Thread.sleep(20);
        }
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
