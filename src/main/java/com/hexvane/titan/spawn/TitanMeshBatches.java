package com.hexvane.titan.spawn;

import com.hexvane.titan.asset.TitanBoneDef;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Prebaked cuboids are used only with the exact prefab they were generated from. */
public final class TitanMeshBatches {
    private TitanMeshBatches() { }

    public record Piece(PrefabVoxels.Voxel voxel, int sizeX, int sizeY, int sizeZ, int renderScale,
                        List<PrefabVoxels.Voxel> originals) {
        public Piece(PrefabVoxels.Voxel voxel, int sx, int sy, int sz, int scale) {
            this(voxel, sx, sy, sz, scale, List.of());
        }
    }
    private record Cell(int x, int y, int z) { }
    private record Batch(int x, int y, int z, int sx, int sy, int sz, String block) { }
    private record Manifest(String signature, List<Batch> batches) { }
    private static final Map<String, Manifest> MANIFESTS = load();
    public record BodyVisual(String model, Set<String> batches) { }
    private static final List<BodyVisual> BODY_VISUALS = loadBodyVisuals();

    public static List<BodyVisual> templeBodyVisuals() { return BODY_VISUALS; }

    private static List<BodyVisual> loadBodyVisuals() {
        try (var stream = TitanMeshBatches.class.getResourceAsStream("/TitanMeshes/BodyVisuals.tsv")) {
            if (stream == null) throw new IllegalStateException("Missing temple body visual manifest");
            var result = new ArrayList<BodyVisual>();
            var covered = new HashSet<String>();
            for (String line : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList()) {
                var fields = line.split("\t");
                if (fields.length < 2) throw new IllegalStateException("Empty temple body visual");
                var batches = new HashSet<String>();
                for (int i=1;i<fields.length;i++) {
                    if (!covered.add(fields[i])) throw new IllegalStateException("Duplicate temple death blocks");
                    batches.add(fields[i]);
                }
                result.add(new BodyVisual(fields[0], Set.copyOf(batches)));
            }
            var body = MANIFESTS.get("Body");
            if (body == null || !covered.equals(body.batches.stream().map(Batch::block).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalStateException("Incomplete temple body visual manifest");
            return List.copyOf(result);
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid temple body visual manifest", e); }
    }

    public static List<Piece> merge(TitanBoneDef bone, PrefabVoxels voxels, Predicate<String> eligible) {
        String signature = signature(voxels.getVoxels());
        Manifest manifest = MANIFESTS.get(isTalusBone(bone) ? signature+"#"+bone.isMirrorX() : section(bone));
        if (manifest != null && manifest.signature.equals(signature)) {
            var result = apply(manifest, voxels.getVoxels(), eligible);
            if (result != null) return stableDetails(bone, result);
        }
        var cells = new HashMap<Cell, PrefabVoxels.Voxel>();
        for (var v : voxels.getVoxels()) cells.put(new Cell(v.x(), v.y(), v.z()), v);
        return stableDetails(bone, TitanVoxelMerger.merge(voxels.getVoxels(), eligible).stream().map(p -> {
            var originals = new ArrayList<PrefabVoxels.Voxel>();
            if (p.size() > 1) for (int y = 0; y < p.size(); y++) for (int z = 0; z < p.size(); z++) for (int x = 0; x < p.size(); x++)
                originals.add(cells.get(new Cell(p.voxel().x()+x, p.voxel().y()+y, p.voxel().z()+z)));
            return new Piece(p.voxel(), p.size(), p.size(), p.size(), p.size(), List.copyOf(originals));
        }).toList());
    }

    public static boolean hasWholeTempleBody(TitanBoneDef bone, PrefabVoxels voxels, List<Piece> pieces) {
        var manifest = MANIFESTS.get("Body");
        return "Body".equals(section(bone)) && manifest != null && manifest.signature.equals(signature(voxels.getVoxels()))
            && pieces.stream().filter(p -> p.voxel().blockKey().startsWith("Titan_Temple_Body_")).count() == manifest.batches.size();
    }

    public static boolean isTalusBone(TitanBoneDef bone) {
        return bone.getPrefab() != null && bone.getPrefab().startsWith("Titan/Talus/")
            && bone.getPrefabRotation() == PrefabRotation.ROTATION_0;
    }

    private static List<Piece> stableDetails(TitanBoneDef bone, List<Piece> pieces) {
        if (!"Body".equals(section(bone))) return pieces;
        return pieces.stream().map(p -> {
            var v = p.voxel();
            if (!v.blockKey().equals("Plant_Vine_Jungle") && !v.blockKey().equals("Plant_Vine_Wall_Winter")) return p;
            String key = "Titan_Temple_Detail_"+v.blockKey();
            if (BlockType.getAssetMap().getAsset(key) == null) return p;
            return new Piece(new PrefabVoxels.Voxel(v.x(),v.y(),v.z(),key,v.rotation(),v.surface(),v.standable()),
                1,1,1,4,List.of(v));
        }).toList();
    }

    private static String section(TitanBoneDef bone) {
        if (bone.isMirrorX()) return "";
        if (isYagaHouse(bone)) return "Yaga_Baba";
        if (bone.getPrefabRotation() != PrefabRotation.ROTATION_0) return "";
        if ("Titan/Temple/Temple_Body".equals(bone.getPrefab())
            && bone.getSliceMinY() == Integer.MIN_VALUE && bone.getSliceMaxY() == Integer.MAX_VALUE) return "Body";
        if (!"Titan/Temple/Temple_Leg".equals(bone.getPrefab())) return "";
        if (bone.getSliceMinY() == 0 && bone.getSliceMaxY() == 4) return "Foot";
        if (bone.getSliceMinY() == 5 && bone.getSliceMaxY() == 14) return "Calf";
        if (bone.getSliceMinY() == 15 && bone.getSliceMaxY() == 24) return "Thigh";
        return "";
    }

    public static boolean isYagaHouse(TitanBoneDef bone) {
        return "Titan/Yaga/Yaga_Baba_Body".equals(bone.getPrefab())
            && bone.getPrefabRotation() == PrefabRotation.ROTATION_90
            && bone.getSliceMinY() == Integer.MIN_VALUE && bone.getSliceMaxY() == Integer.MAX_VALUE;
    }

    private static List<Piece> apply(Manifest manifest, List<PrefabVoxels.Voxel> voxels, Predicate<String> eligible) {
        var cells = new HashMap<Cell, PrefabVoxels.Voxel>();
        for (var v : voxels) cells.put(new Cell(v.x(), v.y(), v.z()), v);
        var used = new HashSet<Cell>();
        var allowed = new HashMap<String, Boolean>();
        var result = new ArrayList<Piece>();
        for (var b : manifest.batches) {
            if (BlockType.getAssetMap().getAsset(b.block) == null) return null;
            boolean surface = false, standable = false;
            var originals = new ArrayList<PrefabVoxels.Voxel>();
            for (int y = 0; y < b.sy; y++) for (int z = 0; z < b.sz; z++) for (int x = 0; x < b.sx; x++) {
                var cell = new Cell(b.x+x, b.y+y, b.z+z);
                var v = cells.get(cell);
                if (v == null || v.rotation() >= 4 || !used.add(cell)
                    || !allowed.computeIfAbsent(v.blockKey(), eligible::test)) return null;
                originals.add(v);
                surface |= v.surface();
                standable |= v.standable();
            }
            result.add(new Piece(new PrefabVoxels.Voxel(b.x, b.y, b.z, b.block, 0, surface, standable),
                b.sx, b.sy, b.sz, Math.max(b.sx, Math.max(b.sy, b.sz)), List.copyOf(originals)));
        }
        for (var v : voxels) if (!used.contains(new Cell(v.x(), v.y(), v.z())))
            result.add(new Piece(v, 1, 1, 1, 1));
        return List.copyOf(result);
    }

    static String signature(List<PrefabVoxels.Voxel> voxels) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            voxels.stream().sorted(Comparator.comparingInt(PrefabVoxels.Voxel::x)
                .thenComparingInt(PrefabVoxels.Voxel::y).thenComparingInt(PrefabVoxels.Voxel::z)).forEach(v ->
                    digest.update((v.x()+","+v.y()+","+v.z()+","+v.blockKey()+","+v.rotation()+"\n")
                        .getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Manifest> load() {
        var manifests = new HashMap<String, Manifest>();
        final List<String> entries;
        try (var stream = TitanMeshBatches.class.getResourceAsStream("/TitanMeshes/index.tsv")) {
            if (stream == null) return Map.of();
            entries = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList();
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid mesh index", e); }
        for (var entry : entries) {
            var spec = entry.split("\t");
            String section = spec[0];
            var stream = TitanMeshBatches.class.getResourceAsStream("/TitanMeshes/"+section+".tsv");
            if (stream == null) continue;
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String signature = reader.readLine();
                var batches = new ArrayList<Batch>();
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isBlank()) continue;
                    var fields = line.split("\t");
                    if (fields.length != 7) throw new IllegalArgumentException("Invalid temple batch");
                    var b = new Batch(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), Integer.parseInt(fields[5]), fields[6]);
                    if (b.sx < 1 || b.sy < 1 || b.sz < 1 || b.sx > 4 || b.sy > 4 || b.sz > 4)
                        throw new IllegalArgumentException("Invalid temple batch dimensions");
                    batches.add(b);
                }
                var manifest = new Manifest(signature, List.copyOf(batches));
                manifests.put(section, manifest);
                if (section.startsWith("Talus_")) manifests.put(signature+"#"+spec[5].equals("1"), manifest);
            } catch (java.io.IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Invalid bundled temple geometry: " + section, e);
            }
        }
        return Map.copyOf(manifests);
    }
}
