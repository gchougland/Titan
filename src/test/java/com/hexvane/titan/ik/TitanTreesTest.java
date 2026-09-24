package com.hexvane.titan.ik;

import com.hexvane.titan.config.TitanConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TitanTreesTest {
    @Test void treesAreRecognizedWithoutTreatingAllWoodAsTrees() {
        for (var id : new String[]{"Wood_Oak_Trunk", "Wood_Ash_Trunk_Full", "Wood_Ash_Branch_Corner", "Plant_Leaves_Oak", "Plant_Roots_Cave_Small"})
            assertTrue(TitanTrees.isTree(id), id);
        for (var id : new String[]{"Wood_Oak_Planks", "Wood_Deadwood_Roof", "Furniture_Human_Ruins_Bed", "Rock_Basalt", "Soil_Grass", "Empty"})
            assertFalse(TitanTrees.isTree(id), id);
        assertFalse(new TitanConfig().isRoamingTempleDestroysTrees());
    }
}
