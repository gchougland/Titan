"""Generates the Stone Talus .blockyanim clips.

Blockbench stores per-bone keyframes as quaternion *deltas* on top of the model's bind pose, which is
awkward to hand-write, so the clips are authored here as Euler angles per bone and converted. The files it
emits are stock-format .blockyanim and can be opened and re-edited in Blockbench against the stand-in model
that tools/generate_talus_model.py writes; this script is only the starting point.

Run from the repo root:  python tools/generate_talus_clips.py

Conventions, matching the runtime:
  - Bone names follow the vanilla player rig, so that character animations can be borrowed. See the
    skeleton JSON for what that buys and what a talus leaves out.
  - Titan-local space is +X right, +Y up, -Z forward, in model units (one prefab block). Note this is the
    opposite of the player rig on both axes, which is why borrowed clips need FlipFacing.
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
SHOULDER = (4.0, 1.3)     # x offset from the centreline, y relative to the Pelvis bone
HIP = (2.2, -2.0)
UPPER_ARM, LOWER_ARM = 3.4, 3.4
UPPER_LEG, LOWER_LEG = 1.8, 1.8
BODY_HEIGHT = 5.0
# The back slab reaches this far up and forward of the Pelvis node, per the skeleton's socket comment.
BACK_HALF_HEIGHT, BACK_HALF_DEPTH = 2.0, 2.0


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
# The arms are long relative to the shoulder height (6.8 units against 6.3) because they double as the
# ramp the player climbs after a smash. That length forces a heavily bent resting elbow, which is what
# gives the Talus its hunched knuckle-walker silhouette.

def elbow_back(upper_x, lower_x):
    """Re-solves an arm so the elbow bends backwards without moving the hand.

    Arm poses are far easier to author as "swing the whole arm forward, then fold the forearm back under
    it", but that leaves the elbow ahead of the shoulder-to-hand line, which reads as a joint bending the
    wrong way. Both arm segments are the same length, so the shoulder-to-hand chord bisects the elbow
    angle, and reflecting the bend across that chord is just this swap: same hand position, elbow behind.
    """
    return upper_x + lower_x, -lower_x


def one_arm(side, upper_x, upper_z, lower_x):
    """One arm's angles. `upper_z` is the outward splay, already signed for the side."""
    upper_x, lower_x = elbow_back(upper_x, lower_x)
    return {
        f'{side}-Arm': (upper_x, 0, upper_z),
        f'{side}-Forearm': (lower_x, 0, 0),
    }


def arm_pose(upper_x, upper_z, lower_x):
    """Symmetric arm angles, mirrored across the centreline."""
    return {
        **one_arm('L', upper_x, -upper_z, lower_x),
        **one_arm('R', upper_x, upper_z, lower_x),
    }


def leg_pose(upper_x, lower_x, foot_x):
    return {
        'L-Thigh': (upper_x, 0, 0),
        'L-Calf': (lower_x, 0, 0),
        'L-Foot': (foot_x, 0, 0),
        'R-Thigh': (upper_x, 0, 0),
        'R-Calf': (lower_x, 0, 0),
        'R-Foot': (foot_x, 0, 0),
    }


# Awake resting stance: knuckles down and slightly out, elbows flared back.
REST = {**arm_pose(42, 10, -100), **leg_pose(10, -20, 10)}

# Curled up: body dropped, elbows out, hands tucked under the chest, legs folded beneath.
#
# Note every arm angle here is stated elbow-forward and converted by elbow_back(), so the numbers read as
# "how far the arm swings and how far the forearm folds under it", not as final bone rotations.
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
    return {'Pelvis': [(frame, delta) for frame, delta in frames]}


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
                    (85, merge(blend(SLEEP, REST, 1.15), Pelvis=(-8, 0, 0))),
                    (150, merge(REST, Pelvis=(0, 0, 0))),
                ]),
                body_keys([(0, (0, -2.2, 0)), (40, (0, -1.6, 0)), (85, (0, 0.35, 0)), (150, (0, 0, 0))]))


def build_idle():
    # Breathing bob with a touch of sway; the arms drift a few degrees so it never looks frozen.
    return clip(180, False,
                poses_to_keys([
                    (0, merge(REST, Pelvis=(0, 0, 0))),
                    (60, merge(arm_pose(45, 12, -103), **leg_pose(10, -20, 10), Pelvis=(1.5, 2, 1))),
                    (120, merge(arm_pose(39, 8, -97), **leg_pose(10, -20, 10), Pelvis=(-1, -2, -1))),
                    (180, merge(REST, Pelvis=(0, 0, 0))),
                ]),
                body_keys([(0, (0, 0, 0)), (60, (0, 0.18, 0)), (120, (0, -0.12, 0)), (180, (0, 0, 0))]))


def build_walk():
    # Legs are driven by the gait planner, so this is the upper-body half of the cycle: arms swinging in
    # counter-phase with a heavy roll onto whichever foot is planted.
    left_forward = {**one_arm('L', 60, -10, -105), **one_arm('R', 24, 10, -95)}
    right_forward = {**one_arm('L', 24, -10, -95), **one_arm('R', 60, 10, -105)}
    legs = leg_pose(10, -20, 10)

    return clip(100, False,
                poses_to_keys([
                    (0, merge(REST, Pelvis=(2, 0, 0))),
                    (25, merge(left_forward, **legs, Pelvis=(3, -4, 5))),
                    (50, merge(REST, Pelvis=(2, 0, 0))),
                    (75, merge(right_forward, **legs, Pelvis=(3, 4, -5))),
                    (100, merge(REST, Pelvis=(2, 0, 0))),
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
        return one_arm(other, upper_x, sign * -14, lower_x)

    legs = leg_pose(14, -26, 12)

    return clip(110, True,
                poses_to_keys([
                    (0, merge(REST, Pelvis=(0, 0, 0))),
                    # Rear back, weight onto the opposite leg.
                    (35, merge(counterweight(15, -80), **legs, Pelvis=(-10, sign * 18, sign * -8))),
                    # Drive down and through.
                    (65, merge(counterweight(55, -120), **legs, Pelvis=(22, sign * -10, sign * 10))),
                    (110, merge(counterweight(45, -105), **legs, Pelvis=(16, sign * -6, sign * 6))),
                ]),
                body_keys([(0, (0, 0, 0)), (35, (0, 0.5, 0)), (65, (0, -0.9, 0)), (110, (0, -0.7, 0))]))


# --- Body slam -------------------------------------------------------------------------------------
#
# Both arms are pinned by IK for the whole move, so what these clips actually control is the Pelvis node.
# A negative X tips the slab's top towards -Z, and at -40 with the body dropped 2.2 the leading underside
# sits right on the floor while the front lip of the back comes down to about 3.0 — low enough to step onto
# from a braced forearm, which is the entire point of the move. See the climb_check output.

SLAM_PITCH = -40
SLAM_DROP = -2.2

REARED = merge(arm_pose(-15, 20, -60), **leg_pose(-10, -30, 4), Pelvis=(26, 0, 0))
FLOORED = merge(arm_pose(35, 12, -70), **leg_pose(45, -85, 20), Pelvis=(SLAM_PITCH, 0, 0))


def build_slam_windup():
    # Rocks back onto the hind legs. Deliberately slow and large: it is the tell that the slam is coming.
    return clip(66, True,
                poses_to_keys([(0, merge(REST, Pelvis=(0, 0, 0))), (40, blend(REST, REARED, 0.7)), (66, REARED)]),
                body_keys([(0, (0, 0, 0)), (40, (0, 0.8, 0)), (66, (0, 1.1, 0))]))


def build_slam():
    # Everything comes down at once. The AOE fires at frame 18, which is where the body bottoms out.
    return clip(30, True,
                poses_to_keys([(0, REARED), (18, FLOORED), (30, FLOORED)]),
                body_keys([(0, (0, 1.1, 0)), (18, (0, SLAM_DROP, 0)), (30, (0, SLAM_DROP, 0))]))


def build_prone():
    # Face down, heaving. This is the window the player climbs, so it barely moves.
    heave = merge(arm_pose(37, 12, -68), **leg_pose(47, -87, 20), Pelvis=(SLAM_PITCH - 1, 0, 0))
    return clip(150, False,
                poses_to_keys([(0, FLOORED), (60, heave), (110, FLOORED), (150, heave)]),
                body_keys([(0, (0, SLAM_DROP, 0)), (60, (0, SLAM_DROP + 0.15, 0)),
                           (110, (0, SLAM_DROP, 0)), (150, (0, SLAM_DROP + 0.15, 0))]))


def build_rise():
    # Shoves back up onto its feet and settles into the resting stance.
    return clip(96, True,
                poses_to_keys([(0, FLOORED), (48, blend(FLOORED, REST, 0.6)), (96, merge(REST, Pelvis=(0, 0, 0)))]),
                body_keys([(0, (0, SLAM_DROP, 0)), (48, (0, -1.0, 0)), (96, (0, 0, 0))]))


def build_stunned():
    # Hunched over the buried fist, straining to pull it free. This is the window the player climbs, so the
    # pose barely moves.
    #
    # The pitch is what makes the climb work. A negative Pelvis X tips the slab's top towards -Z, which drops
    # the front lip of the back from 7.0 to 4.1 and pulls it forward to meet the planted arm, taking the
    # arm from a 52-degree wall down to a 39-degree ramp. See the climb_check output. Crouching further
    # would flatten it more, but at this pitch the slab's leading underside is only 0.74 off the ground and
    # any more would have it ploughing through rises in the terrain.
    hunched = merge(arm_pose(50, 6, -95), **leg_pose(20, -34, 14), Pelvis=(-32, 0, 0))
    strain = merge(arm_pose(52, 6, -93), **leg_pose(22, -36, 14), Pelvis=(-34, 2, 0))
    return clip(120, False,
                poses_to_keys([(0, hunched), (45, strain), (85, hunched), (120, strain)]),
                body_keys([(0, (0, -1.5, 0)), (45, (0, -1.65, 0)), (85, (0, -1.5, 0)), (120, (0, -1.65, 0))]))


def build_death():
    # Buckle, then collapse. TitanPartSyncSystem cuts the voxels loose almost immediately, so only the
    # first half second of this is ever really seen.
    buckle = merge(arm_pose(60, 20, -70), **leg_pose(40, -70, 20), Pelvis=(18, 0, 6))
    collapse = merge(arm_pose(85, 45, -30), **leg_pose(75, -110, 30), Pelvis=(38, 0, 14))
    return clip(90, True,
                poses_to_keys([
                    (0, merge(REST, Pelvis=(0, 0, 0))),
                    (20, merge(arm_pose(30, 4, -110), **leg_pose(4, -10, 6), Pelvis=(-8, 0, -2))),
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
    'Slam_Windup': build_slam_windup,
    'Slam': build_slam,
    'Prone': build_prone,
    'Rise': build_rise,
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
        q_up = euler_to_quat(*pose[f'{side}-Arm'])
        d_up = rotate(q_up, (0, -1, 0))
        elbow = tuple(shoulder[i] + UPPER_ARM * d_up[i] for i in range(3))
        q_lo = quat_mul(q_up, euler_to_quat(*pose[f'{side}-Forearm']))
        d_lo = rotate(q_lo, (0, -1, 0))
        hand = tuple(elbow[i] + LOWER_ARM * d_lo[i] for i in range(3))
        out.append(f'hand_{side}=({hand[0]:6.2f},{hand[1]:6.2f},{hand[2]:6.2f})')

        hip = (sx * HIP[0], body_y + HIP[1], 0)
        q_ul = euler_to_quat(*pose[f'{side}-Thigh'])
        knee = tuple(hip[i] + UPPER_LEG * rotate(q_ul, (0, -1, 0))[i] for i in range(3))
        q_ll = quat_mul(q_ul, euler_to_quat(*pose[f'{side}-Calf']))
        foot = tuple(knee[i] + LOWER_LEG * rotate(q_ll, (0, -1, 0))[i] for i in range(3))
        out.append(f'foot_{side}=({foot[0]:6.2f},{foot[1]:6.2f},{foot[2]:6.2f})')
    print(f'  {pose_name:8} ' + '  '.join(out))


def climb_check(pose_name, body_euler, body_dy, reach=5.0):
    """Reports the geometry a player has to climb during a pose.

    The attacking arm is fully IK-driven onto the impact point, so its clip angles say nothing about what
    the arm looks like — the shoulder is what sets the slope. This walks the Pelvis node's own rotation and
    translation to find the shoulder and the front lip of the back slab, then prints the slope of the line
    from the planted fist up to the shoulder. Anything much past 45 degrees is a wall, not a ramp.
    """
    q_body = euler_to_quat(*body_euler)
    origin_y = BODY_HEIGHT + body_dy

    def place(local):
        v = rotate(q_body, local)
        return (v[0], origin_y + v[1], v[2])

    shoulder = place((SHOULDER[0], SHOULDER[1], 0))
    # Front lip of the back slab, the edge stepped onto from the arm.
    lip = place((0, BACK_HALF_HEIGHT, -BACK_HALF_DEPTH))
    # Front underside, which must stay clear of the ground.
    chin = place((0, -BACK_HALF_HEIGHT, -BACK_HALF_DEPTH))

    run = reach - shoulder[2]
    slope = math.degrees(math.atan2(shoulder[1], run)) if run > 0 else 90.0
    print(f'  {pose_name:9} shoulder={shoulder[1]:5.2f} high  back lip={lip[1]:5.2f} high at z{lip[2]:6.2f}  '
          f'underside={chin[1]:5.2f}  arm slope={slope:5.1f} deg')


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

    print('Climb geometry (ground at y=0, titan facing -Z, fist planted 5.0 ahead):')
    climb_check('STAND', (0, 0, 0), 0.0)
    climb_check('STUNNED', (-32, 0, 0), -1.5)
    climb_check('PRONE', (SLAM_PITCH, 0, 0), SLAM_DROP)

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
