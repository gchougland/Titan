"""Restore the two recessed crypt walls clipped by the author's selection.

Coordinates remain in the original prefab frame. Front x=5 and left z=37
have bone faces one block behind their trim and basalt another block behind.
The selected back x=-66 and right z=-41 stop at their trim. Only their repeated
recess spans are extended; the existing corner masonry and front entrance stay
untouched. The old interior-air algorithm must run on the original selection,
not on these expanded bounds.
"""
from __future__ import annotations

from copy import deepcopy

BONE = "Deco_Bone_Skulls_Wall"
BASALT = "Rock_Basalt_Cobble"
BACK_PANELS = tuple(range(-35, 29, 7))
RIGHT_PANELS = tuple(range(-60, -3, 7))
BOTTOM, TOP = 18, 45


def point(block):
    return tuple(block[a] for a in ("x", "y", "z"))


def missing_walls(blocks):
    """Return additions and a cell-by-cell donor audit without mutating input."""
    cells = {point(block): block for block in blocks}
    assert min(p[0] for p in cells) == -66 and min(p[2] for p in cells) == -41
    additions = {}
    layers = []

    def layer(side, name, yaw, plane, span, panels=None):
        mappings = []
        templated = 0
        across = [v for start in panels for v in range(start, start + 4)] if panels else list(span)
        for y in range(BOTTOM, TOP + 1):
            for v in across:
                target = (plane, y, v) if side == "back" else (v, y, plane)
                donor = (-61-plane, y, v) if side == "back" else (v, y, -4-plane)
                if cells.get(donor, {}).get("name") != name:
                    # The front doorway interrupts two lower recess panels, but
                    # the back trim has ordinary windows here. Repeat the first
                    # intact seven-block bay instead of creating a back doorway.
                    assert side == "back" and -8 <= v <= 4 and y <= 31, donor
                    donor = (donor[0], y, -35 + (v + 35) % 7)
                    templated += 1
                source = cells[donor]
                assert source["name"] == name, donor
                # Skull-wall models need their visible face reversed inward.
                # Cobble is a full cube; the front doorway has a few otherwise
                # identical cubes with different saved rotations.
                if name == BONE: assert source.get("rotation", 0) == yaw-2, donor
                assert target not in cells and target not in additions, target
                block = deepcopy(source)
                block.update(zip(("x", "y", "z"), target))
                block["rotation"] = yaw
                additions[target] = block
                mappings.append([*target, *donor])
        layers.append({"side": side, "block": name, "rotation": yaw,
            "addedBlocks": len(mappings), "repeatedDoorwayDonors": templated,
            "mappingFormat": "target x,y,z then original donor x,y,z",
            "sourceMappings": mappings})

    layer("back", BONE, 3, -67, range(-35, 32), BACK_PANELS)
    layer("back", BASALT, 3, -68, range(-35, 32))
    layer("right", BONE, 2, -42, range(-60, 0), RIGHT_PANELS)
    layer("right", BASALT, 2, -43, range(-60, 0))

    # Some openings in the last selected plane were also omitted. All have an
    # exact authored Empty donor opposite, between intact trim and a new bone
    # face; clear only those window cells, never an exterior rectangular volume.
    openings = []
    for start in RIGHT_PANELS:
        for x in (start+1, start+2):
            for base in (19, 26, 33, 40):
                for y in range(base, base+3):
                    target = (x, y, -41)
                    donor = (x, y, 37)
                    assert cells[donor]["name"] == "Empty", donor
                    if target in cells:
                        assert cells[target]["name"] == "Empty", target
                        continue
                    assert additions[(x, y, -42)]["name"] == BONE
                    block = deepcopy(cells[donor])
                    block.update(zip(("x", "y", "z"), target))
                    additions[target] = block
                    openings.append([*target, *donor])

    assert [item["addedBlocks"] for item in layers] == [1120, 1876, 1008, 1680]
    assert len(openings) == 30
    assert not cells.keys() & additions.keys()
    audit = {
        "reason": "Original selection clipped the back and right recessed bone faces and their basalt backing.",
        "sourceSelectionMinimum": {"x": -66, "z": -41},
        "reflectionPlanes": {"frontToBack": "x'=-61-x", "leftToRight": "z'=-4-z"},
        "sourceYRange": [BOTTOM, TOP], "panelWidth": 4,
        "backPanelStartSourceZ": list(BACK_PANELS), "rightPanelStartSourceX": list(RIGHT_PANELS),
        "backBackingSourceZRange": [-35, 31], "rightBackingSourceXRange": [-60, -1],
        "scope": "Bone panels and continuous basalt backing stop at the first and last recess edges; all existing cells, corner masonry, front doorway, and other terrain omissions are unchanged.",
        "layers": layers,
        "windowAir": {"addedBlocks": len(openings), "mappingFormat": "target x,y,z then original donor x,y,z", "sourceMappings": openings},
        "addedSolidBlocks": sum(item["addedBlocks"] for item in layers),
        "addedAirBlocks": len(openings), "addedEmptyFluids": len(additions),
        "addedUndergroundColumns": 203, "changedExistingCells": 0,
    }
    return [additions[p] for p in sorted(additions)], audit


def audited_cells(audit):
    return {tuple(row[:3]) for group in [*audit["layers"], audit["windowAir"]]
        for row in group["sourceMappings"]}
