"""Checks the generated Talus clips against the skeleton and the server-side parser's expectations.

Run from the repo root: python tools/check_talus_clips.py
"""

import glob
import json
import os
import sys

SKELETON = "src/main/resources/Server/Titan/Skeletons/Stone_Talus.json"
CLIP_SET = "src/main/resources/Server/Titan/Clips/Stone_Talus.json"
COMMON_ROOT = "src/main/resources/Common"


def main() -> int:
    skeleton = json.load(open(SKELETON, encoding="utf-8"))
    bones = {bone["Name"] for bone in skeleton["Bones"]}

    clip_set = json.load(open(CLIP_SET, encoding="utf-8"))
    declared = {name: entry["File"] for name, entry in clip_set["Animations"].items()}

    problems = []

    for name, rel in sorted(declared.items()):
        path = os.path.join(COMMON_ROOT, rel)
        if not os.path.isfile(path):
            problems.append(f"{name}: clip set points at missing file {rel}")
            continue

        clip = json.load(open(path, encoding="utf-8"))
        if not isinstance(clip.get("duration"), (int, float)) or clip["duration"] <= 0:
            problems.append(f"{name}: duration must be a positive frame count")

        nodes = clip.get("nodeAnimations", {})
        unknown = sorted(set(nodes) - bones)
        if unknown:
            problems.append(f"{name}: animates bones that the skeleton does not declare: {unknown}")

        keyframes = 0
        for bone, track in nodes.items():
            for channel, size in (("position", 3), ("orientation", 4)):
                for i, key in enumerate(track.get(channel, [])):
                    keyframes += 1
                    if not isinstance(key.get("time"), (int, float)):
                        problems.append(f"{name}/{bone}/{channel}[{i}]: missing numeric time")
                    if key["time"] > clip["duration"]:
                        problems.append(f"{name}/{bone}/{channel}[{i}]: time {key['time']} past duration {clip['duration']}")
                    delta = key.get("delta", {})
                    missing = [axis for axis in "xyzw"[:size] if axis not in delta]
                    if missing:
                        problems.append(f"{name}/{bone}/{channel}[{i}]: delta missing {missing}")

        print(f"{name:14} {clip['duration']:6.1f}f  {len(nodes):2} bones  {keyframes:3} keys  {rel}")

    orphans = sorted(
        os.path.relpath(p, COMMON_ROOT).replace("\\", "/")
        for p in glob.glob(os.path.join(COMMON_ROOT, "Titan/Talus/Animations/*.blockyanim"))
    )
    for orphan in set(orphans) - set(declared.values()):
        problems.append(f"clip file {orphan} is not referenced by the clip set")

    for problem in problems:
        print(f"PROBLEM: {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
