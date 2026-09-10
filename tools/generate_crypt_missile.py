"""Generate the visible emerald soul projectile model/texture using only Python's standard library."""
import json
import math
from pathlib import Path
import random
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
OUT = ROOT / 'Common/Items/Titan/CryptKeeper/Projectile'
OUT.mkdir(parents=True, exist_ok=True)
random.seed(70217)

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

palette = [(49, 194, 106), (130, 255, 173), (13, 57, 49), (216, 255, 207)]
pixels = bytearray()
for y in range(32):
    pixels.append(0)
    for x in range(64):
        base = palette[x // 16]
        change = random.choice((-9, -4, 0, 0, 4, 9)) + (6 if (x + y) % 11 == 0 else 0)
        pixels.extend((*[max(0, min(255, c + change)) for c in base], 255))

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', 64, 32, 8, 6, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')
(OUT / 'Emerald_Soul.png').write_bytes(png)

def xyz(values): return dict(zip('xyz', values))

nodes = []
def box(name, position, size, material=0, angle=0):
    radians = math.radians(angle) / 2
    nodes.append({
        'id': str(len(nodes) + 1), 'name': name, 'children': [], 'position': xyz(position),
        'orientation': {'x': 0, 'y': 0, 'z': math.sin(radians), 'w': math.cos(radians)},
        'shape': {'type': 'box', 'offset': xyz((0, 0, 0)), 'stretch': xyz((1, 1, 1)),
            'settings': {'size': xyz(size)}, 'visible': True, 'doubleSided': False,
            'shadingMode': 'fullbright', 'unwrapMode': 'custom',
            'textureLayout': {face: {'offset': {'x': material * 16, 'y': 0},
                'mirror': {'x': False, 'y': False}, 'angle': 0}
                for face in ('front', 'back', 'left', 'right', 'top', 'bottom')}}})

# Stepped emerald facets surround a luminous skull-like face. At 32 units/block it is ~.55 blocks.
box('Emerald_Soul', (0, 0, 0), (11, 11, 10), 0, 45)
box('Crown_Facet', (0, 4, 0), (8, 7, 9), 1)
box('Wide_Facet', (0, 0, 0), (15, 7, 8), 0)
box('Soul_Core', (0, 0, -5), (10, 8, 2), 1)
box('Brow', (0, 3, -6.4), (11, 2, 2), 3)
for sign in (-1, 1):
    box('Eye_Hollow', (sign * 3, .5, -6.5), (3, 3, 2), 2, sign * 12)
    box('Eye_Flame', (sign * 3, .9, -7.6), (1, 1, 1), 3)
box('Jaw', (0, -4, -3), (8, 3, 5), 0)
for x in (-3, 0, 3): box('Soul_Tooth', (x, -3.8, -6.4), (2, 3, 1), 3)
box('Soul_Tail', (0, 0, 7), (6, 6, 8), 1, 45)
box('Tail_Ember', (0, 0, 13), (3, 3, 5), 0, 45)
write(OUT / 'Emerald_Soul.blockymodel', {'nodes': nodes, 'format': 'prop'})
write(ROOT / 'Server/Models/Titan/Crypt_Emerald_Soul.json', {
    'Model': 'Items/Titan/CryptKeeper/Projectile/Emerald_Soul.blockymodel',
    'Texture': 'Items/Titan/CryptKeeper/Projectile/Emerald_Soul.png',
    'MinScale': 1, 'MaxScale': 1,
    'HitBox': {'Min': {'X': -.22, 'Y': -.22, 'Z': -.22}, 'Max': {'X': .22, 'Y': .22, 'Z': .22}},
    'Light': {'Color': '#5f9'}
})
print(f'Generated emerald soul model: {len(nodes)} fullbright cubes, 64x32 pixel texture.')
