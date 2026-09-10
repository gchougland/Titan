"""Offline music metering: K-weighted gated integrated loudness and short-term dynamics.

Uses the BS.1770 K-weighting biquads in the frequency domain, then 400ms blocks
with 75% overlap and the -70 LUFS / -10 LU gates. Circular filter boundaries
match the authored loop; negligible boundary transients remain for stock excerpts.
"""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.append(str(ROOT/"build/crypt-fx-deps"))
import numpy as np
import soundfile as sf


def weighting(rate, n):
    f = np.fft.rfftfreq(n, 1/rate)
    z = np.exp(-2j*np.pi*f/rate)
    k = np.tan(np.pi*1681.974450955533/rate)
    q = .7071752369554196
    vh, vb = 1.584864701130855, 1.258720930232561
    a0 = 1+k/q+k*k
    b = np.array([vh+vb*k/q+k*k, 2*(k*k-vh), vh-vb*k/q+k*k])/a0
    a = np.array([1, 2*(k*k-1)/a0, (1-k/q+k*k)/a0])
    shelf = (b[0]+b[1]*z+b[2]*z*z)/(1+a[1]*z+a[2]*z*z)
    k = np.tan(np.pi*38.13547087602444/rate)
    q = .5003270373238773
    a0 = 1+k/q+k*k
    a1, a2 = 2*(k*k-1)/a0, (1-k/q+k*k)/a0
    highpass = (1-2*z+z*z)/(1+a1*z+a2*z*z)
    return shelf*highpass


def metrics(signal, rate):
    if signal.ndim == 1:
        signal = signal[:,None]
    n = len(signal)
    filtered = np.fft.irfft(np.fft.rfft(signal, axis=0)*weighting(rate,n)[:,None], n, axis=0)
    energy = np.sum(filtered**2, axis=1)
    accumulated = np.r_[0,np.cumsum(energy)]
    def blocks(seconds, stride):
        length, step = round(seconds*rate), round(stride*rate)
        starts = np.arange(0,n-length+1,step)
        return (accumulated[starts+length]-accumulated[starts])/length
    block = blocks(.4,.1)
    loudness = -.691+10*np.log10(np.maximum(block,1e-20))
    absolute = block[loudness>-70]
    relative = -.691+10*np.log10(np.mean(absolute))-10
    gated = block[(loudness>-70)&(loudness>relative)]
    integrated = -.691+10*np.log10(np.mean(gated))
    short = -.691+10*np.log10(np.maximum(blocks(3,1),1e-20))
    peak = float(np.max(np.abs(signal)))
    rms = float(np.sqrt(np.mean(signal**2)))
    return {"seconds":n/rate,"channels":signal.shape[1],"rate":rate,
            "integratedLUFS":round(float(integrated),3),"rms":round(rms,6),
            "rmsDbFS":round(20*np.log10(rms),3),"peak":round(peak,6),"peakDbFS":round(20*np.log10(peak),3),
            "shortTerm10thLUFS":round(float(np.percentile(short,10)),3),
            "shortTerm90thLUFS":round(float(np.percentile(short,90)),3),
            "boundaryDelta":round(float(np.max(np.abs(signal[-1]-signal[0]))),6)}


def true_peak(signal, oversample=4):
    # Band-limited interpolation also tests inter-sample peaks at the seamless boundary.
    peak=0.0
    for channel in signal.T:
        interpolated=np.fft.irfft(np.fft.rfft(channel),len(channel)*oversample)*oversample
        peak=max(peak,float(np.max(np.abs(interpolated))))
    return peak


def limit_linked(signal, ceiling=.64, rate=44100):
    """Stereo-linked 5ms lookahead peak control with 120ms release and circular state."""
    step=round(.005*rate)
    count=int(np.ceil(len(signal)/step))
    padded=np.pad(signal,((0,count*step-len(signal)),(0,0)),mode="wrap")
    peaks=np.max(np.abs(padded.reshape(count,step,2)),axis=(1,2))
    # Both interpolated gain anchors cover every sample in their intervening block.
    required=np.minimum(1,ceiling/np.maximum(.001,np.maximum(np.maximum(peaks,np.roll(peaks,1)),np.roll(peaks,-1))))
    release=np.exp(-step/(rate*.12))
    gains=np.ones(count)
    value=1.0
    for _ in range(3):
        for index,target in enumerate(required):
            value=min(target,1-(1-value)*release)
            gains[index]=value
    anchors=np.arange(count+1)*step
    gain=np.interp(np.arange(len(signal)),anchors,np.r_[gains,gains[0]])
    return signal*gain[:,None]


def master(signal, rate, target=-13.5):
    # Retain the composition while reducing sub-bass masking and bringing the bowed
    # melody/choir forward on ordinary speakers. These are broad, gentle tonal changes.
    f=np.fft.rfftfreq(len(signal),1/rate)
    db=-2.0/(1+(f/95)**4)+2.0*np.exp(-.5*(np.log2(np.maximum(f,1)/1350)/1.3)**2)
    equalized=np.fft.irfft(np.fft.rfft(signal,axis=0)*10**(db[:,None]/20),len(signal),axis=0)
    drive=10**((target-metrics(equalized,rate)["integratedLUFS"])/20)
    for _ in range(4):
        result=limit_linked(equalized*drive,rate=rate)
        difference=target-metrics(result,rate)["integratedLUFS"]
        if abs(difference)<.05:
            break
        drive*=10**(difference/20)
    return result


def references():
    base = ROOT.parent/"HytaleSourceCode/hytale-shared-source/HytaleAssets/Common"
    paths = [("Crypt existing",ROOT/"src/main/resources/Common/Music/Titan/Crypt_Keeper_Battle.ogg",0),
             ("Native Goblin boss opening",base/"Music/Zone1/Z1D_Goblin_Boss_Battle.ogg",0),
             ("Native Goblin boss middle",base/"Music/Zone1/Z1D_Goblin_Boss_Battle.ogg",40),
             ("Native FightorFlight opening",base/"Music/zUnused/FightorFlight.ogg",0)]
    result={}
    for label,path,start in paths:
        info=sf.info(path)
        data,rate=sf.read(path,start=round(start*info.samplerate),frames=round(40*info.samplerate))
        result[label]=metrics(data,rate)
    return result


if __name__ == "__main__":
    print(json.dumps(references(),indent=2))
