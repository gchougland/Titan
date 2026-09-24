"""Validate the reward's shipped references, clip transforms, geometry and pixel atlas bounds."""
import json
import math
import struct
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'
COMMON=RES/'Common'
BASE=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets'

def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def common(path):
    assert (COMMON/path).is_file() or (BASE/'Common'/path).is_file(), f'Missing common asset: {path}'

item=read(RES/'Server/Item/Items/Titan/Titan_Staff_Crypt_Keeper.json')
for field in ('Model','Texture','Icon','Animation','DroppedItemAnimation'): common(item[field])
assert item['Icon'].startswith('Icons/ItemsGenerated/')
icon=(COMMON/item['Icon']).read_bytes()
assert icon[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II',icon[16:24]) == (64,64), 'Staff icon must be exactly 64x64'
assert icon[25] == 6, 'Staff icon must retain RGBA transparency'
assert item['TranslationProperties']['Name']=='titan_crypt_staff.staff.name'
language=(RES/'Server/Languages/en-US/titan_crypt_staff.lang').read_text()
assert 'staff.name = Staff of the Crypt Keeper' in language and 'staff.description =' in language
for interaction in set(item['Interactions'].values()):
    assert (RES/f'Server/Item/RootInteractions/Titan/{interaction}.json').exists()
for particle in item['Particles']:
    assert list((RES/'Server/Particles').rglob(particle['SystemId']+'.particlesystem'))

# A loaded spell is insufficient: the equipped item's real input path must reach it with base stats.
primary=read(RES/f"Server/Item/RootInteractions/Titan/{item['Interactions']['Primary']}.json")
assert primary['RequireNewClick'] is True
gate=primary['Interactions'][0]
stamina=read(BASE/'Server/Entity/Stats/Stamina.json')
assert gate['Type']=='StatsCondition' and gate['Costs']=={'Stamina':5}
assert stamina['InitialValue'] >= gate['Costs']['Stamina'] and stamina['Max'] >= gate['Costs']['Stamina']
assert 'Mana' not in json.dumps(primary), 'Primary must not depend on the disabled base mana stat'
charge=gate['Next']
assert charge['Type']=='Charging' and charge['AllowIndefiniteHold'] and charge['DisplayProgress']
assert charge['Effects']['ClearAnimationOnFinish']
assert 'CryptMissiles' not in json.dumps(charge['Next']['0.0']), 'An early release must not spend or fire'
release=charge['Next']['0.65']
assert release['Costs']==gate['Costs'] and release['Failed']=='Staff_Cast_Fail'
assert sum(step.get('Type')=='CryptMissiles' for step in release['Next']['Interactions'])==1
assert gate['Failed']=='Staff_Cast_Fail'

animations=read(RES/'Server/Item/Animations/Titan_Crypt_Staff.json')['Animations']
assert animations[charge['Effects']['ItemAnimationId']]['Looping'], 'Charge pose must remain held until release'
for name, animation in animations.items():
    for key in ('FirstPerson','ThirdPerson','ThirdPersonMoving','ThirdPersonFace'):
        if key in animation:
            common(animation[key])
            assert animation[key].startswith(('Characters/','NPC/')), f'Invalid character clip path {animation[key]}'
for clip in (COMMON/'Characters/Titan/CryptKeeper').glob('*.blockyanim'):
    data=read(clip)
    assert data['duration']>0
    for node,channels in data['nodeAnimations'].items():
        for channel,keys in channels.items():
            previous=-1
            for key in keys:
                assert previous<=key['time']<=data['duration'], f'Invalid key time in {clip}:{node}'
                previous=key['time']
                values=key['delta']
                if isinstance(values,dict): assert all(math.isfinite(v) for v in values.values())
                if channel=='orientation':
                    assert abs(sum(v*v for v in values.values())-1)<.0001, f'Nonunit quaternion {clip}:{node}'

model=read(COMMON/item['Model'])
# The attachment flag, not the node name alone, binds an item to the animated player socket.
# Keep this contract across exports: a detached item can appear aligned in Idle and drift when casting.
assert len(model['nodes'])==1 and model['nodes'][0]['name']=='R-Attachment'
assert model['nodes'][0]['shape']['settings'].get('isPiece') is True, 'Staff root must attach to the player hand'
# Primary animations must move the player rig as one coordinated pose in all three views. The staff's
# independent idle animation may pulse the orb, but must not drive its attachment or grip again.
native_animations=read(BASE/'Server/Item/Animations/Staff.json')['Animations']
for custom,native in [('SoulCharge','CastSummonCharging'),('SoulVolley','CastSummonCharged')]:
    for view in ('FirstPerson','ThirdPerson','ThirdPersonMoving'):
        clip=read(COMMON/animations[custom][view])
        reference=read(BASE/'Common'/native_animations[native][view])
        assert clip==reference, f'{custom} {view} no longer uses the coordinated native staff pose'
orb_animation=read(COMMON/item['Animation'])['nodeAnimations']
assert set(orb_animation)<= {'Orb','Orb_Facet_A','Orb_Facet_B','Soul_Core','Soul_Glint'}
assert all(not channels.get('position') and not channels.get('orientation') for channels in orb_animation.values()), \
    'Orb animation must not move the held staff independently of the hand'
texture=(COMMON/item['Texture']).read_bytes()
assert texture[:8] == b'\x89PNG\r\n\x1a\n', 'Invalid staff texture PNG'
texture_width,texture_height=struct.unpack('>II', texture[16:24])
node_ids=set();nodes=[]
def inspect(n):
    assert n['id'] not in node_ids
    node_ids.add(n['id']);nodes.append(n)
    shape=n['shape']
    if shape['type']=='box':
        size=shape['settings']['size']; assert all(v>0 for v in size.values())
        for face,uv in shape['textureLayout'].items():
            width=size['z'] if face in ('left','right') else size['x']
            height=size['z'] if face in ('top','bottom') else size['y']
            assert uv['offset']['x']+width<=texture_width
            assert uv['offset']['y']+height<=texture_height
    for child in n.get('children',[]): inspect(child)
for node in model['nodes']: inspect(node)
names=[n['name'] for n in nodes]
assert all(n in names for n in ('Radius','Ulna','Palm','Thumb_Base','Thumb_Middle','Thumb_Tip','Orb'))
assert all(f'Finger{i}_{joint}' in names for i in range(3) for joint in ('Proximal','Middle','Tip'))

# The summoned arm must keep the newly supplied geometry, not regenerate the old placeholder.
arm=read(RES/'Server/Prefabs/Titan/Crypt/Skeleton_Arm.prefab.json')
bones=[b for b in arm['blocks'] if b['name']!='Empty']
assert len(bones)==130 and all(b['name']=='Deco_Bone_Full' for b in bones)
assert {axis:(min(b[axis] for b in bones),max(b[axis] for b in bones)) for axis in 'xyz'} == {
    'x':(-2,3),'y':(0,2),'z':(-7,7)}
assert max(b['z']+1 for b in bones)-10.5 == -2.5, 'Arm wrist no longer meets the palm rear edge'
clip_count=len(list((COMMON/'Characters/Titan/CryptKeeper').glob('*.blockyanim')))
print(f'PASS: {len(nodes)} model nodes, authored 130-block arm, pixel atlas UV bounds, {clip_count} character clips, charged Primary release/cancel/stamina path, interaction/FX/common references and translations.')
