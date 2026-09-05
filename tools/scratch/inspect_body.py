import json, collections

p = r"src\main\resources\Server\Prefabs\Titan\Yaga\Yaga_Baba_Body.prefab.json"
d = json.load(open(p, encoding='utf-8-sig'))
blocks = d['blocks']
xs = [b['x'] for b in blocks]
ys = [b['y'] for b in blocks]
zs = [b['z'] for b in blocks]
print("x", min(xs), max(xs), "y", min(ys), max(ys), "z", min(zs), max(zs))
print("anchor", d.get('anchorX'), d.get('anchorY'), d.get('anchorZ'))
per = collections.Counter(ys)
for y in sorted(per):
    print("layer y=%d: %d blocks" % (y, per[y]))
