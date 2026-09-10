"""Compose the original, seamless 40-second Crypt Keeper battle music loop.

96 BPM / D minor with a Phrygian passing tone, synthetic bowed ostinato,
inharmonic bell melody, distant choir and two-register ritual percussion.
Requires numpy and soundfile; no third-party samples or recordings.
"""
from pathlib import Path
import json
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.append(str(ROOT/"build/crypt-fx-deps"))
import numpy as np
import soundfile as sf
from crypt_music_levels import metrics, master, true_peak, references

SR=44100
BEAT=60/96
DURATION=64*BEAT
N=round(SR*DURATION)
TAU=2*np.pi
CONTAINER_VOLUME=3
MUSIC_PATH=ROOT/"src/main/resources/Common/Music/Titan/Crypt_Keeper_Battle.ogg"
CONTAINER_PATH=ROOT/"src/main/resources/Server/Audio/MusicContainers/Titan/Track_Crypt_Keeper_Battle.json"


def check_master():
    decoded,rate=sf.read(MUSIC_PATH)
    assert rate==SR and decoded.shape==(N,2) and np.isfinite(decoded).all()
    result=metrics(decoded,rate)
    peak=true_peak(decoded)
    assert -14.1 <= result["integratedLUFS"] <= -13.0, result
    assert result["shortTerm10thLUFS"] > -15.5, result
    assert result["boundaryDelta"] < .015, result
    assert peak*10**(CONTAINER_VOLUME/20)<.99, ("post-container true peak",peak)
    result["truePeak"]=round(peak,6)
    result["postContainerTruePeak"]=round(peak*10**(CONTAINER_VOLUME/20),6)
    result["containerVolumeDb"]=CONTAINER_VOLUME
    result["nativeCategoryVolumeDb"]=-14
    result["effectiveLUFSAtFullMusicVolume"]=round(result["integratedLUFS"]+CONTAINER_VOLUME-14,3)
    return result


if "--check-only" in sys.argv:
    assert json.loads(CONTAINER_PATH.read_text())["Volume"]==CONTAINER_VOLUME
    print(json.dumps(check_master(),indent=2))
    raise SystemExit(0)

mix=np.zeros((N,2))
random=np.random.default_rng(918324)


def note(midi):
    return 440*2**((midi-69)/12)


def add(start, samples, pan=0):
    index=(round(start*SR)+np.arange(len(samples)))%N
    mix[index,0] += samples*np.sqrt((1-pan)/2)
    mix[index,1] += samples*np.sqrt((1+pan)/2)


def noise(n, cutoff):
    data=random.normal(size=n)
    spectrum=np.fft.rfft(data)
    freq=np.fft.rfftfreq(n,1/SR)
    data=np.fft.irfft(spectrum/(1+(freq/cutoff)**4),n)
    return data/max(.1,float(np.std(data)))


def bowed(midi, length, amp=.08):
    t=np.arange(round(SR*length))/SR
    hz=note(midi)
    sig=sum(np.sin(TAU*hz*k*t+.006*k*np.sin(TAU*5*t))/k**1.3 for k in range(1,9))
    env=np.minimum(t/.03,1)*np.minimum((length-t)/.09,1)
    return sig*env*amp


def bell(midi,length,amp=.1):
    t=np.arange(round(SR*length))/SR
    sig=sum(a*np.sin(TAU*note(midi)*k*t)*np.exp(-t*(1.1+.45*k)) for k,a in [(1,1),(2.71,.36),(4.12,.14)])
    return sig*np.minimum(t/.005,1)*amp


def drum(hz,length,amp):
    t=np.arange(round(SR*length))/SR
    f=hz+hz*1.4*np.exp(-t*24)
    phase=TAU*np.cumsum(f)/SR
    sig=np.sin(phase)*np.exp(-t*6)+.19*noise(len(t),1800)*np.exp(-t*22)
    return amp*sig*np.minimum(t/.004,1)


roots=[38,38,34,36,38,41,39,33]  # D, Bb, C, F, Eb, A tension.
motif=[74,77,81,77,75,74,69,72]
for bar in range(16):
    root=roots[(bar//2)%len(roots)]
    start=bar*4*BEAT
    # Four notes pulse under two heavier drum strikes; sparse enough to leave
    # player-facing telegraph sounds intelligible over the music bed.
    for step,interval in enumerate([0,7,12,7,0,7,10,7]):
        add(start+step*.5*BEAT,bowed(root+interval,.36,.075 if step%2==0 else .052),-.25)
    for beat in [0,2.5]:
        add(start+beat*BEAT,drum(48,.8,.26),-.12)
    for beat in [1,3,3.5]:
        add(start+beat*BEAT,drum(112,.3,.075),.4)
    if bar%2==0:
        add(start+BEAT,bell(motif[(bar//2)%8],2.4,.095),.3)
    if bar>=8:
        add(start+2.5*BEAT,bell(motif[((bar//2)+3)%8]-12,1.8,.052),-.4)
    # Harmonic choir pad rolls across bar boundaries and wraps at loop end.
    length=4*BEAT+.8
    t=np.arange(round(length*SR))/SR
    env=np.minimum(t/.7,1)*np.minimum((length-t)/.8,1)
    pad=np.zeros_like(t)
    for interval in [12,19,24,27 if root!=33 else 28]:
        f=note(root+interval)
        for detune in [-.002,.002]:
            phase=TAU*f*(1+detune)*t+.035*np.sin(TAU*4.7*t)
            pad += (np.sin(phase)+.27*np.sin(phase*2)+.10*np.sin(phase*3))*.009
    add(start-.4,pad*env,0)

# Circular reflections preserve the tails across the loop boundary.
direct=mix.copy()
for delay,gain in [(.117,.24),(.251,.18),(.403,.12),(.711,.07),(.967,.04)]:
    mix+=np.roll(direct[:,::-1],round(delay*SR),axis=0)*gain
mix-=mix.mean(axis=0)
# Preserve the old master for an honest gain comparison; the new master controls
# percussion peaks rather than merely raising a container gain into clipping.
legacy=np.tanh(mix*1.3)
legacy*=.63/np.max(np.abs(legacy))
before=metrics(legacy,SR)
mix=master(legacy,SR)
native={name:values for name,values in references().items() if not name.startswith("Crypt")}
path=MUSIC_PATH
path.parent.mkdir(parents=True,exist_ok=True)
with sf.SoundFile(path, mode="w", samplerate=SR, channels=2, format="OGG", subtype="VORBIS") as encoded:
    for offset in range(0,N,4096):
        encoded.write(mix[offset:offset+4096].astype(np.float32))
result=check_master()
container={"Type":"SingleTrack","Track":"Music/Titan/Crypt_Keeper_Battle.ogg",
           "AudioCategory":"AudioCat_Music_In_Game","Volume":CONTAINER_VOLUME,"LoopCount":0,
           "FadeInDuration":1.8,"FadeOutDuration":3}
path=CONTAINER_PATH
path.write_text(json.dumps(container,indent=2)+"\n",encoding="utf-8")
report={"oldMaster":before,"newMaster":result,"native40SecondReferences":native,
        "oldContainerVolumeDb":-1,"effectiveLoudnessIncreaseDb":round(result["integratedLUFS"]-before["integratedLUFS"]+CONTAINER_VOLUME+1,3),
        "processing":"Broad sub-bass cut/presence EQ, stereo-linked lookahead peak control, gated K-weighted loudness target -13.5 LUFS"}
report_path=ROOT/"build/crypt-fx-preview/music-mastering.json"
report_path.parent.mkdir(parents=True,exist_ok=True)
report_path.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
print(json.dumps(report,indent=2))
