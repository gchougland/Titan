from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json
import sys

OUT = Path(__file__).resolve().parent
SCALE = 2
W, H = 1920, 1080
CHARCOAL = (25, 33, 39, 228)
SLATE = (38, 50, 57, 235)
IVORY = (241, 239, 229, 255)
SAGE = (193, 213, 155, 255)
FONT_BOLD = 'C:/Windows/Fonts/arialbd.ttf'
FONT_REGULAR = 'C:/Windows/Fonts/arial.ttf'
TEXT_ONLY = '--text-only' in sys.argv


def font(size, bold=True):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REGULAR, round(size*SCALE))


def tracked_width(text, size, spacing, bold=True):
    f = font(size, bold)
    return (sum(f.getlength(c) for c in text)+(len(text)-1)*spacing*SCALE)/SCALE


def text(draw, xy, content, size, color, spacing=0, bold=True):
    x, y = xy
    f = font(size, bold)
    # Keep capitals at the requested top while sharing a typographic baseline.
    # Per-glyph top anchoring would incorrectly lift punctuation such as periods.
    cap_top = f.getbbox('H', anchor='la')[1]
    for char in content:
        draw.text((round(x*SCALE), round(y*SCALE)-cap_top), char, font=f, fill=color, anchor='la', stroke_width=0)
        x += f.getlength(char)/SCALE+spacing


def poly(draw, pts, color):
    draw.polygon([(round(x*SCALE), round(y*SCALE)) for x,y in pts], fill=color)


def save(im, name):
    im = im.resize((W,H), Image.Resampling.LANCZOS)
    im.save(OUT/name, optimize=True)
    return im


names = [
    ('stone_talus', 'STONE TALUS'),
    ('baby_yaga', 'BABY YAGA'),
    ('baba_yaga', 'BABA YAGA'),
    ('dunewyrm', 'DUNEWYRM'),
    ('roaming_temple', 'ROAMING TEMPLE'),
    ('crypt_keeper', 'CRYPT KEEPER'),
]

info = []
previews = []
for slug, title in ([] if TEXT_ONLY else names):
    im = Image.new('RGBA', (W*SCALE,H*SCALE), (0,0,0,0))
    d = ImageDraw.Draw(im)
    title_width = tracked_width(title, 56, 0.7)
    right = max(610, int(110+title_width+56))
    # The slanted edge and inset strip echo the existing vector banners.
    poly(d, [(80,784),(right,784),(right-30,916),(80,916)], CHARCOAL)
    poly(d, [(right-82,784),(right,784),(right-30,916),(right-112,916)], SLATE)
    poly(d, [(80,784),(86,784),(86,916),(80,916)], SAGE)
    text(d, (110,805), 'MEET THE TITANS', 17, SAGE, spacing=2.25)
    text(d, (107,846), title, 56, IVORY, spacing=0.7)
    rendered = save(im, f'title_{slug}.png')
    info.append({'name':title,'file':f'title_{slug}.png','size':[W,H], 'bounds':[80,784,right,916]})
    bg = Image.new('RGBA', (W,H), '#52616a')
    bg.alpha_composite(rendered)
    previews.append(bg.crop((50,754,1030,946)).convert('RGB').resize((980,192)))

im = Image.new('RGBA',(W*SCALE,H*SCALE),(0,0,0,0))
d = ImageDraw.Draw(im)
tagline = 'THINK BIGGER.'
credit = 'A HYTALE MOD BY HEXVANE'
text(d, ((W-tracked_width(tagline,62,4))/2,736), tagline,62,IVORY,spacing=4)
poly(d, [(908,829),(1012,829),(1012,833),(908,833)], SAGE)
text(d, ((W-tracked_width(credit,23,3))/2,868),credit,23,SAGE,spacing=3)
save(im,'end_text.png')

im = Image.new('RGBA',(W*SCALE,H*SCALE),(0,0,0,0))
d = ImageDraw.Draw(im)
text(d, ((W-tracked_width(tagline,41,3))/2,746),tagline,41,IVORY,spacing=3)
save(im,'intro_tagline.png')

if not TEXT_ONLY:
    sheet = Image.new('RGB',(980,192*len(previews)), '#52616a')
    for i,p in enumerate(previews): sheet.paste(p,(0,i*192))
    sheet.save(OUT/'title_contact_sheet.jpg',quality=92)
    (OUT/'graphics_manifest.json').write_text(json.dumps(info,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(info,indent=2))
