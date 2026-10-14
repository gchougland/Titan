"""Regression checks for hierarchy flattening, atlas contents, face UVs and client budgets."""
import hashlib
import json
import math
import unittest
from pathlib import Path
from PIL import Image
from generate_titan_part_models import (flatten_nodes, unwrap, uv_bounds, uv_at, face_size, rotate, vec,
                                        FACE_FRAMES, NORMAL_FACES)

RES=Path(__file__).resolve().parents[1]/"src/main/resources"
ATLASES=("TempleAtlas","PartsAtlas")
ITEM_FOLDERS=("Server/Item/Items/Titan/Parts","Server/Item/Items/Titan/TempleMeshes")
MODEL_FOLDERS=("Server/Models/Titan/Parts","Server/Models/Titan/TempleMeshes")


def texels(shape, width, height):
    """What a shape draws: (point in the node's frame, face) -> texel of a repeating texture."""
    size=vec(shape["settings"].get("size",{}));quad=shape["type"]=="quad"
    if quad:
        normal=shape["settings"].get("normal","+Z")
        size=(0,size[1],size[0]) if normal.endswith("X") else (size[0],0,size[1]) if normal.endswith("Y") else (size[0],size[1],0)
    stretch=vec(shape.get("stretch",{}),1);offset=vec(shape.get("offset",{}));drawn={}
    for name,uv in shape["textureLayout"].items():
        if quad and name!="front":continue
        face=NORMAL_FACES[normal] if quad else name
        _,corner,right,below=FACE_FRAMES[face];w,h=face_size(shape,name)
        for a in range(int(w)):
            for b in range(int(h)):
                point=tuple(round((corner[i]*size[i]/2+right[i]*(a+.5)+below[i]*(b+.5))*stretch[i]+offset[i],6) for i in range(3))
                u,v=uv_at(uv,a+.5,b+.5)
                drawn[point,face]=(math.floor(u)%width,math.floor(v)%height)
    return drawn


def bound_models():
    """Every generated model with the texture the game binds to it."""
    for folder in ITEM_FOLDERS:
        for path in sorted((RES/folder).glob("*.json")):
            block=json.loads(path.read_text())["BlockType"]
            if "CustomModelTexture" in block:yield block["CustomModel"],block["CustomModelTexture"][0]["Texture"]
    for folder in MODEL_FOLDERS:
        for path in sorted((RES/folder).glob("*.json")):
            asset=json.loads(path.read_text());yield asset["Model"],asset["Texture"]


class PartModelsTest(unittest.TestCase):
    def test_yaga_block_renderer_preserves_every_vertex_and_uv(self):
        count=0
        for entry in json.loads((RES/"TitanMeshes/Parts.json").read_text()):
            for group in entry["models"]:
                if "block" not in group:continue
                count+=1;extent=group["blockScale"]
                original=json.loads((RES/f"Common/VFX/Titan/Parts/{group['model']}.blockymodel").read_text())
                item=json.loads((RES/f"Server/Item/Items/Titan/Parts/{group['block']}.json").read_text())["BlockType"]
                converted=json.loads((RES/"Common"/item["CustomModel"]).read_text())
                source=list(flatten_nodes(original["nodes"]));target=list(flatten_nodes(converted["nodes"]))
                self.assertEqual(len(source),len(target));self.assertLessEqual(len(target),256)
                self.assertEqual(item["InteractionHint"],"titan_yaga.yaga.hint.toggleRest")
                for (p,q,s),(bp,bq,bs) in zip(source,target):
                    self.assertEqual(q,bq);self.assertEqual(s["textureLayout"],bs["textureLayout"])
                    if s["type"]=="none":continue
                    size=vec(s["settings"].get("size",{}))
                    if s["type"]=="quad":
                        normal=s["settings"].get("normal","+Z")
                        size=(0,size[1],size[0]) if normal.endswith("X") else (size[0],0,size[1]) if normal.endswith("Y") else (size[0],size[1],0)
                    for x in (-.5,.5):
                        for y in (-.5,.5):
                            for z in (-.5,.5):
                                c=rotate(tuple(size[i]*s["stretch"][a]*v+s["offset"][a] for i,(a,v) in enumerate(zip("xyz",(x,y,z)))),q)
                                bc=rotate(tuple(size[i]*bs["stretch"][a]*v+bs["offset"][a] for i,(a,v) in enumerate(zip("xyz",(x,y,z)))),bq)
                                for i in range(3):
                                    rendered=(bp[i]+bc[i]-(16 if i==1 else 0))/32
                                    self.assertLessEqual(abs(rendered),.5+1e-7)
                                    self.assertAlmostEqual(rendered*extent,(p[i]+c[i])/64,places=7)
                boxes=json.loads((RES/f"Server/Item/Block/Hitboxes/Titan/Parts/{group['block']}.json").read_text())["Boxes"]
                # Its own cells first; any further box is a cull anchor, checked below.
                self.assertGreaterEqual(len(boxes),len(group["cells"]))
                for cell,box in zip(sorted(tuple(map(int,c.split(','))) for c in group["cells"]),boxes):
                    for i,a in enumerate("XYZ"):
                        lo=cell[i] if i!=0 or not entry["mirror"] else -cell[i]-1
                        self.assertAlmostEqual((box["Min"][a]-.5)*extent+group["origin"][i],lo)
                        self.assertAlmostEqual((box["Max"][a]-.5)*extent+group["origin"][i],lo+1)
        self.assertGreater(count,0)
        print(f"Validated {count} Yaga block models against original vertices, textures and occupied cells")

    def test_nested_shape_offset_is_inherited_before_child_rotation(self):
        q=math.sqrt(.5)
        nodes=[{"position":{"x":10},"orientation":{"x":0,"y":0,"z":q,"w":q},
            "shape":{"type":"box","offset":{"x":3,"y":4}},
            "children":[{"position":{"x":2},"orientation":{"x":0,"y":0,"z":0,"w":1},
                         "shape":{"type":"box"}}]}]
        parent,child=list(flatten_nodes(nodes))
        for actual,expected in zip(child[0],(6,5,0)):self.assertAlmostEqual(actual,expected)
        self.assertEqual(parent[0],(10,0,0))

    def test_faces_that_repeat_a_texture_are_cut_into_faces_inside_it(self):
        # A vanilla face may sit in a repeat of its texture, or run across one in
        # either direction, turned and mirrored. Whatever unwrap() returns must
        # draw the same texel at the same place with no face leaving the texture.
        width,height=8,4
        layout=lambda x,y,angle=0,mx=False,my=False:{"offset":{"x":x,"y":y},"mirror":{"x":mx,"y":my},"angle":angle}
        box={"type":"box","offset":{"x":1,"y":-2,"z":.5},"stretch":{"x":2,"y":-1.5,"z":1},
             "settings":{"size":{"x":6,"y":3,"z":10},"isStaticBox":True},"visible":True,"doubleSided":False,
             "shadingMode":"standard","unwrapMode":"custom","textureLayout":{
                 "front":layout(1,0),"back":layout(-8,-4),"left":layout(5,2),"right":layout(3,9,90,True),
                 "top":layout(-2,6,270,False,True),"bottom":layout(13,1,180)}}
        quads=[{"type":"quad","offset":{"x":0,"y":0,"z":0},"stretch":{"x":1,"y":1,"z":-3},
                "settings":{"size":{"x":11,"y":7},"normal":normal},"visible":True,"doubleSided":True,
                "shadingMode":"flat","unwrapMode":"custom","textureLayout":{"front":layout(-3,2,angle)}}
               for normal,angle in (("+Z",0),("-X",90),("+Y",180),("-Y",270))]
        for shape in (box,*quads):
            expected=texels(shape,width,height);actual={};pieces=unwrap(shape,width,height)
            for piece in pieces:
                for name,uv in piece["textureLayout"].items():
                    x,y,right,bottom=uv_bounds(piece,name,uv)
                    self.assertTrue(0<=x and 0<=y and right<=width and bottom<=height,(name,uv))
                drawn=texels(piece,width,height)
                self.assertFalse(actual.keys()&drawn.keys(),"a texel is drawn twice")
                actual.update(drawn)
            self.assertEqual(actual,expected)
        self.assertEqual([p["type"] for p in unwrap(box,width,height)].count("box"),1)
        self.assertEqual(set(unwrap(box,width,height)[0]["textureLayout"]),{"front","back"},
            "faces inside one repeat stay on the box; only faces across a repeat become quads")
        inside=dict(box,textureLayout={"front":layout(1,0)})
        self.assertEqual(unwrap(inside,width,height),[inside])

    def test_packaged_models_fit_buffers_bounds_and_textures(self):
        entries=json.loads((RES/"TitanMeshes/Parts.json").read_text())
        model_ids=set();total=0
        for entry in entries:
            cells=set()
            for group in entry["models"]:
                self.assertFalse(cells.intersection(group["cells"]));cells.update(group["cells"])
                name=group["model"];self.assertNotIn(name,model_ids);model_ids.add(name)
                asset=json.loads((RES/f"Server/Models/Titan/Parts/{name}.json").read_text())
                self.assertEqual(len(group["origin"]),3)
                self.assertTrue(all(math.isfinite(v) for v in group["origin"]))
                # The sampling origin is above the geometry, not an arbitrary
                # prefab corner that can be buried while the house is sitting.
                self.assertAlmostEqual(asset["HitBox"]["Max"]["Y"],-.25)
                self.assertAlmostEqual(asset["HitBox"]["Min"]["X"]+asset["HitBox"]["Max"]["X"],0)
                self.assertAlmostEqual(asset["HitBox"]["Min"]["Z"]+asset["HitBox"]["Max"]["Z"],0)
                if entry["prefab"].startswith("Titan/Yaga/"):
                    boxes=asset["DetailBoxes"]["Root"]
                    if "block" in group:self.assertGreaterEqual(len(boxes),len(group["cells"]))
                    else:self.assertEqual(len(boxes),len(group["cells"]))
                    for cell,box in zip(sorted(tuple(map(int,c.split(','))) for c in group["cells"]),boxes):
                        expected=((cell[0]+.5)*(-1 if entry["mirror"] else 1),cell[1]+.5,cell[2]+.5)
                        for i,a in enumerate("XYZ"):
                            self.assertAlmostEqual(box["Offset"][a]+group["origin"][i],expected[i])
                            self.assertEqual(box["Box"]["Min"][a],-.5);self.assertEqual(box["Box"]["Max"][a],.5)
                model=json.loads((RES/"Common"/asset["Model"]).read_text());texture=Image.open(RES/"Common"/asset["Texture"])
                self.assertEqual(len(model["nodes"]),1);root=model["nodes"][0]
                self.assertTrue(root["shape"]["visible"])
                self.assertLessEqual(len(root["children"])+1,256,name)
                ids={root["id"]}
                for n in root["children"]:
                    total+=1;self.assertNotIn(n["id"],ids);ids.add(n["id"]);self.assertFalse(n["children"])
                    shape=n["shape"];size=vec(shape["settings"].get("size",{}));q=tuple(n["orientation"][a] for a in "xyzw")
                    if shape["type"]=="quad":
                        normal=shape["settings"].get("normal","+Z")
                        size=(0,size[1],size[0]) if normal.endswith("X") else (size[0],0,size[1]) if normal.endswith("Y") else (size[0],size[1],0)
                    for xx in (-.5,.5):
                        for yy in (-.5,.5):
                            for zz in (-.5,.5):
                                corner=rotate(tuple(size[i]*shape["stretch"][a]*v+shape["offset"][a] for i,(a,v) in enumerate(zip("xyz",(xx,yy,zz)))),q)
                                for i,a in enumerate("xyz"):
                                    value=(n["position"][a]+corner[i])/64
                                    self.assertTrue(math.isfinite(value));self.assertGreaterEqual(value,asset["HitBox"]["Min"][a.upper()]-1e-6)
                                    self.assertLessEqual(value,asset["HitBox"]["Max"][a.upper()]+1e-6)
                    for face,uv in shape["textureLayout"].items():
                        x,y,right,bottom=uv_bounds(shape,face,uv)
                        self.assertGreaterEqual(x,-1e-6,(name,face));self.assertGreaterEqual(y,-1e-6,(name,face))
                        self.assertLessEqual(right,texture.width+1e-6,(name,face));self.assertLessEqual(bottom,texture.height+1e-6,(name,face))
                texture.close()
        print(f"Validated {len(entries)} prefab configurations, {len(model_ids)} models, {total} shapes")

    # What 1.0.1 shipped for every usable block model: how many use-target boxes, the
    # boxes in order on a 1/4096 grid, how many nodes, and each node's shape and size.
    # 1.0.1's layout is the only one seen working in the client for both drawing
    # distance and the use prompt, so none of it may drift; only where a face points
    # in the texture page is free. The grid absorbs the last digits a re-exported
    # vanilla model moves a bone's bounds by.
    USABLE_1_0_1={
        "Titan_Part_039_00_Usable":(444,"19e62c8a36b98fb3",255,"5f46c45f0117a845"),
        "Titan_Part_039_01_Usable":(34,"bbd8d5849f4b1a50",255,"1726f72191581de9"),
        "Titan_Part_039_02_Usable":(44,"658e28fc8dcbea2b",255,"fb7ae0681fc17303"),
        "Titan_Part_039_03_Usable":(42,"e3341d48f06ba391",255,"af59950ea595763d"),
        "Titan_Part_039_04_Usable":(42,"5dbc8e24546eaf3c",255,"0a75b6a1aff687b0"),
        "Titan_Part_039_05_Usable":(37,"0eb84d672391ddb1",255,"4d6813702b8836c0"),
        "Titan_Part_039_06_Usable":(23,"8c33c393ad47c018",128,"5def96d178a7765b"),
        "Titan_Part_041_00_Usable":(30,"bc75849623eeefb3",30,"76f8362f0d3e72a4"),
        "Titan_Part_042_00_Usable":(9,"d5c57dac218dc03f",9,"5b3bac4075373e0a"),
        "Titan_Part_043_00_Usable":(8,"4799aef79579bdff",8,"e6a49e2898669cfc"),
        "Titan_Part_044_00_Usable":(30,"37c06263393cdbc1",30,"76f8362f0d3e72a4"),
        "Titan_Part_045_00_Usable":(9,"d5c57dac218dc03f",9,"5b3bac4075373e0a"),
        "Titan_Part_046_00_Usable":(8,"4799aef79579bdff",8,"e6a49e2898669cfc"),
        "Titan_Part_047_00_Usable":(94,"3d047b25ca8c033e",255,"d5048a176b2598eb"),
        "Titan_Part_047_01_Usable":(20,"c87aeebd9bcee098",30,"e06b66a19c205164"),
        "Titan_Part_049_00_Usable":(5,"f3cc5659ac6f9092",9,"b479328ddcf58687"),
        "Titan_Part_050_00_Usable":(3,"f1ef81ca5136b774",5,"ffa4c263ff400af5"),
        "Titan_Part_051_00_Usable":(6,"fb6cb0690ad29e30",8,"0fa85be2c631e8ac"),
    }

    def test_usable_models_are_built_as_in_1_0_1(self):
        def shapes(node):
            shape=node["shape"];settings=shape.get("settings",{})
            yield [shape.get("type","none"),settings.get("size",{}),settings.get("normal")]
            for child in node.get("children",[]):yield from shapes(child)
        digest=lambda value:hashlib.sha256(json.dumps(value,sort_keys=True).encode()).hexdigest()[:16]
        seen=set()
        for entry in json.loads((RES/"TitanMeshes/Parts.json").read_text()):
            claimed={};targets={}
            for group in entry["models"]:
                if "block" not in group:continue
                block=group["block"];seen.add(block);self.assertIn(block,self.USABLE_1_0_1)
                boxes=json.loads((RES/f"Server/Item/Block/Hitboxes/Titan/Parts/{block}.json").read_text())["Boxes"]
                grid=[[round(box[side][a]*4096) for side in ("Min","Max") for a in "XYZ"] for box in boxes]
                model=json.loads((RES/f"Common/VFX/Titan/Parts/Blocks/{block}.blockymodel").read_text())
                nodes=[s for node in model["nodes"] for s in shapes(node)]
                self.assertEqual((len(boxes),digest(grid)[:16],len(nodes)-1,digest(nodes)),self.USABLE_1_0_1[block],block)
                details=json.loads((RES/f"Server/Models/Titan/Parts/{group['model']}.json").read_text())["DetailBoxes"]["Root"]
                self.assertEqual((len(details),len(group["cells"])),(len(boxes),len(boxes)),block)
                item=json.loads((RES/f"Server/Item/Items/Titan/Parts/{block}.json").read_text())["BlockType"]
                self.assertEqual((item["HitboxType"],item["InteractionHint"]),(block,"titan_yaga.yaga.hint.toggleRest"))
                # No use target may belong to two models of one bone.
                for cell in group["cells"]:self.assertEqual(claimed.setdefault(cell,block),block,"two usable models claim one cell")
                for box in map(tuple,grid):
                    self.assertEqual(targets.setdefault(box,block),block,"two usable models claim one use-target box")
        self.assertEqual(seen,set(self.USABLE_1_0_1))
        print(f"Validated {len(seen)} usable block models against their 1.0.1 use targets, node counts and shapes")

    def test_atlas_pages_hold_each_source_texture_once(self):
        for name in ATLASES:
            atlas=json.loads((RES/f"TitanMeshes/{name}.json").read_text())
            page=Image.open(RES/"Common"/atlas["texture"]).convert("RGBA")
            self.assertEqual(page.size,(atlas["width"],atlas["height"]))
            # The client wants multiples of 32, and the 0.7.0 block atlas turned
            # away pages that were 4096 wide. These stay far below that.
            self.assertEqual((page.width%32,page.height%32),(0,0),name)
            self.assertLessEqual(page.width,1024,name);self.assertLessEqual(page.height,2048,name)
            taken=set();pixels={};sources=set()
            for region in atlas["regions"]:
                x,y,w,h=(region[k] for k in ("x","y","width","height"))
                self.assertEqual((x%32,y%32,w%32,h%32),(0,0,0,0),region)
                self.assertTrue(0<=x and 0<=y and x+w<=page.width and y+h<=page.height,region)
                cells={(cx,cy) for cx in range(x,x+w,32) for cy in range(y,y+h,32)}
                self.assertFalse(taken&cells,f"{name}: regions overlap at {region}");taken|=cells
                # No copy per block, per merged face, per rotation: the same
                # pixels may be stored once, and so may each source texture.
                key=(w,h,hashlib.sha256(page.crop((x,y,x+w,y+h)).tobytes()).digest())
                self.assertNotIn(key,pixels,f"{name}: {region['sources']} repeats {pixels.get(key)}")
                pixels[key]=region["sources"]
                self.assertTrue(region["sources"])
                for source in region["sources"]:
                    self.assertNotIn(source,sources,f"{name}: {source} is stored twice");sources.add(source)
            # As small as its contents allow: less than one row of spare cells.
            spare=page.width*page.height//1024-len(taken)
            self.assertLess(spare,min(page.width,page.height)//32,name)
            print(f"{name}: {page.width}x{page.height}, {len(atlas['regions'])} distinct textures, {spare} spare cells")

    def test_every_face_lies_inside_the_one_texture_it_shows(self):
        pages={}
        for name in ATLASES:
            atlas=json.loads((RES/f"TitanMeshes/{name}.json").read_text())
            pages[atlas["texture"]]=(atlas["regions"],set())
        models=faces=0
        for model_path,texture in bound_models():
            if texture not in pages:continue
            regions,used=pages[texture];models+=1
            for _,_,shape in flatten_nodes(json.loads((RES/"Common"/model_path).read_text())["nodes"]):
                for face,uv in shape["textureLayout"].items():
                    if shape["type"]=="quad" and face!="front":continue
                    x,y,right,bottom=uv_bounds(shape,face,uv);faces+=1
                    self.assertTrue(right>x and bottom>y,(model_path,face))
                    inside=[i for i,r in enumerate(regions) if r["x"]<=x and r["y"]<=y
                            and right<=r["x"]+r["width"] and bottom<=r["y"]+r["height"]]
                    self.assertEqual(len(inside),1,f"{model_path} {face} {uv} reaches outside its texture")
                    used.add(inside[0])
        for texture,(regions,used) in pages.items():
            self.assertEqual(used,set(range(len(regions))),f"{texture} holds a texture no face shows")
        self.assertGreater(models,1000)
        print(f"Validated {faces} faces of {models} models against the atlas regions")

    def test_temple_cuboids_draw_one_tile_per_block_face(self):
        tiles={(r["x"],r["y"]) for r in json.loads((RES/"TitanMeshes/TempleAtlas.json").read_text())["regions"]}
        count=0
        for row in (RES/"TitanMeshes/index.tsv").read_text().splitlines():
            label=row.split("\t")[0]
            for line in (RES/f"TitanMeshes/{label}.tsv").read_text().splitlines()[1:]:
                x,y,z,sx,sy,sz,key=line.split("\t");sx,sy,sz=int(sx),int(sy),int(sz);extent=max(sx,sy,sz);count+=1
                model=json.loads((RES/f"Common/VFX/Titan/TempleMeshes/{key}.blockymodel").read_text())
                self.assertEqual(len(model["nodes"]),1);root=model["nodes"][0]
                self.assertEqual(root["shape"]["type"],"none")
                self.assertLessEqual(len(root["children"])+1,256,key)
                cells=set()
                for node in root["children"]:
                    shape=node["shape"]
                    self.assertEqual(shape["type"],"box");self.assertEqual(vec(shape["settings"]["size"]),(32,32,32))
                    self.assertEqual(vec(shape["stretch"]),(1/extent,)*3);self.assertEqual(vec(shape["offset"]),(0,0,0))
                    cell=tuple(node["position"][a]*extent/32+d/2-.5 for a,d in zip("xyz",(sx,sy,sz)))
                    for value,limit in zip(cell,(sx,sy,sz)):
                        self.assertAlmostEqual(value,round(value));self.assertTrue(0<=round(value)<limit,key)
                    cell=tuple(map(round,cell));self.assertNotIn(cell,cells);cells.add(cell)
                    # Only faces on the outside of the cuboid, never one between two of its cells.
                    outside={"left":cell[0]==0,"right":cell[0]==sx-1,"bottom":cell[1]==0,"top":cell[1]==sy-1,
                             "back":cell[2]==0,"front":cell[2]==sz-1}
                    self.assertTrue(shape["textureLayout"],key)
                    for face,uv in shape["textureLayout"].items():
                        self.assertTrue(outside[face],(key,cell,face))
                        left,top,right,bottom=uv_bounds(shape,face,uv)
                        self.assertIn((left,top),tiles,(key,face));self.assertEqual((right-left,bottom-top),(32,32))
        self.assertGreater(count,900)

    def test_generated_items_stay_out_of_the_creative_library(self):
        # They are items only because a block type has to be declared inside one.
        count=0
        for folder in ITEM_FOLDERS:
            for path in sorted((RES/folder).glob("*.json")):
                count+=1;self.assertIs(json.loads(path.read_text()).get("Variant"),True,path.name)
        self.assertGreater(count,1000)
        print(f"Validated {count} generated block items are marked Variant")


if __name__=="__main__":unittest.main()
