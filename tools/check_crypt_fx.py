"""Check Crypt asset references, one-shot cleanup bounds, texture/audio headers and codec field names.

Uses only the standard library. Optionally passes authored JSON field names against
the checked-out Hytale codec source. This is not an in-client render/playback test.
"""
import json
import math
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/main/resources"
PART = RES / "Server/Particles/Titan/Crypt"
SOURCE = ROOT.parent / "HytaleSourceCode/hytale-shared-source/HytaleServer/CoreServer/src/main/java/com/hypixel/hytale/server/core/asset/type"
BASE_COMMON = ROOT.parent / "HytaleSourceCode/hytale-shared-source/HytaleAssets/Common"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def fields(data, codec):
    if not SOURCE.exists():
        return
    text = (SOURCE / codec).read_text(encoding="utf-8")
    allowed = set(re.findall(r'new KeyedCodec<>\(\s*"([^"]+)"', text))
    unknown = set(data) - allowed - {"$Comment", "Parent"}
    assert not unknown, (codec, unknown)


systems = {p.stem: read(p) for p in PART.glob("*.particlesystem")}
spawners = {p.stem: read(p) for p in (PART/"Spawners").glob("*.particlespawner")}
assert len(systems) >= 27
assert systems["Crypt_Loot_Form"]["LifeSpan"] <= 1.5
assert systems["Crypt_Loot"]["LifeSpan"] < 4
assert all(group.get("StartDelay", 0) == 0 for group in systems["Crypt_Loot"]["Spawners"])
for name, data in systems.items():
    fields(data, "particle/config/ParticleSystem.java")
    assert 0 < data["LifeSpan"] <= 5, name
    # Hytale's ParticleSystem codec allows255; our native tiled area systems cap64.
    assert 0 < len(data["Spawners"]) <= 64, name
    for group in data["Spawners"]:
        fields(group, "particle/config/ParticleSpawnerGroup.java")
        assert group["SpawnerId"] in spawners, (name, group)
        assert group.get("TotalSpawners", 1) > 0
for name, data in spawners.items():
    fields(data, "particle/config/ParticleSpawner.java")
    assert 0 < data["LifeSpan"] <= 5, name
    assert 0 < data["MaxConcurrentParticles"] <= 100, name
    assert 0 < data["TotalParticles"]["Max"] <= 100, name
    assert data["ParticleLifeSpan"]["Max"] <= data["LifeSpan"], name
    particle = data["Particle"]
    fields(particle, "particle/config/Particle.java")
    assert particle["InitialAnimationFrame"]["Opacity"] > 0, name
    assert particle["Animation"]["100"]["Opacity"] == 0, name
    for frame in [particle["InitialAnimationFrame"], *particle["Animation"].values()]:
        fields(frame, "particle/config/ParticleAnimationFrame.java")
        assert 0 <= frame.get("Opacity", 1) <= 1, name
    for attractor in data.get("Attractors", []):
        fields(attractor, "particle/config/ParticleAttractor.java")
    texture = RES / "Common" / particle["Texture"]
    png = texture.read_bytes()
    assert png[:8] == b"\x89PNG\r\n\x1a\n", texture
    w, h = struct.unpack(">II", png[16:24])
    assert w > 0 and h > 0 and png[25] == 6, (texture, "must be RGBA")
footprints = read(ROOT/"tools/crypt-fx-footprints.json")
assert {name: spec["radius"] for name,spec in footprints.items()} == {
    "Crypt_Blue_Fire_Flames":4.8, "Crypt_Poison_Cloud":8.5,
    "Crypt_Slam_Impact":6, "Crypt_Sweep_Impact":5.8}
for name, spec in footprints.items():
    groups = systems[name]["Spawners"]
    tiles = [group for group in groups if group["SpawnerId"] == spec["tileSpawner"]]
    assert len(tiles) == spec["tileCount"]
    positions = {(group["PositionOffset"]["X"],group["PositionOffset"]["Z"]) for group in tiles}
    assert len(positions) == spec["tileCount"] and (0,0) in positions
    assert all(math.hypot(x,z) <= spec["radius"] for x,z in positions)
    assert max(math.hypot(x,z) for x,z in positions) >= spec["radius"]*.839
    if spec["floorSpawner"]:
        floor = spawners[spec["floorSpawner"]]["Particle"]
        assert floor["Texture"].endswith("Crypt_Field.png")
        assert abs(floor["InitialAnimationFrame"]["Scale"]["X"]["Min"]*.965-spec["radius"]) < 1e-8
    else:
        assert name == "Crypt_Poison_Cloud"
        assert all(not spawners[g["SpawnerId"]]["SpawnBurst"] for g in groups)
    budget = sum(spawners[group["SpawnerId"]]["TotalParticles"]["Max"] for group in groups)
    assert budget <= 150, (name,"excessive area burst",budget)
for name in ("Crypt_Coffin_Stream","Crypt_Soul_Stream_Close","Crypt_Soul_Stream_End","Crypt_Loot_Form"):
    assert all(not spawners[group["SpawnerId"]]["Particle"]["Texture"].endswith("Crypt_Filament.png")
        for group in systems[name]["Spawners"]), (name,"soul transfer must use billowing smoke rather than streaks")
halo = spawners["Crypt_Beam_Halo"]["Particle"]
assert halo["InitialAnimationFrame"]["Scale"]["X"]["Min"] >= 3.2
assert halo["Animation"]["65"]["Scale"]["X"]["Min"] == 1
assert halo["Animation"]["100"]["Scale"]["X"]["Min"] == 1
events = list((RES/"Server/Audio/SoundEvents/Titan/Crypt").glob("*.json"))
sounds = set()
base_sounds = set()
assert (RES/"Server/Audio/SoundEvents/Titan/Crypt/SFX_Crypt_Staff_Charge.json").is_file()
assert (RES/"Server/Audio/SoundEvents/Titan/Crypt/SFX_Crypt_Bracelet_Hit.json").is_file()
for path in events:
    data = read(path)
    fields(data, "soundevent/config/SoundEvent.java")
    for layer in data["Layers"]:
        fields(layer, "soundevent/config/SoundEventLayer.java")
        assert not layer.get("Looping", False), path
        for file in layer["Files"]:
            sound_path = RES/"Common"/file
            is_base = not sound_path.exists()
            if is_base:
                assert path.stem == "SFX_Crypt_Roar" and file in {
                    f"Sounds/NPC/Mythic/Rex/Rex_Alerted_{i:02d}.ogg" for i in range(1,4)}, file
                sound_path = BASE_COMMON/file
                base_sounds.add(file)
            blob = sound_path.read_bytes()
            assert blob[:4] == b"OggS", file
            header = blob.index(b"\x01vorbis")
            assert blob[header+11] == 1, (file, "must be mono for spatial sound")
            rate = struct.unpack("<I", blob[header+12:header+16])[0]
            assert rate in ((44100,48000) if is_base else (44100,)), file
            if is_base:
                last = blob.rfind(b"OggS")
                duration = struct.unpack("<Q",blob[last+6:last+14])[0]/rate
                assert layer.get("StartDelay",0)+duration/2**(layer["RandomSettings"]["MinPitch"]/12) <= 3.5
            sounds.add(file)
helper = (ROOT/"src/main/java/com/hexvane/titan/crypt/CryptFx.java").read_text()
assert float(re.search(r"SOUL_STREAM_RADIUS\s*=\s*([\d.]+)",helper).group(1)) == 1.1
assert int(re.search(r"SOUL_STREAM_STRANDS\s*=\s*(\d+)",helper).group(1)) == 2
assert int(re.search(r"SOUL_STREAM_SAMPLES\s*=\s*(\d+)",helper).group(1)) == 7
for name, width in {"Crypt_Soul_Stream_Body":2.4,"Crypt_Soul_Stream_Core":1.2,"Crypt_Soul_Stream_Arrival":1.1,
                    "Crypt_Soul_Stream_Close_Body":1.8,"Crypt_Soul_Stream_Close_Core":1.2}.items():
    assert spawners[name]["Particle"]["InitialAnimationFrame"]["Scale"]["X"]["Min"] >= width
    assert spawners[name]["ParticleRotationInfluence"] == "Billboard"
    assert spawners[name]["Particle"]["ScaleRatioConstraint"] == "OneToOne"
    assert spawners[name]["Particle"]["Texture"].endswith("Crypt_Soul_Smoke.png")
    assert spawners[name].get("VelocityStretchMultiplier",0) == 0
for name in ("Crypt_Coffin_Stream", "Crypt_Soul_Stream_Close"):
    assert sum(spawners[group["SpawnerId"]]["TotalParticles"]["Max"] for group in systems[name]["Spawners"]) == 2
    body,core=[spawners[group["SpawnerId"]]["Particle"] for group in systems[name]["Spawners"]]
    assert core["InitialAnimationFrame"]["Scale"]["X"]["Min"] >= body["InitialAnimationFrame"]["Scale"]["X"]["Min"]*.79
    assert max(frame["Opacity"] for frame in core["Animation"].values()) <= .18, "Highlights must stay broad and restrained"
gather_particles=sum(spawners[group["SpawnerId"]]["TotalParticles"]["Max"] for group in systems["Crypt_Soul_Stream_End"]["Spawners"])
assert 2*7*2+gather_particles <= 35
for name,boundary in {"Crypt_Soul_Stream_Body":"SOUL_STREAM_NEAR_DISTANCE",
                      "Crypt_Soul_Stream_Close_Body":"SOUL_STREAM_GATHER_DISTANCE"}.items():
    data=spawners[name]
    assert data["ParticleRotateWithSpawner"] and data["InitialVelocity"]["Yaw"]["Max"] <= 3
    safe=float(re.search(boundary+r"\s*=\s*([\d.]+)",helper).group(1))
    assert data["ParticleLifeSpan"]["Max"]*data["InitialVelocity"]["Speed"]["Max"] < safe
for name in ("Crypt_Soul_Stream_Arrival","Crypt_Soul_Stream_Gather_Glow","Crypt_Loot_Coalescing_Mist"):
    data=spawners[name]
    assert data["Attractors"][0]["RadialImpulse"] < 0
    assert "RadialAxis" not in data["Attractors"][0], "Must contract to the destination in3D, not a vertical line"
    assert data["Particle"]["Animation"]["100"]["Scale"]["X"]["Max"] <= .06
for asset in re.findall(r'"(Crypt_[A-Za-z_]+)"', helper):
    assert asset in systems, ("Java helper references missing effect", asset)
geometry = (ROOT/"src/main/java/com/hexvane/titan/crypt/CryptAttackGeometry.java").read_text()
for constant, name in {"FIRE_RADIUS":"Crypt_Blue_Fire_Flames", "POISON_RADIUS":"Crypt_Poison_Cloud",
                       "SLAM_RADIUS":"Crypt_Slam_Impact", "SWEEP_DAMAGE_RADIUS":"Crypt_Sweep_Impact"}.items():
    value = float(re.search(r"\b"+constant+r"\s*=\s*([\d.]+)", geometry).group(1))
    assert value == footprints[name]["radius"], (constant, "damage and authored VFX radii differ")
assert float(re.search(r"\bBEAM_DAMAGE_RADIUS\s*=\s*([\d.]+)",geometry).group(1)) == 3.2
music = read(RES/"Server/Audio/MusicContainers/Titan/Track_Crypt_Keeper_Battle.json")
assert music["Type"] == "SingleTrack" and music["LoopCount"] == 0
fields({k:v for k,v in music.items() if k not in ("Type", "Track")}, "musiccontainer/config/MusicContainer.java")
fields({"Track":music["Track"]}, "musiccontainer/config/SingleTrackMusicContainer.java")
blob = (RES/"Common"/music["Track"]).read_bytes()
assert len(blob) > 100000 and blob[:4] == b"OggS"
header = blob.index(b"\x01vorbis")
assert blob[header+11] == 2 and struct.unpack("<I", blob[header+12:header+16])[0] == 44100
print(f"Crypt FX validated: {len(systems)} systems, {len(spawners)} spawners, {len(events)} sound events, {len(sounds)-len(base_sounds)} original mono Ogg files and {len(base_sounds)} base-game roar references; finite cleanup, resolved textures, codec field compatibility.")
print("Original stereo battle music: container fields, seamless loop settings, Ogg header and sample rate validated.")
