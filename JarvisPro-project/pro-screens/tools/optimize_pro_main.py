"""Keep the Pro home reactor on two decoders in the source and packaged themes."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "pages/10-pro-главная-телефон.html"
ASSETS = ROOT.parent / "android/app/src/main/assets/pro"
START = "// ---------- ядро: всего ДВА плеера; невидимый освобождается ----------"
END = "// ---------- состояния и разговор ----------"

PLAYER = """// ---------- ядро: два слоя, один активный декодер в покое ----------
const ORDER=['idle','listen','think','speak'];
const players=Array.from(coreBox.querySelectorAll('video'));
players.forEach(v=>{v.muted=true;v.loop=true;v.playsInline=true;v.preload='none';v.poster=DATA.p});
let cur='idle',active=0,request=0,fade=null,finishFade=null;
players[0].src=U.idle;players[0].preload='auto';players[0].style.opacity=1;
players[0].play().catch(()=>{});
function release(v){v.pause();v.removeAttribute('src');v.load();v.style.opacity=0}
function showCore(k){
  if(!ORDER.includes(k)||k===cur)return;
  if(finishFade)finishFade();
  cur=k;const serial=++request,front=players[active],back=players[1-active];
  back.onloadeddata=null;back.pause();back.style.opacity=0;back.src=U[k];back.preload='auto';
  const ready=()=>{
    if(serial!==request)return;
    back.onloadeddata=null;
    const reveal=()=>{
      if(serial!==request)return;
      back.style.zIndex=(+front.style.zIndex||1)+1;
      if(document.hidden){release(front);back.style.opacity=1;active=1-active;return}
      const animation=back.animate([{opacity:0},{opacity:1}],{duration:420,easing:'ease-in-out',fill:'forwards'});
      fade=animation;
      finishFade=()=>{if(serial!==request)return;back.style.opacity=1;animation.cancel();fade=null;finishFade=null;release(front);active=1-active};
      animation.onfinish=finishFade;
    };
    back.play().then(reveal).catch(reveal);
  };
  back.onloadeddata=ready;back.load();
  if(back.readyState>=HTMLMediaElement.HAVE_CURRENT_DATA)ready();
}
document.addEventListener('visibilitychange',()=>{
  if(document.hidden){players.forEach(v=>v.pause())}
  else {players[active].play().catch(()=>{});lastDraw=0;if(!animating){animating=true;requestAnimationFrame(frame)}}
});

"""

for path in [SOURCE, *(ASSETS / f"main{s}.html" for s in ("", "-az", "-am", "-ru", "-em"))]:
    text = path.read_text(encoding="utf-8")
    begin = START if START in text else "// ---------- ядро: два слоя, один активный декодер в покое ----------"
    assert text.count(begin) == 1 and text.count(END) == 1, path
    first = text.index(begin)
    last = text.index(END, first)
    text = text[:first] + PLAYER + text[last:]
    text = text.replace("function frame(now){requestAnimationFrame(frame);if(now-lastDraw<31)return;", "function frame(now){if(document.hidden){animating=false;return}requestAnimationFrame(frame);if(now-lastDraw<40)return;")
    text = text.replace("function frame(now){if(document.hidden)return;requestAnimationFrame(frame);if(now-lastDraw<40)return;", "function frame(now){if(document.hidden){animating=false;return}requestAnimationFrame(frame);if(now-lastDraw<40)return;")
    text = text.replace("function frame(now){if(document.hidden){animating=false;return}requestAnimationFrame(frame);if(now-lastDraw<40)return;", "function frame(now){if(document.hidden){animating=false;return}setTimeout(()=>requestAnimationFrame(frame),state==='idle'?80:40);")
    text = text.replace("function frame(now){if(document.hidden){animating=false;return}setTimeout(()=>requestAnimationFrame(frame),state==='idle'?80:40);", "function frame(now){if(document.hidden){animating=false;return}if(state==='idle')animating=false;else setTimeout(()=>requestAnimationFrame(frame),40);")
    text = text.replace("function setState(k){state=k;phone.dataset.state=k;", "function setState(k){state=k;if(!document.hidden&&k!=='idle'&&!animating){animating=true;requestAnimationFrame(frame)}phone.dataset.state=k;")
    text = text.replace("function setState(k){state=k;if(k!=='idle'&&!animating){", "function setState(k){state=k;if(!document.hidden&&k!=='idle'&&!animating){")
    if ".phone[data-state=idle] .hudsvg .lead" not in text:
        text = text.replace("@keyframes dash{to{stroke-dashoffset:-190}}", ".phone[data-state=idle] .hudsvg .lead,.phone[data-state=idle] .mic .i-dots circle{animation-play-state:paused}\n@keyframes dash{to{stroke-dashoffset:-190}}")
    assert "function frame(now){if(document.hidden){animating=false;return}if(state==='idle')animating=false;else setTimeout(()=>requestAnimationFrame(frame),40);" in text, path
    text = text.replace("let ampS=.05,gS=0,scS=0,spd=.8,phL=0,phR=1.7,last=performance.now(),lastDraw=0;", "let ampS=.05,gS=0,scS=0,spd=.8,phL=0,phR=1.7,last=performance.now(),lastDraw=0,animating=true;")
    path.write_text(text, encoding="utf-8")
    print(path)
