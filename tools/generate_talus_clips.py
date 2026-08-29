"""Generates the Stone Talus .blockyanim clips.

Blockbench stores per-bone keyframes as quaternion *deltas* on top of the model's bind pose, which is
awkward to hand-write, so the clips are authored here as Euler angles per bone and converted. The files it
emits are stock-format .blockyanim and can be opened and re-edited in Blockbench against a stand-in model
whose node names match the Stone_Talus bone names; this script is only the starting point.

Run from the repo root:  python tools/generate_talus_clips.py

Conventions, matching the runtime:
  - Titan-local space is +X right, +Y up, -Z forward, in model units (one prefab block).
  - Euler angles are XYZ degrees composed the same way JOML's rotateXYZ does, because that is what
    TitanPose.resetToBind uses for bind rotations.
  - A bone's own axis points down its local -Y, so +X rotation swings a limb forward and +Z swings it
    towards the titan's right.
  - Times are frames at 60fps, which is what the format and BlockyAnimParser both assume.
"""

import json
import math
import os

FPS = 60
OUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources',
                       'Common', 'Titan', 'Talus', 'Animations')

# Mirrors Server/Titan/Skeletons/Stone_Talus.json. Kept here only so the forward-kinematics check below
# can report where a pose actually puts the hands and feet.
SHOULDER = (4.0, 1.3)     # x offset from the centreline, y relative to the Body bone
HIP = (2.2, -2.0)
UPPER_ARM, LOWER_ARM = 5.0, 4.5
UPPER_LEG, LOWER_LEG = 1.8, 1.8
BODY_HEIGHT = 5.0

ARMS = ['Arm_L_Upper', 'Arm_L_Lower', 'Hand_L', 'Arm_R_Upper', 'Arm_R_Lower', 'Hand_R']
LEGS = ['Leg_L_Upper', 'Leg_L_Lower', 'Foot_L', 'Leg_R_Upper', 'Leg_R_Lower', 'Foot_R']


def euler_to_quat(rx, ry, rz):
    """XYZ-ordered Euler degrees to a quaternion, composed as qx * qy * qz."""
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

    q = mul(mul(axis(rx, (1, 0, 0)), axis(ry, (0, 1, 0))), axis(rz, (0, 0, 1)))
    n = math.sqrt(sum(c * c for c in q))
    return tuple(round(c / n, 6) for c in q)


def rotate(q, v):
    x, y, z, w = q
    vx, vy, vz = v
    # t = 2 * (q_vec x v); v' = v + w*t + q_vec x t
    tx = 2 * (y * vz - z * vy)
    ty = 2 * (z * vx - x * vz)
    tz = 2 * (x * vy - y * vx)
    return (
        vx + w * tx + y * tz - z * ty,
        vy + w * ty + z * tx - x * tz,
        vz + w * tz + x * ty - y * tx,
    )


# --- Poses -----------------------------------------------------------------------------------------
#
# A pose maps a bone name to XYZ Euler degrees. Bones left out hold their bind rotation, which for the
# arms means hanging straight down through the floor, so every pose states the arms explicitly.
#
# The arms are deliberately long (9.5 units against a 6.3 shoulder) because they double as the ramp the
# player climbs after a smash. That length forces a heavily bent resting elbow, which is what gives the
# Talus its hunched knuckle-walker silhouette.

def arm_pose(upper_x, upper_z, lower_x):
    """Symmetric arm angles, mirrored across the centreline."""
    return {
        'Arm_L_Upper': (upper_x, 0, -upper_z),
        'Arm_L_Lower': (lower_x, 0, 0),
        'Arm_R_Upper': (upper_x, 0, upper_z),
        'Arm_R_Lower': (lower_x, 0, 0),
    }


def leg_pose(upper_x, lower_x, foot_x):
    return {
        'Leg_L_Upper': (upper_x, 0, 0),
        'Leg_L_Lower': (lower_x, 0, 0),
        'Foot_L': (foot_x, 0, 0),
        'Leg_R_Upper': (upper_x, 0, 0),
        'Leg_R_Lower': (lower_x, 0, 0),
        'Foot_R': (foot_x, 0, 0),
    }


# Awake resting stance: knuckles down and slightly out, elbows forward.
REST = {**arm_pose(42, 10, -100), **leg_pose(10, -20, 10)}

# Curled up: body dropped, elbows out, hands tucked under the chest, legs folded beneath.
SLEEP = {**arm_pose(70, 35, -140), **leg_pose(70, -140, 60)}


def blend(a, b, t):
    """Linear blend between two poses, for building transition keyframes."""
    keys = set(a) | set(b)
    out = {}
    for k in keys:
        pa = a.get(k, (0, 0, 0))
        pb = b.get(k, (0, 0, 0))
        out[k] = tuple(round(pa[i] + (pb[i] - pa[i]) * t, 2) for i in range(3))
    return out


def merge(base, **overrides):
    out = dict(base)
    out.update(overrides)
    return out


# --- Clip assembly ---------------------------------------------------------------------------------

def clip(duration, hold, rotation_keys, position_keys=None):
    """Builds the .blockyanim document.

    rotation_keys: bone -> list of (frame, (rx, ry, rz))
    position_keys: bone -> list of (frame, (dx, dy, dz)) in model units
    """
    nodes = {}
    bones = set(rotation_keys) | set(position_keys or {})
    for bone in sorted(bones):
        orientation = [
            {
                'time': frame,
                'delta': dict(zip('xyzw', euler_to_quat(*angles))),
                'interpolationType': 'smooth',
            }
            for frame, angles in rotation_keys.get(bone, [])
        ]
        position = [
            {
                'time': frame,
                'delta': {'x': round(d[0], 4), 'y': round(d[1], 4), 'z': round(d[2], 4)},
                'interpolationType': 'smooth',
            }
            for frame, d in (position_keys or {}).get(bone, [])
        ]
        nodes[bone] = {
            'position': position,
            'orientation': orientation,
            'shapeStretch': [],
            'shapeVisible': [],
            'shapeUvOffset': [],
        }

    return {
        'formatVersion': 1,
        'duration': duration,
        'holdLastKeyframe': hold,
        'nodeAnimations': nodes,
    }


def poses_to_keys(frames):
    """Turns [(frame, pose)] into the per-bone keyframe lists clip() wants."""
    keys = {}
    for frame, pose in frames:
        for bone, angles in pose.items():
            keys.setdefault(bone, []).append((frame, angles))
    return keys


def body_keys(frames):
    return {'Body': [(frame, delta) for frame, delta in frames]}


# --- The clips -------------------------------------------------------------------------------------

def build_sleep():
    # A slow swell so the boulder is not perfectly static and reads as alive up close.
    return clip(240, False,
                poses_to_keys([(0, SLEEP), (120, blend(SLEEP, REST, 0.04)), (240, SLEEP)]),
                body_keys([(0, (0, -2.2, 0)), (120, (0, -2.05, 0)), (240, (0, -2.2, 0))]))


def build_wake():
    # Shove off the ground, rock back, then settle into the resting stance.
    return clip(150, True,
                poses_to_keys([
                    (0, SLEEP),
                    (40, blend(SLEEP, REST, 0.25)),
                    (85, merge(blend(SLEEP, REST, 1.15), Body=(-8, 0, 0))),
                    (150, merge(REST, Body=(0, 0, 0))),
                ]),
                body_keys([(0, (0, -2.2, 0)), (40, (0, -1.6, 0)), (85, (0, 0.35, 0)), (150, (0, 0, 0))]))


def build_idle():
    # Breathing bob with a touch of sway; the arms drift a few degrees so it never looks frozen.
    return clip(180, False,
                poses_to_keys([
                    (0, merge(REST, Body=(0, 0, 0))),
                    (60, merge(arm_pose(45, 12, -103), **leg_pose(10, -20, 10), Body=(1.5, 2, 1))),
                    (120, merge(arm_pose(39, 8, -97), **leg_pose(10, -20, 10), Body=(-1, -2, -1))),
                    (180, merge(REST, Body=(0, 0, 0))),
                ]),
                body_keys([(0, (0, 0, 0)), (60, (0, 0.18, 0)), (120, (0, -0.12, 0)), (180, (0, 0, 0))]))


def build_walk():
    # Legs are driven by the gait planner, so this is the upper-body half of the cycle: arms swinging in
    # counter-phase with a heavy roll onto whichever foot is planted.
    left_forward = {
        'Arm_L_Upper': (60, 0, -10), 'Arm_L_Lower': (-105, 0, 0),
        'Arm_R_Upper': (24, 0, 10), 'Arm_R_Lower': (-95, 0, 0),
    }
    right_forward = {
        'Arm_L_Upper': (24, 0, -10), 'Arm_L_Lower': (-95, 0, 0),
        'Arm_R_Upper': (60, 0, 10), 'Arm_R_Lower': (-105, 0, 0),
    }
    legs = leg_pose(10, -20, 10)

    return clip(100, False,
                poses_to_keys([
                    (0, merge(REST, Body=(2, 0, 0))),
                    (25, merge(left_forward, **legs, Body=(3, -4, 5))),
                    (50, merge(REST, Body=(2, 0, 0))),
                    (75, merge(right_forward, **legs, Body=(3, 4, -5))),
                    (100, merge(REST, Body=(2, 0, 0))),
                ]),
                body_keys([(0, (0, 0, 0)), (25, (0, 0.3, 0)), (50, (0, -0.15, 0)),
                           (75, (0, 0.3, 0)), (100, (0, 0, 0))]))


def build_attack(side):
    """Windup and swing for one arm. The swinging arm is under IK from windup onwards, so what this clip
    actually contributes is the counterweight: the body twists into the blow and the other arm drops back.
    """
    sign = -1 if side == 'L' else 1
    other = 'R' if side == 'L' else 'L'

    def counterweight(upper_x, lower_x):
        return {
            f'Arm_{other}_Upper': (upper_x, 0, sign * -14),
            f'Arm_{other}_Lower': (lower_x, 0, 0),
        }

    legs = leg_pose(14, -26, 12)

    return clip(110, True,
                poses_to_keys([
                    (0, merge(REST, Body=(0, 0, 0))),
                    # Rear back, weight onto the opposite leg.
                    (35, merge(counterweight(15, -80), **legs, Body=(-10, sign * 18, sign * -8))),
                    # Drive down and through.
                    (65, merge(counterweight(55, -120), **legs, Body=(22, sign * -10, sign * 10))),
                    (110, merge(counterweight(45, -105), **legs, Body=(16, sign * -6, sign * 6))),
                ]),
                body_keys([(0, (0, 0, 0)), (35, (0, 0.5, 0)), (65, (0, -0.9, 0)), (110, (0, -0.7, 0))]))


def build_stunned():
    # Hunched over the buried fist, straining to pull it free. This is the window the player climbs, so the
    # pose barely moves.
    hunched = merge(arm_pose(50, 6, -95), **leg_pose(20, -34, 14), Body=(20, 0, 0))
    strain = merge(arm_pose(52, 6, -93), **leg_pose(22, -36, 14), Body=(23, 2, 0))
    return clip(120, False,
                poses_to_keys([(0, hunched), (45, strain), (85, hunched), (120, strain)]),
                body_keys([(0, (0, -0.8, 0)), (45, (0, -0.95, 0)), (85, (0, -0.8, 0)), (120, (0, -0.95, 0))]))


def build_death():
    # Buckle, then collapse. TitanPartSyncSystem cuts the voxels loose almost immediately, so only the
    # first half second of this is ever really seen.
    buckle = merge(arm_pose(60, 20, -70), **leg_pose(40, -70, 20), Body=(18, 0, 6))
    collapse = merge(arm_pose(85, 45, -30), **leg_pose(75, -110, 30), Body=(38, 0, 14))
    return clip(90, True,
                poses_to_keys([
                    (0, merge(REST, Body=(0, 0, 0))),
                    (20, merge(arm_pose(30, 4, -110), **leg_pose(4, -10, 6), Body=(-8, 0, -2))),
                    (50, buckle),
                    (90, collapse),
                ]),
                body_keys([(0, (0, 0, 0)), (20, (0, 0.2, 0)), (50, (0, -1.8, 0)), (90, (0, -3.4, 0))]))


CLIPS = {
    'Sleep': build_sleep,
    'Wake': build_wake,
    'Idle': build_idle,
    'Walk': build_walk,
    'Attack_Arm_L': lambda: build_attack('L'),
    'Attack_Arm_R': lambda: build_attack('R'),
    'Stunned': build_stunned,
    'Death': build_death,
}


# --- Forward-kinematics check ----------------------------------------------------------------------

def check(pose_name, pose, body_dy=0.0):
    """Reports where a pose puts the hands and feet, so obviously broken angles are caught here rather
    than by spawning a titan and watching its arms disappear into the floor."""
    body_y = BODY_HEIGHT + body_dy
    out = []
    for side, sx in (('L', -1), ('R', 1)):
        shoulder = (sx * SHOULDER[0], body_y + SHOULDER[1], 0)
        q_up = euler_to_quat(*pose[f'Arm_{side}_Upper'])
        d_up = rotate(q_up, (0, -1, 0))
        elbow = tuple(shoulder[i] + UPPER_ARM * d_up[i] for i in range(3))
        q_lo = quat_mul(q_up, euler_to_quat(*pose[f'Arm_{side}_Lower']))
        d_lo = rotate(q_lo, (0, -1, 0))
        hand = tuple(elbow[i] + LOWER_ARM * d_lo[i] for i in range(3))
        out.append(f'hand_{side}=({hand[0]:6.2f},{hand[1]:6.2f},{hand[2]:6.2f})')

        hip = (sx * HIP[0], body_y + HIP[1], 0)
        q_ul = euler_to_quat(*pose[f'Leg_{side}_Upper'])
        knee = tuple(hip[i] + UPPER_LEG * rotate(q_ul, (0, -1, 0))[i] for i in range(3))
        q_ll = quat_mul(q_ul, euler_to_quat(*pose[f'Leg_{side}_Lower']))
        foot = tuple(knee[i] + LOWER_LEG * rotate(q_ll, (0, -1, 0))[i] for i in range(3))
        out.append(f'foot_{side}=({foot[0]:6.2f},{foot[1]:6.2f},{foot[2]:6.2f})')
    print(f'  {pose_name:8} ' + '  '.join(out))


def quat_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    )


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    print('Effector positions for the base poses (model units, ground at y=0):')
    check('REST', REST)
    check('SLEEP', SLEEP, body_dy=-2.2)

    for name, build in CLIPS.items():
        document = build()
        path = os.path.normpath(os.path.join(OUT_DIR, f'{name}.blockyanim'))
        with open(path, 'w', encoding='utf-8') as handle:
            json.dump(document, handle, indent=2)
            handle.write('\n')
        frames = document['duration']
        print(f'{name:14} {frames:4} frames ({frames / FPS:.2f}s)  {len(document["nodeAnimations"])} bones  -> {path}')


if __name__ == '__main__':
    main()
