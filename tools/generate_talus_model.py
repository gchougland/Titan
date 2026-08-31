"""Builds a .blockymodel stand-in for the Stone Talus rig, for animating in Blockbench.

A titan has no model file of its own: it is assembled at runtime out of prefab voxels parented to the bones
in Server/Titan/Skeletons/Stone_Talus.json. Blockbench cannot open that, so this writes an equivalent model
in the stock .blockymodel schema, with one node per bone and a box standing in for each bone's prefab. Edit
animations against it, export the .blockyanim, and the runtime will play it as-is.

What makes that round-trip work is that both sides agree on units and on names:
  - Positions are in titan model units (one prefab block), matching what the runtime reads out of a clip's
    position keys. Do not rescale the model, or exported translations will be wrong by that factor.
  - Node names are the skeleton's bone names, which in turn are the vanilla player rig's names.

The boxes are untextured. They exist to show where the limbs are, not what they look like.

Run from the repo root:  python tools/generate_talus_model.py
"""

import json
import math
import os

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..'))
SKELETON = os.path.join(ROOT, 'src', 'main', 'resources', 'Server', 'Titan', 'Skeletons', 'Stone_Talus.json')
PREFAB_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'Server', 'Prefabs')
OUT = os.path.join(ROOT, 'tools', 'blockbench', 'Stone_Talus.blockymodel')


def load_json(path):
    with open(path, encoding='utf-8-sig') as handle:
        return json.load(handle)


def prefab_bounds(prefab_key):
    """Voxel extent of a prefab, as (size, centre) in prefab-local continuous coordinates.

    Voxels are indexed by their minimum corner, so a block at index 3 occupies 3.0 to 4.0. The centre is
    therefore half a unit past the midpoint of the indices.
    """
    path = os.path.join(PREFAB_DIR, *prefab_key.split('/')) + '.prefab.json'
    blocks = load_json(path)['blocks']

    lo = [min(b[axis] for b in blocks) for axis in 'xyz']
    hi = [max(b[axis] for b in blocks) for axis in 'xyz']
    size = [hi[i] - lo[i] + 1 for i in range(3)]
    centre = [(lo[i] + hi[i] + 1) / 2 for i in range(3)]
    return size, centre


def euler_to_quat(rx, ry, rz):
    """XYZ-ordered Euler degrees to a quaternion, matching TitanPose.resetToBind."""
    def axis(angle, ax):
        h = math.radians(angle) * 0.5
        s, c = math.sin(h), math.cos(h)
        return (s * ax[0], s * ax[1], s * ax[2], c)

    def mul(a, b):
        ax, ay, az, aw = a
        bx, by, bz, bw = b
        return (
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )

    return mul(mul(axis(rx, (1, 0, 0)), axis(ry, (0, 1, 0))), axis(rz, (0, 0, 1)))


def round3(value):
    return round(value + 0.0, 4)


def shape_for(bone):
    """The box standing in for a bone's prefab, or an empty shape for a bone that carries none.

    A bone's Pivot says where in the prefab the joint sits, so the box has to be offset by however far the
    prefab's centre is from that pivot. Getting this right is the difference between a model whose arms
    hang off the shoulders and one whose arms are centred on them.
    """
    prefab = bone.get('Prefab')
    if not prefab:
        return {
            'offset': {'x': 0, 'y': 0, 'z': 0},
            'stretch': {'x': 1, 'y': 1, 'z': 1},
            'textureLayout': {},
            'type': 'none',
            'settings': {'isPiece': False},
            'unwrapMode': 'custom',
            'visible': True,
            'doubleSided': False,
            'shadingMode': 'flat',
        }

    size, centre = prefab_bounds(prefab)
    scale = bone.get('Scale', 1.0)
    pivot = bone.get('Pivot', {'X': 0, 'Y': 0, 'Z': 0})
    mirror = -1 if bone.get('MirrorX') else 1

    offset = [
        mirror * (centre[0] - pivot.get('X', 0)) * scale,
        (centre[1] - pivot.get('Y', 0)) * scale,
        (centre[2] - pivot.get('Z', 0)) * scale,
    ]

    return {
        'offset': {'x': round3(offset[0]), 'y': round3(offset[1]), 'z': round3(offset[2])},
        'stretch': {'x': 1, 'y': 1, 'z': 1},
        'textureLayout': {},
        'type': 'box',
        'settings': {
            'size': {
                'x': round3(size[0] * scale),
                'y': round3(size[1] * scale),
                'z': round3(size[2] * scale),
            }
        },
        'unwrapMode': 'custom',
        'visible': True,
        'doubleSided': False,
        'shadingMode': 'flat',
    }


def build():
    skeleton = load_json(SKELETON)
    bones = skeleton['Bones']

    nodes = {}
    for index, bone in enumerate(bones):
        rotation = bone.get('Rotation', {'X': 0, 'Y': 0, 'Z': 0})
        quat = euler_to_quat(rotation.get('X', 0), rotation.get('Y', 0), rotation.get('Z', 0))
        offset = bone.get('Offset', {'X': 0, 'Y': 0, 'Z': 0})

        nodes[bone['Name']] = {
            'id': str(index + 1),
            'name': bone['Name'],
            'position': {
                'x': round3(offset.get('X', 0)),
                'y': round3(offset.get('Y', 0)),
                'z': round3(offset.get('Z', 0)),
            },
            'orientation': {
                'x': round3(quat[0]),
                'y': round3(quat[1]),
                'z': round3(quat[2]),
                'w': round3(quat[3]),
            },
            'shape': shape_for(bone),
            'children': [],
        }

    roots = []
    for bone in bones:
        parent = bone.get('Parent')
        if parent:
            nodes[parent]['children'].append(nodes[bone['Name']])
        else:
            roots.append(nodes[bone['Name']])

    return {'lod': 'auto', 'nodes': roots}


def describe(node, depth=0):
    shape = node['shape']
    if shape['type'] == 'box':
        s = shape['settings']['size']
        detail = f"box {s['x']} x {s['y']} x {s['z']}"
    else:
        detail = 'joint'
    print('  ' * depth + f"{node['name']:<14} {detail}")
    for child in node['children']:
        describe(child, depth + 1)


def main():
    model = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as handle:
        json.dump(model, handle, indent=2)
        handle.write('\n')

    for root in model['nodes']:
        describe(root)
    print(f'\n-> {OUT}')


if __name__ == '__main__':
    main()
