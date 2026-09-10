package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

/** Unsaved, short lived spell state. Each hand part removes itself when its root is gone. */
public final class CryptSpellComponent implements Component<EntityStore> {
    enum Kind { MISSILE, ARM, PART }
    Kind kind = Kind.MISSILE;
    Ref<EntityStore> owner, target, root;
    final Vector3d velocity = new Vector3d(), anchor = new Vector3d(), local = new Vector3d();
    float age, fxTimer, seekTimer, yaw;
    int joint = -1, digit = -1, rotation;
    boolean impacted;
    public CryptSpellComponent() {}
    CryptSpellComponent(Ref<EntityStore> owner, Kind kind) { this.owner = owner; this.kind = kind; }
    @Override public Component<EntityStore> clone() {
        var c = new CryptSpellComponent(owner, kind); c.target = target; c.root = root;
        c.velocity.set(velocity); c.anchor.set(anchor); c.local.set(local);
        c.age = age; c.fxTimer = fxTimer; c.seekTimer = seekTimer; c.yaw = yaw;
        c.joint = joint; c.digit = digit; c.rotation = rotation; c.impacted = impacted; return c;
    }
}
