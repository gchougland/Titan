package com.hexvane.titan.spawn;

import com.hexvane.titan.entity.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.collision.*;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionValidation;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;

public final class TitanInteractionRuntimeSmoke {
    public static void verifyYaga(Store<EntityStore> store, Ref<EntityStore> root) {
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(new Vector3d(),new Rotation3f()));
        var player=store.addEntity(holder,AddReason.SPAWN);
        // Only attached during this synchronous check, before any full-avatar systems can run.
        store.addComponent(player,Player.getComponentType(),new Player(){ @Override public com.hypixel.hytale.protocol.GameMode getGameMode() { return com.hypixel.hytale.protocol.GameMode.Adventure; }});
        int[] targets={0},visuals={0};
        store.ensureComponent(root,com.hexvane.titan.yaga.YagaComponent.getComponentType());
        var viewer=new com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.EntityViewer(128,null);
        var prompts=new com.hexvane.titan.yaga.YagaInteractionPromptSystem();
        try {
            var eye=store.getComponent(player,TransformComponent.getComponentType());
            store.forEachChunk(TitanPartComponent.getComponentType(),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) {
                    var part=chunk.getComponent(i,TitanPartComponent.getComponentType());
                    if(!root.equals(part.getOwner())) continue;
                    if(chunk.getComponent(i,Interactable.getComponentType())==null) continue;
                    var interactions=chunk.getComponent(i,Interactions.getComponentType());
                    require(interactions!=null && interactions.getInteractionHint()!=null,"nearby use target keeps its prompt");
                    var model=chunk.getComponent(i,ModelComponent.getComponentType());
                    if(part.isCombinedVisual()) {
                        var block=chunk.getComponent(i,com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType());
                        require(block!=null && model==null,"visible Yaga uses its original block entity interaction path");
                        var type=com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(block.getBlockTypeKey());
                        require(type.getCustomModel().startsWith("VFX/Titan/Parts/Blocks/"),"block entity draws actual combined artwork");
                        require(interactions.getInteractionHint().equals(type.getInteractionHint()),"native block asset and entity have the same prompt");
                        visuals[0]++;
                    }
                    String hint=interactions.getInteractionHint();
                    require(com.hypixel.hytale.server.core.modules.i18n.I18nModule.get().getMessage("en-US",hint)!=null,"use prompt translation exists: "+hint);
                    var ref=chunk.getReferenceTo(i);
                    var visible=chunk.getComponent(i,com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.Visible.getComponentType());
                    viewer.visible.add(ref);visible.visibleTo.put(player,viewer);visible.newlyVisibleTo.put(player,viewer);
                    try {
                        // SetInteractable supplies the Hint in this packet, not
                        // merely in InteractionsUpdate. Check the actual queue.
                        viewer.queueUpdate(ref,new com.hypixel.hytale.protocol.InteractableUpdate());
                        if(part.isCombinedVisual()) {
                            new com.hypixel.hytale.server.core.modules.entity.BlockEntitySystems.BlockEntityTrackerSystem(
                                com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.Visible.getComponentType(),
                                com.hypixel.hytale.server.core.entity.entities.BlockEntity.getComponentType()).tick(.05f,i,chunk,store,cb);
                            require(Arrays.stream(viewer.updates.get(ref).toUpdatesArray()).anyMatch(u->u instanceof com.hypixel.hytale.protocol.BlockUpdate),
                                "visible artwork is sent through native BlockUpdate");
                        }
                        prompts.tick(.05f,i,chunk,store,cb);
                        var updates=viewer.updates.get(ref).toUpdatesArray();
                        require(updates[updates.length-1] instanceof com.hypixel.hytale.protocol.InteractableUpdate prompt
                            && hint.equals(prompt.interactionHint),"final interactable packet contains on-screen text");
                        viewer.updates.clear();visible.newlyVisibleTo.clear();
                        prompts.tick(.05f,i,chunk,store,cb);
                        require(viewer.updates.isEmpty(),"unchanged prompts do not resend every tick");
                        visible.newlyVisibleTo.put(player,viewer);
                        prompts.tick(.05f,i,chunk,store,cb);
                        require(viewer.updates.containsKey(ref),"approaching again restores prompt");
                    } finally {visible.visibleTo.remove(player);visible.newlyVisibleTo.remove(player);viewer.visible.remove(ref);viewer.updates.clear();}
                    if(part.isCombinedVisual()) continue;
                    targets[0]++;
                    var pos=chunk.getComponent(i,TransformComponent.getComponentType()).getPosition();
                    var box=chunk.getComponent(i,BoundingBox.getComponentType()).getBoundingBox();
                    for(int axis=0;axis<3;axis++) for(int sign:new int[]{-1,1}) {
                        var at=new Vector3d(pos);
                        at.setComponent(axis,at.get(axis)+(sign<0?box.min.get(axis)-3:box.max.get(axis)+3));
                        eye.setPosition(at.sub(0,ModelComponent.getEyeHeight(player,store),0));
                        require(InteractionValidation.canPlayerInteractWithEntity(player,store,null,chunk.getReferenceTo(i)),
                            "existing click reach is preserved: "+part.getLocalOffset());
                    }
                }
            });
            require(targets[0]>0 && visuals[0]>0,"both original clicks and visible model prompts are present");
            System.out.println("[YAGA USE] "+visuals[0]+" visible models and "+targets[0]+" original targets send SetInteractable prompt packets; existing click reach preserved");
        } finally { if(player.isValid()){store.removeComponent(player,Player.getComponentType());store.removeEntity(player,RemoveReason.REMOVE);} }
    }

    public static void arrows(Store<EntityStore> store) {
        var refs=new ArrayList<Ref<EntityStore>>();
        var dummy=EntityStore.REGISTRY.newHolder();dummy.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var owner=store.addEntity(dummy,AddReason.SPAWN);refs.add(owner);
        try {
            var shell=TitanPartBuilder.buildVoxel(store,owner,"Rock_Basalt",new Vector3d(40,180,40),new Rotation3f(),0,2,0,new Vector3d(),false,null);
            TitanPartBuilder.hideBlockVisual(shell);
            var body=store.addEntity(shell,AddReason.SPAWN);refs.add(body);
            var target=TitanPartBuilder.buildBlock(store,"Rock_Basalt",new Vector3d(40,180,45),new Rotation3f(),1);
            var weak=store.addEntity(target,AddReason.SPAWN);refs.add(weak);
            var resource=store.getResource(CollisionModule.get().getTangibleEntitySpatialResourceType());
            var data=resource.getSpatialData();data.clear();
            store.forEachChunk(new TangiableEntitySpatialSystem(CollisionModule.get().getTangibleEntitySpatialResourceType()).getQuery(),(chunk,cb)->{
                data.addCapacity(chunk.size());
                for(int i=0;i<chunk.size();i++)data.append(chunk.getComponent(i,TransformComponent.getComponentType()).getPosition(),chunk.getReferenceTo(i));
            });
            resource.getSpatialStructure().rebuild(data);
            store.forEachChunk(Query.and(TitanPartComponent.getComponentType(),ModelComponent.getComponentType()),(chunk,cb)->{
                for(int i=0;i<chunk.size();i++) if(body.equals(chunk.getReferenceTo(i))) {
                    var sweep=new EntityRefCollisionProvider();
                    var arrow=new Box(-.075,-.075,-.075,.075,.075,.075);
                    double t=sweep.computeNearest(cb,arrow,new Vector3d(40,180,35),new Vector3d(0,0,12),null,null);
                    require(t>=0 && t<=1 && sweep.getContact(0).getEntityReference().equals(body),"native arrow hits shell before hidden weakpoint");
                    sweep.clear();
                    t=sweep.computeNearest(cb,arrow,new Vector3d(40,180,48),new Vector3d(0,0,-6),null,null);
                    require(t>=0 && t<=1 && sweep.getContact(0).getEntityReference().equals(weak),"exposed weakpoint remains hittable");
                    var packet=chunk.getComponent(i,ModelComponent.getComponentType()).getModel().toPacket();
                    require(packet.detailBoxes!=null && packet.detailBoxes.size()>0,"predicted arrows receive collision geometry");
                }
            });
            System.out.println("[TITAN INTERACTIONS] Native arrow shell blocking, exposed target and client pick boxes PASS");
        } finally {for(var ref:refs)if(ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);}
    }
    private static void require(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
