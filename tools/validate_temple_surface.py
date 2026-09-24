"""Independently compare the whole island's rendered surface and UVs with its source blocks."""
import argparse
from collections import Counter
import json
import math
from functools import lru_cache
from pathlib import Path
import zipfile

RES = Path(__file__).resolve().parents[1] / "src/main/resources"
FACE = {(0,1):"right",(0,-1):"left",(1,1):"top",(1,-1):"bottom",(2,1):"front",(2,-1):"back"}


def rotate(vector, quaternion):
    x,y,z,w = (quaternion[a] for a in "xyzw")
    assert math.isclose(x*x+y*y+z*z+w*w, 1, abs_tol=1e-9)
    vx,vy,vz = vector
    tx,ty,tz = 2*(y*vz-z*vy),2*(z*vx-x*vz),2*(x*vy-y*vx)
    return (vx+w*tx+y*tz-z*ty, vy+w*ty+z*tx-x*tz, vz+w*tz+x*ty-y*tx)


def face_uv(cell, low, high, axis, sign):
    x,y,z = cell
    return {(0,1):(high[2]-1-z,high[1]-1-y), (0,-1):(z-low[2],high[1]-1-y),
            (1,1):(x-low[0],z-low[2]), (1,-1):(x-low[0],high[2]-1-z),
            (2,1):(x-low[0],high[1]-1-y), (2,-1):(high[0]-1-x,high[1]-1-y)}[axis,sign]


def validate(assets):
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
    expected={}
    for row in (RES/"TitanMeshes/Body.tsv").read_text().splitlines()[1:]:
        x,y,z,sx,sy,sz,key=row.split("\t")
        x,y,z,sx,sy,sz=map(int,(x,y,z,sx,sy,sz))
        low=(x,y,z); high=(x+sx,y+sy,z+sz)
        model=json.loads((RES/f"Common/VFX/Titan/TempleMeshes/{key}.blockymodel").read_text())
        uv=model["nodes"][0]["shape"]["textureLayout"]
        for i in range(x,x+sx):
            for j in range(y,y+sy):
                for k in range(z,z+sz):
                    cell=(i,j,k)
                    for axis in range(3):
                        for sign in (-1,1):
                            neighbour=list(cell);neighbour[axis]+=sign
                            if tuple(neighbour) in opaque: continue
                            assert cell[axis]==(high[axis]-1 if sign>0 else low[axis])
                            base=uv[FACE[axis,sign]]["offset"]
                            u,v=face_uv(cell,low,high,axis,sign)
                            expected[cell,axis,sign]=(base["x"]+u*32,base["y"]+v*32)
    nodes=[];covered=set();model_count=0
    for row in (RES/"TitanMeshes/BodyVisuals.tsv").read_text().splitlines():
        ident,*batches=row.split("\t")
        assert not covered.intersection(batches), "duplicate death block ownership"
        covered.update(batches)
        asset=json.loads((RES/f"Server/Models/Titan/TempleMeshes/{ident}.json").read_text())
        model=json.loads((RES/"Common"/asset["Model"]).read_text())
        assert len(model["nodes"])==1
        children=model["nodes"][0]["children"]
        assert 0<len(children)<=255, "Client node buffer budget exceeded (root also needs one node)"
        nodes.extend(children);model_count+=1
    assert covered=={row.split("\t")[-1] for row in (RES/"TitanMeshes/Body.tsv").read_text().splitlines()[1:]}
    actual={}; duplicates=0
    for node in nodes:
        shape=node["shape"]; settings=shape["settings"]
        assert settings["normal"]=="+Z", "Use a nondegenerate XY quad and explicit node rotation"
        w=settings["size"]["x"]/32;h=settings["size"]["y"]/32
        assert all(shape["stretch"][a]==2 for a in "xyz")
        assert all(shape["offset"][a]==0 for a in "xyz")
        q=node["orientation"]
        normal=rotate((0,0,1),q)
        axis=max(range(3),key=lambda a:abs(normal[a]));sign=round(normal[axis])
        assert abs(sign)==1 and all(math.isclose(n, sign if a==axis else 0,abs_tol=1e-9) for a,n in enumerate(normal))
        centre=[node["position"][a]/64 for a in "xyz"]
        corners=[[centre[a]+r[a] for a in range(3)] for r in
                 (rotate((u*w/2,v*h/2,0),q) for u,v in ((-1,1),(1,1),(1,-1),(-1,-1)))]
        low=[min(c[a] for c in corners) for a in range(3)];high=[max(c[a] for c in corners) for a in range(3)]
        assert all(math.isclose(v,round(v),abs_tol=1e-9) for v in low+high), "face not aligned with source cells"
        low=list(map(round,low));high=list(map(round,high))
        assert math.isclose(math.prod(high[a]-low[a] for a in range(3) if a!=axis),w*h), "collapsed face"
        low[axis]-=sign>0; high[axis]=low[axis]+1
        base=shape["textureLayout"]["front"]["offset"]
        u_direction=rotate((1,0,0),q);v_direction=rotate((0,-1,0),q)
        for x in range(low[0],high[0]):
            for y in range(low[1],high[1]):
                for z in range(low[2],high[2]):
                    cell=(x,y,z);key=(cell,axis,sign)
                    duplicates+=key in actual
                    midpoint=[cell[a]+.5 if a!=axis else centre[a] for a in range(3)]
                    delta=[midpoint[a]-corners[0][a] for a in range(3)]
                    u=sum(delta[a]*u_direction[a] for a in range(3))-.5
                    v=sum(delta[a]*v_direction[a] for a in range(3))-.5
                    assert math.isclose(u,round(u),abs_tol=1e-9) and math.isclose(v,round(v),abs_tol=1e-9)
                    actual[key]=(base["x"]+round(u)*32,base["y"]+round(v)*32)
    assert duplicates==0, f"{duplicates} duplicated face cells"
    assert actual.keys()==expected.keys(), f"missing={len(expected.keys()-actual.keys())} extra/internal={len(actual.keys()-expected.keys())}"
    assert actual==expected, "UV placement changed on an exposed source block"
    upward=sum(axis==1 and sign==1 for _,axis,sign in actual)
    print(f"PASS: {model_count} body models within 256 nodes each, {len(nodes)} rotated surface rectangles, {len(actual)} exterior cell faces including {upward} upward faces; no collapsed/missing/internal/duplicate faces; exact UV placement and death block ownership")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets",required=True,type=Path)
    validate(parser.parse_args().assets)
