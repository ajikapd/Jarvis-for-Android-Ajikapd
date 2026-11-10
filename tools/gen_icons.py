#!/usr/bin/env python3
"""Иконки приложения для премиум- и классического интерфейса (современный — прежний реактор).

Пишет передний план адаптивной иконки 432×432 (108 dp в xxxhdpi):
  app/src/main/res/drawable/ic_launcher_fg_premium.png — ядро из частиц, как на главной премиума;
  app/src/main/res/drawable/ic_launcher_fg_classic.png — латунные кольца и бирюзовое ядро консоли.
Рисует в 4 раза крупнее и уменьшает: края гладкие.

Запуск: python3 tools/gen_icons.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")
N = 432
S = 4  # сглаживание
W = N * S
C = W / 2


def rgba(hex_, a=255):
    hex_ = hex_.lstrip("#")
    return (int(hex_[0:2], 16), int(hex_[2:4], 16), int(hex_[4:6], 16), a)


def glow(color, radius, strength, blur):
    """Мягкое свечение: залитый круг, размытый."""
    layer = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([C - radius, C - radius, C + radius, C + radius], fill=rgba(color, strength))
    return layer.filter(ImageFilter.GaussianBlur(blur))


def premium():
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    r0 = 116 * S  # шар целиком в безопасной зоне (66 dp = 264 px)
    img.alpha_composite(glow("7FE8FF", r0 * 1.15, 70, 40 * S))
    dots = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(dots)
    n = 1400
    rng = random.Random(5)
    tilt = 0.35
    ry = 0.6
    for i in range(n):
        y = 1 - 2 * (i + 0.5) / n
        r = math.sqrt(1 - y * y)
        th = i * 2.399963
        x, z = math.cos(th) * r, math.sin(th) * r
        # лёгкая «волна» по поверхности, как у ядра, когда оно слушает
        lat = math.asin(y)
        lon = math.atan2(z, x)
        k = 1 + 0.035 * math.sin(lat * 6) * math.cos(lon * 5)
        x, y2, z = x * k, y * k, z * k
        X = x * math.cos(ry) + z * math.sin(ry)
        Z = -x * math.sin(ry) + z * math.cos(ry)
        Y = y2 * math.cos(tilt) - Z * math.sin(tilt)
        Z = y2 * math.sin(tilt) + Z * math.cos(tilt)
        pz = 1 / (1.9 - Z * 0.5)
        px = C + X * r0 * pz * 1.5
        py = C + Y * r0 * pz * 1.5
        a = 0.2 + 0.8 * (Z + 1) / 2
        s = (1.6 + (Z + 1) * 1.1) * S
        spark = rng.random() > 0.93
        col = (255, 255, 255) if spark else (127, 232, 255)
        d.ellipse([px - s / 2, py - s / 2, px + s / 2, py + s / 2], fill=col + (int(255 * a),))
    img.alpha_composite(dots)
    # сердцевина
    img.alpha_composite(glow("7FE8FF", r0 * 0.42, 150, 18 * S))
    img.alpha_composite(glow("FFFFFF", r0 * 0.18, 235, 8 * S))
    return img


def classic():
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    brass = "E3A542"
    teal = "4FB8AC"
    img.alpha_composite(glow(teal, 70 * S, 90, 30 * S))
    d = ImageDraw.Draw(img)

    def ring(radius, width, color, a, dash=None, gap=0.0, rot=0.0):
        box = [C - radius, C - radius, C + radius, C + radius]
        if dash is None:
            d.arc(box, 0, 360, fill=rgba(color, a), width=int(width))
            return
        ang = rot
        while ang < 360 + rot:
            d.arc(box, ang, ang + dash, fill=rgba(color, a), width=int(width))
            ang += dash + gap

    ring(118 * S, 7 * S, brass, 235, dash=38, gap=7, rot=-90)
    ring(102 * S, 2 * S, brass, 140)
    ring(90 * S, 9 * S, teal, 210, dash=14, gap=6, rot=0)
    ring(74 * S, 2 * S, teal, 120)
    # риски по кругу, как шкала приборов консоли
    for i in range(60):
        a = i / 60 * math.tau
        r1 = 128 * S
        r2 = (136 if i % 5 == 0 else 132) * S
        d.line([C + math.cos(a) * r1, C + math.sin(a) * r1, C + math.cos(a) * r2, C + math.sin(a) * r2],
               fill=rgba(brass, 200 if i % 5 == 0 else 110), width=int(2 * S))
    img.alpha_composite(glow(teal, 52 * S, 200, 14 * S))
    img.alpha_composite(glow("E8FBFF", 24 * S, 245, 7 * S))
    return img


def save(img, name):
    out = os.path.join(OUT, name)
    img.resize((N, N), Image.LANCZOS).save(out, optimize=True)
    print(out, os.path.getsize(out), "байт")


if __name__ == "__main__":
    save(premium(), "ic_launcher_fg_premium.png")
    save(classic(), "ic_launcher_fg_classic.png")
