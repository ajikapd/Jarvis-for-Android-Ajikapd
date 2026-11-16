# Собирает файлы демо-приложения: D:/apk/Jarvis Pro/app/assets/www — экраны из макетов + app.js/app.css.
# Видео вынимаются из страниц в отдельные файлы v/*.mp4 (одинаковые — один раз).
# Темы (1.3): каждая страница есть в пяти цветах — main.html (золото), main-az.html, main-am.html, main-ru.html, main-em.html.
# Цвета, картинки и видео перекрашиваются одной формулой из themes.py; видео тем — заранее (render/theme_videos.py).
import base64, hashlib, io, json, os, re, shutil, sys, time
P = 'D:/apk/Jarvis Pro/'
sys.path.insert(0, P + 'render')
import themes
from PIL import Image
A = P + 'app/'
W = A + 'assets/www/'
TV = P + 'render/out/themes/v2/'
PAGES = {'main': '10-pro-главная-телефон.html', 's11': '11-pro-экраны.html', 'tour': '12-pro-экскурсия.html',
         's13': '13-pro-экраны-2.html', 's14': '14-pro-напоминания.html', 's15': '15-pro-виджеты-приложений.html',
         's16': '16-pro-планы.html',
         's17': '17-pro-новое.html', 's18': '18-pro-новое-2.html', 's19': '19-pro-новое-3.html'}
BLOB = ("function blobUrl(b64){const bin=atob(b64),n=bin.length,u=new Uint8Array(n);for(let k=0;k<n;k++)"
        "u[k]=bin.charCodeAt(k);return URL.createObjectURL(new Blob([u],{type:'video/mp4'}))}")
PATCH = {
  'main': [("setTimeout(()=>runDialog(true),1400);", "if(window.APP_TALK)setTimeout(()=>runDialog(false),700);"),
           ("if(k==='speak'){say(d.a,2.7);$('lat').textContent=d.lat.toFixed(2)+' s'}", "if(k==='speak'){say(d.a,2.7);if(!window.REAL_LAT)$('lat').textContent=d.lat.toFixed(2)+' s'}"),
           ("if(state==='think'){const d=DLG[dlg%DLG.length];", "if(state==='think'&&!window.REAL_LAT){const d=DLG[dlg%DLG.length];")],
  'tour': [("\nstart(true);", "\nstart(!/Again/.test(location.hash));"),
           ("const HELLO='Здравствуйте, сэр. Я — Джарвис. Показать, что я умею?';",
            "const HELLO=/Again/.test(location.hash)?'Снова здесь, сэр. Показать всё ещё раз?':'Здравствуйте, сэр. Я — Джарвис. Показать, что я умею?';"),
           ("touring=false;try{localStorage.setItem('jarvisTourSeen','1')}catch(e){}}",
            "touring=false;try{localStorage.setItem('jarvisTourSeen','1')}catch(e){}window.appTourDone&&appTourDone()}")],
}
if os.path.isdir(W): shutil.rmtree(W)
os.makedirs(W + 'v'); os.makedirs(W + 'fonts')
STAMP = int(time.time())
vids = {}
def vfile(raw):
    h = hashlib.md5(raw).hexdigest()[:10]; fn = 'v/%s.mp4' % h
    if fn not in vids: open(W + fn, 'wb').write(raw); vids[fn] = len(raw)
    return fn
def tvid(fn, t):
    """v/<md5>.mp4 → v/<md5>-<тема>.mp4 (готовый перекрашенный файл)."""
    if t == 'gold': return fn
    out = fn[:-4] + '-' + t + '.mp4'
    if out not in vids:
        src = TV + fn[2:-4] + '-' + t + '.mp4'
        assert os.path.exists(src), 'нет видео темы: ' + src + ' — запусти render/theme_videos.py'
        shutil.copy(src, W + out); vids[out] = os.path.getsize(src)
    return out
_img = {}
def recolor_images(s, t):
    def f(m):
        key = (hashlib.md5(m.group(2).encode()).hexdigest(), t)
        if key not in _img:
            im = Image.open(io.BytesIO(base64.b64decode(m.group(2))))
            im2 = themes.recolor_image(im, t); buf = io.BytesIO()
            if m.group(1) == 'png': im2.save(buf, 'PNG', optimize=True)
            else: im2.convert('RGB').save(buf, 'JPEG', quality=90)
            _img[key] = 'data:image/%s;base64,%s' % (m.group(1), base64.b64encode(buf.getvalue()).decode())
        return _img[key]
    return re.sub(r'data:image/(jpeg|png);base64,([A-Za-z0-9+/=]+)', f, s)

src = {}
for name, f in PAGES.items():
    s = open(P + f, encoding='utf-8').read()
    i = s.find('const DATA=')
    if i >= 0:
        j = s.index(';\n', i); d = json.loads(s[i + 11:j])
        # в приложении экран «Темы» показывает уже перекрашенную страницу — запасные видео ядра не нужны
        d['v'] = {k: vfile(base64.b64decode(b)) for k, b in d['v'].items() if not k.startswith('core_')}
        s = s[:i] + 'const DATA=' + json.dumps(d, ensure_ascii=False) + s[j:]
        assert s.count(BLOB) == 1, name
        s = s.replace(BLOB, 'function blobUrl(x){return x}')
    s, n = re.subn(r'<link[^>]*fonts\.g[^>]*>\s*', '', s); assert n >= 1, name
    for a, b in PATCH.get(name, []):
        assert s.count(a) == 1, (name, a[:40]); s = s.replace(a, b)
    assert s.count('</head>') == 1, name
    src[name] = s
sp = vfile(open(P + 'render/final/sprite_speak_480.mp4', 'rb').read())
TH = {k: {'n': v['name'], 'c': v['accent'], 's': v['sub'], 'p': v['phrase']} for k, v in themes.THEMES.items()}
js0 = open(A + 'web/app.js', encoding='utf-8').read().replace('__SPTALK__', sp).replace('__THEMES__', json.dumps(TH, ensure_ascii=False))
css0 = open(A + 'web/app.css', encoding='utf-8').read()
# настоящие данные телефона для экранов: web/real/*.js по порядку имён (работают только в настоящем приложении)
import glob as _g
real0 = '\n'.join(open(f, encoding='utf-8').read() for f in sorted(_g.glob(A + 'web/real/*.js')))
for t in themes.ORDER:
    suf = '' if t == 'gold' else '-' + t
    sizes = []
    for name, s in src.items():
        if t != 'gold':
            s = re.sub(r'v/[0-9a-f]{10}\.mp4', lambda m: tvid(m.group(0), t), s)
            s = recolor_images(s, t)
            s = themes.recolor_text(s, t)
        s = s.replace('</head>', '<link rel="stylesheet" href="app%s.css?%d">\n<script src="app%s.js?%d"></script>\n</head>' % (suf, STAMP, suf, STAMP))
        if real0: s = s.replace('</body>', '<script src="real%s.js?%d"></script>\n</body>' % (suf, STAMP))
        open(W + name + suf + '.html', 'w', encoding='utf-8').write(s)
        sizes.append(len(s.encode()) // 1024)
    tvid(sp, t)
    open(W + 'app%s.css' % suf, 'w', encoding='utf-8').write(themes.recolor_text(css0, t))
    open(W + 'app%s.js' % suf, 'w', encoding='utf-8').write(themes.recolor_text(js0.replace('__THEME__', t), t))
    if real0: open(W + 'real%s.js' % suf, 'w', encoding='utf-8').write(themes.recolor_text(real0, t))
    print('%-5s страницы: %d KB' % (t, sum(sizes)))
shutil.copy(A + 'web/index.html', W + 'index.html')
for name in ('panel.html', 'panel.css', 'panel.js'):
    shutil.copy(A + 'web/' + name, W + name)
shutil.copy('D:/apk/jarvis/dev-code/Jarvis_for_android/app/src/main/assets/fonts/manrope.ttf', W + 'fonts/manrope.ttf')
# обложки: первый кадр каждого видео (тот же кадр, с которого начинается петля)
import subprocess
for fn in sorted(vids):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', W + fn, '-frames:v', '1', '-q:v', '4', W + fn[:-4] + '.jpg'], check=True)
print('видео: %d файлов, %.1f МБ (+ обложки)' % (len(vids), sum(vids.values()) / 1e6))

# ---------- значки приложения во всех цветах (для «Иконка в цвет темы») ----------
RES = A + 'res/'
for t in themes.ORDER[1:]:
    for part in ('fg', 'bg'):
        im = Image.open(RES + 'mipmap-xxxhdpi/ic_launcher_%s.png' % part)
        themes.recolor_image(im, t).save(RES + 'mipmap-xxxhdpi/ic_launcher_%s_%s.png' % (part, t), optimize=True)
    for nm in ('ic_launcher_%s', 'ic_launcher_round_%s'):
        open(RES + 'mipmap-anydpi-v26/' + (nm % t) + '.xml', 'w', encoding='utf-8').write(
            '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@mipmap/ic_launcher_bg_%s" />\n    <foreground android:drawable="@mipmap/ic_launcher_fg_%s" />\n'
            '</adaptive-icon>\n' % (t, t))
print('значки: готово')
