#!/usr/bin/env python3
"""自适应图标：照片放大到略大于可见区，四角被系统 mask 切掉，整块铺满不留边。

关键：照片边长取画布的 78%，比「可见区」(72/108 = 67%) 大一圈，
这样无论圆形还是方圆形 mask，看到的都是照片本身，不会露出背景、也没有白边；
同时人脸按原图比例完整保留，不会被裁到。
"""
import sys
from PIL import Image, ImageDraw, ImageFilter

SRC, OUT = sys.argv[1], sys.argv[2]
DENSITIES = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
             ("xxhdpi", 324), ("xxxhdpi", 432)]
PHOTO = 0.72      # 照片边长 / 画布边长
CORNER = 0.16     # 源图圆角比例（白角去掉，避免和背景撞色）

src = Image.open(SRC).convert("RGBA")
w, h = src.size

# 源图四角是白色，做成透明
mask = Image.new("L", (w * 4, h * 4), 0)
ImageDraw.Draw(mask).rounded_rectangle(
    [0, 0, w * 4 - 1, h * 4 - 1], radius=int(w * 4 * CORNER), fill=255)
mask = mask.resize((w, h), Image.LANCZOS)
photo_src = src.copy()
photo_src.putalpha(mask)

for dpi, canvas in DENSITIES:
    inner = round(canvas * PHOTO)
    photo = photo_src.resize((inner, inner), Image.LANCZOS)

    # 背景：整图模糊铺满
    bg = src.resize((canvas, canvas), Image.LANCZOS)
    bg = bg.filter(ImageFilter.GaussianBlur(canvas * 0.05))
    bg.save(f"{OUT}/mipmap-{dpi}/ic_launcher_background.png", optimize=True)

    # 前景：同样铺满模糊图打底，再把人像居中叠上去
    # → mask 不管切成圆形还是方圆形，露出来的都是照片/同色系，不会出现白边或空洞
    fg = bg.copy()
    off = (canvas - inner) // 2
    fg.alpha_composite(photo, (off, off))
    fg.save(f"{OUT}/mipmap-{dpi}/ic_launcher_foreground.png", optimize=True)

    # API 25 及以下兜底：整块照片
    src.resize((canvas, canvas), Image.LANCZOS).save(
        f"{OUT}/mipmap-{dpi}/ic_launcher.png", optimize=True)

    print(f"{dpi}: canvas={canvas} photo={inner}")