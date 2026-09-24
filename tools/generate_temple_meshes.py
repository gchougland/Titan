"""Bake temple, Baba Yaga, and Talus cuboids into combined meshes.

Geometry stays inside the block renderer's unit envelope. Entity scale supplies
the size in the world, and an explicit client hitbox describes the same volume.
The temple body also gets full-sized entity models at 64 units per block, with
centred explicit bounds, to avoid the moving-block renderer's distance path.
Only full opaque cubes with supported yaw rotations are eligible. No opening is filled. Textures
are face mosaics, not enlarged block textures; native material tints are baked.
Run with Pillow and --assets pointing at the installed game's Assets.zip.
"""
import argparse
import copy
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
FACES = {"front": "South", "back": "North", "left": "West",
         "right": "East", "top": "Up", "bottom": "Down"}
LIMIT = 4
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


def rectangles(mask):
    """Cover only exposed cells; a partially exposed cuboid face is not a full face."""
    remaining = set(mask)
    while remaining:
        u, v = min(remaining, key=lambda p: (p[1], p[0]))
        w = 1
        while (u+w, v) in remaining:
            w += 1
        h = 1
        while all((u+i, v+h) in remaining for i in range(w)):
            h += 1
        remaining.difference_update((u+i, v+j) for i in range(w) for j in range(h))
        yield u, v, w, h


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

    @lru_cache(None)
    def tile(key, face):
        t = material(key)["BlockType"]
        textures = t["Textures"][0]
        path = textures.get(face, textures.get("UpDown" if face in ("Up", "Down") else "Sides", textures.get("All")))
        im = Image.open(io.BytesIO(archive.read("Common/" + path))).convert("RGBA")
        im = im.resize((32, 32), Image.Resampling.NEAREST)
        tint = t.get("Tint" + face, t.get("Tint"))
        if tint:
            im = ImageChops.multiply(im, Image.new("RGBA", im.size, ImageColor.getrgb(tint[0]) + (255,)))
        assert im.getextrema()[3] == (255, 255), (key, face, "nonopaque texture")
        return im

    def rotated_tile(block, face):
        yaw = block.get("rotation", 0)
        if face in ("Up", "Down"):
            return tile(block["name"], face).rotate((90 if face == "Up" else -90)*yaw)
        sides = ["South", "East", "North", "West"]
        return tile(block["name"], sides[(sides.index(face)-yaw) % 4])

    # Shelf packing with shared face images, two-pixel extruded gutters, and a
    # separate atlas page only when necessary. All six faces of a box share a page.
    pages = []
    cache = {}
    page_size = 4096
    page = Image.new("RGBA", (page_size, page_size))
    rows_on_page = []
    used_height = 0

    def pack(images):
        nonlocal page, rows_on_page, used_height, cache
        placements = {}
        for name, im in sorted(images.items(), key=lambda e: (-e[1].height, -e[1].width)):
            key = (im.size, hashlib.sha256(im.tobytes()).digest())
            if key in cache:
                placements[name] = cache[key]
                continue
            w, h = im.width + 4, im.height + 4
            # Reuse space in earlier shelves instead of leaving every short face
            # in the tallest face's row. Models still use exactly one atlas page.
            suitable = [r for r in rows_on_page if r[1] >= h and r[2]+w <= page_size]
            row = min(suitable, key=lambda r: (r[1]-h, page_size-r[2]-w)) if suitable else None
            if row is None and used_height + h > page_size:
                pages.append(page)
                page = Image.new("RGBA", (page_size, page_size))
                rows_on_page = []
                used_height = 0
                cache = {}
                # A box needs one texture, so repeat its earlier faces on this page.
                return pack(images)
            if row is None:
                row = [used_height, h, 0]
                rows_on_page.append(row)
                used_height += h
            x, y = row[2] + 2, row[0] + 2
            page.paste(im, (x, y))
            page.paste(im.crop((0, 0, 1, im.height)).resize((2, im.height)), (x-2, y))
            page.paste(im.crop((im.width-1, 0, im.width, im.height)).resize((2, im.height)), (x+im.width, y))
            page.paste(page.crop((x-2, y, x+im.width+2, y+1)).resize((w, 2)), (x-2, y-2))
            page.paste(page.crop((x-2, y+im.height-1, x+im.width+2, y+im.height)).resize((w, 2)), (x-2, y+im.height))
            cache[key] = placements[name] = (x, y)
            row[2] += w
        return len(pages), placements

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
        new_faces = 0
        body_patches = []
        body_batches = []
        body_bounds = [float("inf")]*3 + [float("-inf")]*3
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
            images = {}
            exposed_cells = {}
            # Pixel axes match the official Blockbench cube UV conventions.
            for face in FACES:
                w, h = (sx, sz) if face in ("top", "bottom") else ((sz, sy) if face in ("left", "right") else (sx, sy))
                im = Image.new("RGBA", (w*32, h*32))
                exposed = False
                mask = set()
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
                            mask.add((u, v))
                        im.paste(rotated_tile(cells[sample], FACES[face]), (u*32, v*32))
                if talus and not visible:
                    exposed = True
                if exposed:
                    images[face] = im
                    exposed_cells[face] = mask
            atlas, offsets = pack(images)
            ident = f"Titan_Temple_{label}_{len(rows):04}"
            extent = max(dims)
            model = {"format": "prop", "lod": "off", "nodes": [{"id": "1", "name": "Solid",
                "position": {"x": 0, "y": 16, "z": 0}, "orientation": {"x": 0, "y": 0, "z": 0, "w": 1},
                "children": [], "shape": {"type": "box", "offset": {"x": 0, "y": 0, "z": 0},
                "stretch": dict.fromkeys(("x", "y", "z"), 1/extent),
                "settings": {"size": dict(zip(("x", "y", "z"), (sx*32, sy*32, sz*32))), "isStaticBox": True},
                # The body's open surface patches must remain visible from below.
                # Keep the already verified limb rendering unchanged.
                "visible": True, "doubleSided": label in ("Body", "Yaga_Baba") or talus, "shadingMode": "standard", "unwrapMode": "custom",
                "textureLayout": {f: {"offset": {"x": uv[0], "y": uv[1]}, "mirror": {"x": False, "y": False}, "angle": 0} for f, uv in offsets.items()}}}]}
            write(COMMON / f"{ident}.blockymodel", model)
            if label == "Body":
                # Moving body patches use the entity model path, not the block
                # renderer's distance-dependent representation. Entity art uses
                # 64 units per block instead of 32. Keep texture sizes/UVs exact;
                # double the stretch and author the full world-sized geometry.
                # No compensating BlockUpdate scale or half-block translation.
                # Emit only the exterior portions of each face. Drawing the
                # entire face when just one tile is exposed leaves buried,
                # sometimes coincident surfaces between neighbouring batches.
                patches = []
                # Author every quad in its XY plane, then rotate the node.
                # Axis-normal quads omit size.z; bounds readers that scale XYZ
                # before interpreting the normal collapse their X/Y faces to
                # lines. Explicit rotations give geometry and bounds the same
                # nondegenerate corners without adding any surfaces/entities.
                half = math.sqrt(.5)
                orientations = {"front":(0,0,0,1), "back":(0,1,0,0),
                    "left":(0,-half,0,half), "right":(0,half,0,half),
                    "top":(-half,0,0,half), "bottom":(half,0,0,half)}
                for face, mask in exposed_cells.items():
                    for u, v, w, h in rectangles(mask):
                        cu, cv = u+w/2, v+h/2
                        centre = {"front":(cu-sx/2, sy/2-cv, sz/2),
                                  "back":(sx/2-cu, sy/2-cv, -sz/2),
                                  "left":(-sx/2, sy/2-cv, cu-sz/2),
                                  "right":(sx/2, sy/2-cv, sz/2-cu),
                                  "top":(cu-sx/2, sy/2, cv-sz/2),
                                  "bottom":(cu-sx/2, -sy/2, sz/2-cv)}[face]
                        uv = offsets[face]
                        patches.append({"id":str(len(patches)+2), "name":face+"_"+str(len(patches)),
                            "position":dict(zip(("x","y","z"),(c*64 for c in centre))),
                            "orientation":dict(zip(("x","y","z","w"),orientations[face])), "children":[],
                            "shape":{"type":"quad", "offset":dict.fromkeys(("x","y","z"),0),
                                "stretch":dict.fromkeys(("x","y","z"),2),
                                "settings":{"size":{"x":w*32,"y":h*32},"normal":"+Z","isStaticBox":True},
                                "visible":True,"doubleSided":True,"shadingMode":"standard","unwrapMode":"custom",
                                "textureLayout":{"front":{"offset":{"x":uv[0]+u*32,"y":uv[1]+v*32},
                                    "mirror":{"x":False,"y":False},"angle":0}}}})
                assert atlas == 0, "The complete body must fit one atlas page"
                for patch in patches:
                    patch["id"] = str(len(body_patches)+2)
                    patch["name"] = "Surface_"+str(len(body_patches))
                    for axis, centre in zip(("x","y","z"),(x+sx/2,y+sy/2,z+sz/2)):
                        patch["position"][axis] += centre*64
                    body_patches.append(patch)
                body_batches.append((ident, patches))
                for axis, (lo, hi) in enumerate(zip((x,y,z),(x+sx,y+sy,z+sz))):
                    body_bounds[axis] = min(body_bounds[axis],lo)
                    body_bounds[axis+3] = max(body_bounds[axis+3],hi)
            hitbox = "Titan_Temple_Box_" + "_".join(map(str, dims))
            write(BOXES / f"{hitbox}.json", {"Boxes": [{"Min": dict(zip(("X", "Y", "Z"), ((1-d/extent)/2 for d in dims))), "Max": dict(zip(("X", "Y", "Z"), ((1+d/extent)/2 for d in dims)))}]})
            write(ITEMS / f"{ident}.json", {"TranslationProperties": {"Name": "Temple stone"}, "BlockType": {
                "Material": "Solid", "DrawType": "Model", "Opacity": "Transparent" if label in ("Body", "Yaga_Baba") or talus else "Solid", "HitboxType": hitbox,
                "CustomModel": f"VFX/Titan/TempleMeshes/{ident}.blockymodel",
                "CustomModelTexture": [{"Texture": f"VFX/Titan/TempleMeshes/Atlas_{atlas}.png", "Weight": 1}]}})
            rows.append("\t".join(map(str, (x,y,z,sx,sy,sz,ident))))
            saved += len(volume if talus else visible)-1
            original_faces += len(volume if talus else visible)*6
            new_faces += len(patches) if label == "Body" else len(images)
        MANIFESTS.mkdir(parents=True, exist_ok=True)
        (MANIFESTS / f"{label}.tsv").write_text(signature(blocks) + "\n" + "\n".join(rows) + "\n", encoding="utf-8")
        if label == "Body":
            entity_model = {"format":"character","lod":"off","nodes":[{
                "id":"1","name":"Solid","position":dict.fromkeys(("x","y","z"),0),
                "orientation":{"x":0,"y":0,"z":0,"w":1},"children":body_patches,
                "shape":{"type":"none","offset":dict.fromkeys(("x","y","z"),0),
                    "stretch":dict.fromkeys(("x","y","z"),1),"settings":{},"textureLayout":{},
                    "visible":True,"doubleSided":False,"shadingMode":"standard","unwrapMode":"custom"}}]}
            groups = []
            nodes, batches = [], []
            for batch, patches in body_batches:
                assert 0 < len(patches) < MODEL_NODE_LIMIT
                if len(nodes)+len(patches)+1 > MODEL_NODE_LIMIT:
                    groups.append((nodes, batches))
                    nodes, batches = [], []
                nodes.extend(patches)
                batches.append(batch)
            if nodes:
                groups.append((nodes, batches))
            visual_manifest = []
            for index, (nodes, batches) in enumerate(groups):
                ident = f"Titan_Temple_Body_Combined_{index:02}"
                part = copy.deepcopy(entity_model)
                part["nodes"][0]["children"] = copy.deepcopy(nodes)
                for node_id, node in enumerate(part["nodes"][0]["children"], 2):
                    node["id"] = str(node_id)
                assert len(nodes)+1 <= MODEL_NODE_LIMIT
                entity_path = f"VFX/Titan/TempleMeshes/BodyEntities/{ident}.blockymodel"
                write(RES / "Common" / entity_path, part)
                # Conservative island-wide bounds keep thin surface groups from
                # acquiring tiny visibility bounds. These are not colliders.
                write(ENTITY_MODELS / f"{ident}.json", {
                    "Model":entity_path,"Texture":"VFX/Titan/TempleMeshes/Atlas_0.png","MinScale":1,"MaxScale":1,
                    "HitBox":{"Min":dict(zip(("X","Y","Z"),body_bounds[:3])),"Max":dict(zip(("X","Y","Z"),body_bounds[3:]))}})
                visual_manifest.append("\t".join([ident]+batches))
            (MANIFESTS / "BodyVisuals.tsv").write_text("\n".join(visual_manifest)+"\n", encoding="utf-8")
            write(ENTITY_MODELS / "Titan_Temple_Collision.json", {
                "Model":"VFX/Titan/TempleMeshes/BodyEntities/Titan_Temple_Collision.blockymodel",
                "Texture":"Characters/Empty_Cube_Texture.png","MinScale":1,"MaxScale":1,
                "HitBox":{"Min":dict.fromkeys(("X","Y","Z"),-.5),"Max":dict.fromkeys(("X","Y","Z"),.5)}})
            invisible = copy.deepcopy(entity_model)
            invisible["nodes"][0]["children"] = []
            write(COMMON / "BodyEntities/Titan_Temple_Collision.blockymodel", invisible)
        original = len(blocks) if talus else len(surface)
        stats.append({"section": label, "copies": copies, "original": original, "batched": original-saved-(len(rows)-len(groups) if label=="Body" else 0),
                      "visual_models":len(groups) if label=="Body" else 0,
                      "collision_only":len(rows) if label=="Body" else 0,
                      "boxes": len(rows), "old_cube_faces": original_faces, "new_box_faces": new_faces})
    (MANIFESTS / "index.tsv").write_text("\n".join("\t".join(map(str, (label,prefab,ymin,ymax,yaw,int(mirror))))
        for label,prefab,ymin,ymax,copies,yaw,mirror in sections)+"\n", encoding="utf-8")
    pages.append(page.crop((0, 0, page_size, ((used_height + 31)//32)*32)))
    COMMON.mkdir(parents=True, exist_ok=True)
    for index, im in enumerate(pages):
        # Every source tile is opaque. Unused space must be opaque as well:
        # the client generates mipmaps for the entire atlas, so two pixels of
        # extrusion cannot stop transparent gaps bleeding into minified faces.
        # Keep the existing face/gutter pixels exact and use the page's average
        # material colour behind unused space instead of transparent black.
        background = im.resize((1, 1), Image.Resampling.BOX).getpixel((0, 0))[:3] + (255,)
        im = Image.alpha_composite(Image.new("RGBA", im.size, background), im)
        assert im.getextrema()[3] == (255, 255), "Solid atlas must remain opaque at every mip level"
        im.save(COMMON / f"Atlas_{index}.png", optimize=True)
    # Remove only obsolete atlases owned by this generator, after writing replacements.
    for path in COMMON.glob("Atlas_*.png"):
        if int(path.stem.removeprefix("Atlas_")) >= len(pages):
            assert path.resolve().parent == COMMON.resolve()
            path.unlink()
    report = {"sections": stats, "atlas_pages": len(pages),
              "atlas_rgba_bytes_with_mipmaps": sum(im.width*im.height for im in pages)*4*4//3}
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
        write(ITEMS / f"{ident}.json", {"Parent": key, "BlockType": {
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
