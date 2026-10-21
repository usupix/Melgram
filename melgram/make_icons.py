#!/usr/bin/env python3
# Usage: python3 make_icons.py <picture> <res-dir>  - builds all launcher icon sizes from one picture
import os, sys
from PIL import Image, ImageDraw
src = Image.open(sys.argv[1]).convert('RGB')
out = sys.argv[2]
w, h = src.size; s = min(w, h)
src = src.crop(((w-s)//2, (h-s)//2, (w-s)//2+s, (h-s)//2+s))
dens = {'mdpi':1, 'hdpi':1.5, 'xhdpi':2, 'xxhdpi':3, 'xxxhdpi':4}
def mask_round_rect(size, r):
    m = Image.new('L', (size*4, size*4), 0)
    ImageDraw.Draw(m).rounded_rectangle((0,0,size*4-1,size*4-1), radius=r*4, fill=255)
    return m.resize((size,size), Image.LANCZOS)
def mask_circle(size):
    m = Image.new('L', (size*4, size*4), 0)
    ImageDraw.Draw(m).ellipse((0,0,size*4-1,size*4-1), fill=255)
    return m.resize((size,size), Image.LANCZOS)
# visible part of an adaptive icon = central 72/108
vis = int(round(s*72/108)); off = (s-vis)//2
visible = src.crop((off, off, off+vis, off+vis))
for d, k in dens.items():
    p = os.path.join(out, 'mipmap-'+d); os.makedirs(p, exist_ok=True)
    bg = int(108*k)
    src.resize((bg,bg), Image.LANCZOS).save(os.path.join(p,'melgram_icon_bg.png'), optimize=True)
    L = int(48*k); inner = int(round(L*44/48)); pad = (L-inner)//2
    ic = visible.resize((inner,inner), Image.LANCZOS)
    sq = Image.new('RGBA', (L,L), (0,0,0,0)); sq.paste(ic, (pad,pad), mask_round_rect(inner, int(inner*0.22)))
    sq.save(os.path.join(p,'ic_launcher.png'), optimize=True)
    rd = Image.new('RGBA', (L,L), (0,0,0,0)); rd.paste(ic, (pad,pad), mask_circle(inner))
    rd.save(os.path.join(p,'ic_launcher_round.png'), optimize=True)
os.makedirs(os.path.join(out,'drawable-nodpi'), exist_ok=True)
src.resize((720,720), Image.LANCZOS).save(os.path.join(out,'drawable-nodpi','melgram_default_icon.jpg'), quality=92)
