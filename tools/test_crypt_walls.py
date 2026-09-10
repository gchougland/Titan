"""Regression coverage for the clipped back/right recessed-wall reconstruction."""
import copy
import json
from pathlib import Path
import unittest
from tempfile import TemporaryDirectory

from crypt_walls import BONE, BASALT, BACK_PANELS, RIGHT_PANELS, missing_walls, point, audited_cells
from prepare_crypt_prefabs import write_dungeon


class PrefabCacheTest(unittest.TestCase):
    def test_reimport_invalidates_only_the_dungeons_generated_cache(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root/"SkeletonDungeon_Site.prefab.json"
            generated = root/(path.name+".lpf")
            standalone = root/"SkeletonDungeon_Site.lpf"
            other = root/"Other.prefab.json.lpf"
            for file in (generated,standalone,other): file.write_bytes(b"preserve unrelated caches")
            write_dungeon(path,{"blocks": []})
            self.assertEqual({"blocks": []},json.loads(path.read_text()))
            self.assertFalse(generated.exists())
            self.assertEqual(b"preserve unrelated caches",standalone.read_bytes())
            self.assertEqual(b"preserve unrelated caches",other.read_bytes())
            write_dungeon(path,{"blocks": []}) # An absent generated cache is fine.


class MissingWallsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = Path.home()/"Downloads/Prefabs/SkeletonDungeon.prefab.json"
        if not source.exists(): raise unittest.SkipTest("Original authored prefab unavailable")
        cls.original = json.loads(source.read_text())["blocks"]
        cls.before = copy.deepcopy(cls.original)
        cls.added, cls.audit = missing_walls(cls.original)
        cls.cells = {point(block): block for block in cls.original}
        cls.repairs = {point(block): block for block in cls.added}

    def test_every_original_cell_is_immutable_and_every_addition_has_an_exact_donor(self):
        self.assertEqual(self.before, self.original)
        self.assertFalse(self.cells.keys() & self.repairs.keys())
        self.assertEqual(5714, len(self.repairs))
        self.assertEqual(self.repairs.keys(), audited_cells(self.audit))
        for group in [*self.audit["layers"], self.audit["windowAir"]]:
            for mapping in group["sourceMappings"]:
                target, donor = tuple(mapping[:3]), tuple(mapping[3:])
                expected = copy.deepcopy(self.cells[donor])
                expected.update(zip(("x", "y", "z"), target))
                if "rotation" in group: expected["rotation"] = group["rotation"]
                self.assertEqual(expected, self.repairs[target])

    def test_only_nineteen_recess_panels_and_their_bounded_backing_are_restored(self):
        back_bones = {(-67,y,z) for start in BACK_PANELS for z in range(start,start+4) for y in range(18,46)}
        right_bones = {(x,y,-42) for start in RIGHT_PANELS for x in range(start,start+4) for y in range(18,46)}
        backing = {(-68,y,z) for z in range(-35,32) for y in range(18,46)}
        backing |= {(x,y,-43) for x in range(-60,0) for y in range(18,46)}
        openings = {tuple(row[:3]) for row in self.audit["windowAir"]["sourceMappings"]}
        self.assertEqual(back_bones | right_bones | backing | openings, self.repairs.keys())
        self.assertEqual(2128, len(back_bones | right_bones))
        self.assertEqual(3556, len(backing))
        for p in back_bones | right_bones:
            self.assertEqual(BONE, self.repairs[p]["name"])
            self.assertEqual(3 if p in back_bones else 2, self.repairs[p]["rotation"])
            x,y,z = p
            self.assertEqual(BASALT,self.repairs[(x-1,y,z) if p in back_bones else (x,y,z-1)]["name"])
        # The cells immediately beyond backing, the bottom/top, and both corner
        # strips must remain outside this tightly bounded reconstruction.
        for p in [(-69,20,-34),(-59,20,-44),(-68,17,-34),(-68,46,-34),
                  (-68,20,-36),(-68,20,32),(-61,20,-43),(0,20,-43)]:
            self.assertNotIn(p,self.repairs)

    def test_front_doorway_is_preserved_but_does_not_create_a_false_back_opening(self):
        self.assertEqual("Empty",self.cells[(7,20,-2)]["name"])
        self.assertNotIn((7,20,-2),self.repairs)
        for z in (-7,-6,-5,-4,0,1,2,3):
            self.assertEqual(BONE,self.repairs[(-67,20,z)]["name"])
            self.assertEqual(BASALT,self.repairs[(-68,20,z)]["name"])
        self.assertEqual([104,160,0,0],[layer["repeatedDoorwayDonors"] for layer in self.audit["layers"]])

    def test_missing_window_air_is_copied_only_from_exact_mirrored_openings(self):
        openings = self.audit["windowAir"]["sourceMappings"]
        self.assertEqual(30,len(openings))
        for x,y,z,dx,dy,dz in openings:
            self.assertEqual((x,y,37),(dx,dy,dz))
            self.assertEqual(-41,z)
            self.assertEqual("Empty",self.cells[(dx,dy,dz)]["name"])
            self.assertEqual("Empty",self.repairs[(x,y,z)]["name"])
            self.assertEqual(BONE,self.repairs[(x,y,-42)]["name"])
        self.assertIn((-37,20,-41),self.repairs)


if __name__ == "__main__": unittest.main()
