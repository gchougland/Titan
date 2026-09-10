"""Render the shipped blockymodel and its UV-mapped texture to a transparent 64x64 icon.

No substitute geometry or painted icon: reads node hierarchy, quaternions, shape offsets,
stretch, face UVs, fullbright materials, and the actual staff atlas. Requires NumPy + Pillow.
"""
import hashlib
import json
import math
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / 'src/main/resources/Common'
MODEL = COMMON / 'Items/Titan/CryptKeeper/Staff.blockymodel'
TEXTURE = COMMON / 'Items/Titan/CryptKeeper/Staff_Texture.png'
ICON = COMMON / 'Icons/ItemsGenerated/Titan_Staff_Crypt_Keeper.png'


def vec(value, default=0):
    return np.array([value.get(k, default) for k in 'xyz'], dtype=float)


def rotation(q):
    x, y, z, w = [q.get(k, 0 if k != 'w' else 1) for k in 'xyzw']
    n = math.sqrt(x*x+y*y+z*z+w*w)
    x, y, z, w = x/n, y/n, z/n, w/n
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
                     [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])


# Four corners follow each texture's top-left, top-right, bottom-right, bottom-left.
FACES = {
    'front': ([0,0,-1], [(-1,1,-1),(1,1,-1),(1,-1,-1),(-1,-1,-1)], (0,1)),
    'back': ([0,0,1], [(1,1,1),(-1,1,1),(-1,-1,1),(1,-1,1)], (0,1)),
    'right': ([1,0,0], [(1,1,-1),(1,1,1),(1,-1,1),(1,-1,-1)], (2,1)),
    'left': ([-1,0,0], [(-1,1,1),(-1,1,-1),(-1,-1,-1),(-1,-1,1)], (2,1)),
    'top': ([0,1,0], [(-1,1,1),(1,1,1),(1,1,-1),(-1,1,-1)], (0,2)),
    'bottom': ([0,-1,0], [(-1,-1,-1),(1,-1,-1),(1,-1,1),(-1,-1,1)], (0,2)),
}


def render():
    data = json.loads(MODEL.read_text(encoding='utf-8'))
    texture = np.asarray(Image.open(TEXTURE).convert('RGBA'))
    boxes = []
    art_frame = None

    def walk(node, parent_q, parent_p):
        nonlocal art_frame
        q = parent_q @ rotation(node.get('orientation', {}))
        p = parent_p + parent_q @ vec(node.get('position', {}))
        if node['name'] == 'Crypt_Staff_Art': art_frame = (q, p)
        shape = node.get('shape', {})
        if shape.get('visible', True) and shape.get('type') == 'box': boxes.append((shape, q, p))
        for child in node.get('children', []): walk(child, q, p)

    for node in data['nodes']: walk(node, np.eye(3), np.zeros(3))
    assert boxes and art_frame is not None, 'Expected staff mesh and item presentation frame'
    art_q, art_p = art_frame
    camera = np.array([.55,.22,-1.]); camera /= np.linalg.norm(camera)
    right = np.cross(camera, [0,1,0]); right /= np.linalg.norm(right)
    up = np.cross(right, camera)
    light = np.array([-.5,.85,-.7]); light /= np.linalg.norm(light)
    angle = math.radians(32)
    tilt = np.array([[math.cos(angle),-math.sin(angle)],[math.sin(angle),math.cos(angle)]])
    faces = []
    for shape, world_q, world_p in boxes:
        q = art_q.T @ world_q
        p = art_q.T @ (world_p-art_p)
        size = vec(shape['settings']['size'])
        extent = size * vec(shape.get('stretch', {}), 1) / 2
        offset = vec(shape.get('offset', {}))
        for name, (normal, corners, axes) in FACES.items():
            uv = shape.get('textureLayout', {}).get(name)
            if uv is None: continue
            n = q @ normal
            if n @ camera <= 0 and not shape.get('doubleSided', False): continue
            points = (np.array(corners)*extent+offset) @ q.T + p
            screen = np.column_stack((points@right, -(points@up))) @ tilt.T
            tex = np.array([[0,0],[1,0],[1,1],[0,1]], dtype=float)
            mirror = uv.get('mirror', {})
            if mirror.get('x'): tex[:,0] = 1-tex[:,0]
            if mirror.get('y'): tex[:,1] = 1-tex[:,1]
            turns = round(uv.get('angle',0)/90) % 4
            for _ in range(turns): tex = np.column_stack((tex[:,1], 1-tex[:,0]))
            tex *= size[list(axes)]
            tex += [uv['offset']['x'],uv['offset']['y']]
            shade = 1 if shape.get('shadingMode') == 'fullbright' else .56+.44*max(0,n@light)
            faces.append((screen, points@camera, tex, shade))

    extent = np.concatenate([face[0] for face in faces])
    low, high = extent.min(0), extent.max(0)
    size, samples = 64, 8
    canvas_size = size*samples
    scale = (size-8)*samples / max(high-low)
    middle = (high+low)/2
    rgba = np.zeros((canvas_size,canvas_size,4), dtype=np.uint8)
    depth = np.full((canvas_size,canvas_size), -np.inf)
    for screen, z, uv, shade in faces:
        xy = (screen-middle)*scale + canvas_size/2
        for indices in ([0,1,2], [0,2,3]):
            v, tz, tuv = xy[indices], z[indices], uv[indices]
            left, top = np.maximum(0,np.floor(v.min(0)).astype(int))
            right_edge, bottom = np.minimum(canvas_size-1,np.ceil(v.max(0)).astype(int))
            if left>right_edge or top>bottom: continue
            yy, xx = np.mgrid[top:bottom+1,left:right_edge+1]
            px, py = xx+.5, yy+.5
            denom = (v[1,1]-v[2,1])*(v[0,0]-v[2,0]) + (v[2,0]-v[1,0])*(v[0,1]-v[2,1])
            if abs(denom)<1e-10: continue
            a = ((v[1,1]-v[2,1])*(px-v[2,0])+(v[2,0]-v[1,0])*(py-v[2,1]))/denom
            b = ((v[2,1]-v[0,1])*(px-v[2,0])+(v[0,0]-v[2,0])*(py-v[2,1]))/denom
            c = 1-a-b
            tz_grid = a*tz[0]+b*tz[1]+c*tz[2]
            coords = a[...,None]*tuv[0]+b[...,None]*tuv[1]+c[...,None]*tuv[2]
            tx = np.clip(np.floor(coords[...,0]).astype(int),0,texture.shape[1]-1)
            ty = np.clip(np.floor(coords[...,1]).astype(int),0,texture.shape[0]-1)
            pixels = texture[ty,tx].copy()
            pixels[...,:3] = np.clip(pixels[...,:3]*shade,0,255).astype(np.uint8)
            visible = (a>=-1e-8)&(b>=-1e-8)&(c>=-1e-8)&(tz_grid>depth[yy,xx])&(pixels[...,3]>0)
            rgba[yy[visible],xx[visible]] = pixels[visible]
            depth[yy[visible],xx[visible]] = tz_grid[visible]
    # Area coverage avoids colored ringing around thin translucent bone edges at inventory size.
    result = Image.fromarray(rgba).resize((size,size), Image.Resampling.BOX)
    ICON.parent.mkdir(parents=True,exist_ok=True)
    result.save(ICON)
    assert result.size == (64,64) and result.getchannel('A').getextrema() == (0,255)
    print(f'Rendered {len(boxes)} actual boxes / {len(faces)} textured faces to {ICON} (64x64 RGBA)')
    print('Model SHA256:', hashlib.sha256(MODEL.read_bytes()).hexdigest())
    print('Texture SHA256:', hashlib.sha256(TEXTURE.read_bytes()).hexdigest())


if __name__ == '__main__': render()
