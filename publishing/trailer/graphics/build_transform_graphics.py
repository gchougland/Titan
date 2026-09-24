from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
S = 2
W,H = 1920,1080
IVORY = (241,239,229,255)
SAGE = (193,213,155,255)
previews=[]
for slug,title in [('baby_yaga','BABY YAGA'),('baba_yaga','BABA YAGA')]:
    im=Image.new('RGBA',(W*S,H*S),(0,0,0,0))
    d=ImageDraw.Draw(im)
    def poly(points,color):
        d.polygon([(x*S,y*S) for x,y in points],fill=color)
    def text(x,y,content,size,color,spacing):
        f=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',size*S)
        for c in content:
            d.text((round(x*S),y*S),c,font=f,fill=color,anchor='lt')
            x+=f.getlength(c)/S+spacing
    # Opaque rectangle through x=730; the diagonal cap sits beyond that.
    poly([(40,680),(764,680),(732,832),(40,832)],(25,33,39,255))
    poly([(652,680),(764,680),(732,832),(620,832)],(38,50,57,255))
    poly([(40,680),(46,680),(46,832),(40,832)],SAGE)
    text(73,705,'COMPANION',17,SAGE,2.25)
    text(70,752,title,56,IVORY,0.7)
    im=im.resize((W,H),Image.Resampling.LANCZOS)
    # The full notification rectangle is guaranteed opaque.
    assert im.getchannel('A').crop((50,705,551,776)).getextrema()==(255,255)
    path=OUT/f'title_{slug}_transform.png'
    im.save(path,optimize=True)
    bg=Image.new('RGBA',(W,H),'#52616a')
    bg.alpha_composite(im)
    previews.append(bg.crop((10,650,794,862)).convert('RGB'))
    print(path)
sheet=Image.new('RGB',(784,424))
for i,p in enumerate(previews):sheet.paste(p,(0,i*212))
sheet.save(OUT/'transform_contact_sheet.jpg',quality=92)
