import json, collections

p = r"src\main\resources\Server\Prefabs\Titan\Yaga\Yaga_Baba_Body.prefab.json"
d = json.load(open(p, encoding='utf-8-sig'))
blocks = d['blocks']

by = collections.defaultdict(dict)
for b in blocks:
    by[b['y']][(b['x'], b['z'])] = b['name']

for y in range(0, 7):
    print("=== y =", y)
    for z in range(-6, 7):
        row = ""
        for x in range(-6, 7):
            n = by[y].get((x, z))
            if n is None:
                row += "."
            elif 'Stair' in n:
                row += "S"
            elif 'Door' in n:
                row += "D"
            elif 'Furniture' in n or 'Bench' in n:
                row += "F"
            else:
                row += "#"
        print(row)

names = collections.Counter(b['name'] for b in blocks if b['y'] <= 5)
print(names.most_common(25))
