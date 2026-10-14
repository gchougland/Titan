"""Independently compare the whole island's rendered surface and UVs with its source blocks."""
import argparse
import json
from functools import lru_cache
from pathlib import Path
import zipfile

RES = Path(__file__).resolve().parents[1] / "src/main/resources"
FACE = {(0,1):"right",(0,-1):"left",(1,1):"top",(1,-1):"bottom",(2,1):"front",(2,-1):"back"}
NATIVE = {"front":"South","back":"North","left":"West","right":"East","top":"Up","bottom":"Down"}


def validate(assets):
    atlas=json.loads((RES/"TitanMeshes/TempleAtlas.json").read_text())
    tiles={}
    for region in atlas["regions"]:
        assert (region["width"],region["height"])==(32,32), "the temple atlas holds 32x32 tiles only"
        for source in region["sources"]:
            assert source not in tiles, f"{source} is stored twice"
            tiles[source]=(region["x"],region["y"])
    with zipfile.ZipFile(assets) as archive:
        paths = {Path(p).stem:p for p in archive.namelist() if p.startswith("Server/Item/Items/") and p.endswith(".json")}
        @lru_cache(None)
        def block_type(key):
            raw=json.loads(archive.read(paths[key]))
            return {**(block_type(raw["Parent"]) if "Parent" in raw else {}), **raw.get("BlockType",{})}
        raw=json.loads((RES/"Server/Prefabs/Titan/Temple/Temple_Body.prefab.json").read_text())["blocks"]
        blocks={(b["x"],b["y"],b["z"]):b for b in raw if not b.get("filler") and b["name"]!="Empty"}
        opaque={p for p,b in blocks.items() if block_type(b["name"]).get("DrawType")=="Cube"
                and block_type(b["name"]).get("Opacity","Solid")=="Solid"}
        def source(cell, face):
            """The texture a source block shows on a face, named as the atlas names it."""
            t=block_type(blocks[cell]["name"]);textures=t["Textures"][0];native=NATIVE[face]
            path=textures.get(native,textures.get("UpDown" if native in ("Up","Down") else "Sides",textures.get("All")))
            tint=t.get("Tint"+native,t.get("Tint"))
            return f"{path} tint {tint[0]}" if tint else path
        # What every open face of every batched block must show.
        expected={};cuboids={}
        for row in (RES/"TitanMeshes/Body.tsv").read_text().splitlines()[1:]:
            x,y,z,sx,sy,sz,key=row.split("\t")
            x,y,z,sx,sy,sz=map(int,(x,y,z,sx,sy,sz))
            low=(x,y,z); high=(x+sx,y+sy,z+sz); cuboids[key]=(low,high)
            for i in range(x,x+sx):
                for j in range(y,y+sy):
                    for k in range(z,z+sz):
                        cell=(i,j,k)
                        assert not blocks[cell].get("rotation"), "the island's cuboids hold unrotated blocks only"
                        for axis in range(3):
                            for sign in (-1,1):
                                neighbour=list(cell);neighbour[axis]+=sign
                                if tuple(neighbour) in opaque: continue
                                assert cell[axis]==(high[axis]-1 if sign>0 else low[axis])
                                expected[cell,axis,sign]=tiles[source(cell,FACE[axis,sign])]
    def faces(node, cell):
        shape=node["shape"]
        assert shape["type"]=="box" and all(shape["settings"]["size"][a]==32 for a in "xyz"), "one 32-unit box per source block"
        assert all(shape["offset"][a]==0 for a in "xyz")
        assert all(q==v for q,v in zip((node["orientation"][a] for a in "xyzw"),(0,0,0,1)))
        for (axis,sign),face in FACE.items():
            if face not in shape["textureLayout"]: continue
            uv=shape["textureLayout"][face]
            assert uv["angle"]==0 and not uv["mirror"]["x"] and not uv["mirror"]["y"], "unrotated blocks need no turned faces"
            yield (cell,axis,sign),(uv["offset"]["x"],uv["offset"]["y"])
    # The block items: each cuboid is cut into its cells, and every open face shows its block's tile.
    for key,(low,high) in cuboids.items():
        model=json.loads((RES/f"Common/VFX/Titan/TempleMeshes/{key}.blockymodel").read_text())
        extent=max(high[a]-low[a] for a in range(3));seen={}
        for node in model["nodes"][0]["children"]:
            assert all(node["shape"]["stretch"][a]==1/extent for a in "xyz")
            local=[node["position"][a]*extent/32+(high[i]-low[i])/2-.5 for i,a in enumerate("xyz")]
            assert all(abs(v-round(v))<1e-9 for v in local), "cell not aligned with its cuboid"
            cell=tuple(low[i]+round(v) for i,v in enumerate(local))
            assert all(low[i]<=cell[i]<high[i] for i in range(3))
            for face_key,uv in faces(node,cell):
                assert face_key not in seen;seen[face_key]=uv
        for face_key,uv in expected.items():
            if all(low[i]<=face_key[0][i]<high[i] for i in range(3)):
                assert seen.get(face_key)==uv, f"{key} shows the wrong tile on {face_key}"
    # The entity models the island is really drawn with.
    nodes=[];covered=set();model_count=0
    for row in (RES/"TitanMeshes/BodyVisuals.tsv").read_text().splitlines():
        ident,*batches=row.split("\t")
        assert not covered.intersection(batches), "duplicate death block ownership"
        covered.update(batches)
        asset=json.loads((RES/f"Server/Models/Titan/TempleMeshes/{ident}.json").read_text())
        assert asset["Texture"]==atlas["texture"]
        model=json.loads((RES/"Common"/asset["Model"]).read_text())
        assert len(model["nodes"])==1
        children=model["nodes"][0]["children"]
        assert 0<len(children)<=255, "Client node buffer budget exceeded (root also needs one node)"
        owned={cell for batch in batches for cell in (
            (i,j,k) for i in range(cuboids[batch][0][0],cuboids[batch][1][0])
            for j in range(cuboids[batch][0][1],cuboids[batch][1][1]) for k in range(cuboids[batch][0][2],cuboids[batch][1][2]))}
        for node in children:
            assert all(node["shape"]["stretch"][a]==2 for a in "xyz")
            centre=[node["position"][a]/64-.5 for a in "xyz"]
            assert all(abs(v-round(v))<1e-9 for v in centre), "box not aligned with source cells"
            assert tuple(map(round,centre)) in owned, "a block is drawn by a model that does not own it"
        nodes.extend(children);model_count+=1
    assert covered==set(cuboids)
    actual={}; duplicates=0
    for node in nodes:
        cell=tuple(round(node["position"][a]/64-.5) for a in "xyz")
        for face_key,uv in faces(node,cell):
            duplicates+=face_key in actual
            actual[face_key]=uv
    assert duplicates==0, f"{duplicates} duplicated face cells"
    assert actual.keys()==expected.keys(), f"missing={len(expected.keys()-actual.keys())} extra/internal={len(actual.keys()-expected.keys())}"
    assert actual==expected, "an exposed source block shows the wrong tile"
    upward=sum(axis==1 and sign==1 for _,axis,sign in actual)
    print(f"PASS: {model_count} body models within 256 nodes each, {len(nodes)} block boxes, {len(actual)} exterior cell faces including {upward} upward faces; no missing/internal/duplicate faces; every face shows its own block's tile; exact death block ownership")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets",required=True,type=Path)
    validate(parser.parse_args().assets)
