package com.hexvane.titan.system;

import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.dunewyrm.DunewyrmPartComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.system.ModelSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;

/** ModelSpawned assigns rotating bounds. Hidden block targets must keep their original behavior. */
public final class TitanHiddenCollisionSystem extends HolderSystem<EntityStore> {
    @Override public Query<EntityStore> getQuery() {
        return Query.or(TitanPartComponent.getComponentType(),DunewyrmPartComponent.getComponentType());
    }
    @Override public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER,ModelSystems.ModelSpawned.class));
    }
    @Override public void onEntityAdd(Holder<EntityStore> holder,AddReason reason,Store<EntityStore> store) {
        var part=holder.getComponent(TitanPartComponent.getComponentType());
        var worm=holder.getComponent(DunewyrmPartComponent.getComponentType());
        var saved=part!=null?part.takeCollisionBounds():worm!=null?worm.takeCollisionBounds():null;
        if(saved!=null) {
            var transform=holder.getComponent(com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if(transform!=null) {
                var rotation=transform.getRotation();
                saved.applyRotation(rotation.pitch(),rotation.yaw(),rotation.roll());
            }
            holder.putComponent(BoundingBox.getComponentType(),saved);
        }
    }
    @Override public void onEntityRemoved(Holder<EntityStore> holder,RemoveReason reason,Store<EntityStore> store) { }
}
