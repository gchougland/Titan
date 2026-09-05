import zipfile, json

zp = r"C:\Users\gchou\AppData\Roaming\Hytale\install\pre-release\package\game\latest\Assets.zip"
z = zipfile.ZipFile(zp)

items = {
    "chest_large": "Server/Item/Items/Furniture/Human/Unique/Furniture_Human_Ruins_Chest_Large.json",
    "chest_small": "Server/Item/Items/Furniture/Human/Furniture_Human_Ruins_Chest_Small.json",
    "bed": "Server/Item/Items/Furniture/Human/Furniture_Human_Ruins_Bed.json",
    "furnace": "Server/Item/Items/Bench/Bench_Furnace.json",
    "workbench": "Server/Item/Items/Bench/Bench_WorkBench.json",
    "brazier": "Server/Item/Items/Furniture/Dungeon/Furniture_Dungeon_Earth_Brazier.json",
}

def load(path):
    return json.loads(z.read(path).decode('utf-8-sig'))

for k, p in items.items():
    d = load(p)
    blk = d.get('BlockType') or d
    keys = {kk: vv for kk, vv in blk.items() if 'itbox' in kk or 'Model' in kk or 'Draw' in kk}
    print("==", k, json.dumps(keys, indent=1)[:600])

names = z.namelist()
for n in names:
    if n.startswith("Server/Item/Block/Hitboxes/") and any(t in n for t in ("Furnace", "Workbench", "Bed", "Chest", "Brazier")):
        print("--", n, z.read(n).decode('utf-8-sig')[:400])
