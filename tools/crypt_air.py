"""Conservative reconstruction of omitted editor-air cells inside the authored crypt.

Existing cells are immutable. A missing cell must be enclosed by authored cells,
or lie strictly between authored solids along all three axes with nearby opposing
walls, and it must join
authored air through those same bounded cells or existing partial block models.
No flood is permitted to extend through an unbounded exterior omission.
"""
from collections import deque

# Resolved from the supplied dungeon IDs' inherited Hytale BlockType.DrawType.
# Full cubes intentionally remain impassable, including decorative brick cubes.
PARTIAL_MODELS = frozenset("""
Deco_Bone_Skulls Deco_Bone_Skulls_Wall Deco_Bone_Spike
Furniture_Human_Ruins_Coffin Furniture_Human_Ruins_Ladder Furniture_Human_Ruins_Statue_Broken
Furniture_Temple_Dark_Brazier Plant_Moss_Rug_Green_Dark Plant_Moss_Wall_Green_Dark
Rock_Basalt_Brick_Pillar_Base Rock_Basalt_Brick_Pillar_Middle Rock_Basalt_Brick_Roof
Rock_Basalt_Brick_Roof_Flat Rock_Basalt_Brick_Roof_Hollow Rock_Basalt_Brick_Roof_Shallow
Rock_Basalt_Brick_Stairs Rock_Basalt_Brick_Wall Rock_Basalt_Cobble_Roof
Rock_Basalt_Cobble_Roof_Flat Rock_Basalt_Cobble_Roof_Hollow Rock_Basalt_Cobble_Roof_Shallow
Rock_Basalt_Cobble_Stairs Rock_Basalt_Cobble_Wall Rock_Basalt_Stairs
Rock_Crystal_Red_Large Rock_Crystal_Red_Medium Rubble_Basalt Rubble_Basalt_Medium
Survival_Trap_Spike_Iron
""".split())


def air(name):
    return name == "Empty" or name.startswith("Soil_Clay_")


def partial(name):
    return name.lstrip("*").split("_State_Definitions_")[0] in PARTIAL_MODELS


def interior_air(blocks):
    """Return sorted source-coordinate repairs and their independently testable proof sets."""
    cells = {(b["x"], b["y"], b["z"]): b["name"] for b in blocks}
    if len(cells) != len(blocks):
        raise ValueError("Duplicate source cells")
    low = tuple(min(p[a] for p in cells)-1 for a in range(3))
    sizes = tuple(max(p[a] for p in cells)-low[a]+2 for a in range(3))
    nx, ny, nz = sizes
    sx = ny*nz
    length = nx*sx

    def index(p):
        return (p[0]-low[0])*sx+(p[1]-low[1])*nz+p[2]-low[2]

    def point(i):
        x, remainder = divmod(i, sx)
        y, z = divmod(remainder, nz)
        return x+low[0], y+low[1], z+low[2]

    def neighbors(i):
        x, remainder = divmod(i, sx)
        y, z = divmod(remainder, nz)
        if x: yield i-sx
        if x+1 < nx: yield i+sx
        if y: yield i-nz
        if y+1 < ny: yield i+nz
        if z: yield i-1
        if z+1 < nz: yield i+1

    occupancy = bytearray(length)
    models = set()
    ranges = ({}, {}, {})
    for p, name in cells.items():
        i = index(p)
        occupancy[i] = 1 if air(name) else 2
        if partial(name): models.add(i)
        if air(name): continue
        for axis in range(3):
            column = tuple(p[a] for a in range(3) if a != axis)
            extent = ranges[axis].setdefault(column, [p[axis], p[axis]])
            extent[0] = min(extent[0], p[axis])
            extent[1] = max(extent[1], p[axis])

    # Flood omitted space from padded exterior. Authored air also bounds this pass:
    # an interior hole touching known room air is enclosed by the authored envelope.
    exterior = bytearray(length)
    exterior[0] = 1
    queue = deque([0])
    while queue:
        for i in neighbors(queue.popleft()):
            if not occupancy[i] and not exterior[i]:
                exterior[i] = 1
                queue.append(i)
    enclosed = {i for i in range(length) if not occupancy[i] and not exterior[i]}

    # The vertical range first excludes everything below a tread/floor or above
    # a roof. Both horizontal ranges then require real opposing wall witnesses.
    witnessed = set()
    for (x, z), (bottom, top) in ranges[1].items():
        for y in range(bottom+1, top):
            i = index((x, y, z))
            if occupancy[i]: continue
            xr, zr = ranges[0].get((y,z)), ranges[2].get((x,y))
            if xr and zr and xr[0] < x < xr[1] and zr[0] < z < zr[1]: witnessed.add(i)
    # An unrelated distant structure must not count as an alcove's outer wall.
    # In particular, the stair shell must not cause carving below the room's
    # exterior roof overhang. Open alcoves need opposing wall cells within two
    # blocks on one horizontal axis as well as all six ray witnesses.
    local_walls = set()
    for i in witnessed:
        p = point(i)
        for axis in (0, 2):
            if all(any(cells.get(tuple(p[a]+direction*distance*(a == axis) for a in range(3)))
                       is not None and not air(cells[tuple(p[a]+direction*distance*(a == axis) for a in range(3))])
                       for distance in (1, 2)) for direction in (-1, 1)):
                local_walls.add(i)
                break
    bounded = enclosed | local_walls

    # Keep only cavities open to the authored interior. Partial models such as
    # stairs, pillars and coffin alcove trim do not occupy an entire voxel.
    permitted = bytearray(length)
    for i in bounded | models: permitted[i] = 1
    reached = bytearray(length)
    queue = deque(i for i in range(length) if occupancy[i] == 1)
    for i in queue: reached[i] = 1
    while queue:
        for i in neighbors(queue.popleft()):
            if permitted[i] and not reached[i]:
                reached[i] = 1
                queue.append(i)
    repairs = sorted(point(i) for i in bounded if reached[i])
    return repairs, {
        "enclosed": {point(i) for i in enclosed},
        "sixSolidRays": {point(i) for i in witnessed},
        "localOpposingWalls": {point(i) for i in local_walls},
        "excludedExteriorRim": {point(i) for i in witnessed-enclosed-local_walls},
        "boundedButIsolated": {point(i) for i in bounded if not reached[i]},
    }
