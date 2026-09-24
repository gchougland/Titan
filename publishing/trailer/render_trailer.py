"""Rebuild Titan's 80-second trailer from the non-destructive JSON edit list.

Run with Python + Pillow. FFmpeg is resolved from the local bundled tool folder,
an FFMPEG_EXE environment variable, or PATH. Source recordings stay untouched.
"""
from pathlib import Path
import concurrent.futures
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
LOCAL = list((ROOT/'.deps/imageio_ffmpeg/binaries').glob('ffmpeg*.exe'))
FF = os.environ.get('FFMPEG_EXE') or (str(LOCAL[0]) if LOCAL else shutil.which('ffmpeg'))
PLAN = json.loads((ROOT/'edit.json').read_text(encoding='utf-8'))
WORK = ROOT/'work'
WORK.mkdir(exist_ok=True)
FPS = 60
ENC = ['-c:v','h264_nvenc','-preset','p6','-tune','hq','-rc','vbr','-cq','17','-b:v','0','-pix_fmt','yuv420p','-profile:v','high','-g','120']
if '--cpu' in sys.argv:
    ENC = ['-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-profile:v','high','-g','120']

def run(args, log):
    with open(log,'w',encoding='utf-8') as f:
        proc = subprocess.run([str(FF),'-hide_banner','-y','-filter_complex_threads','2',*map(str,args)],stdout=f,stderr=subprocess.STDOUT)
    if proc.returncode:
        raise RuntimeError(Path(log).read_text(encoding='utf-8',errors='replace')[-7000:])

def render_section(index):
    s=PLAN['sections'][index]
    dst=WORK/f'{index:02d}_{s["id"]}.mp4'
    if '--resume' in sys.argv and dst.exists():
        print(f'Using {dst.name}',flush=True)
        return dst
    args=[]; filters=[]
    shots=s['shots']
    duration=sum(x['duration'] for x in shots)
    handle=PLAN['transition_seconds'] if index<len(PLAN['sections'])-1 else 0
    duration+=handle
    for j,shot in enumerate(shots):
        d=shot['duration']+(handle if j==len(shots)-1 else 0)
        speed=shot.get('speed',1)
        args+=['-threads','2','-ss',shot['start'],'-t',round(d*speed+0.1,5),'-i',str(Path(PLAN['source_dir'])/shot['file'])]
        crop=shot.get('crop')
        vf=(f'crop={crop},' if crop else '')
        vf+=f'setpts=(PTS-STARTPTS)/{speed},fps={FPS},scale=1920:1080:force_original_aspect_ratio=increase:flags=lanczos,crop=1920:1080,setsar=1,trim=duration={d},setpts=PTS-STARTPTS,settb=AVTB,format=yuv420p'
        filters.append(f'[{j}:v]{vf}[s{j}]')
    if len(shots)>1:
        filters.append(''.join(f'[s{j}]' for j in range(len(shots)))+f'concat=n={len(shots)}:v=1:a=0[base]')
    else:
        filters.append('[s0]null[base]')
    n=len(shots)
    current='base'
    if s.get('title'):
        args+=['-loop','1','-framerate',str(FPS),'-i',str(ROOT/'graphics'/s['title'])]
        if s.get('hold_title'):
            filters.append(f'[{n}:v]format=rgba[titles]')
            filters.append('[base][titles]overlay=0:0:shortest=1[titled]')
        else:
            filters.append(f'[{n}:v]format=rgba,fade=t=in:st=0.12:d=0.25:alpha=1,fade=t=out:st=2.75:d=0.35:alpha=1[titles]')
            filters.append("[base][titles]overlay=x='-42*pow(max(0,1-t/0.4),3)':y=0:shortest=1[titled]")
        current='titled'; n+=1
    if s.get('logo'):
        # Branding stays over moving gameplay. Slightly darkened/softened imagery
        # gives the supplied dark stone silhouette enough separation.
        filters.append(f'[{current}]gblur=sigma=2.2,drawbox=x=0:y=0:w=iw:h=ih:color=0x101C22@0.40:t=fill[backdrop]')
        args+=['-loop','1','-framerate',str(FPS),'-i',PLAN['logo']]
        scale="trunc((570+70*exp(-t*3))/2)*2"
        filters.append(f"[{n}:v]format=rgba,scale=w='{scale}':h='{scale}':eval=frame,fade=t=in:st=0.15:d=0.6:alpha=1,fade=t=out:st={duration-0.55}:d=0.5:alpha=1[logo]")
        filters.append("[backdrop][logo]overlay=x='(W-w)/2':y='105+(640-h)/2':shortest=1[branded]")
        n+=1
        args+=['-loop','1','-framerate',str(FPS),'-i',str(ROOT/'graphics'/('end_text.png' if s['logo']=='end' else 'intro_tagline.png'))]
        filters.append(f'[{n}:v]format=rgba,fade=t=in:st=0.7:d=0.35:alpha=1,fade=t=out:st={duration-0.5}:d=0.4:alpha=1[tag]')
        filters.append('[branded][tag]overlay=0:0:shortest=1[logofinal]')
        current='logofinal'
    finalfx=f'format=yuv420p,fps=60,trim=duration={duration},settb=1/60,setpts=N'
    if index==0:
        finalfx+=',fade=t=in:st=0:d=0.25'
    if index==len(PLAN['sections'])-1:
        finalfx+=f',fade=t=out:st={duration-0.65}:d=0.65'
    filters.append(f'[{current}]{finalfx}[v]')
    script=WORK/f'{index:02d}_{s["id"]}.ffgraph'
    script.write_text(';\n'.join(filters),encoding='utf-8')
    args+=['-filter_complex_script',script,'-map','[v]','-an','-t',duration,'-r','60','-fps_mode','cfr',*ENC,'-video_track_timescale','15360','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709','-movflags','+faststart',dst]
    print(f'Rendering {s["id"]}: {duration:.2f}s',flush=True)
    start=time.time()
    run(args,WORK/f'{index:02d}_{s["id"]}.log')
    print(f'Finished {s["id"]} in {time.time()-start:.1f}s',flush=True)
    return dst

def merge():
    args=[]; filters=[]
    for i,s in enumerate(PLAN['sections']):
        args+=['-threads','2','-i',WORK/f'{i:02d}_{s["id"]}.mp4']
        filters.append(f'[{i}:v]setpts=PTS-STARTPTS,fps=60,settb=AVTB,format=yuv420p[v{i}]')
    music=len(PLAN['sections'])
    args+=['-stream_loop','-1','-i',PLAN['music']]
    offset=0;prev='v0'
    for i in range(1,len(PLAN['sections'])):
        offset+=sum(x['duration'] for x in PLAN['sections'][i-1]['shots'])
        trans=PLAN['sections'][i].get('transition','fade')
        label=f'x{i}'
        filters.append(f'[{prev}][v{i}]xfade=transition={trans}:duration={PLAN["transition_seconds"]}:offset={offset}[{label}]')
        prev=label
    total=sum(x['duration'] for s in PLAN['sections'] for x in s['shots'])
    filters.append(f'[{prev}]format=yuv420p,tpad=stop_mode=clone:stop_duration=0.1,trim=duration={total},fps=60[video]')
    filters.append(f'[{music}:a]atrim=duration={total},asetpts=PTS-STARTPTS,aresample=48000,afade=t=in:st=0:d=0.15,afade=t=out:st={total-2}:d=2[audio]')
    script=WORK/'master.ffgraph'
    script.write_text(';\n'.join(filters),encoding='utf-8')
    args+=['-filter_complex_script',script,'-map','[video]','-map','[audio]','-t',total,'-r','60','-fps_mode','cfr',*ENC,'-video_track_timescale','15360','-c:a','aac','-b:a','320k','-ar','48000','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709','-movflags','+faststart','-metadata','title=Titan | Think Bigger | Gameplay Trailer','-metadata','comment=Gameplay edit featuring all six titans. Music: Crypt Keeper Battle. A Hytale mod by Hexvane.',ROOT/'Titan_Gameplay_Trailer_1080p60.mp4']
    print('Assembling final trailer with the Crypt Keeper music only.',flush=True)
    run(args,WORK/'master.log')
    print(f'Complete: {ROOT / "Titan_Gameplay_Trailer_1080p60.mp4"}',flush=True)

if __name__=='__main__':
    if '--section' in sys.argv:
        render_section(int(sys.argv[sys.argv.index('--section')+1]))
    elif '--merge-only' in sys.argv:
        merge()
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(render_section,range(len(PLAN['sections']))))
        merge()
