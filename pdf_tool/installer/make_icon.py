"""설치 파일/실행 파일용 아이콘(.ico)을 만든다. (빌드할 때 자동 실행)

사용법: python installer/make_icon.py installer/app.ico
"""

import sys

from PIL import Image, ImageDraw, ImageFont


def _font(size: int):
    for name in ("arialbd.ttf", "Arial Bold.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default(size=size)


def draw(size: int) -> Image.Image:
    scale = 4  # 크게 그린 뒤 줄여서 테두리를 부드럽게
    big = size * scale
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.18), fill=(217, 48, 37, 255))
    font = _font(int(big * 0.34))
    d.text((big / 2, big / 2), "PDF", font=font, fill="white", anchor="mm")
    return img.resize((size, size), Image.Resampling.LANCZOS)


def main(out: str) -> None:
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = [draw(s) for s in sizes]
    images[-1].save(out, format="ICO", sizes=[(s, s) for s in sizes], append_images=images[:-1])
    print(f"아이콘 생성: {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app.ico")
