package com.hexvane.titan.spawn;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import org.bson.BsonDocument;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Client constraints are stricter than the server asset loader's existence checks. */
class ClientAssetValidationTest {
    private static final Path RES = Path.of("src/main/resources");
    private static final Set<String> CHANNELS = Set.of("position", "orientation", "shapeStretch", "shapeVisible", "shapeUvOffset");

    @Test void commonTexturesAndAnimationsMeetClientRequirements() throws Exception {
        try (var paths = Files.walk(RES.resolve("Common"))) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                var name = path.toString();
                if (name.endsWith(".png")) {
                    var bytes = ByteBuffer.wrap(Files.readAllBytes(path));
                    int width = bytes.getInt(16), height = bytes.getInt(20);
                    assertTrue(width >= 32 && height >= 32 && width % 32 == 0 && height % 32 == 0,
                        name + " invalid texture dimensions " + width + "x" + height);
                } else if (name.endsWith(".blockyanim")) {
                    var clip = BsonDocument.parse(Files.readString(path));
                    for (var entry : clip.getDocument("nodeAnimations").entrySet()) {
                        var tracks = entry.getValue().asDocument();
                        assertTrue(tracks.keySet().containsAll(CHANNELS), name + " missing channels for " + entry.getKey());
                        for (var channel : CHANNELS) assertTrue(tracks.get(channel).isArray(), name + ": " + channel);
                    }
                }
            }
        }
    }

    @Test void entityAttachmentsAlwaysDefineTheirModels() throws Exception {
        try (var paths = Files.walk(RES.resolve("Server/Models"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var model = BsonDocument.parse(Files.readString(path));
                if (!model.containsKey("DefaultAttachments")) continue;
                for (var value : model.getArray("DefaultAttachments")) {
                    var attachment = value.asDocument();
                    assertTrue(attachment.containsKey("Model") && !attachment.getString("Model").getValue().isBlank(),
                        path + " has an attachment without a model");
                }
            }
        }
    }

    @Test void solidMeshAtlasesStayOpaqueAtEveryMipLevel() throws Exception {
        // Checking only the UV rectangle and its two-pixel gutter missed alpha
        // bleeding from unused atlas space once the client minifies the texture.
        try (var paths = Files.list(RES.resolve("Common/VFX/Titan/TempleMeshes"))) {
            for (var path : paths.filter(p -> p.getFileName().toString().matches("Atlas_[0-9]+\\.png")).toList()) {
                var image = javax.imageio.ImageIO.read(path.toFile());
                for (int level = 0;; level++) {
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                        if (image.getRGB(x,y) >>> 24 != 255)
                            fail(path + " has nonopaque pixels at mip " + level + "; solid faces can lose coverage when filtered");
                    if (image.getWidth() == 1 && image.getHeight() == 1) break;
                    var next = new java.awt.image.BufferedImage(Math.max(1,image.getWidth()/2),
                        Math.max(1,image.getHeight()/2), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics = next.createGraphics();
                    try {
                        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(image,0,0,next.getWidth(),next.getHeight(),null);
                    } finally { graphics.dispose(); }
                    image = next;
                }
            }
        }
    }

    @Test void allTitanPartModelsStayWithinClientLimits() throws Exception {
        var seen=new HashSet<String>();
        for (var entry:org.bson.BsonArray.parse(Files.readString(RES.resolve("TitanMeshes/Parts.json")))) {
            var cells=new HashSet<String>();
            for (var value:entry.asDocument().getArray("models")) {
                var group=value.asDocument();String name=group.getString("model").getValue();
                assertTrue(seen.add(name),"Model assets have unique names");
                for (var cell:group.getArray("cells")) assertTrue(cells.add(cell.asString().getValue()),"No duplicate death ownership");
                var asset=BsonDocument.parse(Files.readString(RES.resolve("Server/Models/Titan/Parts/"+name+".json")));
                assertTrue(Files.isRegularFile(RES.resolve("Common/"+asset.getString("Texture").getValue())));
                var model=BsonDocument.parse(Files.readString(RES.resolve("Common/"+asset.getString("Model").getValue())));
                assertEquals("off",model.getString("lod").getValue());
                assertEquals(1,model.getArray("nodes").size());
                var root=model.getArray("nodes").getFirst().asDocument();
                assertTrue(root.getDocument("shape").getBoolean("visible").getValue());
                assertTrue(root.getArray("children").size()+1<=256,name+" exceeds client node budget");
                for (var node:root.getArray("children")) assertTrue(node.asDocument().getArray("children").isEmpty());
            }
        }
        assertFalse(seen.isEmpty());
    }

    @Test void poisonGroundParticlesAlwaysFinishAndPlayerEffectsDoNotStackTime() throws Exception {
        for(var id:java.util.List.of("Crypt_Poison","Dunewyrm_Poison")) {
            var effect=BsonDocument.parse(Files.readString(RES.resolve("Server/Entity/Effects/Status/"+id+".json")));
            assertFalse(effect.getBoolean("Infinite").getValue());
            assertEquals("Overwrite",effect.getString("OverlapBehavior").getValue());
            assertEquals(4,effect.getNumber("Duration").intValue());
        }
        for(var path:java.util.List.of("Titan/Spawners/Dunewyrm_Poison_Cloud",
            "Titan/Crypt/Spawners/Crypt_Poison_Cloud_Field","Titan/Crypt/Spawners/Crypt_Poison_Fog",
            "Titan/Crypt/Spawners/Crypt_Poison_Telegraph")) {
            var spawner=BsonDocument.parse(Files.readString(RES.resolve("Server/Particles/"+path+".particlespawner")));
            assertTrue(spawner.getNumber("LifeSpan").doubleValue()>0);
            assertTrue(spawner.getNumber("LifeSpan").doubleValue()<=2.8);
            assertTrue(spawner.getDocument("TotalParticles").getNumber("Min").intValue()>0);
            assertTrue(spawner.getDocument("ParticleLifeSpan").getNumber("Max").doubleValue()<=2.2);
        }
        assertEquals("Dunewyrm_Poison_Cloud",com.hexvane.titan.dunewyrm.DunewyrmTuning.POISON_CLOUD_PARTICLE);
    }

    @Test void lingeringPoisonRisesAndFadesWithoutBursting() throws Exception {
        for(var path:java.util.List.of("Spawners/Dunewyrm_Poison_Cloud","Crypt/Spawners/Crypt_Poison_Fog")) {
            var s=BsonDocument.parse(Files.readString(RES.resolve("Server/Particles/Titan/"+path+".particlespawner")));
            assertFalse(s.getBoolean("SpawnBurst").getValue());
            assertTrue(s.getNumber("MaxConcurrentParticles").intValue()<=3);
            assertTrue(s.getArray("Attractors").getFirst().asDocument().getDocument("LinearAcceleration").getNumber("Y").doubleValue()>0);
            var animation=s.getDocument("Particle").getDocument("Animation");
            assertEquals(0,animation.getDocument("0").getNumber("Opacity").doubleValue());
            assertEquals(0,animation.getDocument("100").getNumber("Opacity").doubleValue());
        }
    }

    @Test void bodyModelsFitClientNodeBudgetAndCollisionModelsDrawNothing() throws Exception {
        var batches = new HashSet<String>();
        for (var row : Files.readAllLines(RES.resolve("TitanMeshes/BodyVisuals.tsv"))) {
        var fields=row.split("\t");
        for (int i=1;i<fields.length;i++) assertTrue(batches.add(fields[i]),"Each original cuboid has exactly one death owner");
        var asset = BsonDocument.parse(Files.readString(RES.resolve("Server/Models/Titan/TempleMeshes/"+fields[0]+".json")));
        var model = BsonDocument.parse(Files.readString(RES.resolve("Common/"+asset.getString("Model").getValue())));
        assertEquals(1,model.getArray("nodes").size());
        assertEquals("off",model.getString("lod").getValue());
        var root = model.getArray("nodes").getFirst().asDocument();
        assertTrue(root.getArray("children").size()+1<=256,"Client shader node buffer includes the root; static flags do not waive the limit");
        assertEquals("none",root.getDocument("shape").getString("type").getValue());
        var ids = new HashSet<String>(); ids.add(root.getString("id").getValue());
        var texture = javax.imageio.ImageIO.read(RES.resolve("Common/"+asset.getString("Texture").getValue()).toFile());
        assertFalse(root.getArray("children").isEmpty());
        for (var child : root.getArray("children")) {
            var node=child.asDocument(); var shape=node.getDocument("shape");
            assertTrue(ids.add(node.getString("id").getValue()),"unique static node IDs");
            assertEquals("quad",shape.getString("type").getValue());
            assertTrue(node.getArray("children").isEmpty());
            String normal=shape.getDocument("settings").getString("normal").getValue();
            assertEquals("+Z",normal,"All surfaces must have nonzero local XY area before node rotation");
            var size=shape.getDocument("settings").getDocument("size");
            double w=size.getNumber("x").doubleValue()/32, h=size.getNumber("y").doubleValue()/32;
            assertTrue(w>=1 && h>=1 && w==Math.rint(w) && h==Math.rint(h));
            var rotation=node.getDocument("orientation");
            var q=new Quaterniond(rotation.getNumber("x").doubleValue(),rotation.getNumber("y").doubleValue(),
                rotation.getNumber("z").doubleValue(),rotation.getNumber("w").doubleValue());
            assertEquals(1,q.lengthSquared(),1e-9);
            var corners=new ArrayList<Vector3d>();
            for (double[] corner : new double[][]{{-w/2,h/2,0},{w/2,h/2,0},{w/2,-h/2,0},{-w/2,-h/2,0}})
                corners.add(q.transform(new Vector3d(corner[0],corner[1],corner[2])));
            double area=new Vector3d(corners.get(1)).sub(corners.get(0))
                .cross(new Vector3d(corners.get(3)).sub(corners.get(0))).length();
            assertEquals(w*h,area,1e-8,"No surface may collapse when transformed from its local plane");
            for (int axis=0;axis<3;axis++) {
                String lower="xyz".substring(axis,axis+1), upper=lower.toUpperCase(java.util.Locale.ROOT);
                assertEquals(2,shape.getDocument("stretch").getNumber(lower).doubleValue());
                double centre=node.getDocument("position").getNumber(lower).doubleValue()/64;
                for (var corner : corners) {
                    double coordinate=centre+corner.get(axis);
                    assertEquals(Math.rint(coordinate),coordinate,1e-8,"Rotated vertices stay on source block corners");
                    assertTrue(coordinate>=asset.getDocument("HitBox").getDocument("Min").getNumber(upper).doubleValue()-1e-8);
                    assertTrue(coordinate<=asset.getDocument("HitBox").getDocument("Max").getNumber(upper).doubleValue()+1e-8);
                }
            }
            var uv=shape.getDocument("textureLayout").getDocument("front").getDocument("offset");
            assertTrue(uv.getNumber("x").intValue()+w*32<=texture.getWidth());
            assertTrue(uv.getNumber("y").intValue()+h*32<=texture.getHeight());
        }
        }
        var expected=Files.readAllLines(RES.resolve("TitanMeshes/Body.tsv")).stream().skip(1)
            .map(row -> row.split("\t")[6]).collect(java.util.stream.Collectors.toSet());
        assertEquals(expected,batches,"Every collision cuboid's blocks are restored exactly once on death");
        var collision=BsonDocument.parse(Files.readString(RES.resolve("Common/VFX/Titan/TempleMeshes/BodyEntities/Titan_Temple_Collision.blockymodel")));
        var empty=collision.getArray("nodes").getFirst().asDocument();
        assertEquals("none",empty.getDocument("shape").getString("type").getValue());
        assertTrue(empty.getArray("children").isEmpty(),"collision model has no renderable geometry");
    }

    @Test void templeMeshesFitClientBoundsAndCoverExactlyTheirOriginalCells() throws Exception {
        var atlases = new HashMap<Path, java.awt.image.BufferedImage>();
        for (var entry : Files.readAllLines(RES.resolve("TitanMeshes/index.tsv"))) {
            var spec = entry.split("\t");
            String section = spec[0];
            boolean yaga = section.equals("Yaga_Baba");
            String prefab = spec[1];
            int minY = Integer.parseInt(spec[2]), maxY = Integer.parseInt(spec[3]);
            var source = BsonDocument.parse(Files.readString(RES.resolve("Server/Prefabs/Titan/"+prefab+".prefab.json")));
            var cells = new HashMap<String, PrefabVoxels.Voxel>();
            for (var raw : source.getArray("blocks")) {
                var b = raw.asDocument();
                int x = b.getNumber("x").intValue(), y = b.getNumber("y").intValue(), z = b.getNumber("z").intValue();
                if (y < minY || y > maxY || b.containsKey("filler") || b.getString("name").getValue().equals("Empty")) continue;
                int rotation = b.containsKey("rotation") ? b.getNumber("rotation").intValue() : 0;
                if (yaga) {
                    int oldX = x; x = z; z = -oldX;
                    rotation = com.hypixel.hytale.server.core.prefab.PrefabRotation.ROTATION_90.getRotation(rotation);
                }
                var v = new PrefabVoxels.Voxel(x,y,z,b.getString("name").getValue(),rotation,true,true);
                cells.put(x+","+y+","+z, v);
            }
            var manifest = Files.readAllLines(RES.resolve("TitanMeshes/"+section+".tsv"));
            assertEquals(TitanMeshBatches.signature(new ArrayList<>(cells.values())), manifest.getFirst());
            var covered = new HashSet<String>();
            for (var line : manifest.subList(1, manifest.size())) {
                var f = line.split("\t");
                int x = Integer.parseInt(f[0]), y = Integer.parseInt(f[1]), z = Integer.parseInt(f[2]);
                int sx = Integer.parseInt(f[3]), sy = Integer.parseInt(f[4]), sz = Integer.parseInt(f[5]);
                int scale = Math.max(sx, Math.max(sy, sz));
                for (int dy = 0; dy < sy; dy++) for (int dz = 0; dz < sz; dz++) for (int dx = 0; dx < sx; dx++) {
                    String key = (x+dx)+","+(y+dy)+","+(z+dz);
                    assertNotNull(cells.get(key), "must not fill openings: "+key);
                    assertTrue(yaga ? cells.get(key).rotation() < 4 : cells.get(key).rotation() == 0,
                        "only supported block rotations may be baked");
                    assertTrue(covered.add(key), "overlapping batches at "+key);
                }
                var item = BsonDocument.parse(Files.readString(RES.resolve("Server/Item/Items/Titan/TempleMeshes/"+f[6]+".json"))).getDocument("BlockType");
                var boxes = BsonDocument.parse(Files.readString(RES.resolve("Server/Item/Block/Hitboxes/Titan/TempleMeshes/"
                    +item.getString("HitboxType").getValue()+".json"))).getArray("Boxes");
                assertEquals(1, boxes.size());
                var bounds = boxes.getFirst().asDocument();
                var model = BsonDocument.parse(Files.readString(RES.resolve("Common/"+item.getString("CustomModel").getValue())));
                assertEquals("off", model.getString("lod").getValue());
                assertEquals(1, model.getArray("nodes").size(), "one box, no internal cube faces");
                var node = model.getArray("nodes").getFirst().asDocument();
                var shape = node.getDocument("shape");
                boolean body = section.equals("Body") || yaga || section.startsWith("Talus_");
                assertEquals(body, shape.getBoolean("doubleSided").getValue(), "body surfaces remain visible from below");
                assertEquals(body ? "Transparent" : "Solid", item.getString("Opacity").getValue(),
                    "body patches must not act as full opaque cells");
                var dimensions = new int[]{sx,sy,sz};
                for (int axis = 0; axis < 3; axis++) {
                    String lower = "xyz".substring(axis,axis+1), upper = lower.toUpperCase(java.util.Locale.ROOT);
                    double size = shape.getDocument("settings").getDocument("size").getNumber(lower).doubleValue()
                        * shape.getDocument("stretch").getNumber(lower).doubleValue()/32;
                    double position = node.getDocument("position").getNumber(lower).doubleValue()/32;
                    double min = bounds.getDocument("Min").getNumber(upper).doubleValue();
                    double max = bounds.getDocument("Max").getNumber(upper).doubleValue();
                    assertTrue(min >= 0 && max <= 1, "client hitbox must fit unit bounds");
                    assertEquals(dimensions[axis], size*scale, 1e-8, "world geometry size");
                    assertEquals(dimensions[axis], (max-min)*scale, 1e-8, "client collision size");
                    double originOffset = axis == 1 ? 0 : .5;
                    assertEquals(min, position-size*.5+originOffset, 1e-8, "mesh minimum matches client bounds");
                    assertEquals(max, position+size*.5+originOffset, 1e-8, "mesh maximum matches client bounds");
                }
                var texture = RES.resolve("Common/"+item.getArray("CustomModelTexture").getFirst().asDocument().getString("Texture").getValue());
                var image = atlases.get(texture);
                if (image == null) {
                    image = javax.imageio.ImageIO.read(texture.toFile());
                    atlases.put(texture, image);
                }
                for (var face : shape.getDocument("textureLayout").entrySet()) {
                    var uv = face.getValue().asDocument().getDocument("offset");
                    int u = uv.getNumber("x").intValue(), v = uv.getNumber("y").intValue();
                    int width = (face.getKey().equals("left") || face.getKey().equals("right") ? sz : sx)*32;
                    int height = (face.getKey().equals("top") || face.getKey().equals("bottom") ? sz : sy)*32;
                    assertTrue(u >= 2 && v >= 2 && u+width+2 <= image.getWidth() && v+height+2 <= image.getHeight());
                    // Atlas bleed/empty UVs look like missing geometry; every face is opaque, including its gutter.
                    for (int py = v-2; py < v+height+2; py++) for (int px = u-2; px < u+width+2; px++)
                        if (image.getRGB(px, py) >>> 24 != 255) fail(f[6]+" has transparent face pixels");
                }
            }
        }
    }
}
