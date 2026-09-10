package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

public final class CryptPartComponent implements Component<EntityStore> {
    static ComponentType<EntityStore, CryptPartComponent> TYPE;
    public static ComponentType<EntityStore, CryptPartComponent> getComponentType() { return TYPE; }
    public Ref<EntityStore> owner;
    public int bone, blockRotation, pool = -1;
    public Vector3d offset = new Vector3d();
    public boolean platform, colliding;
    public float scale = 1;
    public float drawnScale = -1;
    public double scaleTimer, age;
    public CryptPartComponent() { }
    @Override public Component<EntityStore> clone() {
        var p = new CryptPartComponent();
        p.owner = owner; p.bone = bone; p.blockRotation = blockRotation; p.pool = pool;
        p.offset.set(offset); p.platform = platform; p.colliding = colliding; p.scale = scale;
        return p;
    }
}
