"""Probe the delivered trailer, inspect representative frames, check music-only audio."""
from pathlib import Path
import concurrent.futures
import json
import subprocess
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
FF=next((ROOT/'.deps/imageio_ffmpeg/binaries').glob('ffmpeg*.exe'))
PROBE=Path('C:/Program Files/Krita (x64)/bin/ffprobe.exe')
VIDEO=ROOT/'Titan_Gameplay_Trailer_1080p60.mp4'
PLAN=json.loads((ROOT/'edit.json').read_text())
OUT=ROOT/'review/final'
OUT.mkdir(parents=True,exist_ok=True)

def extract(t):
    p=OUT/f'frame_{t:06.2f}.jpg'
    subprocess.run([str(FF),'-v','error','-y','-ss',str(t),'-i',str(VIDEO),'-frames:v','1','-q:v','2',str(p)],check=True)
    return p

probe=json.loads(subprocess.check_output([str(PROBE),'-v','error','-count_frames','-show_entries','format=duration,size:stream=index,codec_name,codec_type,width,height,r_frame_rate,nb_read_frames,sample_rate,channels,duration','-of','json',str(VIDEO)]))
assert len(probe['streams'])==2,probe
v,a=probe['streams']
assert (v['width'],v['height'],v['r_frame_rate'],int(v['nb_read_frames']))==(1920,1080,'60/1',4800),v
assert a['codec_name']=='aac' and a['channels']==2,a
assert abs(float(probe['format']['duration'])-80)<0.05,probe

times=[0.25,0.75,2,4,5.5,6.5,9.5,13.5,15.5,17,20.5,25,28,30.5,33,36,38,41,44,49,53.5,55,59,64,68.5,70.5,71.75,73,74.5,75.75,77,79]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    frames=list(pool.map(extract,times))
font=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',19)
for page in range(2):
    sheet=Image.new('RGB',(1920,4*302),(18,25,29));draw=ImageDraw.Draw(sheet)
    for k,p in enumerate(frames[page*16:(page+1)*16]):
        x=(k%4)*480;y=(k//4)*302
        im=Image.open(p);im.thumbnail((480,270))
        sheet.paste(im,(x,y+30))
        draw.text((x+10,y+4),f'{times[page*16+k]:.2f}s',font=font,fill=(241,239,229))
    sheet.save(OUT/f'contact_{page+1}.jpg',quality=92)

def decode(args):
    raw=subprocess.check_output([str(FF),'-v','error',*args,'-f','f32le','-ac','2','-ar','48000','pipe:1'])
    return np.frombuffer(raw,dtype=np.float32).reshape(-1,2)
actual=decode(['-i',str(VIDEO),'-vn'])
reference=decode(['-stream_loop','-1','-i',PLAN['music'],'-t','80','-af','aresample=48000,afade=t=in:st=0:d=0.15,afade=t=out:st=78:d=2'])
n=min(len(actual),len(reference));actual=actual[:n];reference=reference[:n]
correlation=float(np.corrcoef(actual[48000:-48000].reshape(-1),reference[48000:-48000].reshape(-1))[0,1])
assert correlation>0.98,correlation
probe['music_only_correlation']=round(correlation,6)
probe['peak_sample_dbfs']=round(float(20*np.log10(np.max(np.abs(actual)))),3)
probe['rms_dbfs']=round(float(20*np.log10(np.sqrt(np.mean(actual**2)))),3)
probe['source_recordings_modified']=False
(OUT/'verification.json').write_text(json.dumps(probe,indent=2))
subprocess.run([str(FF),'-hide_banner','-i',str(VIDEO),'-vf','blackdetect=d=0.15:pix_th=0.03,freezedetect=n=-50dB:d=1.5','-af','ebur128=peak=true','-f','null','-'],stdout=(OUT/'signal_checks.log').open('w'),stderr=subprocess.STDOUT,check=True)
print(json.dumps(probe,indent=2))
print('QA sheets:',OUT/'contact_1.jpg',OUT/'contact_2.jpg')
