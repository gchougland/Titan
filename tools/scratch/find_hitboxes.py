import zipfile, json, re, sys

zp = r"C:\Users\gchou\AppData\Roaming\Hytale\install\pre-release\package\game\latest\Assets.zip"
z = zipfile.ZipFile(zp)
names = z.namelist()
print("entries:", len(names))

wanted = ["Furniture_Human_Ruins_Chest_Large", "Furniture_Human_Ruins_Chest_Small",
          "Furniture_Human_Ruins_Bed", "Bench_Furnace", "Bench_WorkBench",
          "Furniture_Dungeon_Earth_Brazier"]

hits = [n for n in names if any(w in n for w in wanted)]
for h in hits[:40]:
    print("FILE:", h)

# BlockBoundingBoxes assets
boxes = [n for n in names if 'BlockHitbox' in n or 'BoundingBox' in n or 'Hitbox' in n]
for b in boxes[:20]:
    print("BOX:", b)
