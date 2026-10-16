"""앱 아이콘(icon.ico, icon-64.png) 만들기. 사용법: python3 make_icon.py <한글 글꼴 파일> <출력 폴더>"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

font_path, out = sys.argv[1], Path(sys.argv[2])
BG = (226, 103, 60)       # 따뜻한 주황
PAGE = (255, 255, 255)
INK = (196, 78, 38)


def draw(size):
    s = 1024
    im = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((32, 32, s - 32, s - 32), radius=200, fill=BG)
    # 종이
    px0, py0, px1, py1 = 230, 150, 794, 874
    fold = 150
    d.polygon([(px0, py0), (px1 - fold, py0), (px1, py0 + fold), (px1, py1), (px0, py1)], fill=PAGE)
    d.polygon([(px1 - fold, py0), (px1 - fold, py0 + fold), (px1, py0 + fold)], fill=(248, 205, 185))
    if size >= 32:
        f = ImageFont.truetype(font_path, 460)
        txt = '글'
        bb = d.textbbox((0, 0), txt, font=f, stroke_width=14)
        w, h = bb[2] - bb[0], bb[3] - bb[1]
        d.text(((px0 + px1) / 2 - w / 2 - bb[0], (py0 + py1) / 2 - h / 2 - bb[1] + 30), txt, font=f, fill=INK, stroke_width=14, stroke_fill=INK)
    else:
        for y in (330, 470, 610, 750):
            d.rounded_rectangle((px0 + 90, y, px1 - 90 - (120 if y == 750 else 0), y + 70), radius=30, fill=INK)
    return im.resize((size, size), Image.LANCZOS)


sizes = [16, 24, 32, 48, 64, 128, 256]
imgs = [draw(s) for s in sizes]
out.mkdir(parents=True, exist_ok=True)
imgs[-1].save(out / 'icon.ico', sizes=[(s, s) for s in sizes], append_images=imgs[:-1])
draw(64).save(out / 'icon-64.png', optimize=True)
draw(256).save(out / 'icon-256.png', optimize=True)
print('ok')
