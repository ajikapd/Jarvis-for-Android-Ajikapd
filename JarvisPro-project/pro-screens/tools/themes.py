# Темы «Джарвис Pro»: Золото (как было), Лазурь, Аметист, Рубин, Изумруд.
# Одна формула перекраски для всего: цвета в макетах (CSS, SVG, JS), картинки, видео ядра (через 3D-LUT для ffmpeg), иконка.
# Перекраска в пространстве OKLCH: оттенок сдвигается, яркость и насыщенность — по образцу «золото → цвет темы».
import math, re
import numpy as np

# ref — эталон семейства (каким он был), to — каким он станет в теме
GOLD = '#FFC46B'; CYAN = '#7FE8FF'; BAD = '#FF8A6B'; OK = '#3FE0B0'
THEMES = {
    'gold': dict(name='Золото', sub='КАК В ПЕРВЫЙ ДЕНЬ', accent=GOLD,
                 phrase='Классика, сэр. Как в первый день.'),
    'az':   dict(name='Лазурь', sub='ХОЛОДНЫЙ, СПОКОЙНЫЙ', accent='#52B4FF', second='#A6DEFF',
                 phrase='Лазурь. Холодный расчёт — мне идёт.'),
    'am':   dict(name='Аметист', sub='ТАИНСТВЕННЫЙ, НОЧНОЙ', accent='#C49CFF', second='#E2CCFF',
                 phrase='Аметист. Немного магии не повредит.'),
    'ru':   dict(name='Рубин', sub='ГОРЯЧИЙ, СМЕЛЫЙ', accent='#FF5E62', second='#FFB4B8', bad='#FFB547',
                 phrase='Рубин. Боевой режим, сэр.'),
    'em':   dict(name='Изумруд', sub='СВЕЖИЙ, ЖИВОЙ', accent='#45EB9C', second='#A8F5D6', ok='#D4F56E',
                 phrase='Изумруд. Свежо, как утро после дождя.'),
}
ORDER = ['gold', 'az', 'am', 'ru', 'em']
# серые тона и фоны слегка окрашиваются в цвет темы (насыщенность у тёмных фонов, к белому — ноль)
TINT = 0.034

# ---------- sRGB ⇄ OKLab (векторно) ----------
def _lin(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def _gam(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)
def rgb2lch(rgb):
    r, g, b = [_lin(rgb[..., i]) for i in range(3)]
    l = np.cbrt(0.4122214708*r + 0.5363325363*g + 0.0514459929*b)
    m = np.cbrt(0.2119034982*r + 0.6806995451*g + 0.1073969566*b)
    s = np.cbrt(0.0883024619*r + 0.2817188376*g + 0.6299787005*b)
    L = 0.2104542553*l + 0.7936177850*m - 0.0040720468*s
    A = 1.9779984951*l - 2.4285922050*m + 0.4505937099*s
    B = 0.0259040371*l + 0.7827717662*m - 0.8086757660*s
    return L, np.hypot(A, B), np.degrees(np.arctan2(B, A)) % 360
def lch2rgb_raw(L, C, H):
    h = np.radians(H); A = C * np.cos(h); B = C * np.sin(h)
    l = (L + 0.3963377774*A + 0.2158037573*B) ** 3
    m = (L - 0.1055613458*A - 0.0638541728*B) ** 3
    s = (L - 0.0894841775*A - 1.2914855480*B) ** 3
    r = 4.0767416621*l - 3.3077115913*m + 0.2309699292*s
    g = -1.2684380046*l + 2.6097574011*m - 0.3413193965*s
    b = -0.0041960863*l - 0.7034186147*m + 1.7076146010*s
    return np.stack([r, g, b], -1)
def lch2rgb(L, C, H):
    """В пределы sRGB: уменьшаем насыщенность, сохраняя оттенок и яркость."""
    L = np.clip(L, 0, 1); C = np.maximum(C, 0)
    rgb = lch2rgb_raw(L, C, H)
    bad = np.any((rgb < -1e-4) | (rgb > 1 + 1e-4), -1)
    if np.any(bad):
        lo = np.zeros_like(C); hi = C.copy()
        for _ in range(14):
            mid = (lo + hi) / 2
            t = lch2rgb_raw(L, mid, H)
            ok = np.all((t >= -1e-4) & (t <= 1 + 1e-4), -1)
            lo = np.where(ok, mid, lo); hi = np.where(ok, hi, mid)
        C = np.where(bad, lo, C)
        rgb = lch2rgb_raw(L, C, H)
    return _gam(rgb)

def hex2rgb(h):
    h = h.lstrip('#'); return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float) / 255
def lch_of(h):
    L, C, H = rgb2lch(hex2rgb(h)[None]); return float(L[0]), float(C[0]), float(H[0])

def _hd(a, b):  # разница оттенков со знаком, −180…180
    return (a - b + 180) % 360 - 180
def _sat(L, C):  # насыщенность относительно яркости: тёмные линии HUD тоже считаются цветными
    return C / (L + 0.02)
def _band(H, C, L, center, full, fall, smin):
    d = np.abs(_hd(H, center))
    w = np.clip((full + fall - d) / fall, 0, 1)
    w = w * w * (3 - 2 * w)
    c = np.clip((_sat(L, C) - smin) / (smin * 0.8), 0, 1)
    return w * c * c * (3 - 2 * c)

# семейства: (эталон, ключ в теме, центр оттенка, полная зона ±, спад, мин. насыщенность C/L)
# + во сколько раз сохранять разброс оттенков внутри семейства
FAM = [(GOLD, 'accent', 72, 22, 14, 0.030, 1.0),
       (CYAN, 'second', 230, 36, 12, 0.035, 0.35),
       (BAD, 'bad', 36, 8, 8, 0.080, 1.0),
       (OK, 'ok', 168, 10, 10, 0.080, 1.0)]

def map_lch(L, C, H, theme, fams=None):
    """Перекрасить массивы L, C, H под тему. Возвращает новые L, C, H."""
    T = THEMES[theme]
    L2, C2, H2 = L.copy(), C.copy(), H.copy()
    for ref, key, cen, full, fall, cmin, hk in FAM:
        if key not in T or (fams and key not in fams): continue
        rL, rC, rH = lch_of(ref); tL, tC, tH = lch_of(T[key])
        w = _band(H, C, L, cen, full, fall, cmin)
        if not np.any(w > 0): continue
        nH = tH + hk * _hd(H, rH)
        nC = C * (tC / rC)
        nL = np.where(L <= rL, L * (tL / rL), 1 - (1 - L) * (1 - tL) / (1 - rL))
        H2 = np.where(w > 0, (H + w * _hd(nH, H)) % 360, H2)
        C2 = np.where(w > 0, C2 + w * (nC - C), C2)
        L2 = np.where(w > 0, L2 + w * (nL - L), L2)
    if theme != 'gold':
        tH = lch_of(T['accent'])[2]
        n = np.maximum(np.clip((0.06 - _sat(L, C)) / 0.025, 0, 1), np.clip((0.03 - C) / 0.015, 0, 1))
        ct = TINT * np.clip(1 - L, 0, 1) * np.clip(L / 0.08, 0, 1)
        C2 = np.where(n > 0, C2 * (1 - n) + n * np.maximum(ct, C2 * 0.5), C2)
        H2 = np.where(n > 0, np.where(C2 < 1e-5, tH, (H2 + n * _hd(tH, H2)) % 360), H2)
    return L2, C2, H2

def map_rgb(rgb, theme):
    """rgb: массив …×3 в 0..1 → перекрашенный массив."""
    if theme == 'gold': return rgb
    L, C, H = rgb2lch(rgb)
    return lch2rgb(*map_lch(L, C, H, theme))

# ---------- цвета в тексте страниц ----------
RX = re.compile(r'#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})(?![0-9a-zA-Z_-])|(rgba?\(\s*)(\d{1,3})(\s*,\s*)(\d{1,3})(\s*,\s*)(\d{1,3})')
# чужие фирменные цвета (Яндекс, Билайн, аватарки людей) — не трогать
KEEP = {'3a7bff', '2f6bff', '1b3fa8', '2b5bd9', '2787f5', '1f7bff', '0d4cd3', '0077ff', '005bff', '0049c7', '6d5bff', '3a2fb8',
        '7a4dff', '0065c4', '2e7bff', 'cb11ab', '481173', '7d0a6a', 'e5484d', 'ff6b6b', 'ff5a5a', 'ee2f53', '8a0410',
        '2aa3e0', '00aaff', '2f9ae0', '2a7a96', 'ffdd2d', 'ffcc00', 'ff9d6b', 'c4513a', 'ff7a1a', 'c74a00', 'ff6a00', 'fc3f1d', 'e30613', '21a038', '19aa1e', '126b22'}
def _cache_key(r, g, b): return (r, g, b)
_cache = {}
def map_hex6(h, theme):
    key = (h.lower(), theme)
    if key in _cache: return _cache[key]
    if h.lower() in KEEP: _cache[key] = None; return None
    rgb = hex2rgb(h)
    L, C, H = rgb2lch(rgb[None])
    # в тексте берём только «чистые» попадания в семейство — без полутонов на краях
    T = THEMES[theme]; hit = (float(_sat(L[0], C[0])) < 0.06 or float(C[0]) < 0.03) and 0.004 < float(L[0]) < 0.995  # серые и фоны
    for ref, k, cen, full, fall, cmin, hk in FAM:
        if k in T and abs(float(_hd(H[0], cen))) <= full and float(_sat(L[0], C[0])) >= cmin * 1.1: hit = True
    if not hit: _cache[key] = None; return None
    out = map_rgb(rgb[None], theme)[0]
    res = '%02x%02x%02x' % tuple(int(round(v * 255)) for v in out)
    if h.isupper(): res = res.upper()
    _cache[key] = res
    return res

NOMAP = re.compile(r'/\*NOMAP\*/.*?/\*/NOMAP\*/', re.S)
def recolor_text(s, theme):
    if theme == 'gold': return s
    keep = []
    def hold(m): keep.append(m.group(0)); return '\x00%d\x00' % (len(keep) - 1)
    s = NOMAP.sub(hold, s)
    s = re.sub(r'data:[a-z/+]+;base64,[A-Za-z0-9+/=]+', hold, s)
    def rep(m):
        if m.group(1):
            h = m.group(1)
            if len(h) in (3, 4):
                full = ''.join(c * 2 for c in h[:3]); n = map_hex6(full, theme)
                if not n: return m.group(0)
                return '#' + n + (h[3] * 2 if len(h) == 4 else '')
            n = map_hex6(h[:6], theme)
            return m.group(0) if not n else '#' + n + h[6:]
        r, g, b = int(m.group(3)), int(m.group(5)), int(m.group(7))
        if max(r, g, b) > 255: return m.group(0)
        n = map_hex6('%02x%02x%02x' % (r, g, b), theme)
        if not n: return m.group(0)
        R, G, B = int(n[0:2], 16), int(n[2:4], 16), int(n[4:6], 16)
        return m.group(2) + str(R) + m.group(4) + str(G) + m.group(6) + str(B)
    s = RX.sub(rep, s)
    return re.sub('\x00(\\d+)\x00', lambda m: keep[int(m.group(1))], s)

# ---------- картинки ----------
def recolor_image(img, theme):
    """PIL.Image → PIL.Image (RGB/RGBA)."""
    from PIL import Image
    if theme == 'gold': return img
    mode = img.mode
    a = np.asarray(img.convert('RGBA')).astype(np.float64) / 255
    rgb = map_rgb(a[..., :3], theme)
    out = np.concatenate([rgb, a[..., 3:4]], -1)
    im = Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGBA')
    return im if mode == 'RGBA' else im.convert('RGB')

# ---------- 3D-LUT для ffmpeg (lut3d) ----------
def write_cube(theme, path, n=65):
    g = np.linspace(0, 1, n)
    b, gg, r = np.meshgrid(g, g, g, indexing='ij')  # в .cube быстрее всего меняется R
    rgb = np.stack([r, gg, b], -1).reshape(-1, 3)
    out = map_rgb(rgb, theme)
    with open(path, 'w', newline='\n') as f:
        f.write('TITLE "Jarvis Pro %s"\nLUT_3D_SIZE %d\n' % (theme, n))
        f.write('\n'.join('%.6f %.6f %.6f' % tuple(v) for v in out)); f.write('\n')

if __name__ == '__main__':
    for t in ORDER:
        print(t, THEMES[t]['name'], ['%s→%s' % (k, THEMES[t][k]) for k in ('accent', 'second', 'bad', 'ok') if k in THEMES[t]],
              'FFC46B→', map_hex6('FFC46B', t), '7FE8FF→', map_hex6('7FE8FF', t), '3AA8FF→', map_hex6('3AA8FF', t), 'ffb84d→', map_hex6('ffb84d', t), '17110a→', map_hex6('17110a', t))
