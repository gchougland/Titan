package com.hexvane.titan.crypt;

import com.hypixel.hytale.math.Axis;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockFlipType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockRotationUtil;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Prefab orientation followed by the block entity's local mesh correction. */
public final class CryptBlockRotations {
    private static final Quaterniond[] ENTITY_ROTATIONS = new Quaterniond[RotationTuple.VALUES.length];
    static {
        for (var tuple : RotationTuple.VALUES) {
            ENTITY_ROTATIONS[tuple.index()] = new Quaterniond().rotationYXZ(tuple.yaw().getRadians(),
                tuple.pitch().getRadians(), tuple.roll().getRadians()).rotateY(Math.PI);
        }
    }
    private CryptBlockRotations() { }

    /** Resolve once at assembly; mirror the block orientation as well as its cell position. */
    public static int authoredIndex(int index, String blockKey, boolean mirrored) {
        if (!mirrored) return valid(index);
        var block = BlockType.getAssetMap().getAsset(blockKey);
        return mirroredIndex(index, block == null ? BlockFlipType.SYMMETRIC : block.getFlipType());
    }

    static int mirroredIndex(int index, BlockFlipType flipType) {
        var flipped = BlockRotationUtil.getFlipped(RotationTuple.get(valid(index)), flipType, Axis.X);
        return flipped == null ? valid(index) : flipped.index();
    }

    public static Rotation3f compose(Quaterniondc bone, int index, Rotation3f out,
                                      Quaterniond scratch, Vector3d euler) {
        scratch.set(bone).mul(ENTITY_ROTATIONS[valid(index)]).normalize();
        return toRotation(scratch, out, euler);
    }

    /** YXZ decomposition with an explicit pole branch for the authored vertical slabs. */
    static Rotation3f toRotation(Quaterniondc q, Rotation3f out, Vector3d euler) {
        double x=q.x(), y=q.y(), z=q.z(), w=q.w();
        double sinPitch=Math.clamp(2*(w*x-y*z),-1,1);
        if (Math.abs(sinPitch)>1-1e-12) {
            euler.set(Math.copySign(Math.PI/2,sinPitch),
                Math.atan2(2*(w*y-x*z),1-2*(y*y+z*z)),0);
        } else {
            euler.set(Math.asin(sinPitch),Math.atan2(2*(x*z+w*y),1-2*(x*x+y*y)),
                Math.atan2(2*(x*y+w*z),1-2*(x*x+z*z)));
        }
        return out.set((float)euler.x,(float)euler.y,(float)euler.z);
    }

    private static int valid(int index) {
        return index>=0 && index<ENTITY_ROTATIONS.length ? index : RotationTuple.NONE_INDEX;
    }
}
