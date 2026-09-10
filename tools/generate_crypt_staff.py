"""Author the Crypt Keeper reward: pixel atlas, skeletal staff, icon and player clips.

Run with Python + Pillow + NumPy. The source game's Staff idle poses define the hand attachment conventions;
all casting motion, geometry, painted atlas and the inventory render are authored here.
The signature's giant arm is assembled at runtime from the user's supplied Skeleton_Arm,
Skeleton_Palm, Skeleton_Bracelet and Skeleton_Finger prefabs; this script does not replace them.
"""
import json
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets'
RES = ROOT / 'src/main/resources'
COMMON = RES / 'Common'
OUT = COMMON / 'Items/Titan/CryptKeeper'
ANIM = COMMON / 'Characters/Titan/CryptKeeper'
OUT.mkdir(parents=True, exist_ok=True)
random.seed(713)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def xyz(v): return dict(zip('xyz', v))


def mul(a, b):
    x,y,z,w=a; X,Y,Z,W=b
    return (w*X+x*W+y*Z-z*Y, w*Y-x*Z+y*W+z*X, w*Z+x*Y-y*X+z*W, w*W-x*X-y*Y-z*Z)


def quat(rx=0, ry=0, rz=0):
    a,b,c=[math.radians(v)/2 for v in (rx,ry,rz)]
    return mul(mul((math.sin(a),0,0,math.cos(a)),(0,math.sin(b),0,math.cos(b))), (0,0,math.sin(c),math.cos(c)))


def qdict(q): return dict(zip('xyzw',[round(v,6) for v in q]))


PALETTES = {
    'bone': (199,190,150), 'ivory': (232,224,185), 'shadow': (123,119,92),
    'gold': (142,106,48), 'goldlight': (209,163,66), 'leather': (54,42,45),
    'green': (44,201,97), 'orb': (92,247,139), 'orbcore': (186,255,190),
}
atlas = Image.new('RGBA',(256,256),(0,0,0,0))
paint = ImageDraw.Draw(atlas)
palette_xy={}
for i,(material,color) in enumerate(PALETTES.items()):
    tx,ty=(i%4)*64,(i//4)*80
    palette_xy[material]=(tx,ty)
    for y in range(76):
        for x in range(60):
            value=random.choice((-10,-6,-3,0,0,0,3,6,9))
            if material in ('orb','orbcore','green'):
                value += round(15*math.sin(x*.21)*math.cos(y*.18))
            elif x%11==0 and y%13<8: value-=12
            paint.point((tx+x,ty+y),fill=tuple(max(0,min(255,v+value)) for v in color)+(255,))
    if material in ('bone','ivory'):
        for k in range(15):
            x,y=tx+random.randint(3,53),ty+random.randint(3,69)
            paint.line((x,y,x+1,y+4), fill=(159,151,119,255))
atlas.save(OUT/'Staff_Texture.png')

uid=0


def node(name, pos=(0,0,0), size=None, material='bone', rotation=(0,0,0), offset=(0,0,0), children=None):
    global uid
    uid+=1
    shape={'offset':xyz(offset),'stretch':xyz((1,1,1)), 'textureLayout':{},'type':'none',
           'settings':{'isPiece':False},'unwrapMode':'custom','visible':True,'doubleSided':False,'shadingMode':'flat'}
    if size:
        shape['type']='box';shape['settings']={'size':xyz(size)}
        tx,ty=palette_xy[material]
        shape['textureLayout']={side:{'offset':{'x':tx,'y':ty},'mirror':{'x':False,'y':False},'angle':0}
                                for side in ['front','back','left','right','top','bottom']}
        if material in ('orb','orbcore','green'): shape['shadingMode']='fullbright'
    return {'id':str(uid),'name':name,'position':xyz(pos),'orientation':qdict(quat(*rotation)),
            'shape':shape,'children':children or []}


art=[]
def box(name,pos,size,mat='bone',rot=(0,0,0)):
    n=node(name,pos,size,mat,rot);art.append(n);return n

# Anatomical lower shaft: two old forearm bones, bulging knuckles at the pommel and wrist.
box('Radius',(-2,-7,0),(3,65,4),'bone',(0,0,-2))
box('Ulna',(2,-7,0),(3,65,3),'ivory',(0,0,2))
for y in (-41,24):
    for x in (-2.4,2.4): box('Joint', (x,y,0),(5,5,6),'ivory')
for y in (-35,-28,-21,-14,-7,0):
    box('Grip_Wrap',(0,y,0),(7,3,7),'leather',(0,0,5))
for y in (9,18,30):
    box('Relic_Collar',(0,y,0),(10,3,9),'gold')
    box('Collar_Inlay',(0,y+.3,-4.7),(3,2,1),'green')
for x in (-4.5,4.5):
    box('Wrist_Stud',(x,30,0),(2,4,10),'goldlight')

# Palm has four ridges, a inset dark hollow and a cradled emerald orb.
box('Palm',(0,39,0),(14,12,6),'bone')
box('Palm_Hollow',(0,42,-3.3),(8,6,1),'shadow')
for x in (-5,0,5): box('Metacarpal',(x,39,-3),(3,13,3),'ivory',(0,0,-x*1.1))
box('Orb',(0,57,0),(14,14,14),'orb',(0,0,45))
box('Orb_Facet_A',(0,57,0),(17,10,10),'green')
box('Orb_Facet_B',(0,57,0),(10,17,10),'orb')
box('Soul_Core',(-2,59,-7.1),(6,6,1),'orbcore',(0,0,45))
box('Soul_Glint',(-4,62,-7.9),(2,3,1),'orbcore')

# Three fingers curve around the front and crown of the orb, each with three separate phalanges.
for i,x in enumerate((-7,0,7)):
    length=11 if i==1 else 9
    box(f'Finger{i}_Proximal',(x,49,4.5),(3,length,4),'ivory',(-15,0,-x*2))
    box(f'Finger{i}_Knuckle',(x*.94,54,5),(4,4,4),'bone')
    box(f'Finger{i}_Middle',(x*.85,59,4),(3,10,3),'bone',(20,0,x*2))
    box(f'Finger{i}_Tip',(x*.7,64,0),(3,3,8),'ivory',(10,0,x*3))
# Opposing thumb is legible on the orb's near side.
box('Thumb_Base',(-9,43,-1),(5,7,5),'bone',(0,0,-35))
box('Thumb_Middle',(-10,49,-5),(4,8,4),'ivory',(12,0,20))
box('Thumb_Tip',(-6,53,-7),(8,3,3),'ivory',(0,0,-20))
# A worn split cloth tail below the cuff.
box('Ribbon_A',(7,19,2),(3,16,1),'leather',(0,0,22))
box('Ribbon_B',(-7,15,1),(3,13,1),'leather',(0,0,-18))

# Preserve the base game's staff hand socket conventions. Art is authored vertically, then aligns
# to Handle's +X axis exactly like the base staff family.
art_root=node('Crypt_Staff_Art',rotation=(0,0,-90),children=art)
handle=node('Handle',pos=(0,-32,0),children=[art_root])
handle['orientation']={'x':0.653281,'y':0.653281,'z':-0.270598,'w':-0.270598}
origin_item=node('Origin_Item',pos=(0,-61,0),children=[handle])
origin_projectile=node('Origin_Projectile',pos=(0,93,0),children=[origin_item])
root=node('R-Attachment',pos=(0,37,0),children=[origin_projectile])
write(OUT/'Staff.blockymodel', {'lod':'auto','nodes':[root]})

def keys(values):
    return [{'time':frame,'delta':value,'interpolationType':'smooth'} for frame,value in values]


def curve(base, name, channel, samples):
    n=base.setdefault(name, {k:[] for k in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset')})
    original=n[channel][0]['delta'] if n[channel] else (qdict((0,0,0,1)) if channel=='orientation' else xyz((0,0,0)))
    if channel=='orientation':
        q=tuple(original[a] for a in 'xyzw')
        n[channel]=keys([(f,qdict(mul(q,quat(*v)))) for f,v in samples])
    else:
        n[channel]=keys([(f,xyz([original[a]+v[i] for i,a in enumerate('xyz')])) for f,v in samples])


# Custom character clips retain the known-good staff attachment/first-person offsets, then add a
# wrist-led five-soul release and an overhead ritual that mirrors the summoned arm's pound.
for fps in (False,True):
    suffix='_FPS' if fps else ''
    source=ASSETS/f'Common/Characters/Animations/Items/Dual_Handed/Staff/Idle{suffix}.blockyanim'
    neutral=json.loads(source.read_text())['nodeAnimations']
    for name,duration in [('Idle',120),('SoulCharge',36),('SoulVolley',33),('GravePound',54),('Equip',30)]:
        anim=json.loads(json.dumps(neutral))
        if name=='Idle':
            curve(anim,'R-Arm','orientation',[(0,(0,0,0)),(60,(-1,0,1)),(120,(0,0,0))])
            curve(anim,'R-Hand','orientation',[(0,(0,0,0)),(60,(2,0,-2)),(120,(0,0,0))])
        elif name=='SoulCharge':
            curve(anim,'R-Arm','orientation',[(0,(-16,8,-8)),(18,(-20,10,-10)),(36,(-16,8,-8))])
            curve(anim,'R-Forearm','orientation',[(0,(-18,0,0)),(18,(-24,0,0)),(36,(-18,0,0))])
            curve(anim,'R-Hand','orientation',[(0,(-10,0,-18)),(18,(-14,0,-22)),(36,(-10,0,-18))])
            if not fps: curve(anim,'Belly','orientation',[(0,(0,6,0)),(18,(0,9,0)),(36,(0,6,0))])
        elif name=='SoulVolley':
            curve(anim,'R-Arm','orientation',[(0,(0,0,0)),(8,(-18,8,-8)),(14,(17,-12,7)),(23,(5,0,0)),(33,(0,0,0))])
            curve(anim,'R-Forearm','orientation',[(0,(0,0,0)),(8,(-20,0,0)),(14,(24,0,0)),(25,(0,0,0)),(33,(0,0,0))])
            curve(anim,'R-Hand','orientation',[(0,(0,0,0)),(7,(-12,0,-20)),(14,(15,0,15)),(23,(-2,0,-2)),(33,(0,0,0))])
            if not fps: curve(anim,'Belly','orientation',[(0,(0,0,0)),(8,(0,8,0)),(14,(0,-8,0)),(33,(0,0,0))])
        elif name=='GravePound':
            curve(anim,'R-Arm','orientation',[(0,(0,0,0)),(17,(-75,0,-16)),(27,(-80,0,-14)),(34,(28,0,4)),(42,(12,0,0)),(54,(0,0,0))])
            curve(anim,'R-Forearm','orientation',[(0,(0,0,0)),(17,(-30,0,0)),(27,(-35,0,0)),(34,(30,0,0)),(54,(0,0,0))])
            curve(anim,'R-Hand','orientation',[(0,(0,0,0)),(17,(0,0,-22)),(27,(0,0,-25)),(34,(22,0,18)),(54,(0,0,0))])
            if not fps:
                curve(anim,'Belly','orientation',[(0,(0,0,0)),(22,(-8,0,0)),(34,(18,0,0)),(54,(0,0,0))])
                curve(anim,'L-Arm','orientation',[(0,(0,0,0)),(17,(-45,0,25)),(27,(-45,0,25)),(34,(15,0,-12)),(54,(0,0,0))])
        else:
            curve(anim,'R-Arm','orientation',[(0,(50,0,20)),(12,(-10,0,-5)),(22,(3,0,0)),(30,(0,0,0))])
            curve(anim,'R-Hand','orientation',[(0,(0,0,-35)),(12,(0,0,12)),(22,(0,0,-2)),(30,(0,0,0))])
        write(ANIM/f'{name}{suffix}.blockyanim', {'duration':duration,'holdLastKeyframe':False,'nodeAnimations':anim,'formatVersion':1})
        legacy=OUT/f'Animations/{name}{suffix}.blockyanim'
        if legacy.exists(): legacy.unlink()

orb_anim={'duration':120,'holdLastKeyframe':False,'formatVersion':1,'nodeAnimations':{}}
for name in ['Orb','Orb_Facet_A','Orb_Facet_B','Soul_Core','Soul_Glint']:
    orb_anim['nodeAnimations'][name]={'shapeStretch':keys([(0,xyz((1,1,1))),(60,xyz((1.05,1.05,1.05))),(120,xyz((1,1,1)))])}
write(OUT/'Animations/Orb_Pulse.blockyanim',orb_anim)

animations=json.loads((ASSETS/'Server/Item/Animations/Staff.json').read_text())
for name in ['Idle','SoulCharge','SoulVolley','GravePound','Equip']:
    animations['Animations'][name]={
        'FirstPerson':f'Characters/Titan/CryptKeeper/{name}_FPS.blockyanim',
        'ThirdPerson':f'Characters/Titan/CryptKeeper/{name}.blockyanim',
        'ThirdPersonMoving':f'Characters/Titan/CryptKeeper/{name}.blockyanim',
        'Speed':1,'Looping':name in ('Idle','SoulCharge'),'BlendingDuration':0.1}
write(RES/'Server/Item/Animations/Titan_Crypt_Staff.json',animations)

# Render the actual newly written blockymodel and texture at the required inventory dimensions.
from render_crypt_staff_icon import render as render_icon
render_icon()
legacy_icon=OUT/'Staff_Icon.png'
if legacy_icon.exists(): legacy_icon.unlink()

item={
 'TranslationProperties':{'Name':'titan_crypt_staff.staff.name','Description':'titan_crypt_staff.staff.description'},
 'Categories':['Items.Weapons'],'Quality':'Legendary','ItemLevel':60,'MaxStack':1,
 'Model':'Items/Titan/CryptKeeper/Staff.blockymodel','Texture':'Items/Titan/CryptKeeper/Staff_Texture.png',
 'Icon':'Icons/ItemsGenerated/Titan_Staff_Crypt_Keeper.png','PlayerAnimationsId':'Titan_Crypt_Staff',
 'Animation':'Items/Titan/CryptKeeper/Animations/Orb_Pulse.blockyanim',
 'DroppedItemAnimation':'Items/Animations/Dropped/Dropped_Diagonal_Left.blockyanim',
 'IconProperties':{'Scale':.3,'Translation':[-20,-30],'Rotation':[45,90,0]},
 'Light':{'Color':'#6e9','Radius':3},
 'Particles':[{'SystemId':'Crypt_Staff_Orb','TargetNodeName':'Orb','Scale':.35}],
 'Interactions':{'Primary':'Root_Crypt_Missiles','Secondary':'Root_Crypt_Grasp','Ability1':'Root_Crypt_Grasp'},
 'Weapon':{'EntityStatsToClear':['SignatureEnergy'],'StatModifiers':{'SignatureEnergy':[{'Amount':100,'CalculationType':'Additive'}]}},
 'ItemSoundSetId':'ISS_Weapons_Wand',
 'Tags':{'Type':['Weapon'],'Family':['Staff']}
}
write(RES/'Server/Item/Items/Titan/Titan_Staff_Crypt_Keeper.json',item)

for name,stat,cost,clip,duration,cast in [('Grasp','SignatureEnergy',100,'GravePound',.60,'CryptGrasp')]:
    gate={'Type':'StatsCondition','Costs':{stat:cost},'Next':{'Type':'Serial','Interactions':[
        {'Type':'Simple','RunTime':duration,'Effects':{'ItemAnimationId':clip,'ClearAnimationOnFinish':False}},
        {'Type':cast}, {'Type':'Simple','RunTime':.35} ]}}
    if name=='Grasp': gate['ValueType']='Percent'
    write(RES/f'Server/Item/RootInteractions/Titan/Root_Crypt_{name}.json',{
        'RequireNewClick':name=='Grasp','Cooldown':{'Cooldown':1.1 if name=='Missiles' else 2.0},'Interactions':[gate]})

# Native Charging waits for Primary release, chooses a duration-labelled branch and displays charge UI.
# Short taps cancel without spending; a fully charged release pays once in the server interaction.
release={'Type':'StatsCondition','Costs':{'Stamina':5},'Failed':'Staff_Cast_Fail',
         'Next':{'Type':'Serial','Interactions':[
             {'Type':'Simple','RunTime':.22,'Effects':{'ItemAnimationId':'SoulVolley','ClearAnimationOnFinish':False}},
             {'Type':'CryptMissiles'}, {'Type':'Simple','RunTime':.35}]}}
charge={'Type':'Charging','AllowIndefiniteHold':True,'DisplayProgress':True,'CancelOnOtherClick':True,
        'Effects':{'ItemAnimationId':'SoulCharge','ClearAnimationOnFinish':True,
                   'WorldSoundEventId':'SFX_Crypt_Staff_Charge','LocalSoundEventId':'SFX_Crypt_Staff_Charge',
                   'Particles':[{'SystemId':'Crypt_Staff_Orb','TargetNodeName':'Orb','Scale':.8}]},
        'Next':{'0.0':{'Type':'Simple','RunTime':.1},'0.65':release},
        'Failed':{'Type':'Simple','RunTime':.1}}
write(RES/'Server/Item/RootInteractions/Titan/Root_Crypt_Missiles.json',{
    'RequireNewClick':True,'Cooldown':{'Cooldown':1.1},'Tags':{'Attack':['Ranged']},
    'Interactions':[{'Type':'StatsCondition','Costs':{'Stamina':5},'Failed':'Staff_Cast_Fail','Next':charge}]})

print('Created Crypt Keeper staff, pixel atlas, icon, eleven custom clips and charged primary interactions.')
