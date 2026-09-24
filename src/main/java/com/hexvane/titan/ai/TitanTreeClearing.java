package com.hexvane.titan.ai;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.ik.TitanTrees;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import org.joml.Vector3d;

/** Incremental contact checks against the posed geometry, with a fixed work budget. */
public final class TitanTreeClearing {
    public static final class Cursor {
        int bone, voxel;
        final Vector3d local = new Vector3d(), world = new Vector3d();
    }
    private TitanTreeClearing() { }

    public static void tick(TitanComponent titan, ChunkStore chunks) {
        if (!TitanConfig.get().isRoamingTempleDestroysTrees() || titan.getVariant() == null
            || !"Roaming_Temple".equals(titan.getVariant().getId()) || titan.getPose() == null
            || titan.getSkeleton() == null || titan.getState() == TitanState.DYING
            || titan.getVelocity().lengthSquared() < .0001) return;
        var cursor = titan.treeClearing;
        var bones = titan.getSkeleton().getBones();
        int checked = 0, broken = 0, visited = 0;
        // Full prefab volume catches a tree already inside a large merged cube. This does not
        // enumerate world air, force chunk loads, or create item entities for every leaf.
        while (checked < 512 && broken < 32 && visited <= bones.length) {
            cursor.bone %= bones.length;
            var bone = bones[cursor.bone];
            var voxels = PrefabVoxelReader.read(bone.getPrefab(), titan.getVariant().getRockType(),
                bone.getSliceMinY(), bone.getSliceMaxY(), bone.getPrefabRotation());
            if (cursor.voxel >= voxels.size()) {
                cursor.voxel = 0; cursor.bone++; visited++; continue;
            }
            var voxel = voxels.getVoxels().get(cursor.voxel++);
            var pivot = bone.getPivot() == null ? voxels.defaultPivot() : bone.getPivot();
            cursor.local.set(voxel.x() + .5 - pivot.x(), voxel.y() + .5 - pivot.y(), voxel.z() + .5 - pivot.z())
                .mul(bone.getScale());
            if (bone.isMirrorX()) cursor.local.x = -cursor.local.x;
            titan.getPose().transformLocal(bone.getIndex(), cursor.local, cursor.world);
            int x = (int) Math.floor(cursor.world.x), y = (int) Math.floor(cursor.world.y), z = (int) Math.floor(cursor.world.z);
            checked++;
            int id = GroundSampler.blockId(chunks, x, y, z);
            if (id == BlockType.EMPTY_ID || id == BlockType.UNKNOWN_ID) continue;
            var type = BlockType.getAssetMap().getAsset(id);
            if (type == null || !TitanTrees.isTree(type.getId())) continue;
            var section = chunks.getChunkSectionReferenceAtBlock(x, y, z);
            if (section == null) continue;
            BlockOperations.setBlock(chunks, section, x, y, z, BlockType.EMPTY_ID, BlockType.EMPTY,
                RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER, SetBlockSettings.NONE);
            broken++;
        }
    }
}
