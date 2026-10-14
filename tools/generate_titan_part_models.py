"""Combine each bundled titan bone/segment's block visuals, without changing collision.

Run after generate_temple_meshes.py. Models share one atlas page and stay below the
client's 256 node budget. Interactive fixture models keep their native state.

The page holds each source texture once: one 32x32 tile per cube face texture and
one copy of each vanilla model texture. A texture is stored a second time only where
a block's tint has to be baked, because a model has no per-face tint. Faces reach
their texture through the UV offset, mirror and angle. A vanilla face whose UVs run
past its texture is cut where the texture would repeat: inside an atlas there is no
repeat, the next texel belongs to another texture.
"""
import argparse
import copy
import io
import json
import math
from functools import lru_cache
from pathlib import Path
import zipfile
from PIL import Image, ImageChops, ImageColor
from generate_temple_meshes import (merge_dict, signature, FACES, MODEL_NODE_LIMIT, TILE, TileAtlas, tile_layout,
                                    empty_shape, ATLAS_TEXTURE as TEMPLE_TEXTURE)

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/main/resources"
COMMON = RES / "Common/VFX/Titan/Parts"
MODELS = RES / "Server/Models/Titan/Parts"
BLOCK_ITEMS = RES / "Server/Item/Items/Titan/Parts"
BLOCK_BOXES = RES / "Server/Item/Block/Hitboxes/Titan/Parts"
ATLAS_TEXTURE = "VFX/Titan/Parts/Atlas_0.png"
IDENTITY = (0, 0, 0, 1)
# Per face: the quad normal that draws the same face, the corner its UV offset
# names, and the directions its texels run across and down, in the box's axes.
FACE_FRAMES = {
    "front": ("+Z", (-1, 1, 1), (1, 0, 0), (0, -1, 0)),
    "back": ("-Z", (1, 1, -1), (-1, 0, 0), (0, -1, 0)),
    "right": ("+X", (1, 1, 1), (0, 0, -1), (0, -1, 0)),
    "left": ("-X", (-1, 1, -1), (0, 0, 1), (0, -1, 0)),
    "top": ("+Y", (-1, 1, -1), (1, 0, 0), (0, 0, 1)),
    "bottom": ("-Y", (-1, -1, 1), (1, 0, 0), (0, 0, -1)),
}
NORMAL_FACES = {frame[0]: face for face, frame in FACE_FRAMES.items()}


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


def face_size(shape, face):
    sx,sy,sz=vec(shape["settings"].get("size",{}))
    return (sx,sy) if shape["type"]=="quad" else (sx,sz) if face in ("top","bottom") else (sz,sy) if face in ("left","right") else (sx,sy)


def uv_at(uv, a, b):
    """Texel under the point `a` across and `b` down from a face's top-left corner."""
    x=-a if uv.get("mirror",{}).get("x") else a
    y=-b if uv.get("mirror",{}).get("y") else b
    dx,dy={0:(x,y),90:(-y,x),180:(-x,-y),270:(y,-x)}[uv.get("angle",0)]
    return uv["offset"]["x"]+dx,uv["offset"]["y"]+dy


def uv_bounds(shape, face, uv):
    w,h=face_size(shape,face)
    (x,y),(right,bottom)=uv_at(uv,0,0),uv_at(uv,w,h)
    return min(x,right),min(y,bottom),max(x,right),max(y,bottom)


def wrapped_crop(im, box):
    x,y,right,bottom=box
    result=Image.new("RGBA",(right-x,bottom-y))
    for yy in range((y//im.height)*im.height,bottom,im.height):
        for xx in range((x//im.width)*im.width,right,im.width):result.paste(im,(xx-x,yy-y))
    return result


def unwrap(shape, width, height):
    """Shapes that draw `shape` with every face inside a width x height texture.

    A vanilla face may sit in a repeat of its texture, or run across one. The
    first only needs its offset moved back. The second is taken off its box and
    drawn as one quad per piece, cut where the texture would start again, so
    that each piece lies inside the single copy the atlas holds.
    """
    shape=copy.deepcopy(shape);pieces=[];emptied=False
    size=vec(shape["settings"].get("size",{}));quad=shape["type"]=="quad"
    if quad:
        normal=shape["settings"].get("normal","+Z")
        size=(0,size[1],size[0]) if normal.endswith("X") else (size[0],0,size[1]) if normal.endswith("Y") else (size[0],size[1],0)
    for name,uv in list(shape.get("textureLayout",{}).items()):
        if quad and name!="front":continue # The client reads only a quad's front layout.
        face=NORMAL_FACES[normal] if quad else name
        w,h=face_size(shape,name)
        lo_x,lo_y,hi_x,hi_y=uv_bounds(shape,name,uv)
        if lo_x>=0 and lo_y>=0 and hi_x<=width and hi_y<=height:continue
        # Each face axis moves one texture axis by one texel per unit.
        def cuts(length, along_a):
            start=uv_at(uv,0,0);step=uv_at(uv,1,0) if along_a else uv_at(uv,0,1)
            axis=0 if step[0]!=start[0] else 1;period=(width,height)[axis];direction=step[axis]-start[axis]
            assert length==int(length) and start[axis]==int(start[axis]),"A repeating face needs whole texels"
            inner=[t for t in range(1,int(length)) if (start[axis]+direction*t)%period==0]
            return [0,*inner,int(length)]
        across,down=cuts(w,True),cuts(h,False)
        def moved(a0, b0, a1, b1):
            (x0,y0),(x1,y1)=uv_at(uv,a0,b0),uv_at(uv,a1,b1)
            layout=copy.deepcopy(uv)
            layout["offset"]={"x":x0-math.floor(min(x0,x1)/width)*width,"y":y0-math.floor(min(y0,y1)/height)*height}
            return layout
        if len(across)==2 and len(down)==2:
            shape["textureLayout"][name]=moved(0,0,w,h);continue
        del shape["textureLayout"][name]
        emptied=quad or not shape["textureLayout"]
        normal_name,corner,right,below=FACE_FRAMES[face]
        stretch=vec(shape.get("stretch",{}),1);offset=vec(shape.get("offset",{}))
        for a0,a1 in zip(across,across[1:]):
            for b0,b1 in zip(down,down[1:]):
                centre=tuple(corner[i]*size[i]/2+right[i]*(a0+a1)/2+below[i]*(b0+b1)/2 for i in range(3))
                piece=copy.deepcopy(shape);piece["type"]="quad"
                piece["settings"]={**{k:v for k,v in shape["settings"].items() if k not in ("size","normal")},
                                   "size":{"x":a1-a0,"y":b1-b0},"normal":normal_name}
                piece["offset"]=dict(zip("xyz",(offset[i]+centre[i]*stretch[i] for i in range(3))))
                piece["textureLayout"]={"front":moved(a0,b0,a1,b1)}
                pieces.append(piece)
    return ([] if emptied else [shape])+pieces


def generate(archive):
    paths={Path(p).stem:p for p in archive.namelist() if p.startswith("Server/Item/Items/") and p.endswith(".json")}
    paths.update({p.stem:p for p in (RES/"Server/Item/Items").rglob("*.json")})
    atlas=TileAtlas();pending=[];written=set();usable=set();manifests=[]

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
        """One copy per source texture and tint: (handle, width, height)."""
        im=Image.open(io.BytesIO(blob("Common/"+path))).convert("RGBA")
        if cube:im=im.resize((TILE,TILE),Image.Resampling.NEAREST)
        if tint:im=ImageChops.multiply(im,Image.new("RGBA",im.size,ImageColor.getrgb(tint)+(255,)))
        return atlas.add(im,f"{path} tint {tint}" if tint else path),im.width,im.height

    # The cuboid art comes from the tiles generate_temple_meshes.py chose, so a
    # cuboid and a loose block of the same material share one tile here too.
    temple_page=Image.open(RES/"Common"/TEMPLE_TEXTURE).convert("RGBA")
    temple_sources={(r["x"],r["y"]):r["sources"] for r in json.loads((RES/"TitanMeshes/TempleAtlas.json").read_text())["regions"]}

    @lru_cache(None)
    def temple_tile(x,y):
        for source in temple_sources[x,y]:
            handle=atlas.add(temple_page.crop((x,y,x+TILE,y+TILE)),source)
        return handle

    @lru_cache(None)
    def block_shapes(key):
        t=material(key)["BlockType"];result=[]
        if t.get("DrawType")=="Cube":
            layouts={};textures=t["Textures"][0]
            for face,native in FACES.items():
                path=textures.get(native,textures.get("UpDown" if face in ("top","bottom") else "Sides",textures.get("All")))
                tint=(t.get("Tint"+native,t.get("Tint")) or [None])[0]
                layouts[face]=tile_layout(texture(path,tint,True)[0])
            shape={"type":"box","offset":dict.fromkeys("xyz",0),"stretch":dict.fromkeys("xyz",1),
                   "settings":{"size":dict.fromkeys("xyz",TILE),"isStaticBox":True},"textureLayout":layouts,
                   "visible":True,"doubleSided":False,"shadingMode":"standard","unwrapMode":"custom"}
            return [((0,16,0),IDENTITY,shape,1)]
        assert t.get("DrawType")=="Model",(key,t.get("DrawType"))
        raw=json.loads(blob("Common/"+t["CustomModel"]))
        path=t["CustomModelTexture"][0]["Texture"]
        scale=t.get("CustomModelScale",1)
        if path==TEMPLE_TEXTURE:
            for pos,q,source_shape in flatten_nodes(raw["nodes"]):
                s=copy.deepcopy(source_shape)
                for face,uv in s["textureLayout"].items():
                    x,y,right,bottom=map(int,uv_bounds(s,face,uv))
                    assert (x%TILE,y%TILE,right-x,bottom-y)==(0,0,TILE,TILE),"A cuboid face shows exactly one tile"
                    uv["offset"]={"x":uv["offset"]["x"]-x,"y":uv["offset"]["y"]-y};uv["image"]=temple_tile(x,y)
                result.append((pos,q,s,scale))
            return result
        handle,width,height=texture(path,(t.get("Tint") or [None])[0])
        for pos,q,source_shape in flatten_nodes(raw["nodes"]):
            for s in unwrap(source_shape,width,height):
                for face,uv in s["textureLayout"].items():
                    x,y,right,bottom=uv_bounds(s,face,uv)
                    if s["type"]=="box" or face=="front":
                        assert x>=0 and y>=0 and right<=width and bottom<=height,(key,face,"face leaves its texture")
                    uv["image"]=handle
                result.append((pos,q,s,scale))
        return result

    # A usable bone (the Yaga's house and legs) is drawn by block entities that are
    # also what the player points at. Their models are built exactly as 1.0.1 built
    # them, the one layout seen working in the client for both drawing distance and
    # the use prompt: one box for a whole cuboid with every tile of a face baked into
    # one image, and a repeating vanilla face baked the same way. Only where a face
    # points in the page differs. This is the one exception to a tile per face.
    @lru_cache(None)
    def tile_image(key,native):
        t=material(key)["BlockType"];textures=t["Textures"][0]
        path=textures.get(native,textures.get("UpDown" if native in ("Up","Down") else "Sides",textures.get("All")))
        im=Image.open(io.BytesIO(blob("Common/"+path))).convert("RGBA").resize((TILE,TILE),Image.Resampling.NEAREST)
        tint=t.get("Tint"+native,t.get("Tint"))
        if tint:im=ImageChops.multiply(im,Image.new("RGBA",im.size,ImageColor.getrgb(tint[0])+(255,)))
        return im

    def baked_cuboid(key,cuboid,cells,solid,mirror):
        x,y,z,sx,sy,sz=cuboid;images={};sides=["South","East","North","West"]
        for face,native in FACES.items():
            w,h=(sx,sz) if face in ("top","bottom") else (sz,sy) if face in ("left","right") else (sx,sy)
            im=Image.new("RGBA",(w*TILE,h*TILE));exposed=False
            for v in range(h):
                for u in range(w):
                    p={"front":(x+u,y+sy-1-v,z+sz-1),"back":(x+sx-1-u,y+sy-1-v,z),"right":(x+sx-1,y+sy-1-v,z+sz-1-u),
                       "left":(x,y+sy-1-v,z+u),"top":(x+u,y+sy-1,z+v),"bottom":(x+u,y,z+sz-1-v)}[face]
                    sample=(x+sx-1-(p[0]-x),p[1],p[2]) if mirror else p
                    d={"front":(0,0,1),"back":(0,0,-1),"right":(1,0,0),"left":(-1,0,0),"top":(0,1,0),"bottom":(0,-1,0)}[face]
                    if mirror:d=(-d[0],d[1],d[2])
                    exposed|=tuple(a+b for a,b in zip(sample,d)) not in solid
                    block=cells[sample];yaw=block.get("rotation",0)
                    tile=(tile_image(block["name"],native).rotate((90 if native=="Up" else -90)*yaw) if native in ("Up","Down")
                          else tile_image(block["name"],sides[(sides.index(native)-yaw)%4]))
                    im.paste(tile,(u*TILE,v*TILE))
            if exposed:images[face]=im
        layouts={face:{"offset":{"x":0,"y":0},"mirror":{"x":False,"y":False},"angle":0,"image":atlas.add(im,f"{key} {face}")}
                 for face,im in sorted(images.items(),key=lambda e:(-e[1].height,-e[1].width))}
        extent=max(sx,sy,sz)
        double=json.loads(blob("Common/"+material(key)["BlockType"]["CustomModel"]))["nodes"][0]["children"][0]["shape"]["doubleSided"]
        shape={"type":"box","offset":dict.fromkeys("xyz",0),"stretch":dict.fromkeys("xyz",1/extent),
               "settings":{"size":{"x":sx*TILE,"y":sy*TILE,"z":sz*TILE},"isStaticBox":True},
               "visible":True,"doubleSided":double,"shadingMode":"standard","unwrapMode":"custom","textureLayout":layouts}
        return [((0,16,0),IDENTITY,shape,1)]

    @lru_cache(None)
    def baked_shapes(key):
        t=material(key)["BlockType"]
        if t.get("DrawType")=="Cube":return block_shapes(key)
        raw=json.loads(blob("Common/"+t["CustomModel"]));path=t["CustomModelTexture"][0]["Texture"]
        tint=(t.get("Tint") or [None])[0]
        native=Image.open(io.BytesIO(blob("Common/"+path))).convert("RGBA")
        if tint:native=ImageChops.multiply(native,Image.new("RGBA",native.size,ImageColor.getrgb(tint)+(255,)))
        handle,width,height=texture(path,tint);result=[]
        for pos,q,source_shape in flatten_nodes(raw["nodes"]):
            s=copy.deepcopy(source_shape)
            for face,uv in s.get("textureLayout",{}).items():
                lo_x,lo_y,hi_x,hi_y=uv_bounds(s,face,uv)
                if lo_x<0 or lo_y<0 or hi_x>width or hi_y>height:
                    left,top=math.floor(lo_x),math.floor(lo_y)
                    crop=wrapped_crop(native,(left,top,max(left+1,math.ceil(hi_x)),max(top+1,math.ceil(hi_y))))
                    uv["offset"]["x"]-=left;uv["offset"]["y"]-=top;uv["image"]=atlas.add(crop,f"{path} repeated for {key}")
                else:uv["image"]=handle
            result.append((pos,q,s,t.get("CustomModelScale",1)))
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
            if temple_body:continue # Keep the verified solid island models.
            originals=volume&rendered
            if originals:pieces.append((key,(x+sx/2,y+sy/2,z+sz/2),0,max(sx,sy,sz),originals,(x,y,z,sx,sy,sz)))
        for p,b in cells.items():
            if p in used or p not in rendered or b["name"] in fixtures:continue
            key=b["name"];scale=1
            if temple_body and key in ("Plant_Vine_Jungle","Plant_Vine_Wall_Winter"):
                key="Titan_Temple_Detail_"+key;scale=4
            pieces.append((key,tuple(v+.5 for v in p),b.get("rotation",0),scale,{p},None))
        surfaces=[];bounds=[float("inf")]*3+[float("-inf")]*3
        baked=prefab in usable_prefabs;solid={cell(b) for b in blocks if opaque(b)}
        for key,centre,rotation,scale,originals,cuboid in pieces:
            qblock=turn(rotation);centre=((-centre[0] if mirror else centre[0]),centre[1],centre[2])
            entries=[];unowned=set(originals)
            shapes=block_shapes(key) if not baked else baked_cuboid(key,cuboid,cells,solid,mirror) if cuboid else baked_shapes(key)
            if baked:cuboid=None # As in 1.0.1, a piece's first shape answers for all its cells.
            for pos,q,shape,custom_scale in shapes:
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
                owned=set()
                if cuboid:
                    # Each box of a cuboid is one source cell, and answers for it
                    # on death and as a use target. A mirrored cuboid counts its
                    # cells from the other side.
                    x,y,z,sx,sy,sz=cuboid
                    i,j,k=(round((pos[n]-(16 if n==1 else 0))*scale/TILE+d/2-.5) for n,d in enumerate((sx,sy,sz)))
                    assert 0<=i<sx and 0<=j<sy and 0<=k<sz,(key,pos)
                    owned={(x+sx-1-i if mirror else x+i,y+j,z+k)}&unowned
                    unowned-=owned
                entries.append((node,owned))
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
            # A block that is not a cuboid, and the cells in the middle of one,
            # belong to the piece's first shape.
            entries[0][1].update(unowned)
            surfaces.extend(entries)
        groups=[]
        # The client carries one static light sample per entity. The prefab's
        # arbitrary file origin can be below terrain even while its art is above
        # ground (especially a sitting Yaga). Anchor at the top centre instead,
        # and subtract exactly the same offset from every exported node.
        origin=[(bounds[0]+bounds[3])*.5,bounds[4]+.25,(bounds[2]+bounds[5])*.5]
        relative_bounds=[v-origin[i%3] for i,v in enumerate(bounds)]
        for start in range(0,len(surfaces),MODEL_NODE_LIMIT-1):
            part=surfaces[start:start+MODEL_NODE_LIMIT-1];name=f"Titan_Part_{serial:03}_{len(groups):02}"
            nodes=[];originals=set()
            for i,(node,owned_cells) in enumerate(part,2):
                for axis,value in zip("xyz",origin):node["position"][axis]-=value*64
                node["id"]=str(i);node["name"]=f"Surface_{i}";nodes.append(node);originals.update(owned_cells)
            model={"format":"character","lod":"off","nodes":[{"id":"1","name":"Root","position":dict.fromkeys("xyz",0),
                "orientation":dict(zip("xyzw",IDENTITY)),"shape":empty_shape(),"children":nodes}]}
            pending.append((COMMON/f"{name}.blockymodel",model));written.add(name)
            asset={"Model":f"VFX/Titan/Parts/{name}.blockymodel","Texture":ATLAS_TEXTURE,"MinScale":1,"MaxScale":1,
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
                block=name+"_Usable";usable.add(block)
                pending.append((COMMON/f"Blocks/{block}.blockymodel",as_block_model(model,extent)))
                boxes=[]
                for detail in asset["DetailBoxes"]["Root"]:
                    boxes.append({side:{a:(detail["Offset"][a]+detail["Box"][side][a])/extent+.5 for a in "XYZ"} for side in ("Min","Max")})
                assert boxes,"Usable artwork must retain its occupied cells"
                write(BLOCK_BOXES/f"{block}.json",{"Boxes":boxes})
                # Variant keeps the block out of the creative library: it is an
                # item only because a block type has to be declared inside one.
                write(BLOCK_ITEMS/f"{block}.json",{
                    "TranslationProperties":{"Name":"Yaga house"},"Variant":True,"BlockType":{
                        "Material":"Solid","DrawType":"Model","Opacity":"Transparent","HitboxType":block,
                        "CustomModel":f"VFX/Titan/Parts/Blocks/{block}.blockymodel",
                        "CustomModelTexture":[{"Texture":ATLAS_TEXTURE,"Weight":1}],
                        "InteractionHint":"titan_yaga.yaga.hint.toggleRest"}})
                group.update(block=block,blockScale=extent)
            groups.append(group)
        manifests.append({"signature":sig,"mirror":mirror,"hollow":hollow,"voxelScale":voxel_scale,"prefab":prefab,
                          "yaw":yaw,"minY":ymin,"maxY":ymax,"models":groups})
        print(prefab, "mirror" if mirror else "",len(pieces),"pieces ->",len(surfaces),"shapes ->",len(groups),"models")
    # Every model is complete; now the page can be as small as its textures allow.
    atlas.layout()
    for path,model in pending:
        atlas.resolve(model["nodes"]);write(path,model)
    atlas.save(RES/"Common"/ATLAS_TEXTURE)
    write(RES/"TitanMeshes/PartsAtlas.json",atlas.manifest(ATLAS_TEXTURE))
    write(RES/"TitanMeshes/Parts.json",manifests)
    print("atlas",atlas.size,len(atlas.images),"textures")
    stale=[(COMMON,"Titan_Part_*.blockymodel",written),(MODELS,"Titan_Part_*.json",written),
           (COMMON/"Blocks","Titan_Part_*.blockymodel",usable),(BLOCK_ITEMS,"Titan_Part_*.json",usable),
           (BLOCK_BOXES,"Titan_Part_*.json",usable),(COMMON,"Atlas_*.png",{"Atlas_0"})]
    for folder,pattern,keep in stale:
        for path in folder.glob(pattern):
            if path.stem not in keep:
                assert path.resolve().is_relative_to(RES.resolve());path.unlink()
                built=ROOT/"build/resources/main"/path.relative_to(RES)
                if built.exists():built.unlink()


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--assets",required=True,type=Path)
    with zipfile.ZipFile(parser.parse_args().assets) as archive:generate(archive)
