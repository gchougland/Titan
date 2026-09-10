"""Run with python -m unittest discover -s tools -p test_crypt_air.py."""
import copy
import json
import unittest
from pathlib import Path
from crypt_air import interior_air
from crypt_walls import audited_cells


def box():
    return [{"x":x,"y":y,"z":z,"name":"Rock_Basalt_Brick"}
        for x in range(7) for y in range(6) for z in range(7)
        if x in (0,6) or y in (0,5) or z in (0,6)]


class InteriorAirTest(unittest.TestCase):
    def test_enclosed_room_is_filled_without_touching_shell_or_exterior(self):
        blocks=box()+[{"x":3,"y":2,"z":3,"name":"Empty"}]
        before=copy.deepcopy(blocks)
        repaired,_=interior_air(blocks)
        self.assertEqual(99,len(repaired))
        self.assertTrue(all(0<x<6 and 0<y<5 and 0<z<6 for x,y,z in repaired))
        self.assertEqual(before,blocks)

    def test_full_cube_partition_blocks_repair_but_partial_alcove_trim_admits_it(self):
        blocks=box()+[{"x":3,"y":y,"z":z,"name":"Rock_Basalt_Brick"}
            for y in range(1,5) for z in range(1,6)]
        blocks.append({"x":1,"y":2,"z":3,"name":"Empty"})
        repaired,_=interior_air(blocks)
        self.assertNotIn((4,2,3),repaired)
        next(b for b in blocks if (b['x'],b['y'],b['z'])==(3,2,3))["name"]="Rock_Basalt_Brick_Stairs"
        repaired,_=interior_air(blocks)
        self.assertIn((4,2,3),repaired)
        self.assertNotIn((3,2,3),repaired)

    def test_distant_stair_shell_is_not_an_exterior_overhang_wall(self):
        blocks=[b for b in box() if not(b['x']==6 and b['y']==3 and 0<b['z']<6)]
        blocks += [{"x":x,"y":y,"z":z,"name":"Empty"}
            for x in range(1,6) for y in range(1,5) for z in range(1,6)]
        blocks.append({"x":12,"y":3,"z":3,"name":"Rock_Basalt_Brick"})
        repaired,proof=interior_air(blocks)
        self.assertIn((6,3,3),proof['sixSolidRays'])
        self.assertIn((6,3,3),proof['excludedExteriorRim'])
        self.assertNotIn((6,3,3),repaired)

    def test_packaged_changes_are_only_the_two_disjoint_audited_repairs(self):
        root=Path(__file__).resolve().parents[1]
        source=Path.home()/"Downloads/Prefabs/SkeletonDungeon.prefab.json"
        if not source.exists(): self.skipTest("Original authored prefab unavailable")
        original=json.loads(source.read_text())
        packaged=json.loads((root/'src/main/resources/Server/Prefabs/Titan/Crypt/SkeletonDungeon_Site.prefab.json').read_text())
        audit=json.loads((root/'tools/crypt-interior-air-audit.json').read_text())
        wall_audit=json.loads((root/'tools/crypt-wall-repair-audit.json').read_text())
        anchor=(64,74,-2)
        xyz=lambda b:tuple(b[a] for a in ('x','y','z'))
        expected={}
        for authored in original['blocks']:
            block=copy.deepcopy(authored)
            position=xyz(block)
            if block['name'].startswith('Soil_Clay_'): block['name']='Empty'
            if position in ((-22,20,-2),(-22,20,-3)): block['name']='Titan_Crypt_Coffin'
            for axis,offset in zip(('x','y','z'),anchor):block[axis]-=offset
            expected[xyz(block)]=block
        actual={xyz(b):b for b in packaged['blocks']}
        self.assertEqual(expected,{p:actual[p] for p in expected})
        added={tuple(p-a for p,a in zip(position,anchor)) for position in audit['addedSourceCells']}
        self.assertEqual(5267,len(added))
        walls={tuple(p-a for p,a in zip(position,anchor)) for position in audited_cells(wall_audit)}
        self.assertEqual(5714,len(walls))
        self.assertFalse(added & walls)
        self.assertEqual(added | walls,set(actual)-set(expected))
        self.assertTrue(all(actual[p]['name']=='Empty' for p in added))
        for p in audit['excludedExteriorRimSourceCells']:
            self.assertNotIn(tuple(v-a for v,a in zip(p,anchor)),actual)
        repaired,proof=interior_air(original['blocks'])
        self.assertEqual(audit['addedSourceCells'],[list(p) for p in repaired])
        self.assertTrue(set(repaired)<=proof['enclosed']|proof['localOpposingWalls'])


if __name__=='__main__':unittest.main()
