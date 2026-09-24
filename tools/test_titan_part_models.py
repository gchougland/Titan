"""Regression checks for hierarchy flattening, texture wrapping and client budgets."""
import json
import math
import unittest
from pathlib import Path
from PIL import Image
from generate_titan_part_models import flatten_nodes, wrapped_crop, uv_bounds, rotate, vec

RES=Path(__file__).resolve().parents[1]/"src/main/resources"


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
                self.assertEqual(len(boxes),len(group["cells"]))
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

    def test_repeating_native_textures_preserve_negative_and_overflow_uvs(self):
        im=Image.new("RGBA",(3,2))
        for y in range(2):
            for x in range(3):im.putpixel((x,y),(x*70,y*120,10,255))
        crop=wrapped_crop(im,(-4,-3,8,7))
        for y in range(crop.height):
            for x in range(crop.width):self.assertEqual(crop.getpixel((x,y)),im.getpixel(((x-4)%3,(y-3)%2)))

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
                    self.assertEqual(len(boxes),len(group["cells"]))
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


if __name__=="__main__":unittest.main()
