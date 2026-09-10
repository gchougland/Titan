"""Import the authored crypt without changing the originals.

The source's entrance floor is (64, 74, -2). Normal biome prefabs already sample
the terrain height at their anchor; FitHeightmap MUST remain false because true
warps every column of this rigid building. Normal prefabs are pasted after caves
at priority 9 (caves use 6..8), including all explicit air and empty fluids.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from crypt_air import interior_air
from crypt_walls import missing_walls, audited_cells

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/Server/Prefabs/Titan/Crypt"
ANCHOR = (64, 74, -2)
PIT = (-50.5, 18, -2.5)
COFFIN = (-22, 20, -2)
COLORS = {"Blue": "grabs", "Scarlet": "flames", "White": "minions", "Green": "pitBottom"}
PARTS = ("Arm", "Bracelet", "Crown", "Finger", "Head", "Jaw", "Palm")
ROOF_PROFILE = ROOT / "src/main/resources/Titan/Crypt/RoofColumns.csv"
AIR_AUDIT = ROOT / "tools/crypt-interior-air-audit.json"
WALL_AUDIT = ROOT / "tools/crypt-wall-repair-audit.json"


def cell_hash(cells):
    ordered = sorted(cells, key=lambda b: (b["x"], b["y"], b["z"]))
    return hashlib.sha256(json.dumps(ordered, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def point(block):
    return tuple(block[a] for a in ("x", "y", "z"))


def relative(position):
    return tuple(p-a for p,a in zip(position, ANCHOR))


def air_audit(source_dungeon, repairs, proof):
    cells = {point(b) for b in source_dungeon["blocks"]}
    assert not cells.intersection(repairs)
    assert set(repairs) <= proof["enclosed"] | (proof["sixSolidRays"] & proof["localOpposingWalls"])
    assert not set(repairs).intersection(proof["boundedButIsolated"])
    regions = Counter("entranceInterior" if x >= 46 and y >= 75 else "stairway" if x > 7
        else "roomAndAlcoves" if y >= 17 else "pitInterior" for x,y,z in repairs)
    omitted = 1
    for axis in range(3): omitted *= max(p[axis] for p in cells)-min(p[axis] for p in cells)+1
    omitted -= len(cells)
    return {
        "algorithm": "Authored-cell enclosure or six solid ray witnesses plus opposing walls within two blocks; reachable from authored air through bounded cavities and existing partial models.",
        "addedSourceCells": [list(p) for p in repairs],
        "sixRayOnlySourceCells": [list(p) for p in sorted(set(repairs)-proof["enclosed"])],
        "excludedIsolatedSourceCells": [list(p) for p in sorted(proof["boundedButIsolated"])],
        "excludedExteriorRimSourceCells": [list(p) for p in sorted(proof["excludedExteriorRim"])],
        "addedAirBlocks": len(repairs), "addedEmptyFluids": len(repairs),
        "regions": dict(sorted(regions.items())),
        "previouslyOmittedCellsLeftUnchanged": omitted-len(repairs),
        "changedExistingPackagedCells": 0,
    }


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, separators=(",", ":")) + "\n", encoding="utf-8")


def write_dungeon(path, dungeon):
    write(path, dungeon)
    # Generated LPF caches contain the old geometry. Gradle can give a copied
    # JSON and its stale cache identical timestamps; the engine then trusts the
    # cache. A bundled immutable cache wins unconditionally. Remove only this
    # JSON's generated sidecar, never an authored standalone .lpf prefab.
    path.with_name(path.name + ".lpf").unlink(missing_ok=True)


def import_parts(source):
    """Copy supplied part files byte for byte; the original files remain read only."""
    parts = {}
    supplied = {name: (source / f"Skeleton_{name}.prefab.json").read_bytes() for name in PARTS}
    OUT.mkdir(parents=True, exist_ok=True)
    for name, original in supplied.items():
        filename = f"Skeleton_{name}.prefab.json"
        part = json.loads(original)
        solid = [b for b in part["blocks"] if b["name"] != "Empty"]
        if not solid:
            raise ValueError(f"Supplied part has no solid geometry: {filename}")
        target = OUT / filename
        target.write_bytes(original)
        source_hash = hashlib.sha256(original).hexdigest()
        parts[filename] = {
            "anchor": [part.get("anchor" + axis, 0) for axis in ("X", "Y", "Z")],
            "solidBounds": {a: [min(b[a] for b in solid), max(b[a] for b in solid)] for a in ("x", "y", "z")},
            "solidBlocks": len(solid),
            "sourceSHA256": source_hash,
            "packagedSHA256": hashlib.sha256(target.read_bytes()).hexdigest()}
        assert parts[filename]["packagedSHA256"] == source_hash
    return parts


def import_parts_only(source):
    manifest_path = ROOT / "tools/crypt-prefab-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["parts"] = import_parts(source)
    write(manifest_path, manifest)
    print(f"Imported {len(PARTS)} supplied parts byte for byte; dungeon and site data unchanged.")


def roof_profile(dungeon):
    """Cover every affected underground column, including explicit air and fluids."""
    tops = {}
    solid_tops = {}
    for block in dungeon["blocks"]:
        column = (block["x"], block["z"])
        tops[column] = max(tops.get(column, -1000), block["y"])
        if block["name"] != "Empty":
            solid_tops[column] = max(solid_tops.get(column, -1000), block["y"])
    for fluid in dungeon.get("fluids", []):
        column = (fluid["x"], fluid["z"])
        tops[column] = max(tops.get(column, -1000), fluid["y"])
    entrance = {column for column, y in solid_tops.items() if y >= 0}
    assert all(-18 <= x <= 1 for x, z in entrance), "Only the authored surface entrance is exempt"
    columns = [(x, z, y) for (x, z), y in tops.items() if (x, z) not in entrance]
    assert len(columns) == 6679 and len(entrance) == 372
    assert all(y < 0 for x, z, y in columns)
    # Test the shallow stair roof first, so unsuitable slopes reject quickly.
    columns.sort(key=lambda column: (-column[2], column[0], column[1]))
    return ("# Authored and repaired underground columns x,z,highestAffectedY; require one terrain block above.\n"
            f"# Excludes only {len(entrance)} surface entrance columns; includes all {len(columns)} underground columns.\n"
            + "".join(f"{x},{z},{y}\n" for x, z, y in columns))


def write_roof_profile(dungeon):
    ROOF_PROFILE.parent.mkdir(parents=True, exist_ok=True)
    ROOF_PROFILE.write_text(roof_profile(dungeon), encoding="utf-8")


def prepare(source, dungeon_only=False):
    original = source / "SkeletonDungeon.prefab.json"
    dungeon = json.loads(original.read_text(encoding="utf-8"))
    source_block_count = len(dungeon["blocks"])
    # Both passes inspect the original selection. Expanding it first would change
    # the conservative interior-air witnesses and could admit exterior cavities.
    repairs, proof = interior_air(dungeon["blocks"])
    audit = air_audit(dungeon, repairs, proof)
    walls, wall_audit = missing_walls(dungeon["blocks"])
    assert not set(repairs) & {point(block) for block in walls}
    markers = {name: [] for name in COLORS.values()}
    removed = 0
    replaced = 0
    for block in dungeon["blocks"]:
        name = block["name"]
        xyz = [block[a] for a in ("x", "y", "z")]
        if name.startswith("Soil_Clay_"):
            color = name.removeprefix("Soil_Clay_")
            if color not in COLORS:
                raise ValueError(f"Unassigned marker {name}: {xyz}")
            markers[COLORS[color]].append(xyz)
            block["name"] = "Empty"
            removed += 1
        # Preserve decorative wall coffins; only the central podium coffin opens the encounter.
        if xyz in [list(COFFIN), [-22, 20, -3]]:
            assert name == "Furniture_Human_Ruins_Coffin", (xyz, name)
            block["name"] = "Titan_Crypt_Coffin"
            replaced += 1
    assert replaced == 2
    assert {key: len(value) for key, value in markers.items()} == {
        "grabs": 2, "flames": 28, "minions": 7, "pitBottom": 1}
    # Store already-relative block coordinates. Anchor metadata alone does not
    # relocate all server prefab readers; normalizing here makes every paste agree.
    for collection in ("blocks", "fluids"):
        for block in dungeon.get(collection, []):
            for axis, offset in zip(("x", "y", "z"), ANCHOR):
                block[axis] -= offset
    # Freeze every existing packaged cell before adding only the proven interior
    # omissions. Empty fluids clear cave water in precisely those same new cells.
    audit["unchangedExistingBlocksSHA256"] = cell_hash(dungeon["blocks"])
    audit["unchangedExistingFluidsSHA256"] = cell_hash(dungeon["fluids"])
    for source_point in repairs:
        position = dict(zip(("x", "y", "z"), relative(source_point)))
        dungeon["blocks"].append({**position, "name": "Empty"})
        dungeon["fluids"].append({**position, "name": "Empty", "level": 0})
    # Keep new solids separate from the air mask. Clear fluids only at these
    # exact wall and window cells so cave water cannot obscure the reconstruction.
    for source_block in walls:
        position = dict(zip(("x", "y", "z"), relative(point(source_block))))
        dungeon["blocks"].append({**source_block, **position})
        dungeon["fluids"].append({**position, "name": "Empty", "level": 0})
    dungeon.update(anchorX=0, anchorY=0, anchorZ=0)
    dungeon["entities"] = [{"Components": {
        "CryptSite": {"Cleared": False, "RewardDeposited": False},
        "Transform": {"Position": dict(zip(("X", "Y", "Z"), [p-a for p,a in zip(PIT, ANCHOR)])),
                      "Rotation": {"Pitch": 0.0, "Yaw": 0.0, "Roll": 0.0}},
        "HiddenFromAdventurePlayer": {}, "Intangible": {}}}]
    write_dungeon(OUT / "SkeletonDungeon_Site.prefab.json", dungeon)
    write_roof_profile(dungeon)
    write(AIR_AUDIT, audit)
    write(WALL_AUDIT, wall_audit)
    parts = (json.loads((ROOT / "tools/crypt-prefab-manifest.json").read_text())["parts"]
        if dungeon_only else import_parts(source))
    manifest = {"sourceSHA256": hashlib.sha256(original.read_bytes()).hexdigest(),
                "sourceEntranceAnchor": ANCHOR, "sourceArenaCenter": PIT,
                "sourceCoffin": COFFIN, "sourceMarkers": markers,
                "removedMarkers": removed, "replacedCoffinBlocks": replaced,
                "interiorAirRepair": {"audit": "tools/crypt-interior-air-audit.json",
                    "addedAirBlocks": len(repairs), "addedEmptyFluids": len(repairs),
                    "sourceBlockCount": source_block_count,
                    "packagedBlockCount": len(dungeon["blocks"]),
                    "packagedAirCount": sum(b["name"] == "Empty" for b in dungeon["blocks"]),
                    "regions": audit["regions"], "auditSHA256": hashlib.sha256(AIR_AUDIT.read_bytes()).hexdigest()},
                "wallRepair": {"audit": "tools/crypt-wall-repair-audit.json",
                    "addedSolidBlocks": wall_audit["addedSolidBlocks"], "addedAirBlocks": wall_audit["addedAirBlocks"],
                    "addedEmptyFluids": len(walls), "addedUndergroundColumns": wall_audit["addedUndergroundColumns"],
                    "auditSHA256": hashlib.sha256(WALL_AUDIT.read_bytes()).hexdigest()},
                "parts": parts, "roomFloorSourceY": 17,
                "placement": "Rigid normal biome prefab; entrance y=0, room floor y=-57; caves first, prefab last."}
    write(ROOT / "tools/crypt-prefab-manifest.json", manifest)
    validate(dungeon, source)
    print(json.dumps(manifest, indent=2))


def validate(dungeon, source=None):
    blocks = dungeon["blocks"]
    assert ROOF_PROFILE.read_text(encoding="utf-8") == roof_profile(dungeon), "Roof profile is stale"
    assert not any("Soil_Clay_" in b["name"] for b in blocks)
    assert sum(b["name"] == "Titan_Crypt_Coffin" for b in blocks) == 2
    assert min(b["y"] for b in blocks) == -74
    assert max(b["y"] for b in blocks) == 19
    assert len({(b["x"],b["y"],b["z"]) for b in blocks}) == len(blocks)
    assert all(b["name"] == "Empty" and b["level"] == 0 for b in dungeon["fluids"])
    assert any(b["x"] == 0 and b["y"] == 0 and b["name"] != "Empty" for b in blocks)
    manifest = json.loads((ROOT/"tools/crypt-prefab-manifest.json").read_text())
    audit = json.loads(AIR_AUDIT.read_text())
    wall_audit = json.loads(WALL_AUDIT.read_text())
    wall_repair = manifest["wallRepair"]
    assert hashlib.sha256(WALL_AUDIT.read_bytes()).hexdigest() == wall_repair["auditSHA256"]
    wall_added = {relative(p) for p in audited_cells(wall_audit)}
    assert len(wall_added) == wall_repair["addedEmptyFluids"] == 5714
    assert wall_repair["addedSolidBlocks"] == wall_audit["addedSolidBlocks"] == 5684
    assert wall_repair["addedAirBlocks"] == wall_audit["addedAirBlocks"] == 30
    repair = manifest["interiorAirRepair"]
    assert hashlib.sha256(AIR_AUDIT.read_bytes()).hexdigest() == repair["auditSHA256"]
    added = {relative(p) for p in audit["addedSourceCells"]}
    assert not added & wall_added, "Wall reconstruction overlaps the original interior-air mask"
    all_added = added | wall_added
    assert len(added) == audit["addedAirBlocks"] == repair["addedAirBlocks"] == 5267
    assert len(blocks) == repair["packagedBlockCount"] == repair["sourceBlockCount"] + len(all_added)
    assert sum(b["name"] == "Empty" for b in blocks) == repair["packagedAirCount"] == 185339 + len(added) + 30
    assert cell_hash([b for b in blocks if point(b) not in all_added]) == audit["unchangedExistingBlocksSHA256"]
    assert cell_hash([b for b in dungeon["fluids"] if point(b) not in all_added]) == audit["unchangedExistingFluidsSHA256"]
    assert {point(b) for b in blocks if point(b) in added} == added
    assert {point(b) for b in dungeon["fluids"] if point(b) in added} == added
    assert {point(b) for b in blocks if point(b) in wall_added} == wall_added
    assert {point(b) for b in dungeon["fluids"] if point(b) in wall_added} == wall_added
    cells = {point(b): b for b in blocks}
    for group in [*wall_audit["layers"], {**wall_audit["windowAir"], "block": "Empty"}]:
        for mapping in group["sourceMappings"]:
            cell = cells[relative(mapping[:3])]
            assert cell["name"] == group["block"]
            if cell["name"] != "Empty": assert cell["rotation"] == group["rotation"]
    assert all(b == {**dict(zip(("x","y","z"),point(b))), "name":"Empty"}
        for b in blocks if point(b) in added)
    if source is not None and (source / "SkeletonDungeon.prefab.json").exists():
        original = source / "SkeletonDungeon.prefab.json"
        assert hashlib.sha256(original.read_bytes()).hexdigest() == manifest["sourceSHA256"]
        source_dungeon = json.loads(original.read_text())
        expected, proof = interior_air(source_dungeon["blocks"])
        assert [list(p) for p in expected] == audit["addedSourceCells"], "Interior repair mask is stale"
        expected_audit = air_audit(source_dungeon, expected, proof)
        assert all(audit[key] == value for key,value in expected_audit.items())
        expected_walls, expected_wall_audit = missing_walls(source_dungeon["blocks"])
        assert wall_audit == expected_wall_audit, "Wall donor audit is stale"
        for wall in expected_walls:
            position = dict(zip(("x", "y", "z"), relative(point(wall))))
            assert cells[point(position)] == {**wall, **position}
    assert set(manifest["parts"]) == {f"Skeleton_{name}.prefab.json" for name in PARTS}
    for filename, part in manifest["parts"].items():
        assert hashlib.sha256((OUT / filename).read_bytes()).hexdigest() == part["packagedSHA256"] == part["sourceSHA256"], filename
    cells = {(b["x"],b["y"],b["z"]): b for b in blocks}
    for points in manifest["sourceMarkers"].values():
        for marker_point in points:
            marker_relative = tuple(p-a for p,a in zip(marker_point, ANCHOR))
            assert cells[marker_relative]["name"] == "Empty", marker_relative
    coffin = cells[tuple(p-a for p,a in zip(COFFIN, ANCHOR))]
    assert coffin["name"] == "Titan_Crypt_Coffin" and coffin["rotation"] == 1
    assert cells[(-22-64,20-74,-3+2)]["filler"] == 992
    marker = dungeon["entities"][0]["Components"]
    assert marker["Transform"]["Position"] == dict(zip(("X","Y","Z"), [p-a for p,a in zip(PIT,ANCHOR)]))
    mod = json.loads((ROOT/"src/main/resources/Server/WorldGen/Modifier/Crypt_Sites.json").read_text())
    placement = mod["Operations"][0]["Content"]
    assert placement["Type"] == "TitanCryptPrefab"
    assert placement["FitHeightmap"] is False and placement["Submerge"] is False
    assert placement["Rotations"] == ["Rotation0"]
    assert placement["HeightMask"]["Min"] >= 75
    # Parent masks match resolved block IDs, not the item inheritance tree.
    # These variants are the natural top layer in the selected Zone 1 biomes.
    grass = {"Soil_Grass", "Soil_Grass_Full", "Soil_Grass_Sunny", "Soil_Grass_Deep"}
    assert grass <= set(placement["ParentMask"]["Include"]["Blocks"])
    game = ROOT.parent/"HytaleSourceCode/hytale-shared-source/HytaleAssets"
    if game.exists():
        for block in grass:
            assert (game/f"Server/Item/Items/Soil/Grass/{block}.json").is_file(), block
        for rule in mod["Target"]["Rules"]:
            zone, biome = rule.removeprefix("Zones.").split(".", 1)
            assert (game/f"Server/World/Default/Zones/{zone}/{biome}.json").is_file(), rule


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, nargs="?", default=Path.home()/"Downloads/Prefabs")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="Validate the packaged dungeon without importing")
    mode.add_argument("--parts-only", action="store_true", help="Update only supplied body parts and their hashes")
    mode.add_argument("--dungeon-only", action="store_true", help="Rebuild only dungeon, repair audits, and roof profile")
    mode.add_argument("--profile-only", action="store_true", help="Rebuild terrain cover checks from the packaged dungeon")
    args = parser.parse_args()
    if args.check:
        validate(json.loads((OUT/"SkeletonDungeon_Site.prefab.json").read_text()), args.source)
        print("Crypt prefab validated: 5,267 interior air repairs; 5,684 restored wall blocks and 30 mirrored window openings; all pre-existing cells and other exterior omissions unchanged; markers, coffin, and roof cover valid.")
    elif args.parts_only:
        import_parts_only(args.source)
    elif args.profile_only:
        write_roof_profile(json.loads((OUT/"SkeletonDungeon_Site.prefab.json").read_text()))
        print("Rebuilt complete underground roof profile from the packaged dungeon.")
    else:
        prepare(args.source, args.dungeon_only)

