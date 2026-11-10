#!/usr/bin/env python3
"""Текстура дыма для премиум-стиля: app/src/main/assets/ui/smoke.png.

Бесшовный шум с искривлением пространства (domain warping). Плотность дыма лежит
в альфа-канале белого PNG; приложение берёт альфу и красит её в цвет схемы.
Считается здесь, а не на телефоне: там это заняло бы секунды при каждом запуске.

Запуск: python3 tools/gen_smoke_texture.py
"""
import os

import numpy as np
from PIL import Image

SIZE = 512
PERIOD = 4  # клеток шума на сторону текстуры: края совпадают, швов нет

rng = np.random.default_rng(7)
LATTICE = rng.random((1024, 1024)).astype(np.float32)


def noise(x, y, period):
    xi = np.floor(x).astype(np.int64)
    yi = np.floor(y).astype(np.int64)
    fx = x - xi
    fy = y - yi
    fx = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
    fy = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
    x0 = np.mod(xi, period)
    y0 = np.mod(yi, period)
    x1 = np.mod(xi + 1, period)
    y1 = np.mod(yi + 1, period)
    a = LATTICE[y0, x0]
    b = LATTICE[y0, x1]
    c = LATTICE[y1, x0]
    d = LATTICE[y1, x1]
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy


def fbm(x, y):
    v = np.zeros_like(x)
    a = 0.5
    period = PERIOD
    for _ in range(6):
        v += a * noise(x, y, period)
        x = x * 2
        y = y * 2
        period *= 2
        a *= 0.5
    return v / 0.984375


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def main():
    k = PERIOD / SIZE
    ys, xs = np.mgrid[0:SIZE, 0:SIZE].astype(np.float32)
    u = xs * k
    v = ys * k
    qx = fbm(u, v)
    qy = fbm(u + 5.2, v + 1.3)
    wx = fbm(u + 1.6 * qx + 1.7, v + 1.6 * qy + 9.2)
    wy = fbm(u + 1.6 * qx + 8.3, v + 1.6 * qy + 2.8)
    f = fbm(u + 1.8 * wx, v + 1.8 * wy)
    # Растягиваем диапазон: дым должен занимать заметную часть кадра, а не пару пятен.
    f = (f - f.min()) / (f.max() - f.min())
    body = smooth(0.35, 0.85, f)
    edge = smooth(0.05, 0.0, np.abs(f - 0.6)) * 0.45
    d = np.clip(body * body * 0.9 + edge, 0, 1)
    rgba = np.zeros((SIZE, SIZE, 4), np.uint8)
    rgba[..., :3] = 255
    rgba[..., 3] = (d * 255).astype(np.uint8)
    out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "ui", "smoke.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    Image.fromarray(rgba, "RGBA").save(out, optimize=True)
    print(out, os.path.getsize(out), "байт, средняя плотность", round(float(d.mean()), 3))


if __name__ == "__main__":
    main()
