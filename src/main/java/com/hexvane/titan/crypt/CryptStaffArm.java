package com.hexvane.titan.crypt;

import com.hexvane.titan.spawn.BlockRotations;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** The user's original arm, palm, bracelet and finger prefabs, summoned at 65% scale. */
final class CryptStaffArm {
    private static final float SCALE = 0.65f;
    private CryptStaffArm() {}

    static void build(Store<EntityStore> store, Ref<EntityStore> root, Vector3d target, float yaw) {
        prefab(store, root, target, "Skeleton_Palm", new Vector3d(-0.5, 0, -0.5), -1, -1);
        prefab(store, root, target, "Skeleton_Bracelet", new Vector3d(-1, -1.5, -4.5), -1, -1);
        // Authored arm volume: x[-2,4], y[0,3], z[-7,8]. Its wrist end meets the palm
        // at z=-2.5 after translation; their cross-section centres both become (0,1.5).
        prefab(store, root, target, "Skeleton_Arm", new Vector3d(-1, 0, -10.5), -1, -1);
        for (int digit = 0; digit < 4; digit++) {
            for (int joint = 0; joint < 3; joint++) {
                // Three independently curled phalanges on each of the three fingers and the thumb.
                prefab(store, root, target, "Skeleton_Finger", new Vector3d(-0.5, -0.5, 0), digit, joint);
            }
        }
    }

    private static void prefab(Store<EntityStore> store, Ref<EntityStore> root, Vector3d at,
                                String name, Vector3d offset, int digit, int joint) {
        for (var voxel : PrefabVoxelReader.read("Titan/Crypt/" + name).getVoxels()) {
            voxel(store, root, at, voxel.blockKey(),
                new Vector3d(voxel.x() + 0.5, voxel.y() + 0.5, voxel.z() + 0.5).add(offset),
                voxel.rotation(), digit, joint);
        }
    }

    private static void voxel(Store<EntityStore> store, Ref<EntityStore> root, Vector3d at,
                               String block, Vector3d local, int rotation, int digit, int joint) {
        var state = new CryptSpellComponent(null, CryptSpellComponent.Kind.PART);
        state.root = root; state.local.set(local); state.rotation = rotation; state.digit = digit; state.joint = joint;
        var holder = TitanPartBuilder.buildBlock(store, block, new Vector3d(at).add(0, 7, 0), new Rotation3f(), SCALE);
        holder.addComponent(CryptStaff.spellType, state);
        var parent = store.getComponent(root, CryptStaff.spellType);
        pose(state, parent, new Vector3d(at).add(0, 7, 0), holder.getComponent(TransformComponent.getComponentType()));
        store.addEntity(holder, AddReason.SPAWN);
    }

    static void pose(CryptSpellComponent part, CryptSpellComponent arm, Vector3d origin, TransformComponent transform) {
        Vector3d position = new Vector3d(part.local);
        Quaterniond rotation = new Quaterniond();
        if (part.digit >= 0) {
            double close = smooth(Math.min(1, Math.max(0, arm.age / 0.85)));
            double curl = close * -1.12;
            Vector3d jointAt;
            if (part.digit == 3) {
                jointAt = new Vector3d(-2.1, 0.8, -0.4);
                rotation.rotateY(-1.0).rotateZ(0.35);
            } else {
                jointAt = new Vector3d((part.digit - 1) * 1.7, 0.7, 2.2);
            }
            for (int j = 0; j <= part.joint; j++) {
                rotation.rotateX(curl * (j == 0 ? 0.72 : 1));
                if (j < part.joint) jointAt.add(rotation.transform(new Vector3d(0, 0, 1.75)));
            }
            rotation.transform(position).add(jointAt);
        }
        Quaterniond orientation = new Quaterniond().rotateY(arm.yaw).rotateX(Math.PI / 2);
        orientation.transform(position).mul(SCALE).add(origin).add(0, 2.3, 0);
        transform.setPosition(position);
        Vector3d euler = orientation.mul(rotation).getEulerAnglesYXZ(new Vector3d());
        Rotation3f result = new Rotation3f((float)euler.x, (float)euler.y, (float)euler.z);
        BlockRotations.compose(result, part.rotation, new Quaterniond(), new Vector3d());
        transform.setRotation(result);
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }
}
