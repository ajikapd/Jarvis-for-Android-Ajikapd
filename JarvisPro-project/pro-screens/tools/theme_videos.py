# Перекрашивает все видео Джарвиса из демо-приложения во все темы (3D-LUT из themes.py, ffmpeg lut3d).
# Результат: render/out/themes/v/<имя>-<тема>.mp4 — make_app.py берёт их оттуда (повторно не кодирует).
# Запуск: python render/theme_videos.py <папка с исходными mp4>
import os, subprocess, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import themes
R = 'D:/apk/Jarvis Pro/render/out/themes/'
os.makedirs(R + 'v2', exist_ok=True)
src = sys.argv[1] if len(sys.argv) > 1 else 'D:/apk/Jarvis Pro/app/assets/www/v'
for t in themes.ORDER[1:]:
    themes.write_cube(t, R + t + '.cube')
def probe(f):
    o = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=bit_rate',
                        '-of', 'json', f], capture_output=True, text=True).stdout
    return int(json.loads(o)['streams'][0]['bit_rate'])
files = sorted(f for f in os.listdir(src) if f.endswith('.mp4'))
for f in files:
    br = int(probe(os.path.join(src, f)) * 0.85) // 1000
    for t in themes.ORDER[1:]:
        out = R + 'v2/' + f[:-4] + '-' + t + '.mp4'
        if os.path.exists(out): continue
        tmp = out + '.tmp.mp4'
        base = ['ffmpeg', '-v', 'error', '-y', '-i', os.path.join(src, f), '-vf', 'lut3d=file=%s.cube:interp=tetrahedral' % t,
                '-c:v', 'libx264', '-preset', 'slow', '-b:v', '%dk' % br, '-maxrate', '%dk' % int(br * 1.7),
                '-bufsize', '%dk' % (br * 2), '-g', '24', '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-an']
        plog = R + 'pass_' + t
        subprocess.run(base + ['-pass', '1', '-passlogfile', plog, '-f', 'mp4', 'NUL'], cwd=R, check=True)
        subprocess.run(base + ['-pass', '2', '-passlogfile', plog, '-movflags', '+faststart', tmp], cwd=R, check=True)
        os.replace(tmp, out)
        print(f, t, os.path.getsize(out) // 1024, 'KB', flush=True)
print('DONE')
