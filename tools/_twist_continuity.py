"""Checks that IkMath.uprightTwist is continuous as a bone sweeps through the handover band.

Replicates the old and new versions and sweeps a shin's angle off vertical through the 15 degree
threshold, both leaning toward its pole and away from it. The interesting number is the largest jump in
the twist reference between two neighbouring samples: the old version should show a half turn on the
away-leaning sweep, the new one should show essentially nothing on either.
"""

import math

BAND_SIN_SQ = 0.067
BAND_SIN = math.sqrt(BAND_SIN_SQ)
EPS = 1e-6


def projected_up(direction):
    along = direction[1]
    return (-direction[0] * along, 1 - along * along, -direction[2] * along)


def norm(v):
    length = math.sqrt(sum(c * c for c in v))
    return tuple(c / length for c in v)


def old_twist(direction, pole):
    up = projected_up(direction)
    if sum(c * c for c in up) < BAND_SIN_SQ:
        return pole
    return norm(up)


def new_twist(direction, pole):
    up = projected_up(direction)
    sin_sq = sum(c * c for c in up)
    if sin_sq >= BAND_SIN_SQ:
        return norm(up)

    side = -1.0 if sum(a * b for a, b in zip(up, pole)) < 0 else 1.0
    s = math.sqrt(sin_sq) / BAND_SIN
    t = s * s * (3 - 2 * s)

    if sin_sq > EPS * EPS:
        scale = t / math.sqrt(sin_sq)
        up = tuple(c * scale for c in up)
    else:
        up = (0.0, 0.0, 0.0)

    weight = (1 - t) * side
    blended = tuple(u + p * weight for u, p in zip(up, pole))
    length = math.sqrt(sum(c * c for c in blended))
    return pole if length < EPS else tuple(c / length for c in blended)


def angle_between(a, b):
    dot = max(-1.0, min(1.0, sum(x * y for x, y in zip(a, b))))
    return math.degrees(math.acos(dot))


def sweep(label, lean_sign, pole, out_of_plane=0.0, start_deg=0.0):
    """Sweeps the bone from start_deg to 30 degrees off vertical, leaning along +/-X plus a Z tilt."""
    worst_old = worst_new = 0.0
    at_old = at_new = 0.0
    previous_old = previous_new = None

    for step in range(int(start_deg * 100), 3001):
        theta = math.radians(step * 0.01)
        # Down, tilted by theta toward the lean direction, with a little tilt out of the pole's plane.
        horizontal = math.sin(theta)
        direction = norm((
            lean_sign * horizontal * math.cos(out_of_plane),
            -math.cos(theta),
            horizontal * math.sin(out_of_plane),
        ))

        current_old = old_twist(direction, pole)
        current_new = new_twist(direction, pole)
        if previous_old is not None:
            jump_old = angle_between(previous_old, current_old)
            if jump_old > worst_old:
                worst_old, at_old = jump_old, step * 0.01
            jump_new = angle_between(previous_new, current_new)
            if jump_new > worst_new:
                worst_new, at_new = jump_new, step * 0.01
        previous_old, previous_new = current_old, current_new

    print(f"{label:<40} old {worst_old:7.2f} deg at {at_old:5.2f}    new {worst_new:7.2f} deg at {at_new:5.2f}")


POLE_X = (1.0, 0.0, 0.0)

print("Largest twist-reference jump between neighbouring 0.01 deg samples, and the angle it happens at.")
print("Sweeping the whole range including dead vertical:\n")
sweep("toward the pole", 1.0, POLE_X)
sweep("away from the pole (a bent knee's shin)", -1.0, POLE_X)
sweep("away, 30 deg out of the pole's plane", -1.0, POLE_X, math.radians(30))
sweep("away, 60 deg out of the pole's plane", -1.0, POLE_X, math.radians(60))
sweep("away, diagonal pole", -1.0, norm((0.7071, 0.0, 0.7071)), math.radians(20))

print("\nFrom 2 deg up, i.e. skipping dead vertical, where up has no direction to report either way:\n")
sweep("toward the pole", 1.0, POLE_X, start_deg=2.0)
sweep("away from the pole (a bent knee's shin)", -1.0, POLE_X, start_deg=2.0)
sweep("away, 30 deg out of the pole's plane", -1.0, POLE_X, math.radians(30), start_deg=2.0)
sweep("away, 60 deg out of the pole's plane", -1.0, POLE_X, math.radians(60), start_deg=2.0)
sweep("away, diagonal pole", -1.0, norm((0.7071, 0.0, 0.7071)), math.radians(20), start_deg=2.0)

print("\nThe temple's shin actually lives between 10 and 19 deg off vertical, leaning away from its pole.")
