#!/usr/bin/env python3
"""Imports the authored Baba Yaga prefabs into the mod's asset tree.

The house parts are copied under the mod's naming scheme. The egg is the one prefab that has to be cut
apart: it was authored as an egg sitting in its nest, but the nest is scenery that stays behind in the
world while only the white shell becomes a titan. The two are separated by block palette, which is
unambiguous here -- the shell is the white clay and the nest is the needles and hive.

Run from anywhere:

    python tools/split_yaga_prefabs.py [source-directory]

Defaults to the Downloads folder the prefabs were authored in. Re-running is safe; outputs are rewritten.
"""

from __future__ import annotations

import json
import os
import sys

# Block palettes that decide which half of the egg prefab a cell belongs to.
SHELL_BLOCKS = {"Soil_Clay_Smooth_White", "Soil_Clay_White"}
NEST_BLOCKS = {"Soil_Needles", "Soil_Hive"}

# Authored name -> mod asset name. The egg is handled separately.
RENAMES = {
    "BabyYaga_Body": "Yaga_Baby_Body",
    "BabyYaga_Thigh": "Yaga_Baby_Thigh",
    "BabyYaga_Calf": "Yaga_Baby_Calf",
    "BabyYaga_Foot": "Yaga_Baby_Foot",
    "BabyYaga_Tail": "Yaga_Baby_Tail",
    "BabaYaga_Body": "Yaga_Baba_Body",
    "BabaYaga_ThighL": "Yaga_Baba_ThighL",
    "BabaYaga_ThighR": "Yaga_Baba_ThighR",
    "BabaYaga_CalfL": "Yaga_Baba_CalfL",
    "BabaYaga_CalfR": "Yaga_Baba_CalfR",
    "BabaYaga_FootL": "Yaga_Baba_FootL",
    "BabaYaga_FootR": "Yaga_Baba_FootR",
    "BabaYaga_Tail": "Yaga_Baba_Tail",
}

EGG_SOURCE = "BabaYaga_Egg"

DEFAULT_SOURCE = os.path.join(os.path.expanduser("~"), "Downloads", "Prefabs")
TARGET = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources", "Server", "Prefabs", "Titan", "Yaga",
)


def load(source: str, name: str) -> dict:
    with open(os.path.join(source, name + ".prefab.json"), "r", encoding="utf-8") as handle:
        return json.load(handle)


def write(prefab: dict, name: str) -> None:
    path = os.path.join(TARGET, name + ".prefab.json")
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(prefab, handle, indent=2)
        handle.write("\n")
    print(f"  {name}: {len(prefab['blocks'])} blocks")


def rebase(prefab: dict, blocks: list, origin: tuple) -> dict:
    """Shifts a subset of cells by a shared origin.

    Both halves are shifted by the same amount rather than each by its own extents, so the shell still
    sits where it sat inside the nest. The nest is painted into the world at the titan's spawn position
    and the shell is spawned as bone geometry above it; if they were rebased independently the egg would
    settle a block into its own nest.
    """
    keys = {(b["x"], b["y"], b["z"]) for b in blocks}
    min_x, min_y, min_z = origin

    out_blocks = []
    for block in blocks:
        moved = dict(block)
        moved["x"] = block["x"] - min_x
        moved["y"] = block["y"] - min_y
        moved["z"] = block["z"] - min_z
        out_blocks.append(moved)

    # The fluid list is positional and must stay aligned with the blocks it covers.
    out_fluids = []
    for fluid in prefab.get("fluids", []):
        if (fluid["x"], fluid["y"], fluid["z"]) not in keys:
            continue
        moved = dict(fluid)
        moved["x"] = fluid["x"] - min_x
        moved["y"] = fluid["y"] - min_y
        moved["z"] = fluid["z"] - min_z
        out_fluids.append(moved)

    return {
        "version": prefab["version"],
        "blockIdVersion": prefab["blockIdVersion"],
        "anchorX": 0,
        "anchorY": 0,
        "anchorZ": 0,
        "blocks": out_blocks,
        "fluids": out_fluids,
    }


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SOURCE
    if not os.path.isdir(source):
        print(f"Source directory not found: {source}", file=sys.stderr)
        return 1

    os.makedirs(TARGET, exist_ok=True)

    print(f"Reading from {source}")
    print(f"Writing to {TARGET}")

    print("Copying house parts:")
    for authored, renamed in RENAMES.items():
        write(load(source, authored), renamed)

    print("Splitting the egg:")
    egg = load(source, EGG_SOURCE)
    shell = [b for b in egg["blocks"] if b["name"] in SHELL_BLOCKS]
    nest = [b for b in egg["blocks"] if b["name"] in NEST_BLOCKS]

    unclaimed = {b["name"] for b in egg["blocks"]} - SHELL_BLOCKS - NEST_BLOCKS
    if unclaimed:
        print(f"  unrecognised egg blocks, dropped: {sorted(unclaimed)}", file=sys.stderr)

    origin = (
        min(b["x"] for b in egg["blocks"]),
        min(b["y"] for b in egg["blocks"]),
        min(b["z"] for b in egg["blocks"]),
    )
    write(rebase(egg, shell, origin), "Yaga_Egg_Shell")
    write(rebase(egg, nest, origin), "Yaga_Egg_Nest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
