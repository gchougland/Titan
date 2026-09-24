package com.hexvane.titan.spawn;

import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TitanVoxelMergerTest {
    @Test void mergesSolidVolumesWithoutFillingHolesOrChangingMaterials() {
        var voxels = new ArrayList<PrefabVoxels.Voxel>();
        for (int y = -2; y < 6; y++) for (int z = -3; z < 5; z++) for (int x = -4; x < 4; x++) {
            if (x == 0 && z == 0) continue; // An opening must survive.
            voxels.add(new PrefabVoxels.Voxel(x, y, z, x < 0 ? "Rock" : "Brick", y == 5 ? 1 : 0,
                y == -2 || y == 5, y == 5));
        }
        var pieces = TitanVoxelMerger.merge(voxels, ignored -> true);
        assertTrue(pieces.size() < voxels.size() / 2);
        var expected = new HashMap<String, String>();
        for (var v : voxels) expected.put(v.x()+":"+v.y()+":"+v.z(), v.blockKey()+":"+v.rotation());
        var actual = new HashMap<String, String>();
        for (var p : pieces) for (int y = 0; y < p.size(); y++) for (int z = 0; z < p.size(); z++) for (int x = 0; x < p.size(); x++) {
            var v = p.voxel();
            assertNull(actual.put((v.x()+x)+":"+(v.y()+y)+":"+(v.z()+z), v.blockKey()+":"+v.rotation()), "no overlapping pieces");
        }
        assertEquals(expected, actual);
    }

    @Test void interactiveAndPartialBlocksRemainIndividualAndSurfaceFlagsSurvive() {
        var voxels = new ArrayList<PrefabVoxels.Voxel>();
        for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++)
            voxels.add(new PrefabVoxels.Voxel(x, y, z, "Rock", 0, y == 1, y == 1));
        var merged = TitanVoxelMerger.merge(voxels, key -> true);
        assertEquals(1, merged.size());
        assertEquals(2, merged.getFirst().size());
        assertTrue(merged.getFirst().voxel().surface());
        assertTrue(merged.getFirst().voxel().standable());
        assertEquals(8, TitanVoxelMerger.merge(voxels, key -> false).size());
        voxels.set(0, new PrefabVoxels.Voxel(0,0,0,"Stairs",0,true,true));
        assertEquals(8, TitanVoxelMerger.merge(voxels, key -> key.equals("Rock")).size());
    }
}
