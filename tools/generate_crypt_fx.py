"""Rebuild original Crypt Keeper particles, pixel textures and Ogg sound effects.

Requires Python 3.12+, numpy, Pillow and soundfile. Dependencies may be installed
in build/crypt-fx-deps; no downloaded media, samples or third-party art is used.
Textures intentionally retain pixels at the same density as Hytale spell art.
Every particle system has a finite lifetime so an encounter reset leaves no loops.
"""
from __future__ import annotations

import json
import io
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.append(str(ROOT / "build/crypt-fx-deps"))
import numpy as np
from PIL import Image, ImageDraw
STREAMS_ONLY = "--streams-only" in sys.argv
PARTICLES_ONLY = "--particles-only" in sys.argv or STREAMS_ONLY
STREAM_ASSETS = {"Crypt_Coffin_Stream", "Crypt_Soul_Stream_Close", "Crypt_Soul_Stream_End",
                 "Crypt_Soul_Stream_Body", "Crypt_Soul_Stream_Core", "Crypt_Soul_Stream_Close_Body",
                 "Crypt_Soul_Stream_Close_Core", "Crypt_Soul_Stream_Arrival", "Crypt_Soul_Stream_Gather_Glow",
                 "Crypt_Loot_Form", "Crypt_Loot_Coalescing_Mist", "Crypt_Death_Souls", "Crypt_Coffin_Soul_Coil"}
STREAM_TEXTURES = {"Crypt_Soul_Smoke"}
if "--audio-only" in sys.argv:
    from generate_crypt_audio import main as generate_audio
    generate_audio()
    raise SystemExit(0)

RES = ROOT / "src/main/resources"
PART = RES / "Server/Particles/Titan/Crypt"
SPAWN = PART / "Spawners"
TEX = RES / "Common/Particles/Textures/Titan/Crypt"
SOUND = RES / "Common/Sounds/Titan/Crypt"
EVENT = RES / "Server/Audio/SoundEvents/Titan/Crypt"
OUT = ROOT / "build/crypt-fx-preview"
for path in [PART, SPAWN, TEX, SOUND, EVENT, OUT]:
    path.mkdir(parents=True, exist_ok=True)


def write_json(path, data):
    if STREAMS_ONLY and path.stem not in STREAM_ASSETS:
        return
    value = json.dumps(data, indent=2) + "\n"
    if not path.exists() or path.read_text(encoding="utf-8") != value:
        path.write_text(value, encoding="utf-8")


def rng(a, b=None):
    return {"Min": a, "Max": a if b is None else b}


def scale(a, b=None):
    return {"X": rng(a, b), "Y": rng(a, b)}


def texture(name, n, fn):
    if STREAMS_ONLY and name not in STREAM_TEXTURES:
        return
    image = Image.new("RGBA", (n, n))
    pixels = image.load()
    for y in range(n):
        for x in range(n):
            v, a = fn((x + .5 - n / 2) / (n / 2), (y + .5 - n / 2) / (n / 2), x, y)
            v, a = int(np.clip(v, 0, 255)), int(np.clip(a, 0, 255))
            pixels[x, y] = (v, v, v, a)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    path = TEX / f"{name}.png"
    if not path.exists() or path.read_bytes() != buffer.getvalue():
        path.write_bytes(buffer.getvalue())


def rune(x, y, px, py):
    r, a = math.hypot(x, y), math.atan2(y, x)
    border = .90 < r < .965 or .72 < r < .755
    spoke = .77 < r < .88 and abs(math.sin(a * 12)) < .16
    crown = .21 < abs(x) < .44 and -.30 < y < -.03 or abs(x) < .4 and -.06 < y < .08
    notch = abs(abs(x) - .28) < .045 and -.45 < y < -.12
    return (255, 230 if border or spoke or crown or notch else (22 if r < .9 else 0))


def smoke(x, y, px, py):
    r = math.hypot(x * .95, y)
    lobes = .12 * math.sin(x * 10 + math.cos(y * 8)) + .08 * math.cos(y * 14 - x * 4)
    shape = np.clip((.83 - r + lobes) * 3, 0, 1)
    band = round(np.clip(.65 + .25 * math.sin(x * 8 - y * 3) * math.cos(y * 10 + x), 0, 1) * 5) / 5
    return (170 + band * 85, shape * (90 + 130 * band))


def flame(x, y, px, py):
    yy = (y + 1) / 2
    bend = .16 * math.sin(yy * 8)
    w = .12 + .45 * yy
    edge = 1 - abs(x - bend) / w
    value = np.clip(edge * 2, 0, 1) * np.clip(yy * 5, 0, 1) * np.clip((1 - yy) * 5, 0, 1)
    return (min(255, 120 + value * 200), round(value * 5) / 5 * 255)


def soul_smoke(x, y, px, py):
    # Overlapping uneven lobes, soft alpha and stepped self-shading retain a painted,
    # blocky texture instead of stretching a luminous sliver into a smoke substitute.
    lobes = [(-.18,-.22,.48),(.27,-.10,.40),(-.40,.14,.32),(.04,.34,.43),(.40,.30,.29),(-.35,-.43,.25)]
    value = sum(math.exp(-((x-cx)**2+(y-cy)**2)/(r*r*.68)) for cx,cy,r in lobes)
    turbulence = .075*math.sin(x*23+math.sin(y*13))+.04*math.cos(y*29-x*7)
    alpha = np.clip((value-.12+turbulence)*1.1,0,1)*np.clip((.98-math.hypot(x,y))*5,0,1)
    light = .42+.32*np.clip(value,0,1)+.18*np.clip((-x-y+.8)/2,0,1)
    return (round(light*10)/10*255,alpha*215)


texture("Crypt_Rune", 128, rune)
texture("Crypt_Smoke", 32, smoke)
texture("Crypt_Flame", 32, flame)
texture("Crypt_Soul", 32, lambda x, y, px, py: (255, np.clip(1 - math.hypot(x, y), 0, 1) ** 2 * 255))
texture("Crypt_Spark", 16, lambda x, y, px, py: (255, 255 if abs(x) + abs(y) < .75 and (abs(x) < .2 or abs(y) < .2) else 0))
texture("Crypt_Bone", 16, lambda x, y, px, py: (220 + 30 * (x < 0), 255 if (abs(x) < .2 and abs(y) < .65) or (.4 < abs(y) < .75 and abs(x) < .4) else 0))
texture("Crypt_Shockwave", 64, lambda x, y, px, py: (255, np.clip(1 - abs(math.hypot(x, y) - .84) / .14, 0, 1) * 255))
texture("Crypt_Crack", 64, lambda x, y, px, py: (255, 255 if math.hypot(x, y) < .92 and abs(math.sin(math.atan2(y, x) * 5 + math.hypot(x, y) * 8)) < .055 + .12 * (1-math.hypot(x, y)) else 0))
texture("Crypt_Field", 128, lambda x, y, px, py: (255,
    (125 + 65 * math.sin(x * 23 + math.sin(y * 17)) ** 2) if math.hypot(x, y) < .965 else 0))
texture("Crypt_Filament", 32, lambda x, y, px, py: (255,
    255 * max(0, 1 - abs(x - .12 * math.sin(y * 8)) / .28) ** 1.5 * max(0, 1 - abs(y)) ** .35))
texture("Crypt_Beam_Disc", 64, lambda x, y, px, py: (255,
    255 * min(1, max(0, (1 - math.hypot(x, y)) / .2))))
texture("Crypt_Soul_Smoke", 64, soul_smoke)

PURPLE = "#ae69ff"
BLUE = "#45bfff"
GREEN = "#86ff9b"
GOLD = "#ffcf84"
WHITE = "#f0e6cf"


def puff(name, color, *, tex="Crypt_Smoke", count=14, life=(.5, 1.1), size=(.25,.6), grow=2,
         extent=(.4,.2,.4), speed=(.1,.8), lift=.8, radial=0, spin=0,
         mode="BlendLinear", opacity=.7, direction=None, soft=True,
         height=None, rotation="Billboard", tilt=180, steady=False, animation=None, attractors=None, spread=8):
    particle = {
        "Texture": f"Particles/Textures/Titan/Crypt/{tex}.png",
        "ScaleRatioConstraint": "None" if height else "OneToOne", "UVOption": "RandomFlipU",
        "SoftParticles": "Enable" if soft else "Disable",
        "InitialAnimationFrame": {"Opacity": 1, "Color": color, "Scale": scale(*size),
            "Rotation": {"Z": rng(-tilt, tilt)}, "FrameIndex": rng(0)},
        "Animation": {
            "0": {"Opacity": opacity * .35},
            "12": {"Opacity": opacity},
            "65": {"Opacity": opacity * .8, "Scale": scale(grow * .75)},
            "100": {"Opacity": 0, "Scale": scale(grow), "Rotation": {"Z": rng(-35, 35)}}
        }
    }
    if height:
        particle["InitialAnimationFrame"]["Scale"]["Y"] = rng(*height)
    if steady:
        particle["Animation"]["65"]["Scale"] = scale(1)
        particle["Animation"]["100"]["Scale"] = scale(1)
    if animation is not None:
        particle["Animation"] = animation
    result = {
        "RenderMode": mode, "LinearFiltering": tex in ("Crypt_Soul", "Crypt_Shockwave"),
        "LightInfluence": 0, "ParticleRotationInfluence": rotation,
        "ParticleRotateWithSpawner": False, "TrailSpawnerPositionMultiplier": 1,
        "TrailSpawnerRotationMultiplier": 1, "Shape": "Sphere",
        "EmitOffset": {k: rng(-e,e) for k,e in zip("XYZ", extent)},
        "TotalParticles": rng(count), "MaxConcurrentParticles": count,
        "ParticleLifeSpan": rng(*life), "LifeSpan": max(life)+.2,
        "SpawnBurst": True, "SpawnRate": rng(count),
        "InitialVelocity": {"Speed": rng(*speed), "Yaw": rng(0, 360), "Pitch": rng(-70,70)},
        "Attractors": [{"LinearAcceleration": {"X": 0, "Y": lift, "Z": 0},
                        "RadialAxis": {"X":0,"Y":1,"Z":0}, "RadialImpulse": radial,
                        "RadialTangentImpulse": spin}],
        "Particle": particle,
    }
    if direction:
        result["InitialVelocity"].update(Yaw=rng(-spread,spread), Pitch=rng(-spread,spread))
        result["ParticleRotateWithSpawner"] = True
    if attractors is not None:
        result["Attractors"] = attractors
    write_json(SPAWN / f"Crypt_{name}.particlespawner", result)
    return f"Crypt_{name}"


def ground(name, color, *, tex="Crypt_Rune", size=1, end=1, life=.42, opacity=.9, spin=0):
    result = {
        "RenderMode": "BlendAdd", "LinearFiltering": False, "LightInfluence": 0,
        "ParticleRotationInfluence": "None", "ParticleRotateWithSpawner": True,
        "TrailSpawnerPositionMultiplier": 1, "TrailSpawnerRotationMultiplier": 1,
        "MaxConcurrentParticles": 1, "TotalParticles": rng(1), "SpawnRate": rng(1),
        "SpawnBurst": True, "LifeSpan": life+.1, "ParticleLifeSpan": rng(life),
        "Particle": {
            "Texture": f"Particles/Textures/Titan/Crypt/{tex}.png", "ScaleRatioConstraint": "OneToOne",
            "UVOption": "None", "SoftParticles": "Disable",
            "InitialAnimationFrame": {"Opacity": 1, "Color": color, "Scale":scale(size / .965 if tex in ("Crypt_Rune", "Crypt_Field") else size),
                "FrameIndex":rng(0), "Rotation":{"X":rng(90),"Y":rng(0),"Z":rng(0)}},
            "Animation": {"0":{"Opacity":0},"15":{"Opacity":opacity},
                "65":{"Opacity":opacity*.8},"100":{"Opacity":0,"Scale":scale(end),"Rotation":{"Z":rng(spin)}}}
        }
    }
    write_json(SPAWN / f"Crypt_{name}.particlespawner", result)
    return f"Crypt_{name}"


SYSTEMS = {}
FOOTPRINTS = {}
def system(name, spawners, *, life=3, radius=12, important=False):
    entries = [{"SpawnerId": item} if isinstance(item, str) else item for item in spawners]
    data = {"Spawners":entries,"LifeSpan":life,"BoundingRadius":radius,"CullDistance":112,"IsImportant":important}
    write_json(PART / f"Crypt_{name}.particlesystem",data)
    SYSTEMS[f"Crypt_{name}"] = data


def area_system(name, tile, color, radius, bands, life, centre=(), tile_y=.8):
    """Native metre/block offsets, with one network effect for the entire filled disc."""
    floor = ground(name + "_Field", color, tex="Crypt_Field", size=radius, life=life, opacity=.34)
    groups = [{"SpawnerId":floor}, *({"SpawnerId":item} for item in centre)]
    for band in range(bands + 1):
        count = 1 if band == 0 else 6 * band
        r = radius * .84 * band / bands
        for index in range(count):
            angle = index * math.tau / count
            groups.append({"SpawnerId":tile, "PositionOffset":{"X":round(math.cos(angle)*r, 5),
                "Y":tile_y, "Z":round(math.sin(angle)*r, 5)}})
    system(name, groups, life=life+.25, radius=radius+5, important=True)
    FOOTPRINTS["Crypt_"+name] = {"radius":radius,"tileCount":1+3*bands*(bands+1),
        "bands":bands,"floorSpawner":floor,"tileSpawner":tile}


mist = puff("Violet_Mist", PURPLE, count=18, life=(1,1.8),size=(.4,.8),grow=2,spin=.4,lift=.2)
souls = puff("Violet_Embers", "#dcb8ff", tex="Crypt_Soul",count=12,life=(.6,1.2),size=(.08,.18),mode="BlendAdd",lift=1.3,radial=.3)
dust = puff("Bone_Dust", WHITE,count=28,life=(.7,1.5),size=(.3,.7),extent=(1.6,.2,1.6),speed=(1,4),lift=-1.5,opacity=.55)
chips = puff("Bone_Chips",WHITE,tex="Crypt_Bone",count=24,life=(.45,1.1),size=(.05,.13),grow=.6,extent=(.7,.1,.7),speed=(2,6),lift=-7,radial=2)
green = puff("Jade_Embers",GREEN,tex="Crypt_Spark",count=14,life=(.4,.85),size=(.07,.14),mode="BlendAdd",lift=1.5,radial=.4)
frost = puff("Azure_Embers",BLUE,tex="Crypt_Spark",count=20,life=(.5,.9),size=(.09,.17),mode="BlendAdd",lift=1.8,radial=.8)
cracks = ground("Impact_Cracks",PURPLE,tex="Crypt_Crack",size=2.4,end=1.1,life=.8,opacity=.85)
shock = ground("Bone_Shockwave",WHITE,tex="Crypt_Shockwave",size=.3,end=18,life=.7,opacity=.65)
violet_shock = ground("Violet_Shockwave",PURPLE,tex="Crypt_Shockwave",size=.3,end=20,life=1,opacity=.75)
for name,color in [("Sweep_Telegraph",PURPLE),("Slam_Telegraph",PURPLE),("Blue_Fire_Telegraph",BLUE),("Grasp_Telegraph",GREEN),("Poison_Telegraph",GREEN),("Beam_Telegraph",BLUE),("Grab_Telegraph","#ff91dc")]:
    system(name,[ground(name,color)],life=.6,radius=2,important=True)
def billow(opacity, contraction=False):
    return {"0":{"Opacity":0,"Scale":scale(.65)},
            "12":{"Opacity":opacity*.65,"Scale":scale(.9)},
            "40":{"Opacity":opacity,"Scale":scale(.85 if contraction else 1.2)},
            "70":{"Opacity":opacity*.7,"Scale":scale(.4 if contraction else 1.5),"Rotation":{"Z":rng(-25,25)}},
            "100":{"Opacity":0,"Scale":scale(.06 if contraction else 1.7),"Rotation":{"Z":rng(-50,50)}}}


for suffix,speed,life,body_size in [("",(11,13),(.65,.85),(2.4,3.1)),("_Close",(3.2,4),(.5,.65),(1.8,2.4))]:
    body = puff(f"Soul_Stream{suffix}_Body","#8245bb",tex="Crypt_Soul_Smoke",count=1,life=life,size=body_size,
        extent=(.3,.3,.3),speed=speed,lift=0,direction=True,spread=3,opacity=.8,rotation="Billboard",
        animation=billow(.78),attractors=[])
    core = puff(f"Soul_Stream{suffix}_Core","#ab84c7",tex="Crypt_Soul_Smoke",count=1,life=life,size=(body_size[0]*.8,body_size[1]*.8),
        extent=(.25,.25,.25),speed=speed,lift=0,direction=True,spread=3,mode="BlendAdd",opacity=.15,rotation="Billboard",
        animation=billow(.15),attractors=[])
    system("Coffin_Stream" if not suffix else "Soul_Stream_Close",[body,core],life=1.1 if not suffix else .9,radius=15 if not suffix else 8,important=True)
gather = [{"Position":{"X":0,"Y":0,"Z":0},"TrailPositionMultiplier":1,"RadialImpulse":-3.6}]
system("Soul_Stream_End",[
    puff("Soul_Stream_Arrival","#9254c6",tex="Crypt_Soul_Smoke",count=5,life=(.65,.95),size=(1.1,1.7),
         extent=(2.4,1.8,2.4),speed=(0,0),lift=0,opacity=.8,animation=billow(.8,True),attractors=gather),
    puff("Soul_Stream_Gather_Glow","#c7a6e3",tex="Crypt_Soul_Smoke",count=2,life=(.55,.8),size=(.65,1),
         extent=(1.2,.9,1.2),speed=(0,0),lift=0,mode="BlendAdd",opacity=.18,animation=billow(.18,True),attractors=gather)
    ],life=1.2,radius=6,important=True)
system("Coffin_Smoke",[puff("Coffin_Soul_Coil","#8245bb",tex="Crypt_Soul_Smoke",count=12,life=(.65,1.05),size=(.6,1),
    extent=(.6,.2,.6),speed=(0,.1),lift=.3,radial=-.5,spin=.3,opacity=.65,animation=billow(.65))],life=1.3)
system("Emergence",[dust,chips,mist],life=2.3)
system("Roar",[violet_shock,puff("Roar_Wave",PURPLE,tex="Crypt_Shockwave",count=2,life=(.8,1),size=(.6,.8),grow=14,speed=(0,0),lift=0,mode="BlendAdd",soft=False),souls],life=2,radius=20)
area_system("Sweep_Impact",puff("Sweep_Wisp",PURPLE,count=3,life=(.24,.42),size=(2.1,2.6),grow=1.1,
    extent=(.25,.2,.25),speed=(.1,.2),lift=0,opacity=.4),PURPLE,5.8,2,.55)
area_system("Slam_Impact",puff("Slam_Dust_Tile",WHITE,count=4,life=(.55,.85),size=(1.8,2.2),grow=1.3,
    extent=(.25,.15,.25),speed=(.2,.8),lift=.8,opacity=.52),PURPLE,6,2,1.2,centre=(chips,shock,frost),tile_y=.3)
area_system("Blue_Fire_Flames",puff("Soul_Flame",BLUE,tex="Crypt_Flame",count=3,life=(.55,.9),size=(1.55,1.85),height=(2.1,2.8),grow=1.4,
    extent=(.28,.1,.28),speed=(.1,.4),lift=1.8,mode="BlendAdd",opacity=.9,rotation="BillboardY",tilt=8),BLUE,4.8,2,1.1,tile_y=1.3)
system("Minion_Summon",[ground("Summon_Rune",PURPLE,size=2.5,life=1.7,spin=70),
    puff("Minion_Soul_Coil",PURPLE,tex="Crypt_Filament",count=16,life=(.6,1),size=(.12,.25),height=(.65,1.2),grow=.4,
         extent=(1.8,.25,1.8),speed=(0,.2),lift=1.2,radial=-.6,spin=1.8,mode="BlendAdd",rotation="BillboardVelocity",tilt=0),
    puff("Minion_Bones",WHITE,tex="Crypt_Bone",count=14,life=(.5,.9),size=(.15,.23),extent=(1.5,.1,1.5),speed=(.4,1),lift=1.5,spin=1.4)],life=2.2,radius=6)
system("Poison_Breath",[puff("Poison_Cone",GREEN,count=22,life=(.5,1),size=(.3,.6),grow=2.5,extent=(.2,.2,.2),speed=(5,8),lift=.2,direction=True,opacity=.65)],life=1.4)
area_system("Poison_Cloud",puff("Poison_Fog", "#82b855",count=3,life=(.8,1.2),size=(2.25,2.6),height=(1.5,2),grow=1.1,
    extent=(.25,.35,.25),speed=(.02,.08),lift=.08,opacity=.46,rotation="BillboardY",tilt=6,steady=True),GREEN,8.5,3,1.4)
system("Beam_Charge",[puff("Beam_Vortex",BLUE,tex="Crypt_Spark",count=30,life=(.4,.8),size=(.1,.18),grow=.4,extent=(1.2,1.2,1.2),speed=(0,0),lift=0,radial=-1.5,spin=2,mode="BlendAdd"),
    puff("Beam_Core_Charge",WHITE,tex="Crypt_Beam_Disc",count=1,life=(.4,.5),size=(2.5,2.8),grow=1.15,extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd")],life=1.2)
system("Beam",[puff("Beam_Core", "#ecffff",tex="Crypt_Beam_Disc",count=1,life=(.2,.25),size=(2.65,2.85),grow=1,extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd",opacity=.93,soft=False,steady=True),
    puff("Beam_Halo",BLUE,tex="Crypt_Beam_Disc",count=1,life=(.25,.3),size=(3.25,3.4),grow=1,extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd",opacity=.5,soft=False,steady=True)],life=.5,radius=5,important=True)
system("Grab_Catch",[puff("Grab_Binding_Ring","#ff91dc",tex="Crypt_Shockwave",count=2,life=(.4,.6),size=(1.15,1.3),grow=.6,
    extent=(.08,.2,.08),speed=(0,0),lift=0,mode="BlendAdd",soft=False),souls],life=1.4,radius=4,important=True)
system("Grab_Toss",[puff("Grab_Release",PURPLE,tex="Crypt_Filament",count=8,life=(.3,.55),size=(.12,.2),height=(1.3,2),grow=.4,
    extent=(.5,.3,.5),speed=(7,11),lift=0,direction=True,mode="BlendAdd",rotation="BillboardVelocity",tilt=0)],life=.8,radius=8,important=True)
system("Bracelet_Break",[puff("Gilded_Shards",GOLD,tex="Crypt_Bone",count=34,life=(.5,1.2),size=(.07,.2),extent=(.5,.4,.5),speed=(3,7),lift=-5,radial=1.5),violet_shock,souls],life=2)
system("Bracelet_Regen",[puff("Gilded_Reforge",GOLD,tex="Crypt_Spark",count=28,life=(.5,1.1),size=(.08,.2),grow=.4,extent=(1.4,.5,1.4),speed=(0,0),lift=0,radial=-1.4,spin=2,mode="BlendAdd"),souls],life=1.5)
system("Crown_Hit",[puff("Crown_Flash",GOLD,tex="Crypt_Spark",count=13,life=(.2,.45),size=(.07,.16),grow=.2,extent=(.2,.2,.2),speed=(1,3),lift=-1,mode="BlendAdd")],life=.7)
system("Death",[puff("Death_Souls","#8245bb",tex="Crypt_Soul_Smoke",count=26,life=(1,1.5),size=(1.5,2.3),
    extent=(2,1,2),speed=(.4,1),lift=.4,radial=.3,spin=.3,opacity=.6,animation=billow(.6)),chips,shock],life=2,radius=15)
# The departing titan's violet soul contracts into the staff's jade orb.
# Form precedes the item spawn; the reveal plays only when the staff exists.
system("Loot_Form",[
    puff("Loot_Coalescing_Mist","#9254c6",tex="Crypt_Soul_Smoke",count=18,life=(1.15,1.3),size=(1.0,1.7),
         extent=(2.8,1.6,2.8),speed=(0,0),lift=0,opacity=.8,animation=billow(.8,True),
         attractors=[{"Position":{"X":0,"Y":0,"Z":0},"TrailPositionMultiplier":1,"RadialImpulse":-2.8}]),
    puff("Loot_Converging_Souls","#dcb8ff",tex="Crypt_Spark",count=14,life=(.85,1.3),size=(.05,.12),grow=.1,
         extent=(1.45,.2,1.45),speed=(0,0),lift=0,radial=-1.3,spin=-.3,mode="BlendAdd")],life=1.5,radius=5,important=True)
system("Loot",[
    ground("Loot_Sigil",GREEN,size=.8,life=1.8,spin=-45),
    puff("Loot_Halo",GREEN,tex="Crypt_Soul",count=1,life=(1.5,1.6),size=(.18,.22),grow=2,
         extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd",opacity=.65),
    puff("Loot_Reveal_Flash","#edfff3",tex="Crypt_Shockwave",count=1,life=(.22,.3),size=(.14,.17),grow=4,
         extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd",opacity=.9,soft=False),
    green],life=2.2,radius=5,important=True)
system("Staff_Cast",[green,puff("Staff_Flash",GREEN,tex="Crypt_Shockwave",count=1,life=(.2,.3),size=(.15,.2),grow=4,extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd")],life=1.2,radius=4)
system("Missile_Trail",[puff("Missile_Core", "#d6ffe7",tex="Crypt_Soul",count=1,life=(.16,.22),size=(.12,.16),grow=.3,extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd",opacity=.95),
    puff("Missile_Wisps",GREEN,count=3,life=(.3,.45),size=(.08,.14),grow=.3,extent=(.05,.05,.05),speed=(0,.2),lift=.15,opacity=.7)],life=.65,radius=3)
system("Missile_Impact",[green,puff("Missile_Burst",GREEN,tex="Crypt_Shockwave",count=1,life=(.22,.3),size=(.15,.2),grow=5,extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd")],life=1.2,radius=5)
system("Grasp_Impact",[dust,chips,shock,ground("Jade_Cracks",GREEN,tex="Crypt_Crack",size=2.4,end=1.1,life=.8),green],life=2)
system("Staff_Orb",[puff("Staff_Orb_Glow",GREEN,tex="Crypt_Soul",count=1,life=(.7,.9),size=(.14,.17),grow=1.5,extent=(0,0,0),speed=(0,0),lift=0,mode="BlendAdd",opacity=.45),
    puff("Staff_Orb_Sparks",GREEN,tex="Crypt_Spark",count=2,life=(.5,.7),size=(.02,.04),extent=(.07,.07,.07),speed=(0,.1),lift=.15,mode="BlendAdd")],life=1.2,radius=2)


# Audio is isolated so --audio-only cannot touch particle, texture or music resources.
if STREAMS_ONLY:
    obsolete=SPAWN/"Crypt_Soul_Filament.particlespawner"
    if obsolete.exists(): obsolete.unlink()
    print("Updated soul-flow smoke assets only; cinematic timings, attack effects, sounds and music unchanged.")
    raise SystemExit(0)
metrics = []
if not PARTICLES_ONLY:
    from generate_crypt_audio import SOUNDS, main as generate_audio
    metrics = generate_audio()
write_json(ROOT/"tools/crypt-fx-footprints.json",FOOTPRINTS)
atlas = Image.new("RGB",(800,200*math.ceil(len(list(TEX.glob("*.png")))/4)),(24,21,34))
draw=ImageDraw.Draw(atlas)
for i,path in enumerate(sorted(TEX.glob("*.png"))):
    x,y=(i%4)*200,(i//4)*200
    img=Image.open(path).resize((144,144),Image.Resampling.NEAREST)
    tint=Image.new("RGBA",img.size,(132,230,191,255))
    tint.putalpha(img.getchannel("A"))
    atlas.paste(tint,(x+28,y+12),tint)
    draw.text((x+12,y+169),path.stem,fill=(235,230,245))
atlas.save(OUT/"texture-atlas.png")
write_json(OUT/"particle-catalog.json",SYSTEMS)
print(f"Generated {len(SYSTEMS)} systems, {len(list(SPAWN.glob('*.particlespawner')))} spawners, {len(list(TEX.glob('*.png')))} textures" + (" (audio unchanged)." if PARTICLES_ONLY else f", {len(SOUNDS)} sound events and {len(metrics)} original Ogg clips."))
