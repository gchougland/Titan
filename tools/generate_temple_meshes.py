"""Bake temple, Baba Yaga, and Talus cuboids into combined meshes.

Geometry stays inside the block renderer's unit envelope. Entity scale supplies
the size in the world, and an explicit client hitbox describes the same volume.
The temple body also gets full-sized entity models at 64 units per block, with
centred explicit bounds, to avoid the moving-block renderer's distance path.
Only full opaque cubes with supported yaw rotations are eligible. No opening is filled.

The atlas holds one 32x32 tile per block face texture in use, and nothing else.
A face cannot repeat a tile, so every cell of a cuboid is its own box whose faces
point at those tiles; a turned top or bottom is a face angle, not a second tile.
Native material tints are baked, one tile per texture and tint: a model has no
per-face tint, and one cuboid can hold blocks with different tints.
Run with Pillow and --assets pointing at the installed game's Assets.zip.
"""
import argparse
import hashlib
import io
import json
import math
from functools import lru_cache
from pathlib import Path
import zipfile

from PIL import Image, ImageChops, ImageColor

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/main/resources"
COMMON = RES / "Common/VFX/Titan/TempleMeshes"
ITEMS = RES / "Server/Item/Items/Titan/TempleMeshes"
BOXES = RES / "Server/Item/Block/Hitboxes/Titan/TempleMeshes"
ENTITY_MODELS = RES / "Server/Models/Titan/TempleMeshes"
MANIFESTS = RES / "TitanMeshes"
ATLAS_TEXTURE = "VFX/Titan/TempleMeshes/Atlas_0.png"
FACES = {"front": "South", "back": "North", "left": "West",
         "right": "East", "top": "Up", "bottom": "Down"}
LIMIT = 4
TILE = 32
# The client's node uniform buffer has a 256-node fallback. Include the root
# in this budget, even for static models; isStaticBox is an editor hint.
MODEL_NODE_LIMIT = 256
WRITTEN = set()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, separators=(",", ":")) + "\n", encoding="utf-8")
    WRITTEN.add(path)


def merge_dict(parent, child):
    result = dict(parent)
    for key, value in child.items():
        result[key] = merge_dict(result.get(key, {}), value) if isinstance(value, dict) else value
    return result


def signature(blocks):
    return hashlib.sha256("".join(
        f"{b['x']},{b['y']},{b['z']},{b['name']},{b.get('rotation', 0)}\n"
        for b in sorted(blocks, key=lambda b: (b['x'], b['y'], b['z']))).encode()).hexdigest()


class TileAtlas:
    """One copy of each distinct image, on the client's 32-pixel grid, in one small page.

    Images are identified by their pixels, so the same tile reached through two
    block types is stored once. The page size is only known when every image has
    been seen: faces carry an image handle until layout(), and resolve() turns
    the handles into pixel offsets. There are no gutters; tiles sit edge to edge
    the way the faces of a vanilla model texture do.
    """

    def __init__(self):
        self.images = []
        self.sources = []
        self.handles = {}
        self.positions = None
        self.size = None

    def add(self, image, source):
        if image.width % TILE or image.height % TILE:
            padded = Image.new("RGBA", (math.ceil(image.width/TILE)*TILE, math.ceil(image.height/TILE)*TILE))
            padded.paste(image)
            image = padded
        key = (image.size, image.tobytes())
        handle = self.handles.get(key)
        if handle is None:
            assert self.positions is None, "The page has already been laid out"
            handle = self.handles[key] = len(self.images)
            self.images.append(image)
            self.sources.append([])
        if source not in self.sources[handle]:
            self.sources[handle].append(source)
        return handle

    def layout(self):
        """Smallest page, no more than twice as long as it is wide where the images allow."""
        cells = [(im.width//TILE, im.height//TILE) for im in self.images]
        order = sorted(range(len(cells)), key=lambda i: (-cells[i][1], -cells[i][0], i))
        area = sum(w*h for w, h in cells)
        widest = max(w for w, _ in cells)
        best = None
        for columns in range(widest, max(widest, area)+1):
            taken = set()
            placed = {}
            rows = 0
            for i in order:
                w, h = cells[i]
                y = 0
                while i not in placed:
                    for x in range(columns-w+1):
                        if not any((x+dx, y+dy) in taken for dx in range(w) for dy in range(h)):
                            taken.update((x+dx, y+dy) for dx in range(w) for dy in range(h))
                            placed[i] = (x*TILE, y*TILE)
                            rows = max(rows, y+h)
                            break
                    y += 1
            square = max(columns, rows) <= 2*min(columns, rows)
            score = (not square, columns*rows, abs(columns-rows), columns)
            if best is None or score < best[0]:
                best = (score, columns, rows, placed)
            if rows*2 < columns:
                break
        _, columns, rows, placed = best
        self.size = (columns*TILE, rows*TILE)
        self.positions = [placed[i] for i in range(len(cells))]

    def resolve(self, nodes):
        for node in nodes:
            for uv in node["shape"].get("textureLayout", {}).values():
                if "image" in uv:
                    x, y = self.positions[uv.pop("image")]
                    uv["offset"] = {"x": uv["offset"]["x"]+x, "y": uv["offset"]["y"]+y}
            self.resolve(node.get("children", []))

    def save(self, path):
        page = Image.new("RGBA", self.size)
        for image, position in zip(self.images, self.positions):
            page.paste(image, position)
        # Cells no image uses must not be transparent: the client builds mipmaps
        # of the entire page, and a clear cell would thin out the solid tiles
        # beside it. Use the page's average colour there.
        used = {(x+dx, y+dy) for image, (x, y) in zip(self.images, self.positions)
                for dx in range(0, image.width, TILE) for dy in range(0, image.height, TILE)}
        opaque = [p for p in page.getdata() if p[3] == 255]
        background = tuple(sum(p[c] for p in opaque)//len(opaque) for c in range(3)) + (255,)
        for y in range(0, self.size[1], TILE):
            for x in range(0, self.size[0], TILE):
                if (x, y) not in used:
                    page.paste(background, (x, y, x+TILE, y+TILE))
        path.parent.mkdir(parents=True, exist_ok=True)
        page.save(path, optimize=True)
        return page

    def manifest(self, texture):
        return {"texture": texture, "width": self.size[0], "height": self.size[1], "regions": [
            {"x": x, "y": y, "width": image.width, "height": image.height, "sources": sources}
            for image, (x, y), sources in zip(self.images, self.positions, self.sources)]}


def tile_layout(handle, angle=0):
    """Face layout that shows one whole tile, turned by `angle`.

    The offset is the texel under the face's top-left corner, so a turned face
    starts at another corner of the same tile. Angle 90 shows what
    Image.rotate(90) of the tile would. The page position is added by resolve().
    """
    x, y = {0: (0, 0), 90: (TILE, 0), 180: (TILE, TILE), 270: (0, TILE)}[angle]
    return {"offset": {"x": x, "y": y}, "mirror": {"x": False, "y": False}, "angle": angle, "image": handle}


def empty_shape():
    return {"type": "none", "offset": dict.fromkeys(("x", "y", "z"), 0), "stretch": dict.fromkeys(("x", "y", "z"), 1),
            "settings": {}, "textureLayout": {}, "visible": True, "doubleSided": False,
            "shadingMode": "standard", "unwrapMode": "custom"}


def cell_box(name, centre, stretch, layouts, double_sided):
    """One source block: a 32-unit box that draws only the listed faces."""
    return {"id": "0", "name": name, "position": dict(zip(("x", "y", "z"), centre)),
            "orientation": {"x": 0, "y": 0, "z": 0, "w": 1}, "children": [],
            "shape": {"type": "box", "offset": dict.fromkeys(("x", "y", "z"), 0),
                      "stretch": dict.fromkeys(("x", "y", "z"), stretch),
                      "settings": {"size": dict.fromkeys(("x", "y", "z"), TILE), "isStaticBox": True},
                      "visible": True, "doubleSided": double_sided, "shadingMode": "standard",
                      "unwrapMode": "custom", "textureLayout": layouts}}


def generate(archive):
    paths = {Path(p).stem: p for p in archive.namelist()
             if p.startswith("Server/Item/Items/") and p.endswith(".json")}

    @lru_cache(None)
    def material(key):
        if key.startswith("*") and "_State_Definitions_" in key:
            base, state = key[1:].split("_State_Definitions_", 1)
            parent = material(base)
            return merge_dict(parent, {"BlockType": parent["BlockType"]["State"]["Definitions"][state]})
        raw = json.loads(archive.read(paths[key]))
        return merge_dict(material(raw["Parent"]), raw) if "Parent" in raw else raw

    def eligible(b):
        t = material(b["name"]).get("BlockType", {})
        return (b.get("rotation", 0) < 4 and t.get("DrawType") == "Cube"
                and t.get("Opacity", "Solid") == "Solid" and bool(t.get("Textures")))

    def occludes(b):
        t = material(b["name"]).get("BlockType", {})
        return t.get("DrawType") == "Cube" and t.get("Opacity", "Solid") == "Solid"

    atlas = TileAtlas()
    models = []

    @lru_cache(None)
    def tile(key, face):
        t = material(key)["BlockType"]
        textures = t["Textures"][0]
        path = textures.get(face, textures.get("UpDown" if face in ("Up", "Down") else "Sides", textures.get("All")))
        im = Image.open(io.BytesIO(archive.read("Common/" + path))).convert("RGBA")
        im = im.resize((TILE, TILE), Image.Resampling.NEAREST)
        tint = t.get("Tint" + face, t.get("Tint"))
        if tint:
            im = ImageChops.multiply(im, Image.new("RGBA", im.size, ImageColor.getrgb(tint[0]) + (255,)))
        assert im.getextrema()[3] == (255, 255), (key, face, "nonopaque texture")
        return atlas.add(im, f"{path} tint {tint[0]}" if tint else path)

    def face_tile(block, face):
        """The tile a block shows on a world face, and how far it is turned, for its yaw."""
        yaw = block.get("rotation", 0)
        if face in ("Up", "Down"):
            return tile(block["name"], face), (90 if face == "Up" else -90)*yaw % 360
        sides = ["South", "East", "North", "West"]
        return tile(block["name"], sides[(sides.index(face)-yaw) % 4]), 0

    stats = []
    sections = [
        ("Body", "Temple/Temple_Body", -2147483648, 2147483647, 1, 0, False),
        ("Foot", "Temple/Temple_Leg", 0, 4, 4, 0, False), ("Calf", "Temple/Temple_Leg", 5, 14, 4, 0, False),
        ("Thigh", "Temple/Temple_Leg", 15, 24, 4, 0, False),
        ("Yaga_Baba", "Yaga/Yaga_Baba_Body", -2147483648, 2147483647, 1, 1, False)]
    for path in sorted((RES / "Server/Prefabs/Titan/Talus").glob("*.prefab.json")):
        name = path.name.removesuffix(".prefab.json")
        sections.append((name, "Talus/"+name, -2147483648, 2147483647, 1, 0, False))
        if name.startswith(("Talus_Hand", "Talus_Foot")):
            sections.append((name+"_Mirrored", "Talus/"+name, -2147483648, 2147483647, 1, 0, True))
    for label, prefab, ymin, ymax, copies, yaw, mirror in sections:
        talus = label.startswith("Talus_")
        raw = json.loads((RES / f"Server/Prefabs/Titan/{prefab}.prefab.json").read_text())["blocks"]
        blocks = [b for b in raw if not b.get("filler") and b["name"] != "Empty" and ymin <= b["y"] <= ymax]
        if yaw:
            # PrefabRotation.ROTATION_90 rotates cell coordinates about integer zero,
            # then rotates each block about its own centre. These are not interchangeable.
            blocks = [dict(b, x=b["z"], z=-b["x"], rotation=(b.get("rotation", 0)//4)*4+(b.get("rotation", 0)+1)%4) for b in blocks]
        pos = lambda b: (b["x"], b["y"], b["z"])
        cells = {pos(b): b for b in blocks}
        occupied = set(cells)
        opaque = {pos(b) for b in blocks if occludes(b)}
        directions = [(1,0,0), (-1,0,0), (0,1,0), (0,-1,0), (0,0,1), (0,0,-1)]
        surface = {p for p in cells if any(tuple(a+b for a,b in zip(p,d)) not in opaque for d in directions)}
        available = {pos(b) for b in blocks if eligible(b) and (yaw or not b.get("rotation"))}
        used = set()
        rows = []
        saved = 0
        original_faces = 0
        cell_nodes = 0
        tile_faces = 0
        visual_faces = 0
        body_batches = []
        body_bounds = [float("inf")]*3 + [float("-inf")]*3
        # The body's open surface must remain visible from below.
        # Keep the already verified limb rendering unchanged.
        double_sided = label in ("Body", "Yaga_Baba") or talus
        for origin in sorted(available, key=lambda p: (p[1], p[2], p[0])):
            if origin in used:
                continue
            x, y, z = origin
            dims = [1, 1, 1]
            def members(s):
                return {(x+i, y+j, z+k) for i in range(s[0]) for j in range(s[1]) for k in range(s[2])}
            standable = lambda p: (p[0], p[1]+1, p[2]) not in occupied
            for axis in (0, 2, 1):
                while dims[axis] < LIMIT:
                    bigger = dims.copy()
                    bigger[axis] += 1
                    trial = members(bigger)
                    if not trial <= available or trial & used:
                        break
                    # Preserve AUTO/TOP collider selection on Talus bones exactly:
                    # no box may swallow both a foothold and a buried block.
                    if label.startswith("Talus_Body") and any(standable(p) != standable(origin) for p in trial):
                        break
                    dims = bigger
            volume = members(dims)
            used.update(volume)
            visible = volume & surface
            if (len(volume) < 2 and label != "Body") or (not visible and not talus):
                continue
            sx, sy, sz = dims
            # A face of the cuboid is drawn whole when any cell on it is open to
            # the air. Tile axes match the official Blockbench cube UV conventions.
            drawn = {}
            open_faces = set()
            for face in FACES:
                w, h = (sx, sz) if face in ("top", "bottom") else ((sz, sy) if face in ("left", "right") else (sx, sy))
                tiles = {}
                exposed = False
                for v in range(h):
                    for u in range(w):
                        p = {"front": (x+u, y+sy-1-v, z+sz-1),
                             "back": (x+sx-1-u, y+sy-1-v, z),
                             "right": (x+sx-1, y+sy-1-v, z+sz-1-u),
                             "left": (x, y+sy-1-v, z+u),
                             "top": (x+u, y+sy-1, z+v),
                             "bottom": (x+u, y, z+sz-1-v)}[face]
                        sample = (x+sx-1-(p[0]-x),p[1],p[2]) if mirror else p
                        direction = {"front": (0,0,1), "back": (0,0,-1), "right": (1,0,0),
                                     "left": (-1,0,0), "top": (0,1,0), "bottom": (0,-1,0)}[face]
                        actual_direction = (-direction[0],direction[1],direction[2]) if mirror else direction
                        visible_cell = tuple(a+b for a,b in zip(sample,actual_direction)) not in opaque
                        exposed |= visible_cell
                        if visible_cell:
                            open_faces.add((p, face))
                        tiles[p] = face_tile(cells[sample], FACES[face])
                if talus and not visible:
                    exposed = True
                if exposed:
                    drawn[face] = tiles
            ident = f"Titan_Temple_{label}_{len(rows):04}"
            extent = max(dims)
            # One box per cell that touches a drawn face. Cells in the middle of
            # the cuboid have nothing to draw and get no node.
            children = []
            surfaces = []
            for p in sorted(volume, key=lambda p: (p[1], p[2], p[0])):
                faces = [face for face in FACES if p in drawn.get(face, ())]
                if not faces:
                    continue
                i, j, k = p[0]-x, p[1]-y, p[2]-z
                children.append(cell_box(f"Cell_{i}_{j}_{k}",
                    ((i+.5-sx/2)*TILE/extent, (j+.5-sy/2)*TILE/extent, (k+.5-sz/2)*TILE/extent), 1/extent,
                    {face: tile_layout(*drawn[face][p]) for face in faces}, double_sided))
                tile_faces += len(faces)
                # Moving body surfaces use the entity model path, not the block
                # renderer's distance-dependent representation. Entity art uses
                # 64 units per block instead of 32: double the stretch and author
                # the full world-sized geometry, with no compensating BlockUpdate
                # scale or half-block translation. Emit only the faces that are
                # open to the air: a buried face of one cuboid can coincide with
                # a face of its neighbour.
                faces = [face for face in faces if (p, face) in open_faces]
                if label == "Body" and faces:
                    surfaces.append(cell_box("Surface", tuple((c+.5)*64 for c in p), 2,
                        {face: tile_layout(*drawn[face][p]) for face in faces}, True))
                    visual_faces += len(faces)
            assert children and len(children)+1 <= MODEL_NODE_LIMIT
            for node_id, node in enumerate(children, 2):
                node["id"] = str(node_id)
            cell_nodes += len(children)
            models.append((COMMON / f"{ident}.blockymodel", {"format": "prop", "lod": "off", "nodes": [{
                "id": "1", "name": "Solid", "position": {"x": 0, "y": 16, "z": 0},
                "orientation": {"x": 0, "y": 0, "z": 0, "w": 1}, "shape": empty_shape(), "children": children}]}))
            if label == "Body":
                body_batches.append((ident, surfaces))
                for axis, (lo, hi) in enumerate(zip((x,y,z),(x+sx,y+sy,z+sz))):
                    body_bounds[axis] = min(body_bounds[axis],lo)
                    body_bounds[axis+3] = max(body_bounds[axis+3],hi)
            hitbox = "Titan_Temple_Box_" + "_".join(map(str, dims))
            write(BOXES / f"{hitbox}.json", {"Boxes": [{"Min": dict(zip(("X", "Y", "Z"), ((1-d/extent)/2 for d in dims))), "Max": dict(zip(("X", "Y", "Z"), ((1+d/extent)/2 for d in dims)))}]})
            # Variant keeps these out of the creative library: they exist only
            # because a block type has to be declared inside an item.
            write(ITEMS / f"{ident}.json", {"TranslationProperties": {"Name": "Temple stone"}, "Variant": True, "BlockType": {
                "Material": "Solid", "DrawType": "Model", "Opacity": "Transparent" if double_sided else "Solid", "HitboxType": hitbox,
                "CustomModel": f"VFX/Titan/TempleMeshes/{ident}.blockymodel",
                "CustomModelTexture": [{"Texture": ATLAS_TEXTURE, "Weight": 1}]}})
            rows.append("\t".join(map(str, (x,y,z,sx,sy,sz,ident))))
            saved += len(volume if talus else visible)-1
            original_faces += len(volume if talus else visible)*6
        MANIFESTS.mkdir(parents=True, exist_ok=True)
        (MANIFESTS / f"{label}.tsv").write_text(signature(blocks) + "\n" + "\n".join(rows) + "\n", encoding="utf-8")
        groups = []
        if label == "Body":
            nodes, batches = [], []
            for batch, surfaces in body_batches:
                assert 0 < len(surfaces) < MODEL_NODE_LIMIT
                if len(nodes)+len(surfaces)+1 > MODEL_NODE_LIMIT:
                    groups.append((nodes, batches))
                    nodes, batches = [], []
                nodes.extend(surfaces)
                batches.append(batch)
            if nodes:
                groups.append((nodes, batches))
            visual_manifest = []
            for index, (nodes, batches) in enumerate(groups):
                ident = f"Titan_Temple_Body_Combined_{index:02}"
                for node_id, node in enumerate(nodes, 2):
                    node["id"] = str(node_id)
                    node["name"] = f"Surface_{node_id}"
                assert len(nodes)+1 <= MODEL_NODE_LIMIT
                entity_path = f"VFX/Titan/TempleMeshes/BodyEntities/{ident}.blockymodel"
                models.append((RES / "Common" / entity_path, {"format": "character", "lod": "off", "nodes": [{
                    "id": "1", "name": "Solid", "position": dict.fromkeys(("x", "y", "z"), 0),
                    "orientation": {"x": 0, "y": 0, "z": 0, "w": 1}, "shape": empty_shape(), "children": nodes}]}))
                # Conservative island-wide bounds keep thin surface groups from
                # acquiring tiny visibility bounds. These are not colliders.
                write(ENTITY_MODELS / f"{ident}.json", {
                    "Model":entity_path,"Texture":ATLAS_TEXTURE,"MinScale":1,"MaxScale":1,
                    "HitBox":{"Min":dict(zip(("X","Y","Z"),body_bounds[:3])),"Max":dict(zip(("X","Y","Z"),body_bounds[3:]))}})
                visual_manifest.append("\t".join([ident]+batches))
            (MANIFESTS / "BodyVisuals.tsv").write_text("\n".join(visual_manifest)+"\n", encoding="utf-8")
            write(ENTITY_MODELS / "Titan_Temple_Collision.json", {
                "Model":"VFX/Titan/TempleMeshes/BodyEntities/Titan_Temple_Collision.blockymodel",
                "Texture":"Characters/Empty_Cube_Texture.png","MinScale":1,"MaxScale":1,
                "HitBox":{"Min":dict.fromkeys(("X","Y","Z"),-.5),"Max":dict.fromkeys(("X","Y","Z"),.5)}})
            write(COMMON / "BodyEntities/Titan_Temple_Collision.blockymodel", {"format": "character", "lod": "off", "nodes": [{
                "id": "1", "name": "Solid", "position": dict.fromkeys(("x", "y", "z"), 0),
                "orientation": {"x": 0, "y": 0, "z": 0, "w": 1}, "children": [], "shape": empty_shape()}]})
        original = len(blocks) if talus else len(surface)
        stats.append({"section": label, "copies": copies, "original": original, "batched": original-saved-(len(rows)-len(groups) if label=="Body" else 0),
                      "visual_models":len(groups) if label=="Body" else 0,
                      "visual_nodes":sum(len(nodes) for nodes, _ in groups),
                      "visual_faces":visual_faces,
                      "collision_only":len(rows) if label=="Body" else 0,
                      "boxes": len(rows), "old_cube_faces": original_faces,
                      "cell_nodes": cell_nodes, "tile_faces": tile_faces})
    (MANIFESTS / "index.tsv").write_text("\n".join("\t".join(map(str, (label,prefab,ymin,ymax,yaw,int(mirror))))
        for label,prefab,ymin,ymax,copies,yaw,mirror in sections)+"\n", encoding="utf-8")
    # Every model is complete; now the page can be as small as its tiles allow.
    atlas.layout()
    for path, model in models:
        atlas.resolve(model["nodes"])
        write(path, model)
    page = atlas.save(RES / "Common" / ATLAS_TEXTURE)
    assert page.getextrema()[3] == (255, 255), "Solid atlas must remain opaque at every mip level"
    write(MANIFESTS / "TempleAtlas.json", atlas.manifest(ATLAS_TEXTURE))
    # Remove only obsolete atlases owned by this generator, after writing replacements.
    for path in COMMON.glob("Atlas_*.png"):
        if int(path.stem.removeprefix("Atlas_")) >= 1:
            assert path.resolve().parent == COMMON.resolve()
            path.unlink()
    report = {"sections": stats, "atlas_pages": 1, "atlas_size": list(atlas.size), "atlas_tiles": len(atlas.images),
              "atlas_rgba_bytes_with_mipmaps": atlas.size[0]*atlas.size[1]*4*4//3}
    # Side vines were the only native models left across most of the island's
    # middle. Give them the same fixed LOD/unit envelope as the combined rock.
    # Their backing quads sit only 0.25/0.5 pixels off the wall in vanilla;
    # two pixels of separation avoids crossing it under independent entity sync.
    for key in ("Plant_Vine_Jungle", "Plant_Vine_Wall_Winter"):
        block = material(key)["BlockType"]
        model = json.loads(archive.read("Common/"+block["CustomModel"]))
        model["lod"] = "off"
        model["format"] = "prop"
        def normalize(node, root=False):
            p = node.get("position", {})
            node["position"] = {axis: p.get(axis,0)/4 + (12 if root and axis == "y" else .5 if root and axis == "z" else 0) for axis in ("x","y","z")}
            shape = node.get("shape", {})
            shape["offset"] = {axis: shape.get("offset",{}).get(axis,0)/4 for axis in ("x","y","z")}
            shape["stretch"] = {axis: shape.get("stretch",{}).get(axis,1)/4 for axis in ("x","y","z")}
            shape["doubleSided"] = True
            for child in node.get("children", []):
                normalize(child)
        for node in model["nodes"]:
            normalize(node, True)
        ident = "Titan_Temple_Detail_"+key
        write(COMMON / f"{ident}.blockymodel", model)
        # Variant is inherited, so it is stated here whatever the vanilla vine says.
        write(ITEMS / f"{ident}.json", {"Parent": key, "Variant": True, "BlockType": {
            "CustomModel": f"VFX/Titan/TempleMeshes/{ident}.blockymodel", "HitboxType": "Titan_Temple_Detail_Unit"}})
    write(BOXES / "Titan_Temple_Detail_Unit.json", {"Boxes": [{"Min": dict.fromkeys(("X","Y","Z"),.375), "Max": dict.fromkeys(("X","Y","Z"),.625)}]})
    for folder, pattern in ((COMMON,"Titan_Temple_*.blockymodel"), (ITEMS,"Titan_Temple_*.json"), (BOXES,"Titan_Temple_*.json"),
                            (COMMON / "BodyEntities", "Titan_Temple_*.blockymodel"), (ENTITY_MODELS,"Titan_Temple_*.json")):
        for path in folder.glob(pattern):
            if path not in WRITTEN:
                assert path.resolve().is_relative_to(RES.resolve())
                path.unlink()
                built = ROOT / "build/resources/main" / path.relative_to(RES)
                assert built.resolve().is_relative_to((ROOT / "build/resources/main").resolve())
                if built.is_file():
                    built.unlink()
    write(MANIFESTS / "metrics.json", report)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.assets) as archive:
        generate(archive)
