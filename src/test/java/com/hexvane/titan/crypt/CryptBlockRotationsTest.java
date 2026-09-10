package com.hexvane.titan.crypt;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockFlipType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CryptBlockRotationsTest {
    private static final Vector3d[] AXES = {
        new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)
    };

    @Test void everyAuthoredSlabAndStairOrientationSurvivesBoneAndEntityTransforms() {
        for (var bone : new Quaterniond[]{new Quaterniond(),
            new Quaterniond().rotationYXZ(.73, -.41, .27),
            new Quaterniond().rotationYXZ(-Math.PI / 2, Math.PI / 2, 0)}) {
            for (var tuple : RotationTuple.VALUES) {
                var packet = CryptBlockRotations.compose(bone, tuple.index(), new Rotation3f(),
                    new Quaterniond(), new Vector3d());
                // Block entity meshes face the opposite local horizontal direction.
                // Remove that mesh basis to compare with the engine's placed-block geometry.
                var rendered = packet.getQuaternion(new Quaterniond()).rotateY(Math.PI);
                for (var axis : AXES) {
                    Vector3d expected = bone.transform(Rotation.rotate(axis, tuple.yaw(), tuple.pitch(), tuple.roll()));
                    assertEquals(0, expected.distance(rendered.transform(new Vector3d(axis))), 2e-6,
                        "Prefab rotation " + tuple.index() + " axis " + axis);
                }
            }
        }
    }

    @Test void mirroredPartsReflectTheirBlockFacesAlongWithTheirCellPositions() {
        for (var type : BlockFlipType.values()) for (var tuple : RotationTuple.VALUES) {
            int mirrored = CryptBlockRotations.mirroredIndex(tuple.index(), type);
            var packet = CryptBlockRotations.compose(new Quaterniond(), mirrored, new Rotation3f(),
                new Quaterniond(), new Vector3d());
            var rendered = packet.getQuaternion(new Quaterniond()).rotateY(Math.PI);
            for (var axis : AXES) {
                Vector3d localCorrection = switch (type) {
                    case SYMMETRIC -> new Vector3d(-axis.x, axis.y, axis.z);
                    case ORTHOGONAL -> new Vector3d(axis.z, axis.y, axis.x);
                    case ORTHOGONAL_INVERSE -> new Vector3d(-axis.z, axis.y, -axis.x);
                };
                Vector3d expected = Rotation.rotate(localCorrection, tuple.yaw(), tuple.pitch(), tuple.roll());
                expected.x = -expected.x;
                assertEquals(0, expected.distance(rendered.transform(new Vector3d(axis))), 2e-6,
                    type + " mirrored rotation " + tuple.index());
            }
        }
    }

    @Test void verticalSlabPolesPreserveCombinedYawAndRollWhenEncodedForTheClient() {
        for (double pitch : new double[]{-Math.PI / 2, Math.PI / 2}) {
            for (double yaw : new double[]{-2.4, -.7, 0, .6, 2.1}) {
                for (double roll : new double[]{-.8, 0, .3, 2.7}) {
                    var original = new Quaterniond().rotationYXZ(yaw, pitch, roll);
                    var packet = CryptBlockRotations.toRotation(original, new Rotation3f(), new Vector3d());
                    var decoded = packet.getQuaternion(new Quaterniond());
                    assertEquals(1, Math.abs(original.dot(decoded)), 1e-6);
                    assertTrue(Float.isFinite(packet.pitch()) && Float.isFinite(packet.yaw()) && Float.isFinite(packet.roll()));
                }
            }
        }
    }
}
