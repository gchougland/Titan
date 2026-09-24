package com.hexvane.titan.spawn;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.BsonArray;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;

/** Visual-only assets matched to exact source voxels. Collider construction stays independent. */
public final class TitanPartModels {
    private TitanPartModels() { }
    /** Origin is in mirrored prefab block coordinates, before subtracting the bone pivot. */
    public record Group(String model, Set<String> cells, double originX, double originY, double originZ,
                        String block, float blockScale) {
        public org.joml.Vector3d localOffset(org.joml.Vector3d pivot, double mirror, double scale) {
            return new org.joml.Vector3d(originX-pivot.x*mirror,originY-pivot.y,originZ-pivot.z).mul(scale);
        }
    }
    public record Parts(List<Group> groups, Set<String> cells) {
        public boolean covers(TitanMeshBatches.Piece piece) {
            return piece.originals().isEmpty() ? cells.contains(cell(piece.voxel()))
                : piece.originals().stream().allMatch(v -> cells.contains(cell(v)) || !v.surface());
        }
    }
    private static final Map<String, Parts> PARTS = load();
    public static String cell(PrefabVoxels.Voxel v) { return v.x()+","+v.y()+","+v.z(); }
    public static Parts find(PrefabVoxels voxels, boolean mirror, boolean hollow, float voxelScale) {
        var parts=PARTS.get(TitanMeshBatches.signature(voxels.getVoxels())+"#"+mirror+"#"+hollow+"#"+voxelScale);
        if (parts==null || parts.groups.stream().anyMatch(g -> ModelAsset.getAssetMap().getAsset(g.model)==null
            || (g.block!=null && com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(g.block)==null))) return null;
        return parts;
    }
    private static Map<String, Parts> load() {
        try (var stream=TitanPartModels.class.getResourceAsStream("/TitanMeshes/Parts.json")) {
            if (stream==null) return Map.of();
            var result=new HashMap<String, Parts>();
            for (var value:BsonArray.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8))) {
                var entry=value.asDocument();var groups=new ArrayList<Group>();var all=new HashSet<String>();
                for (var model:entry.getArray("models")) {
                    var m=model.asDocument();var cells=new HashSet<String>();
                    for (var c:m.getArray("cells")) {
                        if (!all.add(c.asString().getValue())) throw new IllegalStateException("Duplicate combined visual death block");
                        cells.add(c.asString().getValue());
                    }
                    var origin=m.getArray("origin");
                    groups.add(new Group(m.getString("model").getValue(),Set.copyOf(cells),
                        origin.get(0).asNumber().doubleValue(),origin.get(1).asNumber().doubleValue(),origin.get(2).asNumber().doubleValue(),
                        m.containsKey("block")?m.getString("block").getValue():null,
                        m.containsKey("blockScale")?(float)m.getNumber("blockScale").doubleValue():1f));
                }
                String key=entry.getString("signature").getValue()+"#"+entry.getBoolean("mirror").getValue()+"#"
                    +entry.getBoolean("hollow").getValue()+"#"+entry.getNumber("voxelScale").doubleValue();
                result.put(key,new Parts(List.copyOf(groups),Set.copyOf(all)));
            }
            return Map.copyOf(result);
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid titan part models",e); }
    }
}
