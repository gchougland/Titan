"""Measures the true bounds of a .blockymodel so a ModelAsset HitBox can be authored to match it.

The ore weakpoint's declared HitBox has to cover the geometry it renders, otherwise the node draws in one
place and is struck in another, and the spawner reads the box back to seat a node on its socket.

The grid resolution is not recorded in the file, and it is not a property of the file either: it is the
convention of whatever draws the mesh.

  - Block art is authored at 32 units per block. 290 of the 1030 meshes under Blocks/ measure exactly 32
    units across, and NPC_Spawner_Block is a 2-block post that measures exactly 1x2x1 at 32.
  - An entity model, which is what a ModelAsset is, renders at 64. Characters/Player.blockymodel is 102
    units tall, a 1.6-block player at 64 and an absurd 3.2-block one at 32.

So pass 64 when measuring for a ModelAsset HitBox, whatever grid the art was authored on. Block art
reused as an entity model, as the ore and crystal weakpoints are, draws at half the size it was modelled
at; measuring it at 32 yields a box twice too big, and TitanSpawner reads that box to seat the node, so
an oversized one buries the node in the body it is bolted to.

Usage:  python tools/measure_ore_model.py [path.blockymodel] [units-per-block]
"""

import json
import sys
from pathlib import Path

BLOCK_UNITS = 32.0
ENTITY_UNITS = 64.0

DEFAULT_MODEL = (
    Path(__file__).resolve().parent.parent
    / "src/main/resources/Common/Items/Titan/OreNode/Ore_Large.blockymodel"
)


def rotation_matrix(q):
    w, x, y, z = q.get("w", 1.0), q.get("x", 0.0), q.get("y", 0.0), q.get("z", 0.0)
    return (
        (1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
        (2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
        (2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)),
    )


def walk(node, parent_pos, lo, hi):
    pos = node.get("position") or {"x": 0, "y": 0, "z": 0}
    origin = tuple(parent_pos[i] + pos.get(k, 0.0) for i, k in enumerate("xyz"))

    shape = node.get("shape") or {}
    if shape.get("type") == "box":
        size = (shape.get("settings") or {}).get("size") or {"x": 0, "y": 0, "z": 0}
        stretch = shape.get("stretch") or {"x": 1, "y": 1, "z": 1}
        offset = shape.get("offset") or {"x": 0, "y": 0, "z": 0}
        half = [size.get(k, 0.0) * stretch.get(k, 1.0) / 2.0 for k in "xyz"]
        m = rotation_matrix(node.get("orientation") or {})

        for sx in (-1, 1):
            for sy in (-1, 1):
                for sz in (-1, 1):
                    local = [
                        offset.get("x", 0.0) + sx * half[0],
                        offset.get("y", 0.0) + sy * half[1],
                        offset.get("z", 0.0) + sz * half[2],
                    ]
                    for axis in range(3):
                        v = origin[axis] + sum(m[axis][i] * local[i] for i in range(3))
                        lo[axis] = min(lo[axis], v)
                        hi[axis] = max(hi[axis], v)

    for child in node.get("children") or []:
        walk(child, origin, lo, hi)


def main():
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_MODEL
    units = float(sys.argv[2]) if len(sys.argv) > 2 else ENTITY_UNITS
    model = json.loads(path.read_text(encoding="utf-8"))

    lo = [float("inf")] * 3
    hi = [float("-inf")] * 3
    for root in model.get("nodes") or []:
        walk(root, (0.0, 0.0, 0.0), lo, hi)

    print(f"{path.name}  at {units:g} units/block")
    for axis, name in enumerate("XYZ"):
        a, b = lo[axis] / units, hi[axis] / units
        print(f"  {name}: {a:+.3f} .. {b:+.3f}  (size {b - a:.3f} blocks)")
    print(f"  centre Y: {(lo[1] + hi[1]) / 2 / units:+.3f} blocks")


if __name__ == "__main__":
    main()
