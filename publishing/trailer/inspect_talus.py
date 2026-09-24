from pathlib import Path
import subprocess
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parent
FF=next((ROOT/'.deps/imageio_ffmpeg/binaries').glob('ffmpeg*.exe'))
OUT=ROOT/'review/talus'
OUT.mkdir(parents=True,exist_ok=True)
src=Path('C:/Users/gchou/Videos/TitanGameplay/Stone_Talus.mp4')
subprocess.run([str(FF),'-hide_banner','-loglevel','error','-y','-i',str(src),'-an','-vf','fps=1,scale=480:-2','-q:v','3',str(OUT/'frame_%03d.jpg')],check=True)
font=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',20)
files=list(OUT.glob('frame_*.jpg'))
for page in range((len(files)+14)//15):
    ims=files[page*15:(page+1)*15]
    sheet=Image.new('RGB',(1440,5*302),(18,25,29))
    draw=ImageDraw.Draw(sheet)
    for k,p in enumerate(ims):
        x=(k%3)*480;y=(k//3)*302
        sheet.paste(Image.open(p),(x,y+30))
        draw.text((x+10,y+3),f'STONE TALUS | {page*15+k+0.5:.1f}s',font=font,fill=(240,239,229))
    sheet.save(OUT/f'sheet_{page}.jpg',quality=90)
    print(OUT/f'sheet_{page}.jpg')
