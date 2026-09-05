import json, collections, sys

p = r"src\main\resources\Server\Prefabs\Titan\Yaga\Yaga_Baba_Body.prefab.json"
d = json.load(open(p, encoding='utf-8-sig'))
print("top keys:", list(d.keys()))

blocks = d.get('blocks') or d.get('Blocks')
print("count:", len(blocks))
print("sample:", json.dumps(blocks[0], indent=1))

names = collections.Counter(b.get('name') or b.get('blockType') for b in blocks)
for key in ("Chest", "Bed", "Furnace", "WorkBench", "Brazier", "Door"):
    print("--", key)
    for n, c in names.items():
        if n and key.lower() in n.lower():
            print("   ", n, c)
            cells = [b for b in blocks if (b.get('name') == n)]
            for b in cells:
                print("      ", {k: v for k, v in b.items() if k in ('x', 'y', 'z', 'rotation', 'name')})
