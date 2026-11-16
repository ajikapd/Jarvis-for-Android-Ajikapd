// Джарвис Pro — демо-приложение. Подключается в каждую страницу-макет:
// показывает один экран во весь экран, связывает вкладки и меню, «Назад», окно «Что нового».
(function () {
  'use strict';
  var VERSION = '1.3';
  // тема этой страницы (подставляет сборщик): gold, az, am, ru, em; цвета тем не перекрашиваются
  var THEME = 'em';
  /*NOMAP*/var TH = {"gold": {"n": "Золото", "c": "#FFC46B", "s": "КАК В ПЕРВЫЙ ДЕНЬ", "p": "Классика, сэр. Как в первый день."}, "az": {"n": "Лазурь", "c": "#52B4FF", "s": "ХОЛОДНЫЙ, СПОКОЙНЫЙ", "p": "Лазурь. Холодный расчёт — мне идёт."}, "am": {"n": "Аметист", "c": "#C49CFF", "s": "ТАИНСТВЕННЫЙ, НОЧНОЙ", "p": "Аметист. Немного магии не повредит."}, "ru": {"n": "Рубин", "c": "#FF5E62", "s": "ГОРЯЧИЙ, СМЕЛЫЙ", "p": "Рубин. Боевой режим, сэр."}, "em": {"n": "Изумруд", "c": "#45EB9C", "s": "СВЕЖИЙ, ЖИВОЙ", "p": "Изумруд. Свежо, как утро после дождя."}};/*/NOMAP*/
  var TORDER = ['gold', 'az', 'am', 'ru', 'em'];
  function sufOf(t) { return t === 'gold' ? '' : '-' + t; }
  var SUF = sufOf(THEME);
  window.APP_THEME = THEME;
  var SPRITE0 = 'v/1166e769b6.mp4';
  function spr(t) { return SPRITE0.replace(/\.mp4$/, sufOf(t) + '.mp4'); }
  var SPRITE = spr(THEME);
  // маршрут → [страница, номер телефона на ней]
  var ROUTES = {
    boot: ['s11', 0], agent: ['s11', 1], buy: ['s11', 3],
    main: ['main', 0], tour: ['tour', 0], tourAgain: ['tour', 0],
    dialog: ['s13', 0], memory: ['s13', 1], scen: ['s13', 2], settings: ['s13', 3], overlay: ['s13', 4], more: ['s13', 5],
    plans: ['s16', 0], week: ['s16', 1], newtask: ['s16', 2],
    remind: ['s14', 0], remind2: ['s14', 1], remind3: ['s14', 2], remind4: ['s14', 3],
    appw: ['s15', 0], appw2: ['s15', 1],
    brief: ['s17', 0], reply: ['s17', 1], important: ['s17', 2], chain: ['s17', 3], people: ['s17', 4],
    modes: ['s18', 0], prices: ['s18', 1], gos: ['s18', 2], notes: ['s18', 3], spend: ['s18', 4],
    voices: ['s19', 0], themes: ['s19', 1], devices: ['s19', 2]
  };
  var TABS = { 'Главная': 'main', 'Диалог': 'dialog', 'Агент': 'agent', 'Ещё': 'more' };
  var MENU = { m_plans: 'plans', m_mem: 'memory', m_scen: 'scen', m_over: 'overlay', m_voice: 'voices',
    m_tour: 'tourAgain', m_set: 'settings', m_mod: 'modes', m_prc: 'prices', m_gos: 'gos', m_nts: 'notes', m_spd: 'spend', m_thm: 'themes', m_dev: 'devices', m_rem: 'remind', m_appw: 'appw', m_wn: 'wn',
    m_brief: 'brief', m_reply: 'reply', m_imp: 'important', m_chain: 'chain', m_ppl: 'people' };
  // примеры того, как Джарвис выглядит вне приложения — листаются стрелками и свайпом
  var EXG = [['remind', 'remind2', 'remind3', 'remind4'], ['appw', 'appw2'], ['overlay']];
  var EXCAP = {
    remind: 'Виджеты на рабочем столе', remind2: 'Сверху: всплывающее напоминание', remind3: 'Сверху: шторка уведомлений',
    remind4: 'Снизу: плашка над любым приложением', appw: 'Виджеты приложений · день', appw2: 'Виджеты приложений · дела',
    overlay: 'Шар Джарвиса поверх «Карт» — нажми на него'
  };

  var page = (location.pathname.split('/').pop() || 'index.html').replace(/\.html$/, '').replace(/-(az|am|ru|em)$/, '');
  var nativeHost = page === 'main' || page === 'tour';
  var D = document.documentElement;
  D.classList.add('app');
  // настоящий Джарвис (приложение com.jarvis.pro): голос, переписка, подтверждения — от него
  var REAL = false;
  try { REAL = !!(window.JarvisNative && JarvisNative.real && JarvisNative.real()); } catch (e) {}
  if (REAL) D.classList.add('real');
  // высота настоящей строки уведомлений (её рисует телефон поверх приложения)
  var ST = 0;
  try { ST = window.JarvisNative && JarvisNative.insetTop ? (+JarvisNative.insetTop() || 0) : 0; } catch (e) {}
  D.style.setProperty('--st', ST + 'px');
  // у каждого видео — обложка-картинка (первый кадр, v/*.jpg): пока видео грузится, видно Джарвиса, а не серое окно
  function poster(v) {
    if (!v || v.tagName !== 'VIDEO' || v.getAttribute('poster')) return;
    var s = v.getAttribute('src') || '';
    if (/(^|\/)v\/[0-9a-f]{10}(-[a-z]{2})?\.mp4$/.test(s)) v.setAttribute('poster', s.replace(/\.mp4$/, '.jpg'));
  }
  try {
    new MutationObserver(function (ms) {
      ms.forEach(function (m) {
        if (m.type === 'attributes') poster(m.target);
        else [].forEach.call(m.addedNodes, function (n) { if (n.nodeType === 1) { poster(n); if (n.querySelectorAll) [].forEach.call(n.querySelectorAll('video'), poster); } });
      });
    }).observe(D, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] });
  } catch (e) {}
  var vp = document.querySelector('meta[name=viewport]');
  if (vp) vp.content = 'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover';
  // внутри приложения «во весь экран» браузера не нужен
  Element.prototype.requestFullscreen = function () { return Promise.resolve(); };

  var store = {
    get: function (k) { try { return window.JarvisNative ? JarvisNative.get(k) : (localStorage.getItem(k) || ''); } catch (e) { return ''; } },
    set: function (k, v) { try { if (window.JarvisNative) JarvisNative.set(k, v); else localStorage.setItem(k, v); } catch (e) {} }
  };
  var ss = {
    get: function (k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { sessionStorage.setItem(k, v); } catch (e) {} }
  };
  // ---------- темы ----------
  // «Хамелеон»: утро — изумруд, день — лазурь, вечер — золото, ночь — аметист
  function autoTheme() { var h = new Date().getHours(); return h >= 6 && h < 11 ? 'em' : h >= 11 && h < 17 ? 'az' : h >= 17 && h < 22 ? 'gold' : 'am'; }
  function wantTheme() { if (store.get('auto') === '1') return autoTheme(); var t = store.get('theme'); return TH[t] ? t : 'gold'; }
  var want = wantTheme();
  if (want !== THEME) { location.replace(page + sufOf(want) + '.html' + location.hash); return; }
  function iconSync() { var w = store.get('icon') === '1' ? THEME : 'gold'; if (store.get('iconWant') !== w) store.set('iconWant', w); }
  iconSync();

  var stack = [];
  try { stack = JSON.parse(ss.get('st') || '[]'); } catch (e) {}
  function save() { ss.set('st', JSON.stringify(stack)); }

  var hashR = location.hash.slice(1);
  var cur = ROUTES[hashR] && ROUTES[hashR][0] === page ? hashR : (page === 'tour' ? 'tour' : 'main');
  window.APP_TALK = page === 'main' && ss.get('talk') === '1';
  if (window.APP_TALK) ss.set('talk', '');
  var realTalk = REAL && window.APP_TALK;
  if (REAL) window.APP_TALK = false;

  var host = null, phones = [], homes = [];

  var baseH = innerHeight, baseW = innerWidth;
  function fit() {
    if (!host) return;
    var W = innerWidth, H = innerHeight;
    var ae = document.activeElement, typing = ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA');
    if (W !== baseW) { baseW = W; baseH = H; }
    if (!typing || H >= baseH) baseH = Math.max(H, typing ? baseH : H);
    var kb = typing && H < baseH * 0.85;
    var k = Math.min(W / 370, (kb ? baseH : H) / 836), ty = Math.max(0, ((kb ? baseH : H) - 836 * k) / 2);
    if (kb) {
      // поднять экран так, чтобы поле ввода оказалось над клавиатурой
      var box = ae.closest('.inp') || ae, hr = host.getBoundingClientRect(), br = box.getBoundingClientRect();
      var curK = hr.width / 370 || k, localBottom = (br.bottom - hr.top) / curK;
      ty = Math.min(ty, H - 10 - localBottom * k);
    }
    host.style.transform = 'translate(' + ((W - 370 * k) / 2).toFixed(1) + 'px,' + ty.toFixed(1) + 'px) scale(' + k.toFixed(4) + ')';
  }

  function show(r) {
    cur = r;
    if (nativeHost) return;
    var ph = phones[ROUTES[r][1]];
    if (host.firstChild && host.firstChild !== ph) {
      var old = host.firstChild, i = phones.indexOf(old);
      if (i >= 0) homes[i].appendChild(old);
    }
    if (ph && ph.parentNode !== host) host.appendChild(ph);
    decorate(r);
    exUI(r);
    if (REAL && r === 'dialog') dlgSetup();
    if (REAL) { try { document.dispatchEvent(new CustomEvent('proshow', { detail: { r: r, el: phones[ROUTES[r][1]] } })); } catch (e) {} }
  }

  function leave(url) {
    D.classList.remove('ready');
    setTimeout(function () { location.replace(url); }, 170);
  }

  function go(r, mode) {
    if (r === 'wn') { openWN(true); return; }
    if (!ROUTES[r]) return;
    if (mode === 'push' && cur !== r) stack.push(cur);
    if (mode === 'tab') stack = [];
    save();
    var pg = ROUTES[r][0];
    if (pg === page && !nativeHost) {
      if (r === cur) return;
      host.classList.add('swap');
      setTimeout(function () {
        history.replaceState(null, '', '#' + r);
        show(r);
        host.classList.remove('swap');
      }, 140);
      return;
    }
    leave(pg + SUF + '.html#' + r);
  }

  window.appBack = function () {
    if (wnEl) { closeWN(); return true; }
    if (cfEl) { window.proConfirm(null); try { JarvisNative.dismissConfirm(); } catch (e) {} return true; }
    if (cur === 'boot') return false;
    if (page === 'tour') { store.set('firstDone', '1'); go('main', 'tab'); return true; }
    if (stack.length) { var r = stack.pop(); save(); go(r, 'replace'); return true; }
    if (cur !== 'main') { go('main', 'tab'); return true; }
    return false;
  };
  window.appState = function () { return { page: page, cur: cur, stack: stack.slice() }; };
  window.appTourDone = function () { store.set('firstDone', '1'); setTimeout(function () { go('main', 'tab'); }, 250); };

  // пауза, когда приложение свёрнуто
  var paused = [];
  window.appPause = function (p) {
    if (p) { paused = [].slice.call(document.querySelectorAll('video')).filter(function (v) { return !v.paused; }); paused.forEach(function (v) { v.pause(); }); }
    else {
      paused.forEach(function (v) { v.play().catch(function () {}); }); paused = [];
      if (store.get('auto') === '1' && autoTheme() !== THEME) setTimeout(function () { setTheme(autoTheme(), null, true); }, 400);
    }
  };

  // Джарвис «впитывает» новый цвет: волна от пальца, в центре — Джарвис нового цвета, потом экран перекрашен
  var thBusy = false;
  function thxEl(t, x, y, cls) {
    var o = document.createElement('div');
    o.id = 'thx'; o.className = 'appui' + (cls ? ' ' + cls : '');
    o.style.setProperty('--tc', TH[t].c);
    o.style.setProperty('--x', Math.round(x) + 'px'); o.style.setProperty('--y', Math.round(y) + 'px');
    o.innerHTML = '<div class="thx-fill"></div><div class="thx-ring"></div><div class="thx-core"><video muted loop playsinline autoplay src="' + spr(t) + '"></video></div>' +
      '<div class="thx-t"><small>ТЕМА</small><b>' + TH[t].n + '</b><span>' + TH[t].p + '</span></div><div class="thx-scan"></div>';
    document.body.appendChild(o);
    var v = o.querySelector('video'); v.muted = true; v.play().catch(function () {});
    return o;
  }
  function setTheme(t, ev, keepAuto) {
    if (!TH[t] || thBusy) return;
    if (!keepAuto) store.set('auto', '');
    store.set('theme', t);
    if (store.get('icon') === '1') store.set('iconWant', t);
    if (t === THEME) return;
    thBusy = true;
    var x = ev && ev.clientX != null ? ev.clientX : innerWidth / 2, y = ev && ev.clientY != null ? ev.clientY : innerHeight / 2;
    var o = thxEl(t, x, y);
    requestAnimationFrame(function () { requestAnimationFrame(function () { o.classList.add('grow'); }); });
    ss.set('thx', t);
    setTimeout(function () { location.replace(page + sufOf(t) + '.html#' + cur); }, 1300);
  }
  window.appPickTheme = function (t, ev) { setTheme(t, ev); };
  window.appSetTheme = setTheme;
  function thxReveal() {
    if (ss.get('thx') !== THEME) return;
    ss.set('thx', '');
    var o = thxEl(THEME, innerWidth / 2, innerHeight / 2, 'full');
    setTimeout(function () { o.classList.add('out'); }, 650);
    setTimeout(function () { o.remove(); }, 1900);
  }

  function toast(t) {
    var el = document.createElement('div');
    el.className = 'app-toast appui';
    el.textContent = t;
    document.body.appendChild(el);
    requestAnimationFrame(function () { el.classList.add('on'); });
    setTimeout(function () { el.classList.remove('on'); setTimeout(function () { el.remove(); }, 300); }, 2200);
  }

  // ---------- нажатия: вкладки, меню, кнопки ----------
  function stop(e) { e.stopPropagation(); e.preventDefault(); }
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t || !t.closest || t.closest('#wn') || t.closest('.appui')) return;
    if (page === 'tour') return;
    var it = t.closest('.navw .it');
    if (it) {
      var r = TABS[it.textContent.trim()];
      if (r) { stop(e); if (r !== cur) go(r, 'tab'); }
      return;
    }
    if (t.closest('.navw .mic')) {
      if (REAL) {
        stop(e);
        if (cur === 'plans' || cur === 'week') { go('newtask', 'push'); return; }
        if (cur === 'newtask') return;
        if (page === 'main' || cur === 'dialog') { JarvisNative.mic(); return; }
        ss.set('talk', '1'); go('main', 'tab'); return;
      }
      if (page === 'main') return;
      stop(e);
      if (cur === 'plans' || cur === 'week') { go('newtask', 'push'); return; }
      if (cur === 'newtask') return;
      ss.set('talk', '1'); go('main', 'tab'); return;
    }
    var mi = t.closest('.mi[data-t]');
    if (mi && mi.dataset.real) { stop(e); JarvisNative.open(mi.dataset.real); return; }
    if (mi && MENU[mi.dataset.t]) { stop(e); go(MENU[mi.dataset.t], 'push'); return; }
    if (REAL && cur === 'settings') {
      var row = t.closest('.row3');
      if (row && !row.querySelector('.sw')) {
        var g = row.closest('.grp'), h = g && g.previousElementSibling, sec = h ? h.textContent.trim().toUpperCase() : '';
        var map = { 'ГОЛОС И СЛУХ': 'Голос и слух', 'УМ': 'ИИ и модели', 'ПРИВАТНОСТЬ': 'Память и безопасность' };
        if (map[sec]) { stop(e); JarvisNative.open('section:' + map[sec]); return; }
      }
    }
    if (cur === 'boot' && t.closest('.btnP,.btnS')) { stop(e); go('tour', 'tab'); return; }
    if (cur === 'buy' && t.closest('.btnP')) {
      stop(e); toast('Это демо: пробные 7 дней включены, оплаты нет');
      setTimeout(function () { window.appBack(); }, 1500); return;
    }
    if (cur === 'settings') {
      for (var el = t, n = 0; el && n < 5; el = el.parentElement, n++) {
        if (el.textContent.indexOf('Подписка') >= 0 && el.textContent.length < 90) { stop(e); go('buy', 'push'); return; }
      }
    }
  }, true);

  var lpT = null, lpX = 0, lpY = 0, lpDone = false;
  document.addEventListener('pointerdown', function (e) {
    if (page !== 'main' || wnEl || thBusy) return;
    var cb = document.getElementById('coreBox'); if (!cb) return;
    var b = cb.getBoundingClientRect(), cx = b.left + b.width / 2, cy = b.top + b.height / 2, rr = Math.min(b.width, b.height) * 0.42;
    if (Math.hypot(e.clientX - cx, e.clientY - cy) > rr) return;
    lpX = e.clientX; lpY = e.clientY; clearTimeout(lpT);
    lpT = setTimeout(function () { lpT = null; lpDone = true; setTheme(TORDER[(TORDER.indexOf(THEME) + 1) % TORDER.length], { clientX: lpX, clientY: lpY }); }, 650);
  }, true);
  document.addEventListener('pointermove', function (e) { if (lpT && Math.abs(e.clientX - lpX) + Math.abs(e.clientY - lpY) > 14) { clearTimeout(lpT); lpT = null; } }, true);
  ['pointerup', 'pointercancel'].forEach(function (n) { document.addEventListener(n, function () { clearTimeout(lpT); lpT = null; }, true); });
  document.addEventListener('contextmenu', function (e) { if (page === 'main') e.preventDefault(); });

  // ---------- доработки экранов под приложение ----------
  var ICON = {
    bell: '<svg viewBox="0 0 24 24"><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"></path><path d="M10 20a2 2 0 0 0 4 0"></path></svg>',
    grid: '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="7" height="7" rx="2"></rect><rect x="13" y="4" width="7" height="7" rx="2"></rect><rect x="4" y="13" width="7" height="7" rx="2"></rect><circle cx="16.5" cy="16.5" r="3.5"></circle></svg>',
    sun: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"></circle><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4"></path></svg>',
    flag: '<svg viewBox="0 0 24 24"><path d="M6 21V4M6 4h11l-2 4 2 4H6"></path></svg>',
    reply: '<svg viewBox="0 0 24 24"><path d="M4 5h16v11H9l-5 4z"></path><path d="M9 10h6"></path></svg>',
    chain: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="6" height="5" rx="1.5"></rect><rect x="15" y="10" width="6" height="5" rx="1.5"></rect><rect x="3" y="16" width="6" height="5" rx="1.5"></rect><path d="M9 6.5h3v12H9M12 12.5h3"></path></svg>',
    people: '<svg viewBox="0 0 24 24"><circle cx="9" cy="8" r="3.2"></circle><path d="M3.5 19c.8-3.3 3-5 5.5-5s4.7 1.7 5.5 5"></path><circle cx="17" cy="9" r="2.4"></circle><path d="M16 14c2.3.2 3.9 1.7 4.5 4.5"></path></svg>',
    mode: '<svg viewBox="0 0 24 24"><path d="M4 11 12 4l8 7v9H4z"></path><path d="M10 20v-5h4v5"></path></svg>',
    tag: '<svg viewBox="0 0 24 24"><path d="M3 12V4h8l10 10-8 8z"></path><circle cx="7.5" cy="8.5" r="1.4"></circle></svg>',
    doc: '<svg viewBox="0 0 24 24"><path d="M6 3h9l4 4v14H6z"></path><path d="M14 3v5h5M9 13h7M9 17h5"></path></svg>',
    note: '<svg viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3"></rect><path d="M5 11a7 7 0 0 0 14 0M12 18v3"></path></svg>',
    coin: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"></circle><path d="M10 7h3.5a2.5 2.5 0 0 1 0 5H10V7zM10 12v5M8.5 15h5"></path></svg>',
    core: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="6"></circle><ellipse cx="12" cy="12" rx="10" ry="3.2"></ellipse></svg>',
    watch: '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="4"></rect><path d="M9 6V3h6v3M9 18v3h6v-3M12 9.5V12l1.8 1.2"></path></svg>',
    star: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.4 5.3 5.6.6-4.2 3.8 1.2 5.6L12 16l-5 2.8 1.2-5.6L4 9.4l5.6-.6z"></path></svg>'
  };
  var decorated = {};
  function decorate(r) {
    if (decorated[r]) return;
    decorated[r] = 1;
    if (r === 'more') {
      var menu = document.querySelector('#more .menu');
      var over = menu && menu.querySelector('[data-t=m_over]');
      if (menu && over) {
        var mk = function (t, ic, b, s, em) {
          var d = document.createElement('div');
          d.className = 'mi'; d.dataset.t = t;
          d.innerHTML = ic + '<div><b>' + b + '</b><span>' + s + '</span></div>' + (em ? '<em>' + em + '</em>' : '');
          return d;
        };
        var have = {};
        [].slice.call(menu.querySelectorAll('.mi')).forEach(function (m) { have[m.dataset.t] = m; });
        var add = [
          ['m_brief', ICON.sun, 'Брифинг', 'Утром и вечером — коротко о дне', 'НОВОЕ'],
          ['m_imp', ICON.flag, 'Важное', 'Из 34 уведомлений — 5 нужных', 'НОВОЕ'],
          ['m_mod', ICON.mode, 'Режимы', 'Дом, машина, зал, работа, сон', 'НОВОЕ'],
          ['m_reply', ICON.reply, 'Ответит Джарвис', 'Ответы на сообщения за тебя', 'НОВОЕ'],
          ['m_chain', ICON.chain, 'Цепочки дел', 'Одна фраза — несколько дел', 'НОВОЕ'],
          ['m_prc', ICON.tag, 'Слежу за ценами', 'Скажу, когда подешевеет', 'НОВОЕ'],
          ['m_gos', ICON.doc, 'Госуслуги', 'Штрафы, налоги, записи, документы', 'НОВОЕ'],
          ['m_ppl', ICON.people, 'Люди', 'Дни рождения, договорённости', 'НОВОЕ'],
          ['m_nts', ICON.note, 'Заметки голосом', '«Запомни…» — и потом спроси', 'НОВОЕ'],
          ['m_spd', ICON.coin, 'Расходы', 'Траты по разделам и подписки', 'НОВОЕ'],
          ['m_thm', ICON.core, 'Тема', 'Золото, лазурь, аметист, рубин, изумруд', '1.3'],
          ['m_dev', ICON.watch, 'Часы и машина', 'Wear OS и Android Auto', 'НОВОЕ'],
          ['m_rem', ICON.bell, 'Напоминания', 'Виджеты, уведомления, плашка снизу', 'ПРИМЕР'],
          ['m_appw', ICON.grid, 'Виджеты приложений', 'MAX, Ozon, Госуслуги, банки, такси', 'ПРИМЕР'],
          ['m_wn', ICON.star, 'Что нового', 'Версия ' + VERSION + ' — что появилось', '']
        ];
        add.forEach(function (x) { have[x[0]] = mk(x[0], x[1], x[2], x[3], x[4]); });
        var realItems = [];
        if (REAL) {
          [['n_voice', ICON.note, 'Голос и слух', 'Слово «Джарвис», распознавание, голос', 'section:Голос и слух'],
           ['n_ai', ICON.core, 'ИИ и модели', 'Облако, ключи, локальные модели', 'section:ИИ и модели'],
           ['n_char', ICON.people, 'Характер и ответы', 'Личность, скорость, уверенность', 'section:Характер и ответы'],
           ['n_live', ICON.reply, 'Live-разговор', 'Говорить без слова «Джарвис»', 'live'],
           ['n_mem', ICON.doc, 'Настоящая память', 'Факты, которые Джарвис запомнил', 'screen:memory'],
           ['n_cmd', ICON.chain, 'Мои команды', 'Своя фраза — цепочка действий', 'section:Мои команды'],
           ['n_skill', ICON.grid, 'Навыки', 'Что Джарвис умеет на телефоне', 'screen:skills'],
           ['n_rout', ICON.sun, 'Рутины', 'Сценарии и дела по расписанию', 'screen:routines'],
           ['n_apps', ICON.grid, 'Приложения', 'Синонимы для открытия голосом', 'section:Приложения'],
           ['n_pres', ICON.bell, 'Присутствие', 'Подсказки, объявления, тихие часы', 'section:Присутствие'],
           ['n_safe', ICON.flag, 'Память и безопасность', 'Подтверждения, данные', 'section:Память и безопасность'],
           ['n_sys', ICON.mode, 'Система', 'Службы, нагрузка, состояние', 'screen:status'],
           ['n_perm', ICON.star, 'Разрешения', 'Микрофон, уведомления, спец. возможности', 'setup'],
           ['n_help', ICON.watch, 'Помощь и диагностика', 'Консоль, отчёты об ошибках', 'section:Помощь и диагностика']
          ].forEach(function (x) { var d = mk(x[0], x[1], x[2], x[3], ''); d.dataset.real = x[4]; have[x[0]] = d; realItems.push(x[0]); });
        }
        if (have.m_voice) { have.m_voice.querySelector('b').textContent = 'Голос'; have.m_voice.querySelector('span').textContent = 'Голоса, персонажи, свой голос'; var e0 = have.m_voice.querySelector('em'); if (e0) e0.textContent = 'НОВОЕ'; else have.m_voice.insertAdjacentHTML('beforeend', '<em>НОВОЕ</em>'); }
        var SECT = (REAL ? [['НАСТОЯЩИЙ ДЖАРВИС', realItems]] : []).concat([['ДЕНЬ', ['m_plans', 'm_brief', 'm_imp', 'm_mod']],
          ['ДЕЛАЕТ ЗА ТЕБЯ', ['m_reply', 'm_chain', 'm_prc', 'm_gos']],
          ['ПОМНИТ', ['m_mem', 'm_ppl', 'm_nts', 'm_spd', 'm_scen']],
          ['ДЖАРВИС', ['m_voice', 'm_thm', 'm_over', 'm_dev', 'm_tour', 'm_wn', 'm_set']],
          ['ПРИМЕРЫ ВНЕ ПРИЛОЖЕНИЯ', ['m_rem', 'm_appw']]]);
        menu.textContent = '';
        SECT.forEach(function (sec) {
          var h = document.createElement('div'); h.className = 'app-sec'; h.textContent = sec[0]; menu.appendChild(h);
          sec[1].forEach(function (t) { if (have[t]) menu.appendChild(have[t]); });
        });
        menu.classList.add('app-scroll');
      }
    }
    if (r === 'settings') {
      [].slice.call(document.querySelectorAll('.row3')).forEach(function (row) {
        var sw = row.querySelector('.sw');
        if (!sw) return;
        sw.innerHTML = TORDER.map(function (k) {
          return '<i data-th="' + k + '"' + (k === THEME ? ' class="on"' : '') + ' style="background:radial-gradient(circle at 40% 35%,#fff 0,' + TH[k].c + ' 38%,#000 110%)"></i>';
        }).join('');
        sw.addEventListener('click', function (e) { var i = e.target.closest('[data-th]'); if (!i) return; e.stopPropagation(); setTheme(i.dataset.th, e); }, true);
      });
    }
    if (r === 'themes') {
      var opt = function (k) { return document.querySelector('#thm .opt [data-k=' + k + '] .tg'); };
      var ta = opt('auto'), ti = opt('icon');
      if (ta) {
        ta.classList.toggle('on', store.get('auto') === '1');
        ta.addEventListener('click', function (e) {
          setTimeout(function () {
            var on = ta.classList.contains('on');
            store.set('auto', on ? '1' : '');
            if (!on) { store.set('theme', THEME); toast('Хамелеон выключен — цвет останется ' + TH[THEME].n.toLowerCase()); return; }
            var a = autoTheme();
            if (a === THEME) toast('Хамелеон включён. Сейчас как раз ' + TH[a].n.toLowerCase());
            else setTheme(a, e, true);
          }, 0);
        });
      }
      if (ti) {
        ti.classList.toggle('on', store.get('icon') === '1');
        ti.addEventListener('click', function () {
          setTimeout(function () {
            var on = ti.classList.contains('on');
            store.set('icon', on ? '1' : ''); iconSync();
            toast(on ? 'Значок станет «' + TH[THEME].n + '», когда выйдешь из Джарвиса' : 'Значок снова золотой — после выхода');
          }, 0);
        });
      }
    }
    if (r === 'plans') {
      var tab = document.querySelector('#pDay #dTab');
      if (tab) {
        var w = document.createElement('span');
        w.textContent = 'Неделя';
        w.onclick = function (e) { e.stopPropagation(); go('week', 'push'); };
        tab.appendChild(w);
      }
    }
  }

  // стрелки и подпись на экранах-примерах
  var exEls = [], exT = null, exList = null, exI = 0;
  function exUI(r) {
    exEls.forEach(function (e) { e.remove(); }); exEls = []; clearTimeout(exT); exList = null;
    for (var g = 0; g < EXG.length; g++) if (EXG[g].indexOf(r) >= 0) { exList = EXG[g]; exI = EXG[g].indexOf(r); }
    if (!exList) return;
    var cap = document.createElement('div');
    cap.className = 'ex-cap appui';
    cap.innerHTML = '<small>ПРИМЕР' + (exList.length > 1 ? ' · ' + (exI + 1) + ' / ' + exList.length : '') + '</small><b>' + EXCAP[r] + '</b><span>' +
      (exList.length > 1 ? 'Листай стрелками или свайпом · ' : '') + '«Назад» — вернуться в Джарвис</span>';
    document.body.appendChild(cap); exEls.push(cap);
    exT = setTimeout(function () { exEls.forEach(function (e) { e.classList.add('off'); }); }, 2600);
    if (exList.length > 1) {
      [['p', '‹', -1], ['n', '›', 1]].forEach(function (a) {
        var b = document.createElement('button');
        b.className = 'ex-a appui ' + a[0]; b.textContent = a[1];
        b.onclick = function (e) { e.stopPropagation(); exStep(a[2]); };
        document.body.appendChild(b); exEls.push(b);
      });
    }
  }
  function exStep(d) {
    if (!exList || exList.length < 2) return;
    var n = exList[(exI + d + exList.length) % exList.length];
    go(n, 'replace');
  }
  var tx = 0, ty = 0;
  document.addEventListener('touchstart', function (e) { var p = e.touches[0]; tx = p.clientX; ty = p.clientY; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    if (!exList || wnEl) return;
    var p = e.changedTouches[0], dx = p.clientX - tx, dy = p.clientY - ty;
    if (Math.abs(dx) > 70 && Math.abs(dy) < 50) exStep(dx < 0 ? 1 : -1);
  }, { passive: true });

  // ---------- «Что нового» ----------
  var SVG = {
    core: '<circle cx="12" cy="12" r="6"></circle><ellipse cx="12" cy="12" rx="10" ry="3.2"></ellipse>',
    clock: '<circle cx="12" cy="12" r="8.5"></circle><path d="M12 7v5l3.5 2"></path>',
    app: '<rect x="4" y="4" width="16" height="16" rx="5"></rect><circle cx="12" cy="12" r="3.5"></circle>',
    wave: '<path d="M3 12c2.5-5 5.5-5 8 0s5.5 5 8 0"></path><path d="M3 17c2.5-3 5.5-3 8 0s5.5 3 8 0" opacity=".5"></path>',
    hand: '<path d="M9 11V5.5a1.5 1.5 0 0 1 3 0V11M12 10V4.5a1.5 1.5 0 0 1 3 0V11M15 10.5V6.5a1.5 1.5 0 0 1 3 0v7a6.5 6.5 0 0 1-6.5 6.5h-.6a6 6 0 0 1-4.6-2.2L4 15a1.5 1.5 0 0 1 2.3-1.9L9 15V8.5"></path>',
    info: '<circle cx="12" cy="12" r="8.5"></circle><path d="M12 11v5M12 8h.01"></path>'
  };
  var WN = [
    [TH.az.c, SVG.core, 'Пять цветов Джарвиса', 'Золото, лазурь, аметист, рубин, изумруд. Перекрашивается всё: ядро и кольца, кнопки, фоны, виджеты, уведомления.', 'themes'],
    [TH.em.c, SVG.clock, 'Хамелеон', 'Цвет меняется сам по времени суток: утро — изумруд, день — лазурь, вечер — золото, ночь — аметист.', 'themes'],
    [TH.am.c, SVG.app, 'Значок в цвет', 'Иконка на рабочем столе тоже перекрашивается под тему. Включается в «Теме».', 'themes'],
    [TH.ru.c, SVG.wave, 'Смена цвета волной', 'Джарвис впитывает новый цвет — волна проходит по всему экрану.', ''],
    [TH.gold.c, SVG.hand, 'Секрет', 'На главной удержи палец на Джарвисе — он сменит цвет.', ''],
    ['#6A7977', SVG.info, 'И ещё', 'Голоса, режимы, цены, госуслуги и расходы — из версии 1.2. Всё в «Ещё».', '']
  ];
  var wnEl = null, wnManual = false;
  function openWN(manual) {
    if (wnEl) return;
    wnManual = manual;
    var items = WN.map(function (w, i) {
      return '<div class="it' + (i === WN.length - 1 ? ' demo' : '') + '" data-go="' + (w[4] || '') + '"><div class="ic" style="color:' + w[0] + '"><svg viewBox="0 0 24 24">' + w[1] +
        '</svg></div><div><b>' + w[2] + '</b><span>' + w[3] + '</span></div></div>';
    }).join('');
    wnEl = document.createElement('div');
    wnEl.id = 'wn';
    var sw = '<div class="wn-sw">' + TORDER.map(function (k) {
      return '<button data-th="' + k + '"' + (k === THEME ? ' class="on"' : '') + ' style="--c:' + TH[k].c + '"><i></i><span>' + TH[k].n + '</span></button>';
    }).join('') + '</div>';
    wnEl.innerHTML = '<div class="wn-bg"></div><div class="wn-card"><div class="wn-j"><video muted loop playsinline autoplay src="' + SPRITE + '"></video></div>' +
      '<div class="wn-h"><div class="wn-k">ЧТО НОВОГО · ВЕРСИЯ ' + VERSION + '</div><h2>Джарвис твоего цвета</h2>' +
      '<p class="wn-lead">Выбери цвет прямо здесь — перекрасится всё. Или дочитай, и окно исчезнет.</p>' + sw + '</div><div class="wn-bar"><i></i></div>' +
      '<div class="wn-list">' + items + '</div><div class="wn-f"><button class="wn-ok">Понятно</button>' +
      '<small>БОЛЬШЕ НЕ ПОКАЖУ · СНОВА: ЕЩЁ → ЧТО НОВОГО</small></div></div>';
    document.body.appendChild(wnEl);
    var v = wnEl.querySelector('video'); v.muted = true; v.play().catch(function () {});
    var list = wnEl.querySelector('.wn-list'), bar = wnEl.querySelector('.wn-bar i'), ok = wnEl.querySelector('.wn-ok');
    var its = [].slice.call(wnEl.querySelectorAll('.it')), shown = 0;
    requestAnimationFrame(function () { requestAnimationFrame(function () { wnEl && wnEl.classList.add('on'); }); });
    its.forEach(function (el, i) { setTimeout(function () { el.classList.add('in'); shown = i + 1; prog(); }, 420 + i * 150); });
    function prog() {
      if (!wnEl) return;
      var sc = list.scrollHeight - list.clientHeight;
      var bySc = sc <= 4 ? 1 : list.scrollTop / sc;
      var p = Math.min(shown / its.length, bySc);
      bar.style.width = (p * 100).toFixed(1) + '%';
      if (p > .98) ok.classList.add('lit');
    }
    list.addEventListener('scroll', prog, { passive: true });
    ok.onclick = function (e) { e.stopPropagation(); closeWN(); };
    its.forEach(function (el) { if (el.dataset.go) el.onclick = function (e) { e.stopPropagation(); var r = el.dataset.go; closeWN(); setTimeout(function () { go(r, 'push'); }, 560); }; });
    wnEl.querySelector('.wn-bg').onclick = function () { closeWN(); };
    [].slice.call(wnEl.querySelectorAll('.wn-sw button')).forEach(function (b) {
      b.onclick = function (e) {
        e.stopPropagation();
        store.set('wn', VERSION);
        if (b.dataset.th === THEME) { store.set('auto', ''); store.set('theme', THEME); closeWN(); toast(TH[THEME].p); return; }
        setTheme(b.dataset.th, e);
      };
    });
    holo(wnEl.querySelector('.wn-j'));
  }
  // голограмма в окне по кругу показывает Джарвиса всех цветов
  var holoT = null;
  function holo(wj) {
    clearInterval(holoT);
    var vs = [wj.querySelector('video')], i = TORDER.indexOf(THEME), front = 0;
    var b = document.createElement('video'); b.muted = true; b.loop = true; b.playsInline = true; b.style.opacity = 0; wj.appendChild(b); vs.push(b);
    holoT = setInterval(function () {
      if (!wnEl || !wj.isConnected) { clearInterval(holoT); return; }
      i = (i + 1) % TORDER.length;
      var nx = vs[1 - front], cu = vs[front];
      nx.onloadeddata = function () {
        nx.onloadeddata = null;
        try { nx.currentTime = cu.currentTime % (nx.duration || 8); } catch (e) {}
        nx.play().catch(function () {});
        nx.style.opacity = 1; cu.style.opacity = 0; front = 1 - front;
        setTimeout(function () { cu.pause(); }, 800);
      };
      nx.src = spr(TORDER[i]);
    }, 2300);
  }
  function closeWN() {
    if (!wnEl || wnEl.classList.contains('out')) return;
    var el = wnEl;
    store.set('wn', VERSION);
    el.classList.add('out');
    setTimeout(function () {
      el.remove(); if (wnEl === el) wnEl = null; clearInterval(holoT);
      // на первом запуске ядро зажигается заново — уже после «Что нового»
      if (!wnManual && cur === 'boot' && window.bootRun) window.bootRun();
    }, 620);
  }

  // ================= настоящий Джарвис =================
  var rState = 'OFFLINE', rPartial = '', rReqAt = 0, rLat = 0, rAnswer = '';
  var RS = { IDLE: 'idle', READY: 'idle', LISTENING: 'listen', THINKING: 'think', EXECUTING: 'think', SPEAKING: 'speak' };
  var RT = { OFFLINE: ['ВЫКЛЮЧЕН', 'Нажми на ядро — Джарвис проснётся'], STARTING: ['ЗАПУСК', 'Загружаю голос и слух…'], ERROR: ['ОШИБКА', 'Голос не запустился — открой «Ещё → Система»'],
    IDLE: ['ГОТОВ', 'Говори или пиши — я пойму'], READY: ['ГОТОВ', 'Говори или пиши — я пойму'], LISTENING: ['СЛУШАЮ', 'Говори, сэр'],
    THINKING: ['ДУМАЮ', 'Секунду…'], EXECUTING: ['ДЕЛАЮ', 'Выполняю'], SPEAKING: ['ГОВОРЮ', ''] };
  function rOn() { return rState !== 'OFFLINE' && rState !== 'ERROR' && rState !== 'STARTING'; }
  function $(id) { return document.getElementById(id); }
  window.proToast = function (t) { toast(t); };
  window.proState = function (st) {
    rState = st;
    if (window.REAL_LAT) setTimeout(clock, 0);
    if (page === 'main' && window.setState) {
      var k = RS[st] || 'idle';
      try { window.setState(k); } catch (e) {}
      var ch = $('chipT'), lb = $('lbl'), tr = $('trans');
      if (!rOn()) { if (ch) ch.textContent = st === 'STARTING' ? 'ЗАПУСК' : 'OFFLINE'; if (lb) lb.textContent = st === 'STARTING' ? 'BOOTING' : 'OFFLINE';
        if (tr) tr.innerHTML = '<span class="dim">' + RT[st][1] + '</span>'; }
      else if (tr) {
        if (k === 'listen') tr.innerHTML = '<span class="dim">' + (rPartial ? '«' + esc(rPartial) + '»' : 'Слушаю…') + '</span>';
        if (k === 'think') tr.innerHTML = '<span class="dim">Думаю…</span>';
        if (k === 'speak' && rAnswer && window.say) window.say(rAnswer, Math.min(6, 1 + rAnswer.length / 22));
        if (k === 'idle' && rAnswer && st !== 'STARTING') tr.innerHTML = '<span class="dim">' + esc(rAnswer.length > 120 ? rAnswer.slice(0, 118) + '…' : rAnswer) + '</span>';
      }
      if (st === 'EXECUTING' && lb) lb.textContent = 'EXECUTING';
      if ($('lat')) $('lat').textContent = rLat ? rLat.toFixed(2) + ' s' : '—';
    }
    if (cur === 'dialog') dlgState();
  };
  window.proEvent = function (ev) {
    if (!ev) return;
    if (ev.t === 'partial') { rPartial = ev.text || ''; if (page === 'main' && RS[rState] === 'listen' && $('trans')) $('trans').innerHTML = '<span class="w in">«' + esc(rPartial) + '»</span>'; if (cur === 'dialog') dlgPartial(); }
    if (ev.t === 'request' || ev.t === 'wake') { rReqAt = Date.now(); rAnswer = ''; }
    if (ev.t === 'request') rPartial = '';
    if (ev.t === 'response') {
      rAnswer = ev.text || '';
      if (rReqAt) { rLat = (Date.now() - rReqAt) / 1000; rReqAt = 0; if ($('lat')) $('lat').textContent = rLat.toFixed(2) + ' s'; }
      if (page === 'main' && $('trans') && window.say) window.say(rAnswer, Math.min(6, 1 + rAnswer.length / 22));
    }
    if (ev.t === 'request' || ev.t === 'response' || ev.t === 'history' || ev.t === 'tool') { if (cur === 'dialog') { dlgRender(); dlgState(); } if (page === 'main') { feedRender(); tiles(); } }
  };
  function esc(t) { return String(t).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  // подтверждение опасного действия: снизу, голосом можно ответить так же
  var cfEl = null, cfT = null;
  window.proConfirm = function (msg) {
    clearTimeout(cfT);
    if (cfEl) { cfEl.remove(); cfEl = null; }
    if (msg == null) return;
    cfT = setTimeout(function () { window.proConfirm(null); try { JarvisNative.dismissConfirm(); } catch (e) {} }, 20000);
    cfEl = document.createElement('div');
    cfEl.className = 'appui real-cf';
    cfEl.innerHTML = '<div class="cf-bg"></div><div class="cf-card"><small>ПОДТВЕРДИ, СЭР</small><p>' + esc(msg) + '</p><div class="cf-b"><button class="no">Нет</button><button class="yes">Да</button></div></div>';
    document.body.appendChild(cfEl);
    requestAnimationFrame(function () { cfEl && cfEl.classList.add('on'); });
    cfEl.querySelector('.no').onclick = function (e) { e.stopPropagation(); JarvisNative.answer(false); window.proConfirm(null); };
    cfEl.querySelector('.yes').onclick = function (e) { e.stopPropagation(); JarvisNative.answer(true); window.proConfirm(null); };
  };

  // «Сам сделал сегодня» — настоящая лента
  function feedRender() {
    var box = document.querySelector('.feed'); if (!box) return;
    var list = []; try { list = JSON.parse(JarvisNative.feed() || '[]'); } catch (e) {}
    [].slice.call(box.querySelectorAll('.r')).forEach(function (r) { r.remove(); });
    if (!list.length) { box.insertAdjacentHTML('beforeend', '<div class="r"><b>—</b>Пока ничего. Скажи «Джарвис» или нажми на микрофон<i></i></div>'); return; }
    list.slice(0, 3).forEach(function (x) {
      var st = x.pending ? 'ЖДУ' : x.failed ? 'НЕТ' : 'OK';
      box.insertAdjacentHTML('beforeend', '<div class="r"><b>' + esc(x.at) + '</b>' + esc((x.title + ' · ' + x.detail).slice(0, 46)) + '<i>' + st + '</i></div>');
    });
  }

  // Диалог: настоящая переписка, поле ввода, подсказки
  function dlgRender() {
    var chat = $('dChat'); if (!chat) return;
    var list = []; try { list = JSON.parse(JarvisNative.history() || '[]'); } catch (e) {}
    if (!list.length) { chat.innerHTML = '<div class="msg j"><small>ДЖАРВИС</small>Здравствуйте, сэр. Скажите «Джарвис» или напишите — я пойму.</div>'; return; }
    chat.innerHTML = list.map(function (m) {
      return '<div class="msg ' + (m.me ? 'me' : 'j') + '"><small>' + (m.me ? 'ТЫ' : 'ДЖАРВИС') + ' · ' + esc(m.at) + (m.badge ? ' · ' + esc(m.badge).toUpperCase() : '') + '</small>' + esc(m.text) + '</div>';
    }).join('') + '<div class="msg me real-partial" style="display:none"></div>';
    chat.scrollTop = chat.scrollHeight;
  }
  function dlgPartial() {
    var p = document.querySelector('#dChat .real-partial'); if (!p) return;
    p.style.display = rPartial ? '' : 'none'; p.textContent = rPartial ? rPartial + '…' : '';
    var chat = $('dChat'); if (chat) chat.scrollTop = chat.scrollHeight;
  }
  function dlgState() {
    try { rState = JarvisNative.state() || rState; } catch (e) {}
    var t = RT[rState] || RT.IDLE;
    if ($('dState')) $('dState').textContent = t[0];
    if ($('dHint')) $('dHint').textContent = t[1] || 'Отвечаю';
  }
  function dlgSetup() {
    var inp = document.querySelector('#dlg .inp'); if (!inp) return;
    if (inp.dataset.real) { dlgRender(); dlgState(); return; }
    inp.dataset.real = '1';
    inp.innerHTML = '<input class="real-in" type="text" placeholder="Напиши или скажи «Джарвис»…" enterkeyhint="send"><b class="real-go">↑</b>';
    var field = inp.querySelector('input');
    var go1 = function () { var v = field.value.trim(); if (!v) return; field.value = ''; JarvisNative.send(v); };
    inp.querySelector('.real-go').addEventListener('click', function (e) { e.stopPropagation(); go1(); });
    field.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); go1(); } });
    field.addEventListener('click', function (e) { e.stopPropagation(); });
    var chips = $('dChips');
    if (chips) {
      var fresh = chips.cloneNode(true); chips.parentNode.replaceChild(fresh, chips);
      fresh.addEventListener('click', function (e) { var c = e.target.closest('span'); if (!c) return; e.stopPropagation(); JarvisNative.send(c.textContent.trim()); });
    }
    dlgRender(); dlgState();
  }

  // настоящее время, дата и заряд на всех экранах
  var DAYS = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'], MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  var batt = null;
  function clock() {
    var d = new Date(), hm = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    [].slice.call(document.querySelectorAll('.sb')).forEach(function (sb) {
      var a = sb.firstElementChild; if (a && /^\d\d:\d\d$/.test(a.textContent.trim())) a.textContent = hm;
      var r = sb.querySelector('.r'); if (r && batt != null) { var n = r.lastChild; if (n && n.nodeType === 3 && /%/.test(n.textContent)) n.textContent = ' ' + batt + '%'; }
    });
    if (page === 'main') {
      var h = d.getHours(), t = document.querySelector('.hello .t'), sbt = document.querySelector('.hello .s');
      if (t) t.textContent = h < 5 ? 'Доброй ночи' : h < 12 ? 'Доброе утро' : h < 18 ? 'Добрый день' : 'Добрый вечер';
      if (sbt) sbt.textContent = DAYS[d.getDay()] + ', ' + d.getDate() + ' ' + MON[d.getMonth()] + ' · ' + (rOn() ? 'Джарвис слушает' : 'Джарвис выключен');
    }
  }
  // плитки главной: режим ИИ, сделано сегодня, сообщений в переписке
  function tiles() {
    var inf = {}; try { inf = JSON.parse(JarvisNative.info() || '{}'); } catch (e) {}
    var R = { HYBRID: 'ГИБРИД', LOCAL: 'ЛОКАЛЬНО', CLOUD: 'ОБЛАКО' };
    var d1 = document.querySelector('.dat.d1'), d3 = document.querySelector('.dat.d3'), d4 = document.querySelector('.dat.d4');
    if (d1) d1.innerHTML = 'РЕЖИМ ИИ<b>' + (R[inf.router] || 'ГИБРИД') + '</b>';
    if (d3) { d3.firstChild.textContent = 'СДЕЛАНО'; var b3 = d3.querySelector('b'); if (b3) b3.textContent = (inf.tools || 0) + ' сегодня'; var dt = d3.querySelector('.dots'); if (dt) dt.style.display = 'none'; }
    if (d4) { d4.firstChild.textContent = 'ДИАЛОГ'; var b4 = d4.querySelector('b'); if (b4) b4.textContent = (inf.msgs || 0).toLocaleString('ru-RU'); }
  }
  function realStart() {
    window.REAL_LAT = true;
    try { JarvisNative.set('curTheme', THEME); } catch (e) {}
    if (navigator.getBattery) navigator.getBattery().then(function (b) { batt = Math.round(b.level * 100); clock(); b.onlevelchange = function () { batt = Math.round(b.level * 100); clock(); }; }).catch(function () {});
    clock(); setInterval(clock, 15000);
    try { rState = JarvisNative.state() || 'OFFLINE'; } catch (e) {}
    if (page === 'main') {
      var mic = $('mic');
      if (mic) mic.onclick = function (e) { e.stopPropagation(); JarvisNative.mic(); };
      // ядро не ловит нажатия (сверху слой экрана) — проверяем попадание по координатам
      document.addEventListener('click', function (e) {
        if (lpDone) { lpDone = false; return; }
        var cb = $('coreBox'); if (!cb || wnEl || e.target.closest('.navw,.bar,.feed,.appui,#wn')) return;
        var b = cb.getBoundingClientRect(), cx = b.left + b.width / 2, cy = b.top + b.height / 2;
        if (Math.hypot(e.clientX - cx, e.clientY - cy) > Math.min(b.width, b.height) * 0.42) return;
        if (rState === 'OFFLINE' || rState === 'ERROR') { JarvisNative.power(true); toast('Включаю Джарвиса…'); }
        else if (rOn()) JarvisNative.mic();
      });
      var chip = document.querySelector('.bar .status');
      if (chip) chip.addEventListener('click', function (e) { e.stopPropagation(); if (rState === 'STARTING') return; if (rOn()) { JarvisNative.power(false); toast('Джарвис выключен. Нажми на ядро, чтобы включить'); } else JarvisNative.power(true); });
      feedRender(); tiles();
      window.proState(rState);
      if (realTalk) setTimeout(function () { JarvisNative.mic(); }, 500);
    }
  }

  function start() {
    if (!nativeHost) {
      host = document.createElement('div');
      host.id = 'appHost';
      document.body.appendChild(host);
      phones = [].slice.call(document.querySelectorAll('.phone'));
      homes = phones.map(function (p) { return p.parentNode; });
      fit();
      addEventListener('resize', fit);
    }
    [].forEach.call(document.querySelectorAll('video'), poster);
    show(cur);
    if (REAL) realStart();
    thxReveal();
    requestAnimationFrame(function () { D.classList.add('ready'); });
    if (!ss.get('launched') && page !== 'tour') {
      ss.set('launched', '1');
      if (store.get('wn') !== VERSION) setTimeout(function () { openWN(false); }, 700);
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
