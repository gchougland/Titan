"""Measures the true bounds of a .blockymodel so a ModelAsset HitBox can be authored to match it.

The ore weakpoint's declared HitBox has to cover the geometry it renders, otherwise the node draws in one
place and is struck in another, and the spawner reads the box back to seat a node on its socket.

The grid resolution is not recorded in the file and differs by model kind: block and item art is authored
at 32 units per block, character and creature art at 64. Both were confirmed against models whose declared
HitBox is a close fit: NPC_Spawner_Block is a 2-block post that only measures 2 blocks at 32, and the
grizzly bear's 1.8-block hitbox only matches its mesh at 64.

Usage:  python tools/measure_ore_model.py [path.blockymodel] [units-per-block]
"""

import json
import sys
from pathlib import Path

BLOCK_UNITS = 32.0
CHARACTER_UNITS = 64.0

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
    units = float(sys.argv[2]) if len(sys.argv) > 2 else BLOCK_UNITS
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
