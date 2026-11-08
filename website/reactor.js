/* Реактор Jarvis: плазменное ядро, HUD-кольца, разряды, воронка частиц и свечение. */
(() => {
  "use strict";
  const canvas = document.getElementById("scene");
  const fallback = () => document.body.classList.add("no-webgl");
  const T = window.THREE;
  const probe = document.createElement("canvas");
  if (!T || !canvas || !(probe.getContext("webgl2") || probe.getContext("webgl"))) { fallback(); return; }

  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const small = Math.min(innerWidth, innerHeight) < 720;
  const Q = small ? 0.5 : 1; // доля деталей на телефоне

  let renderer;
  try {
    renderer = new T.WebGLRenderer({ canvas, antialias: !small, alpha: false, powerPreference: "high-performance" });
  } catch (_) { fallback(); return; }
  renderer.setPixelRatio(Math.min(devicePixelRatio, small ? 1.5 : 2));
  renderer.setClearColor(0x04060c, 1);

  const scene = new T.Scene();
  const camera = new T.PerspectiveCamera(42, 1, 0.1, 400);
  camera.position.set(0, 0, 16);

  const root = new T.Group();      // смещение и масштаб по экрану
  const rig = new T.Group();       // наклон за курсором
  root.add(rig);
  scene.add(root);

  const ADD = T.AdditiveBlending;

  /* ---------- Шум для шейдеров (Ashima simplex 3D) ---------- */
  const NOISE = `
    vec3 mod289(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}
    vec4 mod289(vec4 x){return x-floor(x*(1.0/289.0))*289.0;}
    vec4 permute(vec4 x){return mod289(((x*34.0)+1.0)*x);}
    vec4 taylorInvSqrt(vec4 r){return 1.79284291400159-0.85373472095314*r;}
    float snoise(vec3 v){
      const vec2 C=vec2(1.0/6.0,1.0/3.0); const vec4 D=vec4(0.0,0.5,1.0,2.0);
      vec3 i=floor(v+dot(v,C.yyy)); vec3 x0=v-i+dot(i,C.xxx);
      vec3 g=step(x0.yzx,x0.xyz); vec3 l=1.0-g; vec3 i1=min(g.xyz,l.zxy); vec3 i2=max(g.xyz,l.zxy);
      vec3 x1=x0-i1+C.xxx; vec3 x2=x0-i2+C.yyy; vec3 x3=x0-D.yyy;
      i=mod289(i);
      vec4 p=permute(permute(permute(i.z+vec4(0.0,i1.z,i2.z,1.0))+i.y+vec4(0.0,i1.y,i2.y,1.0))+i.x+vec4(0.0,i1.x,i2.x,1.0));
      float n_=0.142857142857; vec3 ns=n_*D.wyz-D.xzx;
      vec4 j=p-49.0*floor(p*ns.z*ns.z); vec4 x_=floor(j*ns.z); vec4 y_=floor(j-7.0*x_);
      vec4 x=x_*ns.x+ns.yyyy; vec4 y=y_*ns.x+ns.yyyy; vec4 h=1.0-abs(x)-abs(y);
      vec4 b0=vec4(x.xy,y.xy); vec4 b1=vec4(x.zw,y.zw);
      vec4 s0=floor(b0)*2.0+1.0; vec4 s1=floor(b1)*2.0+1.0; vec4 sh=-step(h,vec4(0.0));
      vec4 a0=b0.xzyw+s0.xzyw*sh.xxyy; vec4 a1=b1.xzyw+s1.xzyw*sh.zzww;
      vec3 p0=vec3(a0.xy,h.x); vec3 p1=vec3(a0.zw,h.y); vec3 p2=vec3(a1.xy,h.z); vec3 p3=vec3(a1.zw,h.w);
      vec4 norm=taylorInvSqrt(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));
      p0*=norm.x; p1*=norm.y; p2*=norm.z; p3*=norm.w;
      vec4 m=max(0.6-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.0); m=m*m;
      return 42.0*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));
    }
    float fbm(vec3 p){float a=0.5,s=0.0;for(int i=0;i<4;i++){s+=a*snoise(p);p*=2.03;a*=0.5;}return s;}
  `;

  const uni = { uTime: { value: 0 }, uPulse: { value: 0 } };

  /* ---------- Плазменное ядро ---------- */
  const coreMat = new T.ShaderMaterial({
    uniforms: uni,
    vertexShader: NOISE + `
      uniform float uTime; uniform float uPulse;
      varying vec3 vN; varying vec3 vView; varying float vD;
      void main(){
        float d = fbm(normal*1.6 + vec3(0.0, uTime*0.35, uTime*0.2));
        vD = d;
        vec3 p = position + normal*(d*0.16 + uPulse*0.12);
        vec4 mv = modelViewMatrix*vec4(p,1.0);
        vN = normalize(normalMatrix*normal); vView = normalize(-mv.xyz);
        gl_Position = projectionMatrix*mv;
      }`,
    fragmentShader: `
      uniform float uPulse;
      varying vec3 vN; varying vec3 vView; varying float vD;
      void main(){
        float f = pow(1.0 - max(dot(vN, vView), 0.0), 2.2);
        vec3 deep = vec3(0.02, 0.35, 0.75);
        vec3 hot = vec3(0.55, 0.95, 1.0);
        vec3 col = mix(deep, hot, smoothstep(-0.3, 0.7, vD));
        col += vec3(0.25, 0.8, 1.0) * f * 1.2;
        col *= 0.85 + uPulse*0.5;
        gl_FragColor = vec4(col, 1.0);
      }`,
  });
  const core = new T.Mesh(new T.IcosahedronGeometry(1, small ? 24 : 48), coreMat);
  rig.add(core);


  // Плазменная оболочка
  const shellMat = new T.ShaderMaterial({
    uniforms: uni, transparent: true, depthWrite: false, blending: ADD, side: T.BackSide,
    vertexShader: `
      varying vec3 vP; varying vec3 vN; varying vec3 vView;
      void main(){ vP = position; vec4 mv = modelViewMatrix*vec4(position,1.0);
        vN = normalize(normalMatrix*normal); vView = normalize(-mv.xyz); gl_Position = projectionMatrix*mv; }`,
    fragmentShader: NOISE + `
      uniform float uTime;
      varying vec3 vP; varying vec3 vN; varying vec3 vView;
      void main(){
        float n = fbm(vP*1.3 + vec3(uTime*0.25, -uTime*0.4, 0.0));
        float bands = smoothstep(0.15, 0.6, abs(sin(n*6.0 + uTime)));
        float rim = pow(1.0 - abs(dot(vN, vView)), 1.5);
        float a = (0.25 + bands*0.55) * rim;
        gl_FragColor = vec4(vec3(0.2, 0.75, 1.0)*a*1.4, a);
      }`,
  });
  const shell = new T.Mesh(new T.SphereGeometry(1.55, 64, 64), shellMat);
  rig.add(shell);

  /* ---------- Текстуры HUD на холсте ---------- */
  const TEX = small ? 1024 : 2048;
  function ringTexture(draw) {
    const c = document.createElement("canvas");
    c.width = c.height = TEX;
    const g = c.getContext("2d");
    g.translate(TEX / 2, TEX / 2);
    g.scale(TEX / 2, TEX / 2); // единицы: радиус 1 = край текстуры
    draw(g);
    const t = new T.CanvasTexture(c);
    t.anisotropy = 4;
    return t;
  }
  const C = { cyan: "rgba(90,235,255,", blue: "rgba(70,130,255,", gold: "rgba(255,190,90,", white: "rgba(220,250,255," };
  const lw = px => px / (TEX / 2);

  const drawTicks = (r0, r1, n, major, col) => g => {
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2, big = i % major === 0;
      g.strokeStyle = col + (big ? "1)" : ".55)");
      g.lineWidth = lw(big ? 5 : 2.5);
      g.beginPath();
      g.moveTo(Math.cos(a) * r0, Math.sin(a) * r0);
      g.lineTo(Math.cos(a) * (big ? r1 + (r1 - r0) * 0.8 : r1), Math.sin(a) * (big ? r1 + (r1 - r0) * 0.8 : r1));
      g.stroke();
    }
  };
  const drawArcs = (r, w, parts, col, glow) => g => {
    g.lineCap = "butt";
    parts.forEach(([a0, a1, alpha]) => {
      g.shadowColor = col + "1)"; g.shadowBlur = glow;
      g.strokeStyle = col + alpha + ")";
      g.lineWidth = w;
      g.beginPath(); g.arc(0, 0, r, a0 * Math.PI * 2, a1 * Math.PI * 2); g.stroke();
    });
    g.shadowBlur = 0;
  };
  const drawCircle = (r, w, col) => g => { g.strokeStyle = col; g.lineWidth = w; g.beginPath(); g.arc(0, 0, r, 0, Math.PI * 2); g.stroke(); };
  const drawText = (r, text, size, col) => g => {
    g.fillStyle = col;
    g.font = `700 ${size}px Orbitron, Manrope, sans-serif`;
    g.textAlign = "center"; g.textBaseline = "middle";
    const chars = [...text], step = (Math.PI * 2) / chars.length;
    chars.forEach((ch, i) => {
      g.save(); g.rotate(i * step); g.translate(0, -r); g.fillText(ch, 0, 0); g.restore();
    });
  };
  const drawSegments = (r, w, n, gap, col) => g => {
    for (let i = 0; i < n; i++) {
      const a0 = (i / n) * Math.PI * 2 + gap, a1 = ((i + 1) / n) * Math.PI * 2 - gap;
      const grd = g.createLinearGradient(Math.cos(a0) * r, Math.sin(a0) * r, Math.cos(a1) * r, Math.sin(a1) * r);
      grd.addColorStop(0, col + ".95)"); grd.addColorStop(1, col + ".35)");
      g.strokeStyle = grd; g.lineWidth = w;
      g.beginPath(); g.arc(0, 0, r, a0, a1); g.stroke();
    }
  };
  const drawDots = (r, n, size, col) => g => {
    g.fillStyle = col;
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2;
      g.beginPath(); g.arc(Math.cos(a) * r, Math.sin(a) * r, size, 0, Math.PI * 2); g.fill();
    }
  };
  const all = (...fs) => g => fs.forEach(f => f(g));

  function hudRing(radius, draw, opacity) {
    const m = new T.Mesh(
      new T.PlaneGeometry(radius * 2, radius * 2),
      new T.MeshBasicMaterial({ map: ringTexture(draw), transparent: true, opacity, blending: ADD, depthWrite: false, side: T.DoubleSide }),
    );
    m.userData.opacity = opacity;
    return m;
  }

  const hud = new T.Group();
  rig.add(hud);
  const rings = [];
  const ring = (radius, draw, opacity, speed, tilt) => {
    const holder = new T.Group();
    const m = hudRing(radius, draw, opacity);
    holder.add(m);
    if (tilt) holder.rotation.set(tilt[0], tilt[1], 0);
    holder.userData.spin = speed;
    holder.userData.mesh = m;
    hud.add(holder);
    rings.push(holder);
    return holder;
  };

  function buildHud() {
    // 1. Внутренний сегментированный обод, как в приложении
    ring(2.35, all(
      drawSegments(0.80, lw(46), 10, 0.06, C.white),
      drawCircle(0.92, lw(3), "rgba(90,235,255,.7)"),
      drawCircle(0.70, lw(2), "rgba(90,235,255,.45)"),
    ), 0.95, -0.25);
    // 2. Шкала с делениями
    ring(3.2, all(
      drawTicks(0.90, 0.95, 180, 15, C.cyan),
      drawCircle(0.885, lw(2), "rgba(90,235,255,.35)"),
    ), 0.8, 0.08);
    // 3. Яркие дуги-сканеры
    ring(3.75, drawArcs(0.92, lw(14), [[0.02, 0.2, 1], [0.27, 0.31, .9], [0.55, 0.78, .8], [0.83, 0.85, 1]], C.cyan, 30), 1, 0.45);
    // 4. Золотые засечки
    ring(4.2, all(
      drawArcs(0.95, lw(6), [[0.1, 0.16, 1], [0.6, 0.64, 1], [0.9, 0.93, .8]], C.gold, 24),
      drawDots(0.88, 72, lw(3.5), "rgba(150,210,255,.6)"),
    ), 0.9, -0.18);
    // 5. Кольцо с надписью
    ring(5.0, all(
      drawText(0.9, "J.A.R.V.I.S  ·  VOICE CORE  ·  AGENT  ·  MEMORY  ·  ONLINE  ·  OFFLINE  ·  AJIKAPD  ·  ", lw(30), "rgba(130,220,255,.75)"),
      drawCircle(0.95, lw(2), "rgba(90,180,255,.35)"),
      drawCircle(0.85, lw(1.5), "rgba(90,180,255,.25)"),
    ), 0.85, 0.05);
    // 6. Широкая внешняя рамка
    ring(6.4, all(
      drawArcs(0.97, lw(4), [[0, 0.22, .6], [0.25, 0.47, .6], [0.5, 0.72, .6], [0.75, 0.97, .6]], C.blue, 18),
      drawTicks(0.92, 0.94, 96, 8, C.blue),
    ), 0.6, -0.03);
    // Гироскоп: наклонённые кольца вокруг ядра
    ring(2.9, drawArcs(0.95, lw(10), [[0, 0.4, 1], [0.5, 0.9, 1]], C.cyan, 26), 0.75, 0.9, [1.25, 0.3]);
    ring(3.1, drawArcs(0.95, lw(8), [[0.1, 0.35, 1], [0.6, 0.85, .9]], C.blue, 22), 0.7, -0.7, [-1.1, 0.9]);
    ring(2.6, all(drawCircle(0.95, lw(4), "rgba(255,190,90,.8)"), drawDots(0.95, 24, lw(9), "rgba(255,220,150,1)")), 0.55, 0.6, [0.4, 1.4]);
  }

  /* ---------- Разряды между ядром и ободом ---------- */
  const bolts = [];
  const BOLTS = small ? 4 : 7, SEG = 14;
  for (let i = 0; i < BOLTS; i++) {
    const geo = new T.BufferGeometry();
    geo.setAttribute("position", new T.BufferAttribute(new Float32Array(SEG * 3), 3));
    const line = new T.Line(geo, new T.LineBasicMaterial({ color: 0xbff8ff, transparent: true, opacity: 0, blending: ADD, depthWrite: false }));
    line.userData.life = 0;
    rig.add(line);
    bolts.push(line);
  }
  function strike(b) {
    const a = Math.random() * Math.PI * 2, r0 = 1.1, r1 = 2.0 + Math.random() * 0.4;
    const p = b.geometry.attributes.position.array;
    for (let i = 0; i < SEG; i++) {
      const k = i / (SEG - 1), r = r0 + (r1 - r0) * k;
      const jit = (i === 0 || i === SEG - 1) ? 0 : (Math.random() - 0.5) * 0.35;
      const aa = a + jit * 0.6;
      p[i * 3] = Math.cos(aa) * r + jit * 0.2;
      p[i * 3 + 1] = Math.sin(aa) * r + jit * 0.2;
      p[i * 3 + 2] = (Math.random() - 0.5) * 0.3;
    }
    b.geometry.attributes.position.needsUpdate = true;
    b.userData.life = 1;
  }

  /* ---------- Воронка частиц ---------- */
  const N = Math.round(5000 * Q);
  const seed = new Float32Array(N * 4), colors = new Float32Array(N * 3);
  const palette = [[0.35, 0.92, 1], [0.27, 0.5, 1], [0.6, 0.4, 1], [1, 0.75, 0.35]];
  for (let i = 0; i < N; i++) {
    seed[i * 4] = Math.random();                 // фаза
    seed[i * 4 + 1] = Math.random() * Math.PI * 2; // угол
    seed[i * 4 + 2] = 0.03 + Math.random() * 0.07; // скорость
    seed[i * 4 + 3] = (Math.random() - 0.5);      // высота
    const c = palette[Math.random() < 0.08 ? 3 : Math.floor(Math.random() * 3)];
    colors.set(c, i * 3);
  }
  const pGeo = new T.BufferGeometry();
  pGeo.setAttribute("position", new T.BufferAttribute(new Float32Array(N * 3), 3));
  pGeo.setAttribute("seed", new T.BufferAttribute(seed, 4));
  pGeo.setAttribute("tint", new T.BufferAttribute(colors, 3));
  const pMat = new T.ShaderMaterial({
    uniforms: { ...uni, uScale: { value: renderer.getPixelRatio() } },
    transparent: true, depthWrite: false, blending: ADD,
    vertexShader: `
      uniform float uTime; uniform float uScale; uniform float uPulse;
      attribute vec4 seed; attribute vec3 tint;
      varying vec3 vC; varying float vA;
      void main(){
        float ph = fract(seed.x + uTime*seed.z);
        float r = mix(9.0, 1.3, ph*ph);
        float ang = seed.y + ph*6.0 + uTime*0.15;
        vec3 p = vec3(cos(ang)*r, sin(ang)*r*0.92, seed.w*(r*0.35) );
        p.xy *= 1.0 + uPulse*0.15;
        vec4 mv = modelViewMatrix*vec4(p,1.0);
        gl_Position = projectionMatrix*mv;
        gl_PointSize = (1.2 + 3.0*(1.0-ph)) * uScale * (18.0 / -mv.z);
        vC = tint;
        vA = smoothstep(0.0, 0.15, ph) * smoothstep(1.0, 0.75, ph);
      }`,
    fragmentShader: `
      varying vec3 vC; varying float vA;
      void main(){
        float d = length(gl_PointCoord - 0.5);
        float a = smoothstep(0.5, 0.0, d) * vA;
        gl_FragColor = vec4(vC*a, a);
      }`,
  });
  const vortex = new T.Points(pGeo, pMat);
  vortex.frustumCulled = false;
  rig.add(vortex);

  /* ---------- Звёзды и туманность ---------- */
  const S = Math.round(1800 * Q);
  const sp = new Float32Array(S * 3);
  for (let i = 0; i < S; i++) {
    sp[i * 3] = (Math.random() - 0.5) * 160;
    sp[i * 3 + 1] = (Math.random() - 0.5) * 100;
    sp[i * 3 + 2] = -20 - Math.random() * 120;
  }
  const sGeo = new T.BufferGeometry();
  sGeo.setAttribute("position", new T.BufferAttribute(sp, 3));
  const stars = new T.Points(sGeo, new T.PointsMaterial({ color: 0x9fd8ff, size: 0.18, transparent: true, opacity: 0.7, depthWrite: false }));
  scene.add(stars);

  function glowSprite(size, stops) {
    const c = document.createElement("canvas"); c.width = c.height = 256;
    const g = c.getContext("2d"), gr = g.createRadialGradient(128, 128, 0, 128, 128, 128);
    stops.forEach(([o, col]) => gr.addColorStop(o, col));
    g.fillStyle = gr; g.fillRect(0, 0, 256, 256);
    const s = new T.Sprite(new T.SpriteMaterial({ map: new T.CanvasTexture(c), blending: ADD, depthWrite: false, transparent: true }));
    s.scale.set(size, size, 1);
    return s;
  }
  const nebula = glowSprite(40, [[0, "rgba(40,90,200,.35)"], [0.4, "rgba(70,40,160,.12)"], [1, "rgba(0,0,0,0)"]]);
  nebula.position.z = -12;
  rig.add(nebula);
  const halo = glowSprite(8, [[0, "rgba(120,240,255,.55)"], [0.2, "rgba(70,200,255,.2)"], [1, "rgba(0,0,0,0)"]]);
  rig.add(halo);

  /* ---------- Ударная волна по нажатию ---------- */
  const waves = [];
  const waveTex = ringTexture(drawCircle(0.9, lw(10), "rgba(140,240,255,1)"));
  function shock() {
    const m = new T.Mesh(new T.PlaneGeometry(2, 2), new T.MeshBasicMaterial({ map: waveTex, transparent: true, blending: ADD, depthWrite: false }));
    m.userData.t = 0;
    rig.add(m);
    waves.push(m);
    pulse = 1;
  }
  let pulse = 0;
  addEventListener("pointerdown", e => {
    if (e.target.closest("a,button,summary,input")) return;
    if (scrollY < innerHeight * 0.8) shock();
  });

  /* ---------- Свечение ---------- */
  let composer = null, bloom = null;
  if (T.EffectComposer && T.UnrealBloomPass) {
    composer = new T.EffectComposer(renderer);
    composer.addPass(new T.RenderPass(scene, camera));
    bloom = new T.UnrealBloomPass(new T.Vector2(256, 256), small ? 0.75 : 0.95, 0.6, 0.28);
    composer.addPass(bloom);
  }

  /* ---------- Раскладка и ввод ---------- */
  let mx = 0, my = 0, sy = 0, lastSy = 0, spinBoost = 0;
  addEventListener("pointermove", e => { mx = e.clientX / innerWidth - 0.5; my = e.clientY / innerHeight - 0.5; }, { passive: true });
  addEventListener("deviceorientation", e => {
    if (e.gamma == null) return;
    mx = Math.max(-0.5, Math.min(0.5, e.gamma / 60));
    my = Math.max(-0.5, Math.min(0.5, (e.beta - 45) / 80));
  }, { passive: true });
  addEventListener("scroll", () => { sy = scrollY; }, { passive: true });

  function layout() {
    const w = innerWidth, h = innerHeight;
    renderer.setSize(w, h, false);
    if (composer) {
      composer.setSize(w, h);
      bloom.resolution.set(w * (small ? 0.5 : 1), h * (small ? 0.5 : 1));
    }
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    const wide = w > 900;
    // На телефоне реактор над заголовком: центр на 27% высоты экрана
    const viewH = 2 * camera.position.z * Math.tan((camera.fov * Math.PI) / 360);
    root.userData.x = wide ? 4.7 : 0;
    root.userData.y = wide ? 0 : (0.5 - 0.27) * viewH;
    root.userData.s = wide ? 0.92 : Math.min(0.62, (viewH * camera.aspect) / 8.6);
  }
  layout();
  addEventListener("resize", layout);

  /* ---------- Сборка при загрузке ---------- */
  const fontReady = document.fonts && document.fonts.load ? document.fonts.load("700 40px Orbitron") : Promise.resolve();
  let born = performance.now();
  Promise.race([fontReady, new Promise(r => setTimeout(r, 1500))]).then(() => { buildHud(); born = performance.now(); });

  let visible = true;
  document.addEventListener("visibilitychange", () => { visible = !document.hidden; });
  const ease = x => 1 - Math.pow(1 - Math.min(1, Math.max(0, x)), 3);
  const clock = new T.Clock();
  let last = 0, nextBolt = 0;

  function frame(now) {
    requestAnimationFrame(frame);
    if (!visible || (small && now - last < 30)) return;
    const dt = Math.min(0.05, (now - last) / 1000 || 0.016);
    last = now;
    const t = clock.getElapsedTime();
    const k = reduced ? 0.2 : 1;
    uni.uTime.value = t * k;

    // Скорость прокрутки раскручивает кольца
    spinBoost += (Math.min(6, Math.abs(sy - lastSy) * 0.05) - spinBoost) * 0.1;
    lastSy = sy;
    pulse *= 0.94;
    uni.uPulse.value = pulse + Math.sin(t * 2.4) * 0.15 + 0.15;

    const age = (now - born) / 1000;
    rings.forEach((r, i) => {
      const grow = ease((age - i * 0.09) / 1.1);
      r.scale.setScalar(0.001 + grow);
      r.userData.mesh.material.opacity = r.userData.mesh.userData.opacity * grow;
      r.userData.mesh.rotation.z += r.userData.spin * dt * k * (1 + spinBoost);
    });
    hud.rotation.z = Math.sin(t * 0.2) * 0.05;
    halo.material.opacity = 0.6 + Math.sin(t * 2.4) * 0.15 + pulse * 0.3;
    halo.scale.setScalar(8 + pulse * 3);

    // Разряды
    if (!reduced && t > nextBolt) {
      const b = bolts.find(x => x.userData.life <= 0);
      if (b) strike(b);
      nextBolt = t + 0.08 + Math.random() * 0.35;
    }
    bolts.forEach(b => {
      if (b.userData.life > 0) {
        b.userData.life -= dt * 5;
        b.material.opacity = Math.max(0, b.userData.life) * (0.6 + Math.random() * 0.4);
      }
    });

    // Волны
    for (let i = waves.length - 1; i >= 0; i--) {
      const w = waves[i];
      w.userData.t += dt;
      const p = w.userData.t / 1.4;
      w.scale.setScalar(1 + p * 9);
      w.material.opacity = Math.max(0, 1 - p);
      if (p >= 1) { rig.remove(w); w.geometry.dispose(); w.material.dispose(); waves.splice(i, 1); }
    }

    // Положение: справа от заголовка, при прокрутке уходит вглубь и в центр
    const sp = Math.min(1, sy / innerHeight);
    const tx = root.userData.x * (1 - sp * 0.6), ty = root.userData.y + sp * 1.5;
    root.position.x += (tx - root.position.x) * 0.06;
    root.position.y += (ty - root.position.y) * 0.06;
    root.position.z += (-sp * 6 - root.position.z) * 0.06;
    const sc = root.userData.s * ease(age / 1.2 + 0.2);
    root.scale.setScalar(sc);
    rig.rotation.y += ((mx * 0.7 + sp * 0.9) - rig.rotation.y) * 0.05;
    rig.rotation.x += ((my * 0.5 - 0.12 + sp * 0.4) - rig.rotation.x) * 0.05;
    stars.rotation.z = t * 0.004;
    stars.position.x = -mx * 2;

    if (composer) composer.render(); else renderer.render(scene, camera);
  }
  requestAnimationFrame(frame);

  // Отладка на телефоне: что отрисовалось
  window.__reactor = { rings: () => rings.length, bloom: !!composer };
})();
