package com.hexvane.titan.spawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

/** Packs identical full cubes without changing the occupied volume or filling openings. */
public final class TitanVoxelMerger {
    private TitanVoxelMerger() { }

    public record Piece(PrefabVoxels.Voxel voxel, int size) { }
    private record Cell(int x, int y, int z) { }

    public static List<Piece> merge(List<PrefabVoxels.Voxel> input, Predicate<String> canMerge) {
        var cells = new HashMap<Cell, PrefabVoxels.Voxel>();
        var eligible = new HashMap<String, Boolean>();
        for (var voxel : input) {
            if (eligible.computeIfAbsent(voxel.blockKey(), canMerge::test))
                cells.put(new Cell(voxel.x(), voxel.y(), voxel.z()), voxel);
        }
        var used = new HashSet<Cell>();
        var ordered = new ArrayList<>(input);
        ordered.sort(Comparator.comparingInt(PrefabVoxels.Voxel::y)
            .thenComparingInt(PrefabVoxels.Voxel::z).thenComparingInt(PrefabVoxels.Voxel::x));
        var result = new ArrayList<Piece>();
        for (var voxel : ordered) {
            var origin = new Cell(voxel.x(), voxel.y(), voxel.z());
            if (used.contains(origin)) continue;
            int size = 1;
            if (cells.containsKey(origin)) {
                for (int candidate = 4; candidate >= 2; candidate--) {
                    if (fits(cells, used, voxel, candidate)) { size = candidate; break; }
                }
            }
            boolean surface = voxel.surface(), standable = voxel.standable();
            if (size > 1) {
                for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                    var cell = new Cell(voxel.x() + x, voxel.y() + y, voxel.z() + z);
                    var member = cells.get(cell);
                    surface |= member.surface();
                    standable |= member.standable();
                    used.add(cell);
                }
            }
            result.add(new Piece(new PrefabVoxels.Voxel(voxel.x(), voxel.y(), voxel.z(), voxel.blockKey(),
                voxel.rotation(), surface, standable), size));
        }
        return List.copyOf(result);
    }

    private static boolean fits(HashMap<Cell, PrefabVoxels.Voxel> cells, HashSet<Cell> used,
                                PrefabVoxels.Voxel origin, int size) {
        for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            var cell = new Cell(origin.x() + x, origin.y() + y, origin.z() + z);
            var voxel = cells.get(cell);
            if (voxel == null || used.contains(cell) || voxel.rotation() != origin.rotation()
                || !voxel.blockKey().equals(origin.blockKey())) return false;
        }
        return true;
    }
}
