(() => {
  "use strict";
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------- Язык ---------- */
  const ru = {};
  document.querySelectorAll("[data-i18n]").forEach(el => { ru[el.dataset.i18n] = el.textContent; });
  const titles = { ru: document.title, en: "Jarvis — voice assistant for Android" };
  function setLang(lang) {
    const dict = lang === "en" ? window.I18N.en : ru;
    document.querySelectorAll("[data-i18n]").forEach(el => {
      const v = dict[el.dataset.i18n];
      if (v) el.textContent = v;
    });
    document.documentElement.lang = lang;
    document.title = titles[lang];
    document.querySelectorAll(".lang button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.lang === lang)));
    try { localStorage.setItem("lang", lang); } catch (_) { /* приватный режим */ }
  }
  document.querySelectorAll(".lang button").forEach(b => b.addEventListener("click", () => setLang(b.dataset.lang)));
  let saved = null;
  try { saved = localStorage.getItem("lang"); } catch (_) { /* приватный режим */ }
  const initial = saved || (navigator.language || "ru").toLowerCase().startsWith("ru") ? (saved || "ru") : "en";
  if (initial !== "ru") setLang(initial);

  /* ---------- Появление и счётчики ---------- */
  function count(el) {
    const target = Number(el.dataset.count), suffix = el.dataset.suffix || "";
    if (reduced || target === 0) { el.textContent = target + suffix; return; }
    const t0 = performance.now(), dur = 1600;
    const step = now => {
      const p = Math.min(1, (now - t0) / dur), e = 1 - Math.pow(1 - p, 3);
      el.textContent = Math.round(target * e).toLocaleString("ru-RU") + suffix;
      if (p < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }
  const io = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if (!e.isIntersecting) return;
      e.target.classList.add("in");
      e.target.querySelectorAll("[data-count]").forEach(count);
      io.unobserve(e.target);
    });
  }, { threshold: .15 });
  document.querySelectorAll(".reveal").forEach((el, i) => {
    el.style.transitionDelay = (i % 4) * 80 + "ms";
    io.observe(el);
  });

  /* ---------- Наклон карточек ---------- */
  if (!reduced && matchMedia("(hover: hover)").matches) {
    document.querySelectorAll(".tilt").forEach(card => {
      card.addEventListener("pointermove", ev => {
        const r = card.getBoundingClientRect();
        const x = (ev.clientX - r.left) / r.width, y = (ev.clientY - r.top) / r.height;
        card.style.setProperty("--mx", x * 100 + "%");
        card.style.setProperty("--my", y * 100 + "%");
        card.style.transitionDelay = "0ms";
        card.style.transform = `rotateX(${(0.5 - y) * 12}deg) rotateY(${(x - 0.5) * 14}deg) translateZ(10px)`;
      });
      card.addEventListener("pointerleave", () => { card.style.transform = ""; });
    });
  }

  /* ---------- Фон тускнеет под текстом ---------- */
  const backdrop = [document.getElementById("scene"), document.querySelector(".css-reactor")];
  const dim = () => {
    const o = 1 - Math.min(1, scrollY / innerHeight) * .7;
    backdrop.forEach(el => {
      if (!el) return;
      // На телефоне запасной реактор стоит за текстом, поэтому он тусклее
      const base = el.classList.contains("css-reactor") && innerWidth <= 900 ? .55 : 1;
      el.style.opacity = base * o;
    });
  };
  addEventListener("scroll", dim, { passive: true });
  dim();

  /* ---------- 3D-реактор ---------- */
  const canvas = document.getElementById("scene");
  const fallback = () => document.body.classList.add("no-webgl");
  const probe = document.createElement("canvas");
  if (!window.THREE || !canvas || !(probe.getContext("webgl2") || probe.getContext("webgl"))) { fallback(); return; }
  const T = window.THREE;
  let renderer;
  try {
    renderer = new T.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: "high-performance" });
  } catch (_) { fallback(); return; }
  const small = innerWidth < 720;
  renderer.setPixelRatio(Math.min(devicePixelRatio, small ? 1.5 : 2));
  const scene = new T.Scene();
  scene.fog = new T.FogExp2(0x05070d, 0.035);
  const camera = new T.PerspectiveCamera(50, 1, 0.1, 200);
  camera.position.set(0, 0, 14);

  const reactor = new T.Group();
  scene.add(reactor);
  const add = (geo, color, opacity, blend = true) => {
    const m = new T.Mesh(geo, new T.MeshBasicMaterial({
      color, transparent: true, opacity, depthWrite: false,
      blending: blend ? T.AdditiveBlending : T.NormalBlending, side: T.DoubleSide,
    }));
    reactor.add(m);
    return m;
  };
  const glow = (size, color) => {
    const c = document.createElement("canvas"); c.width = c.height = 128;
    const g = c.getContext("2d"), gr = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    gr.addColorStop(0, color); gr.addColorStop(.25, color.replace("1)", ".45)")); gr.addColorStop(1, "rgba(0,0,0,0)");
    g.fillStyle = gr; g.fillRect(0, 0, 128, 128);
    const s = new T.Sprite(new T.SpriteMaterial({ map: new T.CanvasTexture(c), blending: T.AdditiveBlending, depthWrite: false, transparent: true }));
    s.scale.set(size, size, 1);
    reactor.add(s);
    return s;
  };

  // Ядро
  const core = add(new T.IcosahedronGeometry(1.05, 2), 0x9ff4ff, .9);
  const coreWire = add(new T.IcosahedronGeometry(1.35, 1), 0x46e6ff, .35);
  coreWire.material.wireframe = true;
  const halo = glow(7.5, "rgba(70,230,255,1)");
  glow(3.2, "rgba(220,250,255,1)");

  // Кольца
  const rings = [];
  const ringSpec = [
    [2.2, .05, 0x46e6ff, .9], [2.9, .025, 0x7fb6ff, .6], [3.6, .08, 0x3a7bff, .45],
    [4.4, .02, 0x9b5cff, .55], [5.3, .03, 0x46e6ff, .25],
  ];
  ringSpec.forEach(([r, t, c, o], i) => {
    const m = add(new T.TorusGeometry(r, t, 12, 160), c, o);
    m.rotation.x = Math.PI / 2 + (i % 2 ? .35 : -.25) * (i / 2);
    m.rotation.y = i * .4;
    m.userData.speed = (i % 2 ? -1 : 1) * (.12 + i * .05);
    rings.push(m);
  });

  // Сегменты внутреннего кольца, как на реакторе в приложении
  const segs = new T.Group();
  for (let i = 0; i < 10; i++) {
    const s = new T.Mesh(
      new T.BoxGeometry(.55, .18, .12),
      new T.MeshBasicMaterial({ color: 0xbff8ff, transparent: true, opacity: .85, blending: T.AdditiveBlending, depthWrite: false }),
    );
    const a = (i / 10) * Math.PI * 2;
    s.position.set(Math.cos(a) * 1.75, Math.sin(a) * 1.75, 0);
    s.rotation.z = a + Math.PI / 2;
    segs.add(s);
  }
  reactor.add(segs);

  // Дуги-сканеры
  const arcs = [];
  for (let i = 0; i < 3; i++) {
    const m = add(new T.TorusGeometry(3.25 + i * .5, .045, 8, 80, Math.PI * (.35 + i * .12)), i === 1 ? 0xffb547 : 0x46e6ff, .8);
    m.userData.speed = (i % 2 ? -1 : 1) * (.6 - i * .12);
    arcs.push(m);
  }

  // Частицы
  const N = small ? 900 : 2200;
  const pos = new Float32Array(N * 3);
  for (let i = 0; i < N; i++) {
    const r = 6 + Math.random() * 30, th = Math.random() * Math.PI * 2, ph = Math.acos(2 * Math.random() - 1);
    pos[i * 3] = r * Math.sin(ph) * Math.cos(th);
    pos[i * 3 + 1] = r * Math.sin(ph) * Math.sin(th) * .6;
    pos[i * 3 + 2] = r * Math.cos(ph) - 10;
  }
  const pg = new T.BufferGeometry();
  pg.setAttribute("position", new T.BufferAttribute(pos, 3));
  const stars = new T.Points(pg, new T.PointsMaterial({ color: 0x8fdcff, size: .06, transparent: true, opacity: .8, blending: T.AdditiveBlending, depthWrite: false }));
  scene.add(stars);

  // Сетка-пол
  const grid = new T.GridHelper(120, 60, 0x1d4f8a, 0x0c2546);
  grid.position.y = -7; grid.material.transparent = true; grid.material.opacity = .35;
  scene.add(grid);

  let mx = 0, my = 0, scrollP = 0;
  addEventListener("pointermove", e => { mx = e.clientX / innerWidth - .5; my = e.clientY / innerHeight - .5; }, { passive: true });
  addEventListener("scroll", () => { scrollP = scrollY / Math.max(1, document.body.scrollHeight - innerHeight); }, { passive: true });

  function layout() {
    const w = innerWidth, h = innerHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    // На широком экране реактор справа от заголовка, на телефоне за ним
    reactor.userData.baseX = w > 900 ? 4.6 : 0;
    reactor.userData.baseY = w > 900 ? 0 : 1.2;
    reactor.scale.setScalar(w > 900 ? 1 : .78);
  }
  layout();
  addEventListener("resize", layout);

  let visible = true;
  document.addEventListener("visibilitychange", () => { visible = !document.hidden; });
  const clock = new T.Clock();
  let last = 0;
  function frame(now) {
    requestAnimationFrame(frame);
    if (!visible || (small && now - last < 30)) return;
    last = now;
    const t = clock.getElapsedTime(), k = reduced ? .15 : 1;
    rings.forEach(r => { r.rotation.z += r.userData.speed * .01 * k; });
    arcs.forEach(a => { a.rotation.z += a.userData.speed * .02 * k; });
    segs.rotation.z -= .006 * k;
    coreWire.rotation.y += .004 * k; coreWire.rotation.x += .002 * k;
    const pulse = 1 + Math.sin(t * 2.2) * .06;
    core.scale.setScalar(pulse);
    halo.material.opacity = .75 + Math.sin(t * 2.2) * .2;
    stars.rotation.y = t * .01;
    // Камера следует за курсором, прокрутка уводит реактор вглубь и поворачивает
    const tx = reactor.userData.baseX - scrollP * 3, ty = reactor.userData.baseY + scrollP * 2;
    reactor.position.x += (tx - reactor.position.x) * .05;
    reactor.position.y += (ty - reactor.position.y) * .05;
    reactor.position.z = -scrollP * 8;
    reactor.rotation.y += ((mx * .6 + scrollP * Math.PI * 1.2) - reactor.rotation.y) * .05;
    reactor.rotation.x += ((my * .4 + .15) - reactor.rotation.x) * .05;
    grid.position.z = (t * 1.5) % 2;
    renderer.render(scene, camera);
  }
  requestAnimationFrame(frame);
})();
