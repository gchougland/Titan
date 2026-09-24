"""Combine each bundled titan bone/segment's block visuals, without changing collision.

Run after generate_temple_meshes.py. Models share atlases and stay below the
client's 256 node budget. Interactive fixture models keep their native state.
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
from generate_temple_meshes import merge_dict, signature, FACES, MODEL_NODE_LIMIT

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/main/resources"
COMMON = RES / "Common/VFX/Titan/Parts"
MODELS = RES / "Server/Models/Titan/Parts"
IDENTITY = (0, 0, 0, 1)


def as_block_model(model, extent):
    """Same geometry in the native block renderer's 32-unit, bottom-centred frame."""
    result=copy.deepcopy(model);result["format"]="prop"
    def convert(node, root=False):
        for a in "xyz":
            node["position"][a]=node["position"].get(a,0)/(2*extent)+(16 if root and a=="y" else 0)
            node["shape"]["offset"][a]/=2*extent
            node["shape"]["stretch"][a]/=2*extent
        for child in node.get("children",[]):convert(child)
    for node in result["nodes"]:convert(node,True)
    return result


def mul(a, b):
    x,y,z,w=a; X,Y,Z,W=b
    return (w*X+x*W+y*Z-z*Y, w*Y-x*Z+y*W+z*X, w*Z+x*Y-y*X+z*W, w*W-x*X-y*Y-z*Z)


def rotate(v, q):
    return mul(mul(q, (*v,0)), (-q[0],-q[1],-q[2],q[3]))[:3]


def turn(index):
    # RotationTuple index = yaw + 4*pitch + 16*roll, YXZ order.
    a,b,c=((index%4)*math.pi/4, ((index//4)%4)*math.pi/4, ((index//16)%4)*math.pi/4)
    return mul(mul((0,math.sin(a),0,math.cos(a)),(math.sin(b),0,0,math.cos(b))),
               (0,0,math.sin(c),math.cos(c)))


def vec(value, default=0):
    return tuple(value.get(a,default) for a in "xyz")


def flatten_nodes(nodes, parent_pos=(0,0,0), parent_q=IDENTITY):
    """Match the Blockbench codec's child origin relative to the parent's shape offset."""
    for node in nodes:
        q=mul(parent_q,tuple(node.get("orientation",dict(zip("xyzw",IDENTITY)))[a] for a in "xyzw"))
        delta=rotate(vec(node.get("position",{})),parent_q)
        pos=tuple(parent_pos[i]+delta[i] for i in range(3))
        shape=node.get("shape",{})
        if shape.get("type","none")!="none" and shape.get("visible",True):yield pos,q,shape
        offset=rotate(vec(shape.get("offset",{})),q)
        child_origin=tuple(pos[i]+offset[i] for i in range(3))
        yield from flatten_nodes(node.get("children",[]),child_origin,q)


def write(path, data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,separators=(",",":"))+"\n",encoding="utf-8")


def uv_bounds(shape, face, uv):
    sx,sy,sz=vec(shape["settings"].get("size",{}))
    w,h=(sx,sy) if shape["type"]=="quad" else (sx,sz) if face in ("top","bottom") else (sz,sy) if face in ("left","right") else (sx,sy)
    mx=-1 if uv.get("mirror",{}).get("x") else 1
    my=-1 if uv.get("mirror",{}).get("y") else 1
    dx,dy={0:(w*mx,h*my),90:(-h*my,w*mx),180:(-w*mx,-h*my),270:(h*my,-w*mx)}[uv.get("angle",0)]
    x,y=uv["offset"]["x"],uv["offset"]["y"]
    return min(x,x+dx),min(y,y+dy),max(x,x+dx),max(y,y+dy)


def wrapped_crop(im, box):
    x,y,right,bottom=box
    result=Image.new("RGBA",(right-x,bottom-y))
    for yy in range((y//im.height)*im.height,bottom,im.height):
        for xx in range((x//im.width)*im.width,right,im.width):result.paste(im,(xx-x,yy-y))
    return result


class Atlas:
    def __init__(self):
        self.images=[];self.rows=[];self.heights=[];self.cache={}

    def pack(self, image):
        key=(image.size,hashlib.sha256(image.tobytes()).digest())
        if key in self.cache:return self.cache[key]
        w,h=image.width+4,image.height+4
        assert w<=4096 and h<=4096
        for page in range(len(self.images)+1):
            if page==len(self.images):
                # Keep unused pixels opaque so solid materials cannot fade into
                # empty atlas space in mipmaps. Actual foliage cutouts stay clear.
                self.images.append(Image.new("RGBA",(4096,4096),(91,100,69,255)));self.rows.append([]);self.heights.append(0)
            candidates=[r for r in self.rows[page] if r[1]>=h and r[2]+w<=4096]
            row=min(candidates,key=lambda r:r[1]-h) if candidates else None
            if row is None:
                if self.heights[page]+h>4096:continue
                row=[self.heights[page],h,0];self.rows[page].append(row);self.heights[page]+=h
            x,y=row[2]+2,row[0]+2;row[2]+=w
            target=self.images[page];target.paste(image,(x,y))
            target.paste(image.crop((0,0,1,image.height)).resize((2,image.height)),(x-2,y))
            target.paste(image.crop((image.width-1,0,image.width,image.height)).resize((2,image.height)),(x+image.width,y))
            target.paste(target.crop((x-2,y,x+image.width+2,y+1)).resize((w,2)),(x-2,y-2))
            target.paste(target.crop((x-2,y+image.height-1,x+image.width+2,y+image.height)).resize((w,2)),(x-2,y+image.height))
            result=(f"VFX/Titan/Parts/Atlas_{page}.png",x,y);self.cache[key]=result
            return result

    def save(self):
        for page,im in enumerate(self.images):
            im.crop((0,0,4096,math.ceil(self.heights[page]/32)*32)).save(COMMON/f"Atlas_{page}.png",optimize=True)


def generate(archive):
    paths={Path(p).stem:p for p in archive.namelist() if p.startswith("Server/Item/Items/") and p.endswith(".json")}
    paths.update({p.stem:p for p in (RES/"Server/Item/Items").rglob("*.json")})
    atlas=Atlas();written=set();manifests=[]

    def blob(path):
        local=RES/path
        return local.read_bytes() if local.exists() else archive.read(path)

    @lru_cache(None)
    def material(key):
        if key.startswith("*") and "_State_Definitions_" in key:
            base,state=key[1:].split("_State_Definitions_",1);parent=material(base)
            return merge_dict(parent,{"BlockType":parent["BlockType"]["State"]["Definitions"][state]})
        path=paths[key];raw=json.loads(path.read_bytes() if isinstance(path,Path) else archive.read(path))
        return merge_dict(material(raw["Parent"]),raw) if "Parent" in raw else raw

    def opaque(b):
        t=material(b["name"])["BlockType"]
        return t.get("DrawType")=="Cube" and t.get("Opacity","Solid")=="Solid"

    @lru_cache(None)
    def texture(path,tint,cube=False):
        im=Image.open(io.BytesIO(blob("Common/"+path))).convert("RGBA")
        if cube:im=im.resize((32,32),Image.Resampling.NEAREST)
        if tint:im=ImageChops.multiply(im,Image.new("RGBA",im.size,ImageColor.getrgb(tint)+(255,)))
        return atlas.pack(im)

    def layout(path,tint,cube=False):
        if path.startswith("VFX/Titan/TempleMeshes/Atlas_"):
            return path,0,0
        return texture(path,tint,cube)

    @lru_cache(None)
    def block_shapes(key):
        t=material(key)["BlockType"];result=[]
        if t.get("DrawType")=="Cube":
            layouts={};textures=t["Textures"][0];page=None
            for face,native in FACES.items():
                path=textures.get(native,textures.get("UpDown" if face in ("top","bottom") else "Sides",textures.get("All")))
                tint=(t.get("Tint"+native,t.get("Tint")) or [None])[0]
                tex,x,y=layout(path,tint,True)
                assert page is None or page==tex,"Cube texture crosses atlas pages"
                page=tex;layouts[face]={"offset":{"x":x,"y":y},"mirror":{"x":False,"y":False},"angle":0}
            shape={"type":"box","offset":dict.fromkeys("xyz",0),"stretch":dict.fromkeys("xyz",1),
                   "settings":{"size":dict.fromkeys("xyz",32),"isStaticBox":True},"textureLayout":layouts,
                   "visible":True,"doubleSided":False,"shadingMode":"standard","unwrapMode":"custom"}
            return [(page,(0,16,0),IDENTITY,shape,1)]
        assert t.get("DrawType")=="Model",(key,t.get("DrawType"))
        raw=json.loads(blob("Common/"+t["CustomModel"]))
        path=t["CustomModelTexture"][0]["Texture"]
        native_image=Image.open(io.BytesIO(blob("Common/"+path))).convert("RGBA")
        tint=(t.get("Tint") or [None])[0]
        if tint:native_image=ImageChops.multiply(native_image,Image.new("RGBA",native_image.size,ImageColor.getrgb(tint)+(255,)))
        image_size=native_image.size
        old_atlas=path.startswith("VFX/Titan/TempleMeshes/Atlas_")
        atlas_source=Image.open(io.BytesIO(blob("Common/"+path))).convert("RGBA") if old_atlas else None
        tex,ux,uy=layout(path,(t.get("Tint") or [None])[0])
        for pos,q,source_shape in flatten_nodes(raw["nodes"]):
            s=source_shape
            if s.get("type","none")!="none" and s.get("visible",True):
                s=copy.deepcopy(s)
                page=tex
                for face,uv in s.get("textureLayout",{}).items():
                    if old_atlas:
                        sx,sy,sz=vec(s["settings"]["size"])
                        w,h=(sx,sz) if face in ("top","bottom") else (sz,sy) if face in ("left","right") else (sx,sy)
                        x,y=uv["offset"]["x"],uv["offset"]["y"]
                        page,x,y=atlas.pack(atlas_source.crop((x,y,x+int(w),y+int(h))))
                        uv["offset"]={"x":x,"y":y}
                    else:
                        lo_x,lo_y,hi_x,hi_y=uv_bounds(s,face,uv)
                        if lo_x<0 or lo_y<0 or hi_x>image_size[0] or hi_y>image_size[1]:
                            # Vanilla models can repeat a small material tile far
                            # beyond its UV bounds. Bake that repetition before
                            # atlas packing; otherwise it samples other materials.
                            left,top=math.floor(lo_x),math.floor(lo_y)
                            crop=wrapped_crop(native_image,(left,top,max(left+1,math.ceil(hi_x)),max(top+1,math.ceil(hi_y))))
                            page,tx,ty=atlas.pack(crop)
                            uv["offset"]["x"]+=tx-left;uv["offset"]["y"]+=ty-top
                        else:
                            uv["offset"]["x"]+=ux;uv["offset"]["y"]+=uy
                result.append((page,pos,q,s,t.get("CustomModelScale",1)))
        return result

    # Use the already verified cuboid art where available; it stays independent
    # of the runtime collider mode or whether those colliders are combined.
    batches={}
    for row in (RES/"TitanMeshes/index.tsv").read_text().splitlines():
        label,*_=row.split("\t");lines=(RES/f"TitanMeshes/{label}.tsv").read_text().splitlines()
        mirror=label.endswith("_Mirrored")
        batches[lines[0],mirror]=[r.split("\t") for r in lines[1:]]

    configs={};usable_prefabs=set()
    for path in sorted((RES/"Server/Titan/Variants").glob("*.json")):
        variant=json.loads(path.read_text());skeleton=json.loads((RES/f"Server/Titan/Skeletons/{variant['Skeleton']}.json").read_text())
        for bone in skeleton["Bones"]:
            if not bone.get("Prefab"):continue
            prefab=bone["Prefab"]
            if variant.get("RockType") and (RES/f"Server/Prefabs/{prefab}_{variant['RockType']}.prefab.json").exists():prefab+="_"+variant["RockType"]
            if bone.get("Usable") and prefab.startswith("Titan/Yaga/"):usable_prefabs.add(prefab)
            spec=(prefab,bone.get("SliceMinY",-2147483648),bone.get("SliceMaxY",2147483647),bone.get("PrefabYaw",0)//90,
                  bone.get("MirrorX",False),bone.get("Hollow",False),1.0)
            configs[spec]={f["Block"] for f in variant.get("Fixtures",[])}
    for path in sorted((RES/"Server/Prefabs/Titan/Dunewyrm").glob("*.prefab.json")):
        if "Structure" in path.name:continue
        prefab="Titan/Dunewyrm/"+path.name.removesuffix(".prefab.json")
        for mirror in (False,True):
            configs[prefab,-2147483648,2147483647,0,mirror,True,.96 if path.stem.split('.')[0] in ("Dunewyrm_Head","Dunewyrm_Jaw","Dunewyrm_Tongue") else 1.0]=set()
    for serial,(spec,fixtures) in enumerate(configs.items()):
        prefab,ymin,ymax,yaw,mirror,hollow,voxel_scale=spec
        raw=json.loads((RES/f"Server/Prefabs/{prefab}.prefab.json").read_text())["blocks"]
        all_blocks=[b for b in raw if b["name"]!="Empty" and ymin<=b["y"]<=ymax]
        for _ in range(yaw):
            all_blocks=[dict(b,x=b["z"],z=-b["x"],rotation=(b.get("rotation",0)//4)*4+(b.get("rotation",0)+1)%4) for b in all_blocks]
        blocks=[b for b in all_blocks if not b.get("filler")];sig=signature(blocks)
        cell=lambda b:(b["x"],b["y"],b["z"])
        cells={cell(b):b for b in blocks};occluders={cell(b) for b in all_blocks if opaque(b)}
        surface={p for p in cells if any(tuple(p[i]+d[i] for i in range(3)) not in occluders for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)))}
        rendered=set(cells) if not hollow else surface
        used=set();pieces=[]
        temple_body=prefab=="Titan/Temple/Temple_Body"
        for row in batches.get((sig,mirror),[]):
            x,y,z,sx,sy,sz=map(int,row[:6]);key=row[6]
            volume={(i,j,k) for i in range(x,x+sx) for j in range(y,y+sy) for k in range(z,z+sz)}
            used.update(volume)
            if temple_body:continue # Keep the eight verified solid island models.
            originals=volume&rendered
            if originals:pieces.append((key,(x+sx/2,y+sy/2,z+sz/2),0,max(sx,sy,sz),originals))
        for p,b in cells.items():
            if p in used or p not in rendered or b["name"] in fixtures:continue
            key=b["name"];scale=1
            if temple_body and key in ("Plant_Vine_Jungle","Plant_Vine_Wall_Winter"):
                key="Titan_Temple_Detail_"+key;scale=4
            pieces.append((key,tuple(v+.5 for v in p),b.get("rotation",0),scale,{p}))
        by_texture={};bounds=[float("inf")]*3+[float("-inf")]*3
        for key,centre,rotation,scale,originals in pieces:
            qblock=turn(rotation);centre=((-centre[0] if mirror else centre[0]),centre[1],centre[2])
            owned=False
            for tex,pos,q,shape,custom_scale in block_shapes(key):
                # Reflection in this mod moves cells but does not reflect native
                # block geometry. Mirrored cuboid art already mirrors its tiles.
                local=rotate((pos[0]*custom_scale,(pos[1]*custom_scale-16),pos[2]*custom_scale),qblock)
                factor=2*scale*voxel_scale
                world_pos=tuple(centre[i]*64+local[i]*factor for i in range(3))
                shape=copy.deepcopy(shape)
                shape["offset"]={a:shape.get("offset",{}).get(a,0)*factor*custom_scale for a in "xyz"}
                shape["stretch"]={a:shape.get("stretch",{}).get(a,1)*factor*custom_scale for a in "xyz"}
                orientation=mul(qblock,q)
                node={"id":"0","name":"Surface","position":dict(zip("xyz",world_pos)),
                      "orientation":dict(zip("xyzw",orientation)),"shape":shape,"children":[]}
                by_texture.setdefault(tex,[]).append((node,originals if not owned else set()));owned=True
                # Conservative bounds include custom furniture/vines extending
                # outside a cell, rather than assuming all shapes are unit cubes.
                size=vec(shape["settings"].get("size",{}));normal=shape["settings"].get("normal","+Z")
                if shape["type"]=="quad":size=(0,size[1],size[0]) if normal.endswith("X") else (size[0],0,size[1]) if normal.endswith("Y") else (size[0],size[1],0)
                for xx in (-.5,.5):
                    for yy in (-.5,.5):
                        for zz in (-.5,.5):
                            corner=rotate(tuple(size[i]*shape["stretch"][a]*v+shape["offset"][a] for i,(a,v) in enumerate(zip("xyz",(xx,yy,zz)))),orientation)
                            for i in range(3):
                                value=(world_pos[i]+corner[i])/64;bounds[i]=min(bounds[i],value);bounds[i+3]=max(bounds[i+3],value)
        groups=[]
        # The client carries one static light sample per entity. The prefab's
        # arbitrary file origin can be below terrain even while its art is above
        # ground (especially a sitting Yaga). Anchor at the top centre instead,
        # and subtract exactly the same offset from every exported node.
        origin=[(bounds[0]+bounds[3])*.5,bounds[4]+.25,(bounds[2]+bounds[5])*.5]
        relative_bounds=[v-origin[i%3] for i,v in enumerate(bounds)]
        for tex,entries in by_texture.items():
            for start in range(0,len(entries),MODEL_NODE_LIMIT-1):
                part=entries[start:start+MODEL_NODE_LIMIT-1];name=f"Titan_Part_{serial:03}_{len(groups):02}"
                nodes=[];originals=set()
                for i,(node,owned_cells) in enumerate(part,2):
                    for axis,value in zip("xyz",origin):node["position"][axis]-=value*64
                    node["id"]=str(i);node["name"]=f"Surface_{i}";nodes.append(node);originals.update(owned_cells)
                model={"format":"character","lod":"off","nodes":[{"id":"1","name":"Root","position":dict.fromkeys("xyz",0),
                    "orientation":dict(zip("xyzw",IDENTITY)),"shape":{"type":"none","offset":dict.fromkeys("xyz",0),
                    "stretch":dict.fromkeys("xyz",1),"settings":{},"textureLayout":{},"visible":True,
                    "doubleSided":False,"shadingMode":"standard","unwrapMode":"custom"},"children":nodes}]}
                write(COMMON/f"{name}.blockymodel",model);written.add(name)
                asset={"Model":f"VFX/Titan/Parts/{name}.blockymodel","Texture":tex,"MinScale":1,"MaxScale":1,
                    "HitBox":{"Min":dict(zip("XYZ",relative_bounds[:3])),"Max":dict(zip("XYZ",relative_bounds[3:]))}}
                if prefab.startswith("Titan/Yaga/"):
                    # Use only occupied cells for the visible use target, not the
                    # entire house envelope. Windows and access to the separate
                    # furniture must remain open when restoring the client hint.
                    asset["DetailBoxes"]={"Root":[{"Offset":dict(zip("XYZ",(
                        (p[0]+.5)*(-1 if mirror else 1)-origin[0],p[1]+.5-origin[1],p[2]+.5-origin[2]))),
                        "Box":{"Min":dict.fromkeys("XYZ",-.5),"Max":dict.fromkeys("XYZ",.5)}} for p in sorted(originals)]}
                write(MODELS/f"{name}.json",asset)
                group={"model":name,"origin":origin,"cells":[",".join(map(str,p)) for p in sorted(originals)]}
                if prefab in usable_prefabs:
                    # Keep the already combined faces, UVs, origin and node budget.
                    # Normalizing to a unit block avoids the block renderer's
                    # large-model distance path; entity scale restores world size.
                    extent=max(1,max(abs(v) for v in relative_bounds)*2)
                    block=name+"_Usable"
                    write(COMMON/f"Blocks/{block}.blockymodel",as_block_model(model,extent))
                    boxes=[]
                    for detail in asset["DetailBoxes"]["Root"]:
                        boxes.append({side:{a:(detail["Offset"][a]+detail["Box"][side][a])/extent+.5 for a in "XYZ"} for side in ("Min","Max")})
                    assert boxes,"Usable artwork must retain its occupied cells"
                    write(RES/f"Server/Item/Block/Hitboxes/Titan/Parts/{block}.json",{"Boxes":boxes})
                    write(RES/f"Server/Item/Items/Titan/Parts/{block}.json",{
                        "TranslationProperties":{"Name":"Yaga house"},"BlockType":{
                            "Material":"Solid","DrawType":"Model","Opacity":"Transparent","HitboxType":block,
                            "CustomModel":f"VFX/Titan/Parts/Blocks/{block}.blockymodel",
                            "CustomModelTexture":[{"Texture":tex,"Weight":1}],
                            "InteractionHint":"titan_yaga.yaga.hint.toggleRest"}})
                    group.update(block=block,blockScale=extent)
                groups.append(group)
        manifests.append({"signature":sig,"mirror":mirror,"hollow":hollow,"voxelScale":voxel_scale,"prefab":prefab,
                          "yaw":yaw,"minY":ymin,"maxY":ymax,"models":groups})
        print(prefab, "mirror" if mirror else "",len(pieces),"pieces ->",len(groups),"models")
    COMMON.mkdir(parents=True,exist_ok=True);atlas.save()
    write(RES/"TitanMeshes/Parts.json",manifests)
    for folder,pattern in ((COMMON,"Titan_Part_*.blockymodel"),(MODELS,"Titan_Part_*.json")):
        for path in folder.glob(pattern):
            if path.stem not in written:
                assert path.resolve().is_relative_to(RES.resolve());path.unlink()
                built=ROOT/"build/resources/main"/path.relative_to(RES)
                if built.exists():built.unlink()


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--assets",required=True,type=Path)
    with zipfile.ZipFile(parser.parse_args().assets) as archive:generate(archive)
