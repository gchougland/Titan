"""Rebuild the Temple boulder's original geometry, pixel atlas, assembly clip, VFX and acoustic SFX.

Requires Pillow, numpy and soundfile. Entity geometry uses 64 model units per block.
Only writes this attack's named assets; does not regenerate the Temple or other titan art.
"""
from pathlib import Path
import copy
import json
import math
import sys
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
sys.path.append(str(ROOT / 'build/crypt-fx-deps'))
import soundfile as sf
import generate_crypt_audio as acoustic

RES = ROOT / 'src/main/resources'
ART = RES / 'Common/VFX/Titan/RollingBoulder'
SERVER = RES / 'Server'
RNG = np.random.default_rng(241109)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def xyz(x, y=None, z=None):
    return dict(zip('xyz', (x, x if y is None else y, x if z is None else z)))


def key(t, delta):
    return {'time': t, 'delta': delta, 'interpolationType': 'smooth'}


def art():
    ART.mkdir(parents=True, exist_ok=True)
    atlas = Image.new('RGBA', (256, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    palette = [(92, 98, 100), (105, 111, 110), (73, 82, 89), (113, 114, 103), (80, 91, 97), (126, 132, 125)]
    for tile, base in enumerate(palette):
        ox = tile * 32
        draw.rectangle((ox, 0, ox+31, 31), fill=(*base, 255))
        for _ in range(64):
            x, y = RNG.integers(0, 30, 2); w, h = RNG.integers(1, 5, 2)
            shade = int(RNG.choice([-13, -7, 5, 10]))
            color = tuple(int(np.clip(c+shade, 0, 255)) for c in base) + (255,)
            draw.rectangle((ox+x, y, ox+min(31, x+w), min(31, y+h)), fill=color)
        # Broad chipped edges and sparse fracture lines retain readable hand-painted pixel clusters.
        draw.line((ox, 0, ox+28, 0), fill=tuple(min(255,c+25) for c in base)+(255,), width=2)
        draw.line((ox+31, 4, ox+31, 31), fill=tuple(max(0,c-22) for c in base)+(255,), width=2)
        draw.line((ox+7, 10, ox+11, 15, ox+9, 22), fill=tuple(max(0,c-23) for c in base)+(255,), width=1)
    for x in range(192, 224):
        for y in range(32):
            edge = min(x-192, 223-x, y, 31-y)
            c = (29, 107, 155) if edge < 3 else (48, 171, 225) if edge < 8 else (141, 226, 248)
            atlas.putpixel((x, y), (*c, 255))
    atlas.save(ART / 'Boulder.png')
    nodes, tracks = [], {}

    def chunk(name, pos, size, tile, luminous=False):
        n = len(nodes)+1
        base = 24
        uv = {face: {'offset': {'x': tile*32, 'y': 0}, 'mirror': {'x': False, 'y': False}, 'angle': 0}
              for face in ('front','back','left','right','top','bottom')}
        stretch = xyz(*(v*64/base for v in size))
        nodes.append({'id': str(n), 'name': name, 'position': xyz(*(v*64 for v in pos)),
            'orientation': {'x': 0, 'y': 0, 'z': 0, 'w': 1}, 'children': [],
            'shape': {'type': 'box', 'offset': xyz(0), 'stretch': stretch, 'settings': {'size': xyz(base)},
                      'visible': True, 'doubleSided': False, 'shadingMode': 'fullbright' if luminous else 'flat',
                      'unwrapMode': 'custom', 'textureLayout': uv}})
        if luminous:
            tracks[name] = {'shapeStretch': [key(0, xyz(0)), key(47, xyz(0)), key(65, xyz(.18)), key(84, xyz(1))]}
        else:
            delay = int(RNG.integers(0, 16))
            start = xyz(float(pos[0]*110), float(150+RNG.uniform(0, 65)), float(pos[2]*110))
            tracks[name] = {'position': [key(0, start), key(delay, start), key(55+delay, xyz(0)), key(84, xyz(0))],
                            'shapeStretch': [key(0, xyz(.05)), key(delay+10, xyz(.25)), key(55+delay, xyz(1)), key(84, xyz(1))]}

    chunk('Heart', (0,0,0), (1.93,1.93,1.93), 6, True)
    # Six plates and twelve edge stones form a rounded, broken shell with visible seams.
    for axis in range(3):
        for sign in (-1,1):
            p = [0.,0.,0.]; p[axis]=sign*.92
            size = [1.35,1.35,1.35]; size[axis]=.96
            chunk(f'Plate_{axis}_{sign}', p, size, (axis*2+(sign+1)//2)%6)
    count=0
    for a,b in ((0,1),(0,2),(1,2)):
        for sa in (-1,1):
            for sb in (-1,1):
                p=[0.,0.,0.];p[a]=sa*.77;p[b]=sb*.77
                chunk(f'Edge_{count}',p,(.92,.92,.92),count%6);count+=1
    # Short inset blue fissures, not crystals that pop into existence outside the shell.
    for axis in range(3):
        for sign in (-1,1):
            p=[.32,-.24,.26];p[axis]=sign*1.413
            size=[.075,.52,.08];size[axis]=.035
            if axis==1:size=[.57,.035,.07]
            chunk(f'Vein_{axis}_{sign}',p,size,6,True)
    # Match the finished mesh's 1.43-unit axial extent to the 1.65-block rolling radius.
    for node in nodes:
        for axis in 'xyz':
            node['position'][axis] *= 1.15
            node['shape']['stretch'][axis] *= 1.15
    write(ART/'Boulder.blockymodel', {'nodes':nodes,'formatVersion':1})
    write(ART/'Form.blockyanim', {'duration':84,'holdLastKeyframe':True,'formatVersion':1,'nodeAnimations':tracks})
    # Joining viewers start near the current frame; existing viewers keep their original clip.
    animation_sets={}
    for index in range(14):
        frame=index*6
        suffix={}
        for name,channels in tracks.items():
            suffix[name]={}
            for channel,keys in channels.items():
                value=keys[-1]['delta']
                for a,b in zip(keys,keys[1:]):
                    if frame<=b['time']:
                        u=max(0,min(1,(frame-a['time'])/max(.001,b['time']-a['time'])))
                        u=u*u*(3-2*u)
                        value={k:a['delta'][k]*(1-u)+b['delta'][k]*u for k in a['delta']}
                        break
                suffix[name][channel]=[key(0,value)]+[key(k['time']-frame,k['delta']) for k in keys if k['time']>frame]
        clip=f'Form_{index}'
        write(ART/(clip+'.blockyanim'),{'duration':84-frame,'holdLastKeyframe':True,'formatVersion':1,'nodeAnimations':suffix})
        animation_sets[clip]={'Animations':[{'Animation':f'VFX/Titan/RollingBoulder/{clip}.blockyanim','Looping':False}]}
    complete={name:{channel:[key(0,keys[-1]['delta'])] for channel,keys in channels.items()} for name,channels in tracks.items()}
    write(ART/'Complete.blockyanim',{'duration':1,'holdLastKeyframe':True,'formatVersion':1,'nodeAnimations':complete})
    animation_sets['Complete']={'Animations':[{'Animation':'VFX/Titan/RollingBoulder/Complete.blockyanim','Looping':False}]}
    write(SERVER/'Models/Titan/Titan_Temple_Rolling_Boulder.json', {
        'Model':'VFX/Titan/RollingBoulder/Boulder.blockymodel','Texture':'VFX/Titan/RollingBoulder/Boulder.png',
        'MinScale':1,'MaxScale':1,
        'HitBox':{'Min':{'X':-1.65,'Y':-1.65,'Z':-1.65},'Max':{'X':1.65,'Y':1.65,'Z':1.65}},
        'AnimationSets':animation_sets})


def particles():
    dust = json.loads((SERVER/'Particles/Titan/Spawners/Titan_Telegraph_Crack_Dust.particlespawner').read_text())
    spark = json.loads((SERVER/'Particles/Titan/Crypt/Spawners/Crypt_Missile_Core.particlespawner').read_text())
    def spawner(name, obj):write(SERVER/f'Particles/Titan/Spawners/{name}.particlespawner', obj)
    def effect(name, parts, lifespan=1.4):
        write(SERVER/f'Particles/Titan/{name}.particlesystem', {'Spawners':[{'SpawnerId':p} for p in parts],
             'LifeSpan':lifespan,'BoundingRadius':8,'CullDistance':112,'IsImportant':True})
    for name,count,spread,speed,scale in [('Form',7,1.5,1.1,.55),('Trail',6,.85,2.4,.8),('Impact',26,1.2,7.5,1.2),('Shatter',32,1.3,8.5,1.1)]:
        d=copy.deepcopy(dust);d.pop('$Comment',None)
        d['TotalParticles']={'Min':count,'Max':count};d['MaxConcurrentParticles']=count
        d['EmitOffset']['X']={'Min':-spread,'Max':spread};d['EmitOffset']['Z']={'Min':-spread,'Max':spread}
        d['InitialVelocity']['Speed']={'Min':speed*.4,'Max':speed}
        if name=='Form':d['InitialVelocity']['Pitch']={'Min':-80,'Max':-25}
        d['Attractors'][0]['LinearAcceleration']['Y']=-3 if name=='Form' else -9
        d['Particle']['InitialAnimationFrame']['Color']='#777f83'
        for axis in ('X','Y'):d['Particle']['InitialAnimationFrame']['Scale'][axis]={'Min':scale*.5,'Max':scale}
        d['Particle']['Animation']['0']['Color']='#879394';d['Particle']['Animation']['100']['Color']='#485665'
        spawner('Temple_Boulder_'+name+'_Dust',d)
    # An angular mineral chip texture, with a pale edge and blue-gray fracture face.
    chip=Image.new('RGBA',(16,16));dr=ImageDraw.Draw(chip)
    dr.polygon([(2,4),(10,1),(14,5),(12,13),(5,15),(1,9)],fill=(81,99,111,255))
    dr.polygon([(2,4),(10,1),(12,5),(5,8)],fill=(154,172,178,255))
    dr.line((5,8,11,6,9,11),fill=(51,151,204,255),width=1)
    tex=RES/'Common/Particles/Textures/Titan/Temple_Chip.png';tex.parent.mkdir(parents=True,exist_ok=True);chip.save(tex)
    chunks=copy.deepcopy(dust);chunks.pop('$Comment',None)
    chunks['Particle']['Texture']='Particles/Textures/Titan/Temple_Chip.png'
    chunks['Particle']['InitialAnimationFrame']['Color']='#ffffff'
    chunks['Particle']['Animation']={'0':{'Opacity':1},'70':{'Opacity':1},'100':{'Opacity':0}}
    chunks['TotalParticles']={'Min':24,'Max':30};chunks['MaxConcurrentParticles']=30
    chunks['InitialVelocity']['Speed']={'Min':4,'Max':10}
    chunks['ParticleLifeSpan']={'Min':.5,'Max':1.25}
    chunks['Particle']['InitialAnimationFrame']['Scale']={'X':{'Min':.12,'Max':.32},'Y':{'Min':.12,'Max':.32}}
    spawner('Temple_Boulder_Chips',chunks)
    spark['Particle']['Texture']='Particles/Textures/Titan/Temple_Chip.png'
    spark['Particle']['InitialAnimationFrame']['Color']='#67bdff'
    spark['TotalParticles']={'Min':5,'Max':7};spark['MaxConcurrentParticles']=7
    spark['ParticleLifeSpan']={'Min':.3,'Max':.6}
    spark['EmitOffset']={axis:{'Min':-1.3,'Max':1.3} for axis in 'XYZ'}
    spark['InitialVelocity']['Speed']={'Min':.2,'Max':1.5}
    spark['Particle']['InitialAnimationFrame']['Scale']={'X':{'Min':.08,'Max':.16},'Y':{'Min':.08,'Max':.16}}
    spawner('Temple_Boulder_Blue_Sparks',spark)
    effect('Temple_Boulder_Form_Dust',['Temple_Boulder_Form_Dust'])
    effect('Temple_Boulder_Sparks',['Temple_Boulder_Blue_Sparks'],.7)
    effect('Temple_Boulder_Trail',['Temple_Boulder_Trail_Dust','Temple_Boulder_Blue_Sparks'])
    effect('Temple_Boulder_Impact',['Temple_Boulder_Impact_Dust','Temple_Boulder_Chips'])
    effect('Temple_Boulder_Shatter',['Temple_Boulder_Shatter_Dust','Temple_Boulder_Chips','Temple_Boulder_Blue_Sparks'])


def sounds():
    folder=RES/'Common/Sounds/Titan/Temple';folder.mkdir(parents=True,exist_ok=True)
    metrics=[]
    for index,(name,duration) in enumerate([('Form',2.8),('Release',.85),('Land',1.5),('Roll',.84),('Shatter',1.8)]):
        files=[]
        for variation in range(2):
            rng=np.random.default_rng(84400+index*123+variation)
            n=int(duration*acoustic.SR);t=np.arange(n)/acoustic.SR
            low=acoustic.noise(n,rng,35,180);grind=acoustic.noise(n,rng,150,1450)
            if name=='Form':
                swell=(.16+.84*(t/duration)**1.1)
                signal=(.6*low+.23*grind*(.7+.3*acoustic.drift(t,rng,17)))*swell
                signal+=acoustic.scatter(t,rng,24,duration-.12,.22)
            elif name=='Roll':
                signal=.65*low+.3*grind*(.65+.35*acoustic.drift(t,rng,24))
                signal+=acoustic.scatter(t,rng,11,duration-.1,.24)
            else:
                signal=acoustic.fracture(t,rng,1.8)+.65*low*np.exp(-t*5)+acoustic.body(t,rng,48,8)
                signal+=acoustic.scatter(t,rng,22 if name=='Shatter' else 12,duration-.15,.26)
                if name=='Shatter':signal+=acoustic.noise(n,rng,1400,4600)*np.exp(-t*12)*.3
            signal*=acoustic.envelope(t,duration,.018 if name=='Form' else .003,.07)
            signal=np.tanh(signal*.6);signal*=.87/max(.001,np.max(np.abs(signal)))
            filename=f'Temple_Boulder_{name}_{variation+1:02}.ogg';path=folder/filename
            sf.write(path,signal,acoustic.SR,format='OGG',subtype='VORBIS')
            decoded,sr=sf.read(path);assert sr==44100 and np.isfinite(decoded).all() and np.max(np.abs(decoded))<1
            metrics.append({'file':filename,'seconds':len(decoded)/sr,'peak':float(np.max(np.abs(decoded))),
                            'rms':float(np.sqrt(np.mean(decoded**2)))})
            files.append('Sounds/Titan/Temple/'+filename)
        write(SERVER/f'Audio/SoundEvents/Titan/SFX_Temple_Boulder_{name}.json',{
            'AudioCategory':'AudioCat_NPC','Volume':-5 if name=='Roll' else -2,'SpatialBlend':1,
            'StartAttenuationDistance':10,'MaxDistance':96,'MaxInstance':6,
            'Layers':[{'Files':files,'RoundRobinHistorySize':1,
                       'RandomSettings':{'MinPitch':-.3,'MaxPitch':.1,'MinVolume':-1,'MaxVolume':0}}]})
    write(ROOT/'build/temple-boulder-audio-metrics.json',metrics)


if __name__=='__main__':
    art();particles();sounds()
    print('Temple boulder: 25 mesh pieces, 84-frame assembly, 5 particle systems and 10 original acoustic sounds generated.')
