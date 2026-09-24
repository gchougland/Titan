package com.hexvane.titan.spawn;

import com.hexvane.titan.anim.TitanPose;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombinedVisualTransformTest {
    @Test void scaledBonesKeepTheirFacingAfterEntityMeshCorrection() {
        var pose=new TitanPose(1);
        for(double scale:new double[]{.5,1,1.1,1.6,2,3.5}) {
            for(var expected:new Quaterniond[]{new Quaterniond().rotationYXZ(.7,0,0),
                    new Quaterniond().rotationYXZ(-2.1,.37,-.42),new Quaterniond().rotationYXZ(2.4,-1.3,.8)}) {
                pose.getWorld(0).identity().translate(4,90,-7).rotate(expected).scale(scale);
                var packet=pose.getWorldRotation(0,new Rotation3f());
                BlockRotations.compose(packet,0,new Quaterniond(),new Vector3d());
                var rendered=packet.getQuaternion(new Quaterniond()).rotateY(Math.PI);
                for(var v:new Vector3d[]{new Vector3d(3,0,-5),new Vector3d(0,2,0)})
                    assertEquals(0,expected.transform(new Vector3d(v)).distance(rendered.transform(new Vector3d(v))),1e-5);
                var scratch=pose.getWorldRotation(0,new Rotation3f(),new Quaterniond(),new Vector3d());
                assertEquals(1,Math.abs(expected.dot(scratch.getQuaternion(new Quaterniond()))),1e-6);
            }
        }
    }
}
