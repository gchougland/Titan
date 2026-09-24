package com.hexvane.titan.yaga;

import com.hexvane.titan.entity.TitanPartComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.InteractableUpdate;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.system.EntityInteractableSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;

/** The entity-model equivalent of the NPC SetInteractable action's ShowPrompt path. */
public final class YagaInteractionPromptSystem extends EntityTickingSystem<EntityStore> {
    @Override public Query<EntityStore> getQuery() {
        return Query.and(TitanPartComponent.getComponentType(),Interactable.getComponentType(),
            Interactions.getComponentType(),EntityTrackerSystems.Visible.getComponentType());
    }
    @Override public SystemGroup<EntityStore> getGroup() { return EntityTrackerSystems.QUEUE_UPDATE_GROUP; }
    @Override public Set<Dependency<EntityStore>> getDependencies() {
        // The generic marker queues InteractableUpdate() without prompt text.
        // Our text must follow it, just as StateSupport.setInteractable does.
        return Set.of(new SystemDependency<>(Order.AFTER,EntityInteractableSystems.EntityTrackerUpdate.class));
    }
    @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,
                               Store<EntityStore> store,CommandBuffer<EntityStore> cb) {
        var part=chunk.getComponent(index,TitanPartComponent.getComponentType());
        if(part.getOwner()==null || !part.getOwner().isValid()
            || store.getComponent(part.getOwner(),YagaComponent.getComponentType())==null) return;
        var hint=chunk.getComponent(index,Interactions.getComponentType()).getInteractionHint();
        if(hint==null) return;
        var visible=chunk.getComponent(index,EntityTrackerSystems.Visible.getComponentType());
        var viewers=part.interactionHintChanged(hint)?visible.visibleTo:visible.newlyVisibleTo;
        if(viewers.isEmpty()) return;
        // NPC SetInteractable sends this exact packet. InteractionsUpdate's
        // hint alone is not the interactable marker's on-screen prompt.
        var update=new InteractableUpdate(hint);
        var ref=chunk.getReferenceTo(index);
        for(var viewer:viewers.values()) viewer.queueUpdate(ref,update);
    }
}
