package com.hexvane.titan.ik;

/** Natural tree materials, excluding planks, roofs, furniture and other worked wood. */
public final class TitanTrees {
    private TitanTrees() { }

    public static boolean isTree(String id) {
        if (id == null) return false;
        while (id.startsWith("*")) id = id.substring(1);
        return id.startsWith("Plant_Leaves_") || id.startsWith("Plant_Roots_")
            || (id.startsWith("Wood_") && (id.contains("_Trunk") || id.contains("_Branch")));
    }
}
