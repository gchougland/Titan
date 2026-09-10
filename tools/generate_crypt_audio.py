"""Original acoustic Crypt Keeper SFX; no sampled media, notes or arpeggios in generated clips.

Run directly, or generate_crypt_fx.py --audio-only. Requires numpy and soundfile;
both scripts search build/crypt-fx-deps. --check-only decodes packaged effects.
This module never writes particles, staff models/textures, or battle music.
The runtime roar additionally references the base game's Rex voice, without copying it.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.append(str(ROOT / "build/crypt-fx-deps"))
import numpy as np
import soundfile as sf

SR = 44100
TAU = 2 * np.pi
RES = ROOT / "src/main/resources"
SOUND = RES / "Common/Sounds/Titan/Crypt"
EVENT = RES / "Server/Audio/SoundEvents/Titan/Crypt"
OUT = ROOT / "build/crypt-fx-preview"
AUDITION = ROOT / "docs/audio"
BASE_COMMON = ROOT.parent / "HytaleSourceCode/hytale-shared-source/HytaleAssets/Common"
ROAR_TEXTURE = [f"Sounds/NPC/Mythic/Rex/Rex_Alerted_{i:02d}.ogg" for i in range(1, 4)]

# The variation family is acoustic material rather than a musical pitch sequence.
SOUNDS = {
    "Coffin": ("coffin", 2.6), "Emergence": ("emergence", 2.8), "Roar": ("roar", 3.5),
    "Sweep_Charge": ("charge", 1.15), "Sweep": ("sweep", .85),
    "Slam_Charge": ("charge", 1.25), "Slam": ("slam", 2.1),
    "Blue_Fire": ("fire", 1.4), "Minion_Summon": ("summon", 1.8),
    "Poison": ("poison", 2), "Beam_Charge": ("beamcharge", 1.6), "Beam": ("beam", 1.5),
    "Bracelet_Hit": ("bracelethit", .58), "Bracelet_Break": ("break", 1.6),
    "Bracelet_Regen": ("regen", 2), "Crown_Hit": ("crownhit", .65),
    "Death": ("death", 5.5), "Loot": ("loot", 3),
    "Staff_Charge": ("staffcharge", .65), "Staff_Cast": ("cast", .75),
    "Missile_Impact": ("missilehit", .65), "Grasp_Charge": ("charge", 1.2),
    "Grasp_Impact": ("slam", 1.9),
}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(value, indent=2) + "\n"
    if not path.exists() or path.read_text(encoding="utf-8") != text:
        path.write_text(text, encoding="utf-8")


def unit(signal):
    return signal / max(.01, float(np.sqrt(np.mean(signal**2))))


def band(signal, low, high):
    """Smooth acoustic bandwidth, with DC and ultrasonic energy removed."""
    f = np.fft.rfftfreq(len(signal), 1 / SR)
    mask = (f / np.maximum(f, low))**3 / np.sqrt(1 + (f / high)**8)
    return np.fft.irfft(np.fft.rfft(signal) * mask, len(signal))


def noise(n, random, low=40, high=2800):
    return unit(band(random.normal(size=n), low, high))


def drift(t, random, rate=13):
    """Non-periodic breath and vocal instability, without electronic tremolo."""
    knots = np.arange(0, t[-1] + 2 / rate, 1 / rate)
    return np.interp(t, knots, random.uniform(-1, 1, len(knots)))


def envelope(t, duration, attack=.035, release=.2):
    return np.minimum(1, t / attack)**1.3 * np.clip((duration - t) / release, 0, 1)**1.5


def body(t, random, fundamental=72, damping=9, metal=False):
    """Damped inharmonic modes of struck bone/iron; never a scheduled melody."""
    modes = [(1, 1), (1.47, .58), (2.31, .38), (3.91, .21), (6.18, .10)]
    result = np.zeros(len(t))
    for ratio, gain in modes:
        hz = fundamental * ratio * random.uniform(.975, 1.025)
        decay = damping * (ratio**.28 if metal else ratio**.6)
        # Small strain relaxation makes the object sound physical rather than a perfect oscillator.
        phase = TAU * (hz * t + .06 * (1 - np.exp(-t * 28)))
        result += gain * np.sin(phase) * np.exp(-t * decay)
    return result


def fracture(t, random, weight=1, metal=False):
    n = len(t)
    crack = noise(n, random, 300, 3700) * np.exp(-t * random.uniform(45, 85))
    wood = noise(n, random, 95, 1350) * np.exp(-t * 19)
    ringing = body(t, random, 165 if metal else 95, 17 if metal else 22, metal)
    return weight * (.46 * crack + .32 * wood + .25 * ringing) * np.minimum(1, t / .0015)


def scatter(t, random, count, end, gain=.15, metal=False, first=.04):
    result = np.zeros(len(t))
    for at in sorted(random.uniform(first, end, count)):
        start = int(at * SR)
        if start >= len(t):
            continue
        local = np.arange(len(t) - start) / SR
        result[start:] += fracture(local, random, random.uniform(.5, 1.1) * gain, metal)
    return result


def formants(signal, frequencies):
    f = np.fft.rfftfreq(len(signal), 1 / SR)
    mask = np.zeros_like(f)
    for center, width, gain in frequencies:
        mask += gain * np.exp(-.5 * ((f - center) / width)**2)
    return unit(np.fft.irfft(np.fft.rfft(signal) * mask, len(signal)))


def throat(t, random, fundamental=54, breath=.33):
    """Unstable vocal folds through a changing hollow throat, plus unvoiced breath."""
    u = t / max(.001, t[-1])
    instability = drift(t, random, 19)
    frequency = fundamental * (1 + .12 * np.sin(np.pi * u) - .16 * u + .075 * instability)
    phase = TAU * np.cumsum(frequency) / SR
    source = np.zeros(len(t))
    for harmonic in range(1, 31):
        source += np.sin(phase * harmonic + .11 * harmonic * instability) / harmonic**.8
    source += .5 * np.sin(phase * .5 + drift(t, random, 7))
    source += noise(len(t), random, 100, 3200) * breath
    closed = formants(source, [(190, 95, 1), (420, 150, .8), (930, 240, .28)])
    opened = formants(source, [(330, 140, 1), (760, 210, .72), (1480, 310, .25)])
    mouth = np.sin(np.pi * np.clip(u * 1.05, 0, 1))**.7
    result = closed * (1 - mouth) + opened * mouth
    return result * (.8 + .2 * drift(t, random, 28))


def room(signal, random, wet=.18):
    """Dark diffuse crypt reflections, without an audible rhythmic echo train."""
    direct = band(signal, 40, 1600)
    tail = np.zeros(len(signal))
    for delay in sorted(random.uniform(.028, .63, 23)):
        off = round(delay * SR)
        if off < len(signal):
            tail[off:] += direct[:-off] * np.exp(-delay * 4.8) * random.uniform(.45, 1)
    return signal + wet * tail


def synth(kind, duration, seed):
    random = np.random.default_rng(seed)
    n = round(SR * duration)
    t = np.arange(n) / SR
    u = t / duration
    low = noise(n, random, 28, 240)
    grit = noise(n, random, 140, 2200)
    air = noise(n, random, 480, 3400)
    sig = np.zeros(n)
    if kind in ("slam", "emergence", "break", "bracelethit", "crownhit", "missilehit"):
        heavy = kind in ("slam", "emergence", "break")
        decay = 3.8 if heavy else 14
        sig = .43 * low * np.exp(-t * decay) + .43 * body(t, random, 53 if heavy else 106, decay + 2)
        sig += fracture(t, random, .9 if heavy else .65, kind in ("break", "bracelethit", "crownhit"))
        if heavy:
            sig += scatter(t, random, 17 if kind == "emergence" else 10, 1.15 if kind == "emergence" else .58,
                           .30, kind == "break")
        if kind == "emergence":
            sig += .20 * grit * envelope(t, duration, .16, .9) * (.5 + .5 * drift(t, random, 17))
        if kind in ("bracelethit", "crownhit"):
            sig += .23 * body(t, random, 246 if kind == "crownhit" else 173, 14, True)
            sig += scatter(t, random, 3, .21, .11, True, .025)
        if kind == "missilehit":
            sig += .27 * throat(t, random, 68, .8) * np.exp(-t * 8) * envelope(t, duration, .01, .14)
    elif kind == "roar":
        # Inhalation, a chest-driven open roar, then a broken exhalation; no buzzy flat sustain.
        voiced = throat(t, random, 51, .5) + .20 * throat(t, random, 32, .75)
        shape = np.interp(t, [0, .19, .35, .47, .8, 1.4, 1.9, 2.45, 3.0, duration],
                         [0, .14, .28, 1, .88, .98, .66, .81, .29, 0])
        sig = (.72 * voiced + .27 * low + .15 * air) * shape
        sig += .18 * grit * np.exp(-((t - .26) / .14)**2)
        sig += scatter(t, random, 5, 2.3, .08, first=.8)
    elif kind in ("charge", "beamcharge", "staffcharge", "coffin"):
        shape = np.sin(np.pi * u / 2)**1.2 * envelope(t, duration, .08, .10)
        sig = (.28 * low + .37 * grit + .10 * air) * shape * (.85 + .15 * drift(t, random, 16))
        sig += .24 * throat(t, random, 57 if kind == "staffcharge" else 43, .95) * shape
        if kind == "coffin":
            sig += .4 * low * np.exp(-t * 2) + fracture(t, random, .5)
            sig += scatter(t, random, 18, 1.8, .16)
        elif kind == "beamcharge":
            sig += .23 * throat(t, random, 36, .65) * shape
        elif kind == "charge":
            sig += scatter(t, random, 6, duration * .75, .10)
    elif kind in ("sweep", "poison", "fire", "beam"):
        shape = envelope(t, duration, .06, .25)
        turbulence = .7 + .3 * drift(t, random, 19 if kind == "fire" else 9)
        sig = (.30 * low + .32 * grit + .10 * air) * shape * turbulence
        if kind == "sweep":
            sig *= np.sin(np.pi * u)**1.5
            sig += .16 * body(np.maximum(0, t - .16), random, 83, 12) * (t >= .16)
        if kind == "beam":
            sig += .40 * throat(t, random, 39, .7) * shape
        if kind == "fire":
            sig += scatter(t, random, 23, duration * .9, .12, first=.01)
        if kind == "poison":
            sig += .25 * throat(t, random, 71, 1.4) * shape
            sig += scatter(t, random, 12, 1.5, .07)
    elif kind == "cast":
        shape = envelope(t, duration, .014, .28) * np.exp(-t * 3.6)
        sig = (.4 * throat(t, random, 63, 1.1) + .23 * low + .22 * grit) * shape
        sig += fracture(t, random, .40) + .2 * body(t, random, 73, 10)
    elif kind in ("summon", "regen"):
        shape = envelope(t, duration, .07, .55)
        sig = (.30 * low + .19 * throat(t, random, 46, 1.2)) * shape
        sig += scatter(t, random, 21 if kind == "regen" else 17, duration * .72, .27, kind == "regen")
        sig += .2 * grit * shape * (.5 + .5 * drift(t, random, 12))
    elif kind == "death":
        shape = envelope(t, duration, .05, 1.4) * (1 - u)**1.15
        sig = (.42 * throat(t, random, 44, .7) + .38 * low + .10 * grit) * shape
        sig += scatter(t, random, 28, 3.5, .16)
        collapse = np.maximum(0, t - 1.7)
        sig += (.3 * low * np.exp(-collapse * 2.2) + .3 * body(collapse, random, 38, 2)) * (t >= 1.7)
    elif kind == "loot":
        # A single dark struck-vessel toll with breathing wisps, not a reward arpeggio.
        sig = .48 * body(t, random, 92, 1.5, True) + .10 * body(t, random, 151, 2.4, True)
        sig += .14 * throat(t, random, 51, 1) * envelope(t, duration, .4, .9) * (1 - u)
        sig += .12 * grit * np.exp(-t * 14)
    else:
        raise ValueError(kind)
    sig = room(sig, random, .11 if kind in ("bracelethit", "crownhit", "missilehit", "cast") else .18)
    sig = band(sig, 25, 4700)
    sig -= np.mean(sig)
    # Preserve impact dynamics; only shave the largest excursions, without square-wave distortion.
    sig /= max(.01, float(np.max(np.abs(sig))))
    sig = np.tanh(sig * 1.15) / np.tanh(1.15)
    sig *= .76
    fade_in = round(.0025 * SR)
    fade_out = round(min(.10, duration * .16) * SR)
    sig[:fade_in] *= np.linspace(0, 1, fade_in)
    sig[-fade_out:] *= np.linspace(1, 0, fade_out)
    return sig.astype(np.float32)


def inspect_audio(path, expected_duration):
    decoded, rate = sf.read(path)
    assert rate == SR and decoded.ndim == 1 and np.isfinite(decoded).all(), path
    assert len(decoded) == round(expected_duration * SR), path
    peak = float(np.max(np.abs(decoded)))
    rms = float(np.sqrt(np.mean(decoded**2)))
    assert .2 < peak < .95 and .018 < rms < .38, (path, peak, rms)
    assert abs(float(np.mean(decoded))) < .003, path
    assert max(abs(decoded[0]), abs(decoded[-1])) < .015, (path, "click at boundary")
    spectrum = np.abs(np.fft.rfft(decoded))**2
    frequencies = np.fft.rfftfreq(len(decoded), 1 / SR)
    high = float(spectrum[frequencies > 4500].sum() / spectrum.sum())
    assert high < .06, (path, "excessive bright high frequency energy", high)
    return {"file": path.name, "seconds": expected_duration, "peak": round(peak, 4), "rms": round(rms, 4),
            "energyAbove4500Hz": round(high, 5), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def protected_hashes():
    paths = []
    for folder in ["Server/Particles/Titan/Crypt", "Common/Particles/Textures/Titan/Crypt"]:
        paths.extend(path for path in (RES / folder).rglob("*") if path.is_file())
    paths.extend([RES / "Common/Music/Titan/Crypt_Keeper_Battle.ogg",
                  RES / "Server/Audio/MusicContainers/Titan/Track_Crypt_Keeper_Battle.json"])
    return {str(path.relative_to(RES)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def audition():
    names = ["Staff_Charge", "Staff_Cast", "Missile_Impact", "Crown_Hit", "Bracelet_Hit", "Bracelet_Break", "Roar", "Slam", "Death", "Loot"]
    segments, cues = [], []
    cursor = 0
    for name in names:
        samples, _ = sf.read(SOUND / f"Crypt_{name}_01.ogg")
        cues.append(f"| {cursor:.2f}s | {name.replace('_', ' ')} |")
        segments.extend([samples, np.zeros(round(.65 * SR))])
        cursor += len(samples) / SR + .65
    AUDITION.mkdir(parents=True, exist_ok=True)
    sf.write(AUDITION / "crypt-sfx-audition.wav", np.concatenate(segments), SR, subtype="PCM_16")
    (AUDITION / "crypt-sfx-audition.md").write_text(
        "Original Crypt Keeper sound redesign. Each cue is followed by 0.65 seconds of silence. "
        "This montage contains the authored layers; the in-game roar additionally mixes a quiet "
        "base-game Rex throat texture by asset reference. No stock audio is copied into the mod or preview.\n\n"
        "[Play the montage](crypt-sfx-audition.wav)\n\n| Start | Cue |\n| --- | --- |\n" + "\n".join(cues) + "\n",
        encoding="utf-8")


def main():
    before = protected_hashes()
    check_only = "--check-only" in sys.argv
    for path in [SOUND, EVENT, OUT]:
        path.mkdir(parents=True, exist_ok=True)
    metrics = []
    for index, (name, (kind, duration)) in enumerate(SOUNDS.items()):
        files = []
        for variation in range(2):
            basename = f"Crypt_{name}_{variation+1:02d}.ogg"
            path = SOUND / basename
            if not check_only:
                sf.write(path, synth(kind, duration, 862021 + index * 179 + variation * 1087),
                         SR, format="OGG", subtype="VORBIS")
            metrics.append(inspect_audio(path, duration))
            files.append(f"Sounds/Titan/Crypt/{basename}")
        weapon = name.startswith(("Staff", "Missile", "Grasp"))
        repeated = name in ("Crown_Hit", "Bracelet_Hit", "Missile_Impact")
        event = {"Volume": -7 if repeated else -5 if name in ("Beam", "Poison", "Staff_Charge") else -2,
                 "StartAttenuationDistance": 5 if repeated else 8, "MaxDistance": 56 if weapon else 96,
                 "SpatialBlend": 1, "MaxInstance": 5 if repeated else 4 if name in ("Roar", "Death", "Beam") else 8,
                 "AudioCategory": "AudioCat_Weapons" if weapon else "AudioCat_NPC",
                 "Layers": [{"Files": files, "RandomSettings": {"MinPitch": -.55, "MaxPitch": .12,
                              "MinVolume": -1, "MaxVolume": 0}, "RoundRobinHistorySize": 1}]}
        if name == "Roar":
            event["Layers"].append({"Files": ROAR_TEXTURE, "Volume": -7, "StartDelay": .12,
                                     "RandomSettings": {"MinPitch": -.4, "MaxPitch": 0, "MinVolume": -1, "MaxVolume": 0},
                                     "RoundRobinHistorySize": 1})
            # Pitch is measured in semitones. Every base texture finishes within the existing
            # 3.5-second cue, including delay and the slowest permitted pitch variation.
            for file in ROAR_TEXTURE:
                info = sf.info(BASE_COMMON / file)
                assert info.channels == 1 and .12 + info.duration / 2**(-.4 / 12) <= 3.5, file
        if not check_only:
            write_json(EVENT / f"SFX_Crypt_{name}.json", event)
        else:
            assert json.loads((EVENT / f"SFX_Crypt_{name}.json").read_text()) == event, name
    assert protected_hashes() == before, "Particle or music resources were modified"
    if not check_only:
        write_json(OUT / "audio-metrics.json", metrics)
        write_json(OUT / "audio-protected-assets.json", before)
        audition()
    print(f"Crypt organic audio {'validated' if check_only else 'generated'}: {len(SOUNDS)} events, {len(metrics)} mono 44100-Hz Vorbis clips; decoded peaks/RMS/DC/fades/bandwidth passed; particles and music unchanged.")
    return metrics


if __name__ == "__main__":
    main()
