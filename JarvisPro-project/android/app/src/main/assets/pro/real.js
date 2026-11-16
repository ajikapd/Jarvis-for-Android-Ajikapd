// Настоящие данные телефона для экранов Pro. Работает только в настоящем приложении (есть JarvisNative.q).
// Общая часть: запрос к телефону, плашка «нужно разрешение», запуск по показу экрана.
(function () {
  var R = window.PRO = { on: false, cb: {}, n: 0, binds: {} };
  try { R.on = !!(window.JarvisNative && JarvisNative.q && JarvisNative.real()); } catch (e) {}
  if (!R.on) return;

  // q('notes').then(j => …) — ответ JSON от телефона
  R.q = function (kind, args) {
    return new Promise(function (res) {
      var id = 'q' + (++R.n);
      R.cb[id] = res;
      try { JarvisNative.q(id, kind, JSON.stringify(args || {})); } catch (e) { delete R.cb[id]; res({ err: String(e) }); }
    });
  };
  window.proData = function (id, json) { var f = R.cb[id]; delete R.cb[id]; if (f) f(json || {}); };
  R.send = function (text) { try { JarvisNative.send(text); } catch (e) {} };
  R.toast = function (t) { try { window.proToast ? proToast(t) : 0; } catch (e) {} };

  var NEED = {
    calendar: 'календарю', contacts: 'контактам и звонкам', sms: 'SMS (только чтение — для расходов)',
    notifications: 'уведомлениям', bluetooth: 'Bluetooth-устройствам', dnd: 'режиму «Не беспокоить»', write: 'яркости экрана', location: 'местоположению (для погоды)'
  };
  // Плашка-просьба внутри экрана: el — куда вставить. Возвращает true, если разрешения нет.
  R.need = function (el, j) {
    if (!el || !j || !j.need) return false;
    var w = NEED[j.need] || j.need;
    var b = document.createElement('div');
    b.className = 'real-need';
    b.innerHTML = '<b>Нужен доступ к ' + w + '</b><span>Без него Джарвис не видит эти данные на телефоне.</span><button>Разрешить</button>';
    b.querySelector('button').onclick = function (e) { e.stopPropagation(); try { JarvisNative.perm(j.need); } catch (x) {} };
    el.innerHTML = ''; el.appendChild(b);
    return true;
  };
  R.esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); };
  R.time = function (ms) { var d = new Date(ms); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); };
  R.day = function (ms) {
    var d = new Date(ms), t = new Date(); t.setHours(0, 0, 0, 0);
    var k = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - t) / 864e5);
    if (k === 0) return 'Сегодня'; if (k === 1) return 'Завтра'; if (k === -1) return 'Вчера';
    return d.getDate() + ' ' + ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'][d.getMonth()];
  };
  R.ago = function (ms) {
    var m = Math.round((Date.now() - ms) / 6e4);
    if (m < 1) return 'сейчас'; if (m < 60) return m + ' мин назад'; if (m < 1440) return Math.round(m / 60) + ' ч назад';
    return R.day(ms);
  };
  R.money = function (x) { return Math.round(x).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + ' ₽'; };

  // PRO.bind('notes', fn) — fn(phoneEl) вызывается при каждом показе экрана и после выдачи разрешения
  R.bind = function (route, fn) { R.binds[route] = fn; };
  R.el = null;
  function run(r) {
    var f = R.binds[r]; if (!f) return;
    var el = R.el;
    try { f(el); } catch (e) { try { console.error('real ' + r, e); } catch (x) {} }
  }
  R.cur = null;
  document.addEventListener('proshow', function (e) { R.cur = e.detail.r; R.el = e.detail.el; run(R.cur); });
  window.proPerm = function () { if (R.cur) run(R.cur); };
  R.refresh = function () { if (R.cur) run(R.cur); };
})();

// Настоящие данные: память, сценарии, шар поверх приложений (s13), голоса и устройства (s19), агент (s11).
(function () {
  if (!window.PRO || !PRO.on) return;
  var E = PRO.esc;
  function open(t) { try { JarvisNative.open(t); } catch (e) {} }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function phone(el, id) { return (el && (el.id === id ? el : el.querySelector('#' + id))) || document.getElementById(id); }
  function empty(html) { return '<div class="real-empty">' + html + '</div>'; }
  function ru(n, a, b, c) { var m = n % 10, h = n % 100; return m === 1 && h !== 11 ? a : (m >= 2 && m <= 4 && (h < 12 || h > 14) ? b : c); }

  // ---------- Память ----------
  var CATS = { 'Люди': /люд|people|person|contact|семь|family/i, 'Привычки': /привыч|habit|routine/i, 'Места': /мест|place|addr|адрес|loc/i,
    'Вкусы': /вкус|taste|pref|like|любит/i, 'Договорённости': /догов|promise|agree|обещ|task|дел/i };
  function cat(kind) { for (var c in CATS) if (CATS[c].test(kind || '')) return c; return ''; }
  PRO.bind('memory', function (el) {
    var ph = phone(el, 'mem'), box = $('#facts', ph); if (!box) return;
    PRO.q('memory').then(function (j) {
      var items = (j && j.items) || [], cnt = {};
      $$('.tbar .chip', ph).forEach(function (c) { c.innerHTML = '<i></i>' + items.length + ' ' + ru(items.length, 'ФАКТ', 'ФАКТА', 'ФАКТОВ'); });
      items.forEach(function (f) { var c = cat(f.kind); cnt[c] = (cnt[c] || 0) + 1; });
      $$('.cat', ph).forEach(function (c) { var b = c.querySelector('b'); if (b) b.textContent = cnt[c.dataset.c] || 0; });
      var h = items.map(function (f) {
        var c = cat(f.kind), lab = (c || f.kind || 'ФАКТ') + (f.at ? ' · ' + PRO.day(f.at) : '');
        return '<div class="fact" data-c="' + E(c) + '" data-id="' + E(f.id) + '"><i class="d"></i><div><b>' + E(f.text) + '</b><span>' + E(lab.toUpperCase()) + '</span></div><button>Забыть</button></div>';
      }).join('');
      if (!items.length) h = empty('Пока ничего не помню.<br>Скажи: «Джарвис, запомни, что я пью кофе без сахара».');
      h += '<div class="real-empty" data-all style="cursor:pointer;color:var(--gold,#FFC46B);padding:10px 6px">Все настройки памяти ›</div>';
      box.innerHTML = h;
      $('[data-all]', box).onclick = function () { open('screen:memory'); };
      $$('.fact button', box).forEach(function (b) {
        b.onclick = function (e) {
          e.stopPropagation(); var f = b.closest('.fact'); b.textContent = 'Забываю…'; b.disabled = true;
          PRO.q('memoryDel', { id: f.dataset.id }).then(function (r) {
            if (r && r.ok) { b.textContent = 'Забыл'; f.classList.add('del'); setTimeout(PRO.refresh, 450); }
            else { b.textContent = 'Забыть'; b.disabled = false; PRO.toast('Не получилось забыть'); }
          });
        };
      });
    });
  });

  // ---------- Сценарии («Мои команды») ----------
  PRO.bind('scen', function (el) {
    var ph = phone(el, 'scen'), box = $('.scn', ph); if (!box) return;
    var add = $('.addsc', ph);
    if (add) {
      add.onclick = function () { open('screen:routines'); };
      var sm = add.querySelector('small'); if (sm) sm.textContent = 'Или голосом: «Джарвис, создай команду "Я дома": включи музыку и напомни про ужин»';
    }
    PRO.q('routines').then(function (j) {
      var items = (j && j.items) || [], cc = $('#scCount', ph);
      if (cc) cc.textContent = items.length + ' ' + ru(items.length, 'СЦЕНАРИЙ', 'СЦЕНАРИЯ', 'СЦЕНАРИЕВ');
      if (!items.length) { box.innerHTML = empty('Сценариев пока нет.<br>Нажми «Новый сценарий» ниже или скажи: «Джарвис, создай команду…»'); return; }
      box.innerHTML = items.map(function (r, i) {
        var st = (r.steps || []).map(function (s) { return '<span>' + E(s) + '</span>'; }).join('<i>›</i>');
        return '<div class="sc" data-i="' + i + '"><div class="h"><div><b>' + E(r.name) + '</b><span class="tr">ПО ФРАЗЕ · «' + E(String(r.name).toUpperCase()) + '»</span></div>' +
          '<button class="go">ПУСК</button><button class="go" data-del style="margin-left:6px;opacity:.7">✕</button></div><div class="chain">' + st + '</div></div>';
      }).join('');
      $$('.sc', box).forEach(function (sc) {
        var r = items[+sc.dataset.i];
        sc.querySelector('.go:not([data-del])').onclick = function () {
          if (sc.classList.contains('run')) return; sc.classList.add('run'); PRO.send(r.name);
          var st = $$('.chain span', sc), k = 0;
          (function step() {
            if (k > 0) st[k - 1].className = 'done';
            if (k < st.length) { st[k++].className = 'now'; setTimeout(step, 750); }
            else setTimeout(function () { st.forEach(function (s) { s.className = ''; }); sc.classList.remove('run'); }, 1600);
          })();
        };
        sc.querySelector('[data-del]').onclick = function () {
          var b = this; // первый тап — спросить, второй — удалить (confirm() в WebView ненадёжен)
          if (!b.dataset.sure) { b.dataset.sure = '1'; b.textContent = 'УДАЛИТЬ?'; setTimeout(function () { delete b.dataset.sure; b.textContent = '✕'; }, 3000); return; }
          PRO.q('routineDel', { name: r.name }).then(function (x) { if (x && x.ok) PRO.refresh(); else PRO.toast('Не получилось удалить'); });
        };
      });
    });
  });

  // ---------- Шар поверх приложений ----------
  PRO.bind('overlay', function (el) {
    var ph = phone(el, 'ovr'); if (!ph) return;
    var p = $('.panel', ph); if (!p || p.dataset.real) return; p.dataset.real = '1';
    $('small', p).textContent = 'ДЖАРВИС · ПОВЕРХ ПРИЛОЖЕНИЙ';
    $('.q', p).textContent = 'Шар появляется поверх любых приложений, пока идёт Live-разговор.';
    $('.a', p).textContent = 'Начни Live — и сверни Джарвиса: шар останется на экране, нажми на него, чтобы говорить.';
    var yes = $('#ovYes', ph), no = $('#ovNo', ph);
    yes.textContent = 'Попробовать';
    yes.onclick = function (e) { e.stopPropagation(); open('live'); };
    no.onclick = function (e) { e.stopPropagation(); ph.classList.remove('open'); var pl = $('#ovPill', ph); if (pl) pl.textContent = 'НАЖМИ НА МЕНЯ'; };
  });

  // ---------- Голоса ----------
  PRO.bind('voices', function (el) {
    var ph = phone(el, 'vox'), list = $('#vList', ph); if (!list) return;
    var mk = $('#vMake', ph); if (mk) mk.onclick = function () { open('section:Голос и слух'); };
    PRO.q('voices').then(function (j) {
      var items = (j && j.items) || [];
      if (!items.length) { list.innerHTML = empty('Голоса не найдены.<br>Открой «Настройки → Голос и слух», чтобы скачать голос.'); return; }
      var cur = items.filter(function (v) { return v.on; })[0];
      if (cur) { $('#vChip', ph).textContent = String(cur.name).toUpperCase(); $('#vState', ph).textContent = 'ГОЛОС ВЫБРАН'; $('#vLine', ph).textContent = 'Сейчас я говорю голосом «' + cur.name + '».'; }
      list.innerHTML = items.map(function (v, i) {
        var right = v.ready ? '<div class="wv">' + '<i></i>'.repeat(14) + '</div>' :
          '<button data-dl style="margin-left:auto;padding:6px 12px;border-radius:10px;border:1px solid var(--gold,#FFC46B);background:transparent;color:var(--gold,#FFC46B);font-size:12px">Скачать</button>';
        return '<div class="vc ' + (v.on ? 'on' : '') + '" data-i="' + i + '"><div class="pl">' + (v.on ? '✓' : '▶') + '</div><div><b>' + E(v.name) + '</b><span>' + E(v.about || (v.ready ? '' : 'Нужно скачать')) + '</span></div>' + right + '</div>';
      }).join('');
      $$('.vc', list).forEach(function (c) {
        var v = items[+c.dataset.i];
        c.onclick = function () {
          if (!v.ready) { open('section:Голос и слух'); return; }
          if (v.on) return;
          $('#vState', ph).textContent = 'ПЕРЕКЛЮЧАЮ…';
          PRO.q('voiceSet', { id: v.id }).then(function (r) { if (r && r.msg) PRO.toast(r.msg); PRO.refresh(); });
        };
      });
    });
  });

  // ---------- Устройства ----------
  var KIND = { watch: 'Часы', car: 'Машина', audio: 'Наушники', other: 'Устройство' };
  PRO.bind('devices', function (el) {
    var ph = phone(el, 'dev'), dl = $('.dl', ph); if (!dl) return;
    var chip = $('.tbar .chip', ph);
    function setChip(on) { if (chip) chip.innerHTML = '<i style="background:' + (on ? 'var(--ok)' : 'var(--text2,#889)') + ';box-shadow:' + (on ? '0 0 8px var(--ok)' : 'none') + '"></i>' + (on ? 'ОНЛАЙН' : 'НЕТ СВЯЗИ'); }
    PRO.q('bt').then(function (j) {
      if (PRO.need(dl, j)) { setChip(false); return; }
      if (j && j.on === false) { setChip(false); dl.innerHTML = empty('Bluetooth выключен.<br>Включи его в шторке — и Джарвис увидит часы, машину и наушники.'); return; }
      var items = ((j && j.items) || []).filter(function (d) { return d.kind !== 'other'; });
      items.sort(function (a, b) { return (b.connected ? 1 : 0) - (a.connected ? 1 : 0); });
      setChip(items.some(function (d) { return d.connected; }));
      // иллюстрации: часы и машина показывают настоящее состояние
      var w = items.filter(function (d) { return d.kind === 'watch'; })[0], c = items.filter(function (d) { return d.kind === 'car'; })[0];
      var tm = $('#dW .tm', ph); if (tm) tm.textContent = PRO.time(Date.now());
      var wt = $('#dW .t', ph); if (wt) wt.innerHTML = '<small>ДЖАРВИС</small><b>' + E(w ? w.name : 'Часы') + '</b><span>' + (w ? (w.connected ? 'Подключены' : 'Не подключены') : 'Не сопряжены с телефоном') + '</span>';
      var cp = $('#dC .jp p', ph); if (cp) cp.textContent = c ? (c.name + (c.connected ? ' — подключена.' : ' — не подключена.')) : 'Машина не сопряжена с телефоном.';
      if (!items.length) { dl.innerHTML = empty('Часы, машина и наушники не найдены.<br>Сопряги их с телефоном в настройках Bluetooth.'); return; }
      dl.innerHTML = items.map(function (d) {
        return '<div><div>' + E(d.name || KIND[d.kind]) + '<span>' + E(KIND[d.kind] || '') + '</span></div><span class="tagj' + (d.connected ? ' k' : '') + '">' + (d.connected ? 'ПОДКЛЮЧЕНО' : 'НЕ ПОДКЛЮЧЕНО') + '</span></div>';
      }).join('');
    });
  });

  // ---------- Агент «Сделай сам» ----------
  var TIPS = ['Закажи такси домой', 'Найди в Озоне зарядку USB-C до 1000 ₽', 'Включи в YouTube музыку для работы'];
  PRO.bind('agent', function (el) {
    var ph = phone(el, 'agent'); if (!ph) return;
    var chip = $('#agChip', ph); if (chip) chip.textContent = 'ГОТОВ';
    // гнёзда — пустые, демо-прогресс убираем
    $$('.stage .ov .arc, .stage .ov .wait, .stage .ov .flow', ph).forEach(function (x) { x.style.opacity = 0; });
    $$('.stage .sk', ph).forEach(function (s, i) { s.classList.remove('act'); s.textContent = '0' + (i + 1) + ' · СВОБОДНО'; });
    var ok = $('#okbar', ph); if (ok) ok.style.display = 'none';
    var task = $('.task', ph);
    if (task && !task.dataset.real) {
      task.dataset.real = '1';
      task.innerHTML = '<div class="h"><span><b>ЗАДАЧА</b> · СДЕЛАЙ САМ</span><span>СПЕЦ. ВОЗМОЖНОСТИ</span></div>' +
        '<div class="t" style="font-size:13px;color:var(--text2,#aab)">Напиши, что сделать на телефоне — агент сам нажмёт нужные кнопки. Деньги и сообщения — только после твоего «ок».</div>' +
        '<div style="display:flex;gap:8px;margin-top:10px"><input id="agIn" placeholder="Например: закажи такси домой" style="flex:1;min-width:0;padding:10px 12px;border-radius:12px;border:1px solid rgba(255,196,107,.35);background:rgba(0,0,0,.25);color:var(--text,#eef1f6);font-size:13px">' +
        '<button id="agGo" style="padding:0 14px;border-radius:12px;border:0;background:var(--gold,#FFC46B);color:#1a1206;font-weight:700;font-size:13px">Сделай сам</button></div>';
      var inp = $('#agIn', task);
      var go = function () {
        var t = inp.value.trim(); if (!t) { inp.focus(); return; }
        PRO.send(t); inp.value = ''; if (chip) chip.textContent = '1 / 5 В РАБОТЕ'; PRO.toast('Агент взялся: ' + t);
      };
      $('#agGo', task).onclick = go;
      inp.onkeydown = function (e) { if (e.key === 'Enter') go(); };
    }
    var rows = $('.rows', ph);
    if (rows) {
      rows.innerHTML = TIPS.map(function (t, i) { return '<div class="row2" data-i="' + i + '" style="cursor:pointer"><b>›</b>' + E(t) + '<em>ПУСК</em></div>'; }).join('');
      $$('.row2', rows).forEach(function (r) { r.onclick = function () { var t = TIPS[+r.dataset.i]; PRO.send(t); if (chip) chip.textContent = '1 / 5 В РАБОТЕ'; PRO.toast('Агент взялся: ' + t); }; });
    }
  });
})();

// Планы (s16: plans, week, newtask), напоминания (s14: remind*), виджеты приложений (s15: appw*) — настоящие данные телефона.
(function () {
  if (!window.PRO || !PRO.on) return;
  var E = PRO.esc, T = PRO.time, DAY = 864e5;
  var WD = ['ВС', 'ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ'];
  var WDL = ['Воскресенье', 'Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота'];
  var MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  var HINT = 'Скажи: «Джарвис, напомни в 18:00 позвонить маме»';
  function sod(ms) { var d = new Date(ms); d.setHours(0, 0, 0, 0); return +d; }
  function qs(el, s) { return el ? el.querySelector(s) : null; }
  function g(id) { return document.getElementById(id); }
  function word(n, a, b, c) { var m = n % 10, h = n % 100; return m === 1 && h !== 11 ? a : (m >= 2 && m <= 4 && (h < 10 || h >= 20) ? b : c); }
  function left(ms) {
    var m = Math.max(0, Math.round((ms - Date.now()) / 6e4));
    if (m < 60) return m + ' мин'; var h = Math.floor(m / 60), r = m % 60;
    if (h < 24) return h + ' ч' + (r ? ' ' + r + ' мин' : ''); return Math.round(h / 24) + ' ' + word(Math.round(h / 24), 'день', 'дня', 'дней');
  }
  function dateLine() { var d = new Date(); return WDL[d.getDay()] + ', ' + d.getDate() + ' ' + MON[d.getMonth()]; }
  function arr(x) { return Array.isArray(x) ? x : []; }

  // ---------- общая загрузка ----------
  // reminder → время срабатывания в дне [d0, d0+DAY) (ежедневное — каждый день)
  function remAt(r, d0) {
    if (!r.daily) return r.at >= d0 && r.at < d0 + DAY ? r.at : null;
    var a = new Date(r.at), d = new Date(d0); d.setHours(a.getHours(), a.getMinutes(), 0, 0); return +d;
  }
  function nextAt(r) { // ближайшее срабатывание
    if (!r.daily) return r.at; var t = remAt(r, sod(Date.now())); return t < Date.now() ? t + DAY : t;
  }
  function load(days) {
    return Promise.all([PRO.q('reminders'), PRO.q('calendar', { days: days || 2 }), PRO.q('notes')]).then(function (a) {
      var notes = (a[2] && a[2].lists) || {};
      return { rem: arr(a[0] && a[0].items), cal: arr(a[1] && a[1].items), calNeed: a[1] && a[1].need ? a[1] : null, dela: arr(notes['дела']) };
    });
  }
  // список «ближайшее»: напоминания + события календаря, по времени
  function upcoming(D) {
    var now = Date.now(), out = [];
    D.rem.forEach(function (r) { out.push({ at: nextAt(r), text: r.text, kind: 'rem', id: r.id, daily: r.daily }); });
    D.cal.forEach(function (c) { if (!c.allDay && c.begin >= now - 5 * 6e4) out.push({ at: c.begin, text: c.title, kind: 'cal', loc: c.loc }); });
    out.sort(function (x, y) { return x.at - y.at; });
    return out;
  }
  function done(it) { // выполнено / удалить
    var p = it.kind === 'rem' ? PRO.q('reminderDel', { id: it.id }) : PRO.q('noteDel', { list: 'дела', item: it.text });
    return p.then(function (j) { PRO.toast(j && j.ok !== false ? 'Готово: ' + it.text : 'Не получилось'); PRO.refresh(); });
  }

  // ================= s16 · ДЕНЬ =================
  var dayK = 't';
  PRO.bind('plans', function (ph) {
    var tl = g('tl'); if (!tl) return;
    var tabs = document.querySelectorAll('#dTab span');
    tabs.forEach(function (s) { s.onclick = function () { dayK = s.dataset.d; tabs.forEach(function (x) { x.classList.toggle('on', x === s); }); PRO.refresh(); }; });
    load(2).then(function (D) {
      var now = Date.now(), d0 = sod(now) + (dayK === 'm' ? DAY : 0), items = [];
      D.rem.forEach(function (r) { var t = remAt(r, d0); if (t != null) items.push({ at: t, text: r.text, kind: 'rem', id: r.id, sub: r.daily ? 'Каждый день' : 'Напоминание' }); });
      D.cal.forEach(function (c) {
        if (c.begin < d0 + DAY && (c.end || c.begin) >= d0) items.push({ at: c.allDay ? null : c.begin, text: c.title, kind: 'cal', sub: (c.allDay ? 'Весь день' : T(c.begin) + (c.end ? '–' + T(c.end) : '')) + (c.loc ? ' · ' + c.loc : '') });
      });
      items.sort(function (x, y) { return (x.at || 0) - (y.at || 0); });
      var dela = dayK === 't' ? D.dela.map(function (t) { return { text: String(t), kind: 'note', sub: 'Дело без времени · из списка «дела»' }; }) : [];
      var html = '', nowDone = dayK !== 't', past = 0, next = null;
      items.forEach(function (it, i) {
        var isPast = it.at != null && it.at < now;
        if (!nowDone && !isPast && it.at != null) { html += '<div class="now2"><i>' + T(now) + '</i></div>'; nowDone = true; }
        if (isPast) past++; else if (!next && it.at != null) next = it;
        html += row(it, i, isPast);
      });
      if (!nowDone && items.length) html += '<div class="now2"><i>' + T(now) + '</i></div>';
      dela.forEach(function (it, i) { html += row(it, 'n' + i, false); });
      var all = items.concat(dela);
      if (!all.length) html += '<div class="real-empty">' + (dayK === 't' ? 'На сегодня дел нет.' : 'На завтра дел нет.') + '<br>' + HINT + '</div>';
      tl.innerHTML = '<div id="plNeed"></div>' + html;
      if (D.calNeed) PRO.need(g('plNeed'), D.calNeed);
      tl.querySelectorAll('[data-k]').forEach(function (el) {
        var it = el.dataset.k[0] === 'n' ? dela[+el.dataset.k.slice(1)] : items[+el.dataset.k];
        if (!it || it.kind === 'cal') return;
        el.onclick = function (e) {
          if (e.target.tagName === 'BUTTON') return;
          var f = el.querySelector('.fix'); if (f) { f.remove(); return; }
          f = document.createElement('div'); f.className = 'fix';
          f.innerHTML = '<button class="g">Выполнено</button><button>Удалить</button>';
          f.querySelectorAll('button').forEach(function (b) { b.onclick = function () { b.disabled = true; done(it); }; });
          el.appendChild(f);
        };
      });
      var n = all.length - past;
      g('sumB').textContent = n ? n + ' ' + word(n, 'дело', 'дела', 'дел') + (dayK === 't' ? ' впереди' : ' завтра') : (dayK === 't' ? 'Дел нет' : 'Завтра свободно');
      g('sumS').textContent = next ? 'Следующее — ' + T(next.at) + (dayK === 't' ? ' · через ' + left(next.at) : '') + (past ? ' · ' + past + ' прошло' : '') : (all.length ? 'Без точного времени' : 'Свободный день');
      g('sumE').textContent = 'НАПОМИНАНИЙ ' + items.filter(function (x) { return x.kind === 'rem'; }).length + ' · КАЛЕНДАРЬ ' + items.filter(function (x) { return x.kind === 'cal'; }).length + ' · ДЕЛ ' + dela.length;
      var chip = qs(ph, '.tbar .chip'); if (chip) chip.innerHTML = '<i></i>' + (dayK === 't' ? 'СЕГОДНЯ' : 'ЗАВТРА');
    });
    function row(it, k, isPast) {
      var cls = (it.kind === 'cal' ? '' : 'j') + (isPast ? ' done' : '');
      var tag = it.kind === 'cal' ? 'КАЛЕНДАРЬ' : it.kind === 'rem' ? 'НАПОМНЮ' : 'ДЕЛО';
      return '<div class="ev ' + cls + '" data-k="' + k + '"><span class="t">' + (it.at != null ? T(it.at) : '•') + '</span><b>' + E(it.text) + '</b><span>' + E(it.sub) +
        (it.kind !== 'cal' ? ' · нажми, чтобы отметить' : '') + '</span><i class="tag">' + tag + '</i></div>';
    }
  });

  // ================= s16 · НЕДЕЛЯ =================
  PRO.bind('week', function (ph) {
    if (typeof W === 'undefined' || !g('wk')) return;
    load(7).then(function (D) {
      var d0 = sod(Date.now()), total = 0, remN = 0, freeEv = 0;
      W.length = 0;
      for (var i = 0; i < 7; i++) {
        var a = d0 + i * DAY, list = [];
        D.rem.forEach(function (r) { var t = remAt(r, a); if (t != null) list.push([t, r.text, 1]); });
        D.cal.forEach(function (c) { if (c.begin < a + DAY && (c.end || c.begin) >= a) list.push([c.allDay ? a : c.begin, c.title + (c.allDay ? ' · весь день' : ''), 0]); });
        list.sort(function (x, y) { return x[0] - y[0]; });
        total += list.length; remN += list.filter(function (x) { return x[2]; }).length;
        if (!list.some(function (x) { return new Date(x[0]).getHours() >= 18; })) freeEv++;
        var dd = new Date(a);
        W.push([WD[dd.getDay()], dd.getDate(), Math.min(100, list.length * 17), list.map(function (x) { return [T(x[0]), E(x[1]), x[2]]; })]);
      }
      wSel = 0; drawWeek();
      if (D.calNeed) { var nd = document.createElement('div'); g('dl').prepend(nd); PRO.need(nd, D.calNeed); }
      var e = new Date(d0 + 6 * DAY), s = new Date(d0);
      var chip = qs(ph, '.tbar .chip'); if (chip) chip.innerHTML = '<i></i>' + s.getDate() + '–' + e.getDate() + ' ' + MON[e.getMonth()].slice(0, 3).toUpperCase();
      var st = ph.querySelectorAll('.stat b'), sl = ph.querySelectorAll('.stat span');
      if (st.length === 3) {
        st[0].textContent = total; sl[0].textContent = word(total, 'ДЕЛО', 'ДЕЛА', 'ДЕЛ');
        st[1].textContent = remN; sl[1].textContent = 'НАПОМИНАНИЙ';
        st[2].textContent = freeEv; sl[2].textContent = word(freeEv, 'СВОБОДНЫЙ ВЕЧЕР', 'СВОБОДНЫХ ВЕЧЕРА', 'СВОБОДНЫХ ВЕЧЕРОВ');
      }
      var hi = 0, lo = 0;
      W.forEach(function (d, i) { if (d[3].length > W[hi][3].length) hi = i; if (d[3].length < W[lo][3].length) lo = i; });
      g('tipF').style.display = 'none';
      g('tipP').textContent = !total ? 'Неделя пустая. ' + HINT + '.' :
        (W[hi][3].length - W[lo][3].length >= 3 ? 'Самый плотный день — ' + WDL[(new Date(d0 + hi * DAY)).getDay()].toLowerCase() + ' (' + W[hi][3].length + ' ' + word(W[hi][3].length, 'дело', 'дела', 'дел') + '), а ' + WDL[(new Date(d0 + lo * DAY)).getDay()].toLowerCase() + ' почти свободен. Перенести что-то — скажи голосом.'
          : 'Неделя ровная: ' + total + ' ' + word(total, 'дело', 'дела', 'дел') + ' на 7 дней. Нажми на день — внизу его дела.');
    });
  });

  // ================= s16 · НОВОЕ ДЕЛО =================
  var NT = { rem: 0, rep: 0, type: 0 };
  PRO.bind('newtask', function (ph) {
    var f = ph.querySelectorAll('.fld'); if (f.length < 5) return;
    try { listen.done = 1; nRun++; } catch (e) {}
    f.forEach(function (x) { x.classList.add('in'); });
    g('sayS').textContent = 'НОВОЕ ДЕЛО'; g('sayS').style.color = 'var(--gold)';
    g('sayP').textContent = 'Заполни поля и нажми «Сохранить» — или нажми «Сказать голосом».';
    g('jn').style.opacity = 0; var js = qs(g('jn'), 'span'); if (js) js.textContent = '';
    var inp = 'background:none;border:0;border-bottom:1px solid var(--line, rgba(255,255,255,.15));color:inherit;font:inherit;width:100%;outline:none;padding:2px 0;color-scheme:dark';
    if (!g('ntWhat')) {
      var dt = new Date(Date.now() + 3600e3); dt.setMinutes(0, 0, 0);
      var loc = new Date(+dt - dt.getTimezoneOffset() * 6e4).toISOString().slice(0, 16);
      f[0].querySelector('.v').innerHTML = '<input id="ntWhat" style="' + inp + '" placeholder="Например: позвонить маме">';
      f[1].querySelector('.v').innerHTML = '<input id="ntWhen" type="datetime-local" style="' + inp + '" value="' + loc + '">';
      f[2].querySelector('.k').textContent = 'ЧТО ЭТО';
      f[2].querySelector('.v').outerHTML = '<span class="ch" id="chType"><span class="on">Напоминание</span><span>Дело без времени</span></span>';
      g('chRem').innerHTML = '<span class="on">Вовремя</span><span>За 15 мин</span><span>За час</span>';
      g('chRep').innerHTML = '<span class="on">Нет</span><span>Каждый день</span>';
      g('nAgain').textContent = 'Сказать голосом';
    }
    [['chType', 'type'], ['chRem', 'rem'], ['chRep', 'rep']].forEach(function (p) {
      var sp = g(p[0]).querySelectorAll('span');
      sp.forEach(function (s, i) { s.onclick = function () { NT[p[1]] = i; sp.forEach(function (x) { x.classList.toggle('on', x === s); }); vis(); }; s.classList.toggle('on', i === NT[p[1]]); });
    });
    function vis() { var off = NT.type === 1; [1, 3, 4].forEach(function (i) { f[i].style.opacity = off ? .3 : ''; f[i].style.pointerEvents = off ? 'none' : ''; }); }
    vis();
    g('nAgain').onclick = function () { try { JarvisNative.mic(); } catch (e) {} };
    var toast = g('toast');
    function say(t, ok) { toast.textContent = t; toast.style.background = ok ? '' : 'var(--gold)'; toast.classList.add('on'); setTimeout(function () { toast.classList.remove('on'); }, 2800); }
    g('nSave').onclick = function () {
      var what = (g('ntWhat').value || '').trim();
      if (!what) { say('Напиши, что сделать', false); g('ntWhat').focus(); return; }
      if (NT.type === 1) {
        PRO.q('noteAdd', { list: 'дела', item: what }).then(function (j) { if (j && j.ok !== false) { say('Добавил в «дела»: ' + what, true); g('ntWhat').value = ''; } else say('Не получилось сохранить', false); });
        return;
      }
      var at = new Date(g('ntWhen').value).getTime();
      if (!at) { say('Выбери, когда', false); return; }
      at -= [0, 15, 60][NT.rem] * 6e4;
      if (at < Date.now() && NT.rep !== 1) { say('Это время уже прошло', false); return; }
      PRO.q('reminderAdd', { text: what, at: at, daily: NT.rep === 1 }).then(function (j) {
        if (j && j.ok !== false) { say('Напомню ' + PRO.day(at).toLowerCase() + ' в ' + T(at) + (NT.rep ? ' · каждый день' : ''), true); g('ntWhat').value = ''; }
        else say('Не получилось сохранить', false);
      });
    };
  });

  // ================= s14 · ВИТРИНА НАПОМИНАНИЙ =================
  function nearest() {
    return Promise.all([load(2), PRO.q('notifs')]).then(function (a) {
      var D = a[0], up = upcoming(D);
      return { up: up, dela: D.dela, notifs: arr(a[1] && a[1].items), nNeed: a[1] && a[1].need ? a[1] : null };
    });
  }
  function first(N) { return N.up[0] || (N.dela[0] ? { text: String(N.dela[0]), kind: 'note' } : null); }
  function setClock(ph) {
    var c = qs(ph, '.clock b'); if (c) c.textContent = T(Date.now());
    var s = qs(ph, '.clock span'); if (s) s.textContent = dateLine() + (wx.t ? ' · ' + wx.t : '');
  }
  var wx = { t: '', msg: '', at: 0 };
  function weather() {
    if (Date.now() - wx.at < 15 * 6e4) return Promise.resolve(wx);
    return PRO.q('tool', { id: 'weather', args: {} }).then(function (j) {
      var m = j && j.status === 'SUCCESS' && j.msg ? String(j.msg) : '';
      var t = m.match(/[+\-−]?\d+\s*°/);
      wx = { t: t ? t[0].replace(/\s/g, '') : '', msg: m, at: m ? Date.now() : 0 };
      return wx;
    });
  }
  function ringOf(svg, it) { // кольцо «сколько осталось» (полное = 2 часа)
    if (!svg) return; var fg = svg.querySelector('.fg'), tx = svg.querySelectorAll('text');
    var m = it && it.at ? Math.max(0, Math.round((it.at - Date.now()) / 6e4)) : 0;
    if (fg) fg.style.strokeDashoffset = (169.6 * (1 - Math.min(1, m / 120))).toFixed(1);
    if (tx[0]) tx[0].textContent = !it || !it.at ? '—' : m < 100 ? m : Math.round(m / 60);
    if (tx[1]) tx[1].textContent = !it || !it.at ? '' : m < 100 ? 'МИН' : 'Ч';
  }
  function steal(ids) { // демо-таймеры страницы пишут в эти id: отдаём им невидимые копии
    var box = g('realDummy');
    if (!box) { box = document.createElement('div'); box.id = 'realDummy'; box.style.display = 'none'; document.body.appendChild(box); }
    ids.forEach(function (id) {
      var el = g(id); if (!el || box.contains(el)) return;
      el.removeAttribute('id'); el.dataset.rid = id;
      var d = el.namespaceURI === 'http://www.w3.org/2000/svg' ? document.createElementNS(el.namespaceURI, el.tagName) : document.createElement(el.tagName);
      d.id = id; box.appendChild(d);
    });
  }
  var tick = null;

  PRO.bind('remind', function (ph) {
    steal(['wMin', 'wRingT', 'wRing']);
    nearest().then(function (N) {
      var big = g('wBig'), it = first(N), nx = N.up[1] || (N.up[0] && N.dela[0] ? { text: String(N.dela[0]) } : null);
      function draw() {
        setClock(ph);
        var b = qs(big, '.big'), m = qs(big, '.mut'), row = qs(big, '.row');
        if (!it) { b.innerHTML = 'Дел нет'; m.textContent = HINT; if (row) row.innerHTML = '<b>—</b>Ближайших напоминаний нет'; }
        else {
          b.innerHTML = it.at ? (it.at - Date.now() < 3 * 3600e3 ? 'Через ' + left(it.at) : PRO.day(it.at) + ' в ' + T(it.at)) + '<br>' + E(it.text) : E(it.text);
          m.textContent = it.at ? PRO.day(it.at) + ', ' + T(it.at) + (it.kind === 'cal' ? ' · календарь' : it.daily ? ' · каждый день' : ' · напоминание') : 'Дело без времени';
          if (row) row.innerHTML = nx ? '<b>' + (nx.at ? T(nx.at) : '•') + '</b>' + E(nx.text) + '<i>ДАЛЬШЕ</i>' : '<b>—</b>Больше ничего<i>ВСЁ</i>';
        }
        var sm = ph.querySelectorAll('.wg')[1];
        if (sm) {
          ringOf(sm.querySelector('svg.ring'), it);
          var t = sm.querySelector('div[style*="bottom"]');
          if (t) t.innerHTML = '<small>СЛЕДУЮЩЕЕ</small><div style="font-size:15px;font-weight:500;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">' + (it ? E(it.text) + (it.at ? ' · ' + T(it.at) : '') : 'Пока ничего') + '</div><div class="mut">' + (it ? (it.at ? PRO.day(it.at) : 'без времени') : 'Скажи «Джарвис, напомни…»') + '</div>';
        }
      }
      draw();
      // «Агент · 3 задачи» → список ближайших
      var ag = ph.querySelectorAll('.wg')[2];
      if (ag) {
        var list = N.up.slice(0, 3).concat(N.dela.slice(0, Math.max(0, 3 - N.up.length)).map(function (t) { return { text: String(t) }; }));
        ag.innerHTML = '<small>БЛИЖАЙШИЕ · ' + (N.up.length + N.dela.length) + '</small><div style="margin-top:10px;display:flex;flex-direction:column;gap:9px;font-size:11.5px">' +
          (list.length ? list.map(function (x) { return '<div style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis"><b style="color:var(--gold);font-weight:500">' + (x.at ? T(x.at) : '•') + '</b> ' + E(x.text) + '</div>'; }).join('')
            : '<div class="real-empty">Пусто. Скажи «Джарвис, напомни…»</div>') + '</div>';
      }
      var mic = ph.querySelectorAll('.micb'); mic.forEach(function (m) { m.style.cursor = 'pointer'; m.onclick = function () { try { JarvisNative.mic(); } catch (e) {} }; });
      clearInterval(tick); tick = setInterval(function () { if (PRO.cur === 'remind') draw(); else clearInterval(tick); }, 15000);
      weather().then(function () { setClock(ph); });
    });
  });

  PRO.bind('remind2', function (ph) {
    nearest().then(function (N) {
      var it = first(N), heads = g('heads'); if (!heads) return;
      var card = heads.querySelector('.ntf'), b = card.querySelector('.bd b'), s = card.querySelector('.bd span'), acts = card.querySelector('.acts'), em = card.querySelector('.hd em');
      var bs = acts.querySelectorAll('button');
      // фон: последнее настоящее сообщение
      var n0 = N.notifs.filter(function (x) { return x.text; })[0], app = qs(ph, '.app2');
      if (app) app.innerHTML = '<div class="ah">' + E(n0 ? n0.title || n0.app : 'Любое приложение') + '</div><div class="am">' + (n0 ? '<div>' + E(n0.text) + '</div>' : '<div>Напоминание всплывает поверх того, что открыто.</div>') + '</div>';
      function fill() {
        if (!it) { b.textContent = 'Ближайших напоминаний нет'; s.textContent = HINT + ' — и оно всплывёт здесь.'; acts.style.display = 'none'; em.textContent = 'пример'; return; }
        b.textContent = it.at ? (it.at - Date.now() < 3 * 3600e3 ? 'Через ' + left(it.at) + ' — ' + it.text : it.text) : it.text;
        s.textContent = it.at ? PRO.day(it.at) + ' в ' + T(it.at) + (it.loc ? ' · ' + it.loc : '') + (it.kind === 'cal' ? ' · из календаря' : '') : 'Дело из списка «дела» — без времени';
        em.textContent = it.at ? T(it.at) : 'дело';
        acts.style.display = it.kind === 'cal' ? 'none' : '';
        bs[0].textContent = it.kind === 'rem' ? 'Отложить 10 мин' : 'Скрыть'; bs[1].textContent = 'Выполнено';
      }
      try { hT.forEach(clearTimeout); hT = []; } catch (e) {}
      try { hCycle = function () { hT.forEach(clearTimeout); hT = []; if (PRO.cur !== 'remind2') return; fill(); hT.push(setTimeout(function () { heads.classList.add('on'); }, 700)); hT.push(setTimeout(function () { heads.classList.remove('on'); }, 8000)); hT.push(setTimeout(hCycle, 10500)); }; hCycle(); } catch (e) { fill(); heads.classList.add('on'); }
      bs[0].onclick = function () {
        heads.classList.remove('on');
        if (it && it.kind === 'rem') PRO.q('reminderAdd', { text: it.text, at: Date.now() + 6e5, daily: false }).then(function () { if (!it.daily) return PRO.q('reminderDel', { id: it.id }); }).then(function () { PRO.toast('Напомню через 10 минут'); PRO.refresh(); });
      };
      bs[1].onclick = function () { heads.classList.remove('on'); if (it) done(it); };
    });
  });

  PRO.bind('remind3', function (ph) {
    steal(['agBar']);
    nearest().then(function (N) {
      var sh = qs(ph, '.shade'); if (!sh) return;
      sh.querySelectorAll('.ntf, .real-empty, .real-need, .rnd').forEach(function (x) { x.remove(); });
      var ic = (qs(ph, '.ntf .ic') || {}).outerHTML; // не нужно: берём из шаблона ниже
      var html = '', jic = '<span class="ic"><i style="background-image:' + jicon() + '"></i></span>';
      N.up.slice(0, 2).forEach(function (it, i) {
        html += '<div class="ntf j' + (i ? ' sm' : '') + '" data-i="' + i + '"><div class="hd">' + jic + '<b>Джарвис</b>· ' + (it.kind === 'cal' ? 'календарь' : 'напоминание') + '<em>' + T(it.at) + '</em></div>' +
          '<div class="bd"><div class="t"><b>' + E(it.text) + ' · ' + T(it.at) + '</b><span>' + PRO.day(it.at) + ' · через ' + left(it.at) + '</span></div>' +
          (i ? '' : '<svg class="ring" viewBox="0 0 62 62" style="position:relative;width:50px;height:50px"><circle class="bg" cx="31" cy="31" r="27"/><circle class="fg" cx="31" cy="31" r="27" stroke-dasharray="169.6" transform="rotate(-90 31 31)"/><text x="31" y="35"></text></svg>') + '</div>' +
          (it.kind === 'rem' ? '<div class="acts"><button data-a="del">Удалить</button><button class="g" data-a="ok">Выполнено</button></div>' : '') + '</div>';
      });
      N.dela.slice(0, N.up.length ? 1 : 2).forEach(function (t) {
        html += '<div class="ntf sm j"><div class="hd">' + jic + '<b>Джарвис</b>· дело<em>—</em></div><div class="bd"><div class="t"><b>' + E(t) + '</b></div></div></div>';
      });
      if (!N.up.length && !N.dela.length) html += '<div class="real-empty">Напоминаний Джарвиса нет. ' + E(HINT) + ' — и оно появится в шторке с кольцом.</div>';
      N.notifs.slice(0, 2).forEach(function (n) {
        html += '<div class="ntf sm"><div class="hd"><span style="width:18px;height:18px;border-radius:50%;background:var(--text3);display:inline-block"></span><b>' + E(n.app || n.pkg) + '</b>· ' + E(n.title || '') + '<em>' + (n.time ? T(n.time) : '') + '</em></div><div class="bd"><div class="t"><b>' + E(n.text || n.title || '') + '</b></div></div></div>';
      });
      if (N.nNeed) html += '<div class="rnd"></div>';
      sh.insertAdjacentHTML('beforeend', html);
      if (N.nNeed) PRO.need(sh.querySelector('.rnd'), N.nNeed);
      ringOf(sh.querySelector('svg.ring'), N.up[0]);
      sh.querySelectorAll('[data-a]').forEach(function (b) {
        var it = N.up[+b.closest('.ntf').dataset.i];
        b.onclick = function () { b.disabled = true; done(it); };
      });
    });
  });
  function jicon() { var i = document.querySelector('.ntf .ic i, .heads .ic i'); return i ? i.style.backgroundImage.replace(/"/g, "'") : 'none'; }

  PRO.bind('remind4', function (ph) {
    nearest().then(function (N) {
      var it = first(N);
      var t = !it ? 'Ближайших дел нет. ' + HINT + ' — и я напомню здесь, поверх любого приложения.' :
        (it.at ? 'Через ' + left(it.at) + ' — ' + it.text + '.' : 'Не забудь: ' + it.text + '.') + (it.kind === 'cal' ? ' Это из календаря.' : ' Отметить как выполненное?');
      try { B0.t = t; } catch (e) {}
      var bt = g('bT'); if (bt) bt.textContent = t;
      var y = g('bYes'), n = g('bNo');
      if (y) { y.textContent = it && it.kind !== 'cal' ? 'Выполнено' : 'Понятно'; y.onclick = function () { clearInterval(bTick); if (it && it.kind !== 'cal') done(it); g('bot').classList.remove('on'); }; }
      if (n) { n.textContent = 'Позже'; n.onclick = function () { clearInterval(bTick); g('bot').classList.remove('on'); }; }
      var app = qs(ph, '.app');
      if (app && !app.dataset.real) { app.dataset.real = 1; } // фон-карта — только декорация «любого приложения»
    });
  });

  // ================= s15 · ВИДЖЕТЫ ПРИЛОЖЕНИЙ =================
  var AB = { 'org.telegram': ['tg', 'TG'], 'com.vkontakte': ['vk', 'VK'], 'ru.oneme': ['max', 'MAX'], 'ru.max': ['max', 'MAX'], 'com.whatsapp': ['go', 'WA'], 'ru.sberbank': ['sb', 'СБ'], 'com.idamob.tinkoff': ['tb', 'Т'], 'ru.ozon': ['go', 'OZ'], 'com.wildberries': ['go', 'WB'], 'ru.yandex': ['go', 'Я'], 'com.avito': ['av', 'AV'], 'ru.rostel': ['gu', 'ГУ'], 'ru.gosuslugi': ['gu', 'ГУ'] };
  function ab(n) { var p = n.pkg || ''; for (var k in AB) if (p.indexOf(k) === 0) return AB[k]; return ['gis', E(String(n.app || '?').slice(0, 2).toUpperCase())]; }
  function widgets() {
    return Promise.all([nearest(), PRO.q('status'), weather()]).then(function (a) { return { N: a[0], st: a[1] || {}, wx: a[2] }; });
  }
  function battery(st) {
    var b = st.battery; if (b == null) return '<div class="real-empty">Нет данных о заряде</div>';
    return '<div class="num">' + Math.round(b) + ' <span style="font-size:13px">%</span></div><div class="mut">' + (st.charging ? 'Заряжается' : b < 20 ? 'Пора зарядить' : 'От батареи') + '</div>' +
      '<div class="pbar2" style="margin-top:9px"><i style="width:' + Math.round(b) + '%;background:' + (b < 20 ? 'var(--bad, #ff6b6b)' : 'var(--ok)') + '"></i></div>';
  }
  function wxHtml(w) {
    if (!w.msg) return '<div class="real-empty">Погода недоступна. Нужен интернет и геопозиция.</div>';
    return '<div class="num">' + E(w.t || '—') + '</div><div class="mut" style="display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden">' + E(w.msg) + '</div>';
  }

  PRO.bind('appw', function (ph) {
    widgets().then(function (R) {
      var N = R.N; setClock(ph);
      var wg = ph.querySelectorAll('.wg');
      // 1) сообщения = последние уведомления
      var m = wg[0];
      if (m) {
        var list = N.notifs.filter(function (n) { return n.text || n.title; }).slice(0, 3);
        var h = '<div class="hd2"><small>ДЖАРВИС · УВЕДОМЛЕНИЯ</small>' + (N.notifs.length ? '<span class="ab gis">' + N.notifs.length + '</span>' : '') + '</div>';
        if (N.nNeed) h += '<div class="rnd"></div>';
        else if (!list.length) h += '<div class="real-empty">Новых уведомлений нет — здесь появятся сообщения из Telegram, MAX, ВК.</div>';
        else list.forEach(function (n) { var a = ab(n); h += '<div class="ln"><span class="ab ' + a[0] + '">' + a[1] + '</span><div class="tx"><b>' + E(n.title || n.app) + ':</b> ' + E(n.text || '') + '</div><em>' + (n.time ? T(n.time) : '') + '</em></div>'; });
        var r = list.filter(function (n) { return n.canReply; })[0];
        h += r ? '<div class="jl" id="msgJ"><i></i><span id="msgJT">Ответит Джарвис: ' + E(r.title || r.app) + '</span><b>Ответить</b></div>' : '';
        m.innerHTML = h;
        if (N.nNeed) PRO.need(m.querySelector('.rnd'), N.nNeed);
        if (r) g('msgJ').onclick = function () { PRO.send('Предложи ответ на последнее сообщение от ' + (r.title || r.app) + ' в ' + (r.app || '') + ' и спроси меня перед отправкой'); g('msgJT').textContent = 'Джарвис готовит ответ…'; };
      }
      // 2) доставки → погода
      if (wg[1]) wg[1].innerHTML = '<div class="hd2"><small>ПОГОДА</small></div>' + wxHtml(R.wx);
      // 3) деньги → заряд
      if (wg[2]) { wg[2].onclick = null; wg[2].innerHTML = '<div class="hd2"><small>ЗАРЯД</small></div>' + battery(R.st); }
      // 4) дорога → ближайшее дело
      var it = first(N);
      if (g('goT')) {
        g('goT').textContent = it ? it.text : 'Ближайших дел нет';
        g('goS').textContent = it ? (it.at ? PRO.day(it.at) + ', ' + T(it.at) + ' · через ' + left(it.at) : 'Дело без времени') : 'Скажи «Джарвис, напомни…»';
        var ic = qs(g('go'), '.ab'); if (ic) { ic.className = 'ab gis'; ic.textContent = 'J'; }
        var b = g('goB');
        b.style.display = it && it.kind !== 'cal' ? '' : 'none'; b.textContent = 'Готово';
        b.onclick = function () { if (it) { b.textContent = '…'; done(it); } };
      }
    });
  });

  PRO.bind('appw2', function (ph) {
    widgets().then(function (R) {
      var N = R.N; setClock(ph);
      var wg = ph.querySelectorAll('.wg');
      // 1) госуслуги → дела
      if (wg[0]) {
        var list = N.up.slice(0, 3).map(function (x) { return x; }).concat(N.dela.slice(0, Math.max(0, 3 - N.up.length)).map(function (t) { return { text: String(t), kind: 'note' }; }));
        var h = '<div class="hd2"><small>ДЖАРВИС · ДЕЛА</small><span class="ab gis">' + (N.up.length + N.dela.length) + '</span></div>';
        h += list.length ? list.map(function (x, i) { return '<div class="ln" data-i="' + i + '"><span style="font-size:13px">' + (x.kind === 'cal' ? '📅' : x.kind === 'note' ? '📝' : '⏰') + '</span><div class="tx"><b>' + E(x.text) + '</b>' + (x.at ? ' · ' + PRO.day(x.at).toLowerCase() + ', ' + T(x.at) : '') + '</div><em' + (x.kind !== 'cal' ? ' class="g" style="cursor:pointer"' : '') + '>' + (x.kind !== 'cal' ? 'ГОТОВО' : 'КАЛЕНДАРЬ') + '</em></div>'; }).join('')
          : '<div class="real-empty">Дел нет. ' + E(HINT) + '</div>';
        h += '<div class="jl" id="fineJ"><i></i><span id="fineT">Добавить дело голосом</span><b>Сказать</b></div>';
        wg[0].innerHTML = h;
        wg[0].querySelectorAll('.ln em.g').forEach(function (e) { var it = list[+e.closest('.ln').dataset.i]; e.onclick = function () { e.textContent = '…'; done(it); }; });
        g('fineJ').onclick = function () { try { JarvisNative.mic(); } catch (e) {} };
      }
      // 2) музыка — управление голосовыми командами, без выдуманного трека
      if (g('songT')) {
        g('songT').textContent = 'Музыка по команде';
        var mm = qs(wg[1], '.mut'); if (mm) mm.textContent = 'Скажи «Джарвис, включи музыку»';
        g('pPlay').textContent = '▶';
        g('pPlay').onclick = function () { PRO.send('пауза или продолжить музыку'); };
        g('pNext').onclick = function () { PRO.send('следующий трек'); };
        g('pPrev').onclick = function () { PRO.send('предыдущий трек'); };
      }
      // 3) авито → погода
      if (wg[2]) wg[2].innerHTML = '<div class="hd2"><small>ПОГОДА</small></div>' + wxHtml(R.wx);
      // 4) дорога → заряд
      if (wg[3]) wg[3].innerHTML = '<div class="hd2"><small>ЗАРЯД</small></div>' + battery(R.st);
    });
  });
})();

// s17: брифинг, ответы, важное, «Мои команды», люди — из настоящих данных телефона.
(function () {
  if (!window.PRO || !PRO.on) return;
  var E = PRO.esc;
  function $1(sel) { return document.querySelector(sel); }
  function empty(el, title, hint) { el.innerHTML = '<div class="real-empty"><b>' + E(title) + '</b><span>' + E(hint) + '</span></div>'; }
  var GR = ['#6d5bff,#3a2fb8', '#ff9d6b,#c4513a', '#3fe0b0,#13866a', '#ffb34d,#c46a12', '#7fe8ff,#2a7a96', '#2f6bff,#1b3fa8', '#ff7a1a,#c74a00', '#21a038,#126b22'];
  function grad(s) { var h = 0; s = String(s || '?'); for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return 'linear-gradient(135deg,' + GR[Math.abs(h) % GR.length] + ')'; }
  function ini(s) { s = String(s || '?').trim(); return (s[0] || '?').toUpperCase(); }
  function plural(n, a, b, c) { var m = n % 10, k = n % 100; return m === 1 && k !== 11 ? a : m >= 2 && m <= 4 && (k < 10 || k >= 20) ? b : c; }
  function toast(t) { PRO.toast(t); }
  function arr(j) { return j && j.items && j.items.length ? j.items : []; }

  // ---------- Брифинг ----------
  PRO.bind('brief', function () {
    try { ++bRun; } catch (e) {}
    var t0 = new Date(), sod = new Date(t0); sod.setHours(0, 0, 0, 0);
    var eod = sod.getTime() + 864e5;
    Promise.all([
      PRO.q('tool', { id: 'weather', args: {} }), PRO.q('calendar', { days: 2 }), PRO.q('reminders'), PRO.q('notifs'), PRO.q('status')
    ]).then(function (r) {
      var w = r[0] || {}, cal = r[1] || {}, rem = r[2] || {}, nt = r[3] || {}, st = r[4] || {};
      var now = Date.now();
      var ev = arr(cal).map(function (x) { return { at: x.begin, t: x.title, all: x.allDay }; });
      var rm = arr(rem).map(function (x) {
        var at = x.at; if (x.daily) { var d = new Date(at), k = new Date(sod); k.setHours(d.getHours(), d.getMinutes(), 0, 0); at = k.getTime(); }
        return { at: at, t: x.text };
      });
      var all = ev.concat(rm).sort(function (a, b) { return a.at - b.at; });
      var today = all.filter(function (x) { return x.at >= sod.getTime() && x.at < eod; });
      var left = today.filter(function (x) { return x.at >= now; });
      var tom = all.filter(function (x) { return x.at >= eod && x.at < eod + 864e5; });
      var rep = arr(nt).filter(function (x) { return x.canReply; });
      var M = [], V = [];
      // погода
      var wm = w.status === 'SUCCESS' && w.msg ? String(w.msg) : '';
      if (wm) { var w1 = wm.split(/[.\n]/)[0]; M.push(['☀', w1.slice(0, 40), wm.slice(w1.length + 1).trim().slice(0, 70) || 'Погода на сегодня', '', wm]); }
      else M.push(['☀', 'Погода недоступна', 'Нет связи или геолокации', '', 'Погоду сейчас узнать не получилось.']);
      // дела
      var line = function (a) { return a.slice(0, 3).map(function (x) { return (x.all ? '' : PRO.time(x.at) + ' ') + x.t; }).join(' · '); };
      if (cal.need) M.push(['◷', 'Нет доступа к календарю', 'Разрешите в настройках — покажу встречи', '', 'Календарь закрыт, вижу только напоминания.']);
      if (today.length) {
        var f = left[0] || today[0];
        M.push(['◷', today.length + ' ' + plural(today.length, 'дело', 'дела', 'дел') + (left[0] ? ', ближайшее в ' + PRO.time(f.at) : ''), line(left.length ? left : today), '',
          'Сегодня ' + today.length + ' ' + plural(today.length, 'дело', 'дела', 'дел') + '. ' + (left[0] ? 'Ближайшее — ' + f.t + ' в ' + PRO.time(f.at) + '.' : '')]);
      } else M.push(['◷', 'Дел на сегодня нет', 'Скажи: «Джарвис, напомни…»', '', 'На сегодня дел нет.']);
      // кому ответить
      var repItem;
      if (nt.need) repItem = ['✉', 'Нет доступа к уведомлениям', 'Разрешите — подскажу, кто ждёт ответа', '', 'Уведомления закрыты.'];
      else if (rep.length) repItem = ['✉', 'Ответить: ' + rep.length, rep.slice(0, 3).map(function (x) { return x.title || x.app; }).join(' · '), 'ОТВЕТЫ',
        rep.length + ' ' + plural(rep.length, 'сообщение ждёт', 'сообщения ждут', 'сообщений ждут') + ' ответа.'];
      else repItem = ['✉', 'Никто не ждёт ответа', 'Новых сообщений нет', '', 'Ответить никому не нужно.'];
      M.push(repItem);
      // заряд
      var bat = st.battery, bi = null;
      if (bat != null) bi = ['⚡', 'Заряд ' + bat + '%' + (st.charging ? ' · заряжается' : ''), st.charging ? 'Телефон на зарядке' : bat < 30 ? 'Стоит подзарядить' : 'Хватит на день', bat < 30 && !st.charging ? 'ЗАРЯДИТЬ' : '',
        'Заряд ' + bat + ' процентов.' + (bat < 30 && !st.charging ? ' Стоит подзарядить.' : '')];
      if (bi) M.push(bi);
      // вечер
      var done = today.filter(function (x) { return x.at < now; });
      V.push(['✓', done.length ? 'Прошло дел: ' + done.length : 'Дела за день', today.length ? (left.length ? 'Осталось: ' + line(left) : 'Все дела на сегодня позади') : 'Сегодня дел не было', '',
        today.length ? 'За день прошло ' + done.length + ' из ' + today.length + '.' : 'Сегодня дел не было.']);
      V.push(['⏰', tom.length ? 'Завтра ' + tom.length + ' ' + plural(tom.length, 'дело', 'дела', 'дел') : 'Завтра свободно', tom.length ? line(tom) : 'Дел на завтра нет', '',
        tom.length ? 'Завтра первое дело в ' + PRO.time(tom[0].at) + ' — ' + tom[0].t + '.' : 'Завтра дел нет.']);
      V.push(repItem);
      if (bi) V.push(['⚡', bi[1], st.charging ? 'Телефон на зарядке' : 'Поставь на зарядку на ночь', st.charging ? '' : 'НА ЗАРЯДКУ', st.charging ? 'Телефон заряжается.' : 'Поставь телефон на зарядку.']);
      var hh = PRO.time(now), h = t0.getHours();
      BR.m.chip = hh + ' · УТРО'; BR.e.chip = hh + ' · ВЕЧЕР';
      BR.m.hi = (h < 12 ? 'Доброе утро' : 'Добрый день') + ', сэр. Коротко о дне.'; BR.e.hi = 'Добрый вечер, сэр. Подведу итог.';
      var esc3 = function (a) { return a.map(function (x) { return [x[0], E(x[1]), E(x[2]), x[3], x[4]]; }); };
      BR.m.items = esc3(M); BR.e.items = esc3(V);
      BR.m.len = BR.e.len = 'ПО ДАННЫМ';
      bK = h >= 17 || h < 4 ? 'e' : 'm';
      document.querySelectorAll('#bSeg span').forEach(function (x) { x.classList.toggle('on', x.dataset.k === bK); });
      bDraw(); bPlay();
      document.querySelectorAll('#bList .bc').forEach(function (c, i) {
        if (BR[bK].items[i] && BR[bK].items[i][3] === 'ОТВЕТЫ') { c.style.cursor = 'pointer'; c.onclick = function () { location.hash = 'reply'; }; }
      });
      var again = $1('#bAgain');
      if (again) again.onclick = function () { PRO.send('что сегодня'); bDraw(); bPlay(); };
    });
  });

  // ---------- Ответы ----------
  var TPL = ['Перезвоню позже', 'Ок, понял', 'Скоро буду'];
  PRO.bind('reply', function () {
    var rl = $1('#rpl .rl'); if (!rl) return;
    var sub = $1('#rpl .jhead span'), chip = $1('#rpl .tbar .chip');
    PRO.q('notifs').then(function (j) {
      if (PRO.need(rl, j)) return;
      var it = arr(j).filter(function (x) { return x.canReply; });
      if (chip) chip.innerHTML = '<i></i>' + (it.length ? it.length + ' ЖДУТ' : 'ВСЁ ТИХО');
      if (sub) sub.textContent = it.length ? 'Ждут ответа: ' + it.length + '. Выбери вариант и нажми «Отправить».' : 'Новых сообщений нет.';
      if (!it.length) { empty(rl, 'Никто не ждёт ответа', 'Сообщения из мессенджеров с кнопкой «Ответить» появятся здесь.'); return; }
      rl.innerHTML = it.map(function (x, i) {
        return '<div class="rc" data-n="' + i + '"><div class="h"><div class="av" style="background:' + grad(x.title) + '">' + E(ini(x.title)) + '</div><div><b>' + E(x.title || x.app) + '</b><small>' +
          E(String(x.app || '').toUpperCase() + ' · ' + PRO.ago(x.time).toUpperCase()) + '</small></div><span class="tagj c">ЖДЁТ</span></div>' +
          '<div class="in">' + E(x.text) + '</div><div class="dr"><small>ОТВЕТ ДЖАРВИСА</small><span class="tx">' + TPL[0] + '</span></div>' +
          '<div class="alt">' + TPL.map(function (t, k) { return '<span' + (k ? '' : ' class="on"') + ' data-t="' + t + '">' + t + '</span>'; }).join('') + '</div>' +
          '<div class="btn2"><button class="sk">Позже</button><button class="g go">Отправить</button></div></div>';
      }).join('');
      rl.querySelectorAll('.rc').forEach(function (rc) {
        var x = it[+rc.dataset.n];
        rc.querySelectorAll('.alt span').forEach(function (s) {
          s.onclick = function () { if (rc.classList.contains('sent')) return; rc.querySelectorAll('.alt span').forEach(function (y) { y.classList.toggle('on', y === s); }); rc.querySelector('.tx').textContent = s.dataset.t; };
        });
        rc.querySelector('.go').onclick = function () {
          if (rc.classList.contains('sent')) return;
          var b = this; b.textContent = '…';
          PRO.q('notifReply', { key: x.key, text: rc.querySelector('.tx').textContent }).then(function (r) {
            if (r && r.ok && r.sent !== false) {
              rc.classList.add('sent'); rc.querySelector('.dr small').textContent = 'ОТПРАВЛЕНО · СЕЙЧАС';
              var t = rc.querySelector('.h .tagj'); t.textContent = 'ОТВЕТИЛ'; t.className = 'tagj k';
              rc.querySelector('.btn2').innerHTML = '<button class="ok">✓ Отправлено</button>'; var a = rc.querySelector('.alt'); if (a) a.remove();
            } else { b.textContent = 'Не ушло · ещё раз'; toast('Не удалось отправить'); }
          });
        };
        rc.querySelector('.sk').onclick = function () { rc.classList.add('skip'); var t = rc.querySelector('.h .tagj'); t.textContent = 'ПОЗЖЕ'; t.className = 'tagj w'; };
      });
    });
  });

  // ---------- Важное ----------
  function prio(x) {
    var s = (x.pkg + ' ' + x.app).toLowerCase();
    if (/dialer|telecom|incallui|phone|call|звон/.test(s) || /пропущ/i.test(x.title + ' ' + x.text)) return 3;
    if (/sber|tinkoff|vtb|alfa|bank|banking|raif|gazprom|psb|otkritie|sovcom|ozon.*bank|банк|госуслуг|gosuslugi/.test(s)) return 3;
    if (/telegram|whatsapp|viber|vk|max|oneme|messag|mms|sms|signal|skype|messenger/.test(s)) return 2;
    if (/mail|gmail|calendar|yandex\.taxi|ozon|wildberries|avito/.test(s)) return 1;
    return 0;
  }
  PRO.bind('important', function () {
    var il = $1('#imp .il'); if (!il) return;
    var n = $1('#imp .sum .n'), sp = $1('#imp .sum > span');
    PRO.q('notifs').then(function (j) {
      if (PRO.need(il, j)) { if (n) n.innerHTML = '—<i>→</i><em id="impN">0</em>'; if (sp) sp.textContent = 'Нужен доступ к уведомлениям.'; return; }
      var it = arr(j).map(function (x) { x.p = prio(x); return x; }).sort(function (a, b) { return b.p - a.p || b.time - a.time; });
      var imp = it.filter(function (x) { return x.p >= 2; }), cnt = imp.length;
      if (n) n.innerHTML = it.length + '<i>→</i><em id="impN">' + cnt + '</em>';
      if (sp) sp.textContent = it.length ? 'Уведомлений сейчас: ' + it.length + ', важных: ' + cnt + ' (звонки, банки, мессенджеры).' : 'Уведомлений нет.';
      if (!it.length) { empty(il, 'Уведомлений нет', 'Новые уведомления появятся здесь, важные — сверху.'); return; }
      il.innerHTML = it.map(function (x, i) {
        var act = x.actions && x.actions.length ? x.actions[0] : 'Открыть';
        return '<div class="ic3' + (x.p >= 3 ? ' r' : x.p === 2 ? ' j' : '') + '" data-i="' + i + '"><div class="ap" style="background:' + grad(x.app) + '">' + E(String(x.app || '?').slice(0, 2).toUpperCase()) + '</div>' +
          '<div><b>' + E(x.title || x.app) + '</b><span>' + E((x.text ? x.text + ' · ' : '') + x.app + ' · ' + PRO.ago(x.time)) + '</span></div>' +
          '<div style="display:flex;gap:6px"><button class="g a">' + E(act) + '</button><button class="x" title="Смахнуть">✕</button></div></div>';
      }).join('');
      function done(c, txt, x) {
        c.classList.add('done'); c.classList.remove('r', 'j');
        c.lastChild.innerHTML = '<button>✓ ' + E(txt) + '</button>';
        if (x.p >= 2) { cnt = Math.max(0, cnt - 1); var e = $1('#impN'); if (e) e.textContent = cnt; }
      }
      il.querySelectorAll('.ic3').forEach(function (c) {
        var x = it[+c.dataset.i];
        c.querySelector('.a').onclick = function () {
          if (c.classList.contains('done')) return;
          if (x.actions && x.actions.length) PRO.q('notifAction', { key: x.key, i: 0 }).then(function (r) { if (r && r.ok) done(c, 'Сделано', x); else toast('Не получилось'); });
          else { PRO.q('openApp', { pkg: x.pkg }); done(c, 'Открыто', x); }
        };
        c.querySelector('.x').onclick = function () {
          if (c.classList.contains('done')) return;
          PRO.q('notifDismiss', { key: x.key }).then(function (r) { if (r && r.ok) done(c, 'Смахнул', x); else toast('Не получилось'); });
        };
      });
    });
  });

  // ---------- Мои команды ----------
  PRO.bind('chain', function () {
    try { ++chRun; } catch (e) {}
    var box = $1('#chSteps'); if (!box) return;
    var ph = $1('#chn'); if (ph) ph.onShow = null;
    var chip = $1('#chChip'), tx = $1('#chTxt'), say = $1('#chSay small'), one = $1('#chOne'), all = $1('#chAll');
    if (say) say.textContent = 'МОИ КОМАНДЫ';
    if (one) { one.style.display = ''; one.textContent = 'Добавить'; one.onclick = function () { try { JarvisNative.open('section:Мои команды'); } catch (e) {} }; }
    PRO.q('routines').then(function (j) {
      var it = arr(j), sel = 0;
      if (chip) chip.textContent = it.length ? it.length + ' ' + plural(it.length, 'КОМАНДА', 'КОМАНДЫ', 'КОМАНД') : 'НЕТ КОМАНД';
      if (!it.length) {
        if (tx) tx.textContent = 'Одна фраза — несколько дел сразу.';
        empty(box, 'Своих команд пока нет', 'Нажми «Добавить» и задай фразу, например «Я дома» → свет, музыка, напомнить про ужин.');
        if (all) { all.className = 'g'; all.textContent = 'Добавить команду'; all.onclick = one.onclick; }
        return;
      }
      if (tx) tx.textContent = 'Нажми на команду, затем «Запустить», или скажи её голосом.';
      box.innerHTML = it.map(function (x, i) {
        var s = (x.steps || []);
        return '<div class="st in' + (i ? '' : ' wait') + '" data-s="' + i + '"><div class="h"><b>«' + E(x.name) + '»</b><span class="tagj">' + s.length + ' ' + plural(s.length, 'ШАГ', 'ШАГА', 'ШАГОВ') + '</span></div><span>' + E(s.join(' → ') || '—') + '</span></div>';
      }).join('');
      var els = box.querySelectorAll('.st');
      function pick(i) { sel = i; els.forEach(function (e, k) { e.classList.toggle('wait', k === i); }); if (all) { all.className = 'g'; all.textContent = 'Запустить «' + it[i].name + '»'; } }
      els.forEach(function (e, i) { e.style.cursor = 'pointer'; e.onclick = function () { pick(i); }; });
      pick(0);
      if (all) all.onclick = function () {
        var e = els[sel]; PRO.send(it[sel].name);
        e.classList.remove('wait'); e.classList.add('ok'); var t = e.querySelector('.tagj'); t.textContent = 'ЗАПУЩЕНО'; t.className = 'tagj k';
        all.textContent = '✓ Запущено'; all.className = 'ok';
      };
    });
  });

  // ---------- Люди ----------
  function bdNext(b) {
    var m = /(\d\d)-(\d\d)$/.exec(b || ''); if (!m) return null;
    var t = new Date(); t.setHours(0, 0, 0, 0);
    var d = new Date(t.getFullYear(), +m[1] - 1, +m[2]); if (d < t) d.setFullYear(d.getFullYear() + 1);
    return { d: d, k: Math.round((d - t) / 864e5) };
  }
  var MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  var origDraw = null;
  PRO.bind('people', function () {
    var avs = $1('#pAvs'), card = $1('#pCard'), tip = $1('#pTip'), chip = $1('#ppl .tbar .chip');
    if (!avs) return;
    PRO.q('contacts').then(function (j) {
      if (j && j.need) { avs.innerHTML = ''; tip.innerHTML = ''; PRO.need(card, j); return; }
      var it = arr(j);
      if (chip) chip.innerHTML = '<i></i>' + it.length + ' ' + plural(it.length, 'КОНТАКТ', 'КОНТАКТА', 'КОНТАКТОВ');
      if (!it.length) { avs.innerHTML = ''; tip.innerHTML = ''; empty(card, 'Контактов нет', 'Добавь людей в «Контакты» телефона — здесь появятся избранные и те, кому звонишь чаще.'); return; }
      it = it.slice().sort(function (a, b) {
        return (b.starred ? 1 : 0) - (a.starred ? 1 : 0) || ((b.lastCall && b.lastCall.at) || 0) - ((a.lastCall && a.lastCall.at) || 0) || String(a.name).localeCompare(b.name);
      }).slice(0, 8);
      PP.length = 0;
      it.forEach(function (x) {
        var bd = bdNext(x.birthday), f = [], lc = x.lastCall;
        if (x.phone) f.push(['ТЕЛЕФОН', E(x.phone), 0]);
        if (bd) f.push(['ДЕНЬ РОЖДЕНИЯ', bd.d.getDate() + ' ' + MON[bd.d.getMonth()] + (bd.k === 0 ? ' · сегодня' : ' · через ' + bd.k + ' ' + plural(bd.k, 'день', 'дня', 'дней')), bd.k <= 30 ? 1 : 0]);
        if (lc && lc.at) f.push(['ЗВОНИЛИ', PRO.ago(lc.at) + (lc.dur ? ', ' + Math.max(1, Math.round(lc.dur / 60)) + ' мин' : ''), 0]);
        else f.push(['ЗВОНИЛИ', 'Давно или ни разу', 0]);
        var near = bd && bd.k <= 30, nm = E(x.name);
        var msg = 'С днём рождения, ' + x.name + '! Всего самого доброго!';
        PP.push({ n: nm, c: grad(x.name), r: x.starred ? 'ИЗБРАННОЕ' : lc ? 'НЕДАВНО НА СВЯЗИ' : 'КОНТАКТ', bd: near && bd.k <= 7 ? 1 : 0, f: f,
          phone: x.phone, near: near, msg: msg,
          t: near ? [bd.k === 0 ? 'СЕГОДНЯ ДЕНЬ РОЖДЕНИЯ' : 'СКОРО ДЕНЬ РОЖДЕНИЯ', bd.k === 0 ? 'Поздравить ' + nm + ' сегодня?' : 'У ' + nm + ' день рождения через ' + bd.k + ' ' + plural(bd.k, 'день', 'дня', 'дней') + '.', E(msg), 'Поздравить', 'Позвонить']
            : ['СВЯЗЬ', lc && lc.at ? 'Последний звонок — ' + PRO.ago(lc.at) + '.' : 'Давно не созванивались.', 'Набрать ' + nm + '?', 'Позвонить', 'SMS'] });
      });
      pSel = 0;
      if (!origDraw) {
        origDraw = pDraw;
        window.pDraw = function () {
          origDraw();
          var p = PP[pSel]; if (!p || !p.t) return;
          var g = $1('#pTip .g'), s = $1('#pTip .btn2 button:not(.g)');
          if (!p.phone) { if (g) g.onclick = function () { toast('У контакта нет номера'); }; return; }
          if (g) g.onclick = function () {
            if (p.near) PRO.q('smsTo', { phone: p.phone, text: p.msg }); else PRO.q('dial', { phone: p.phone });
            g.textContent = '✓ Открыл'; g.className = 'ok';
          };
          if (s) s.onclick = function () { if (p.near) PRO.q('dial', { phone: p.phone }); else PRO.q('smsTo', { phone: p.phone, text: '' }); };
        };
      }
      window.pDraw();
    });
  });
})();

// s18: режимы, цены, госуслуги, заметки, расходы — настоящие данные телефона.
(function () {
  if (!window.PRO || !PRO.on) return;
  var E = PRO.esc;
  function Q(el, s) { return el ? el.querySelector(s) : null; }
  function QA(el, s) { return el ? [].slice.call(el.querySelectorAll(s)) : []; }
  function hide(el) { if (el) el.style.display = 'none'; }
  function chip(ph, t) { var c = Q(ph, '.tbar .chip'); if (c) c.innerHTML = '<i></i>' + E(t); }
  var INP = 'flex:1;min-width:0;padding:9px 12px;border-radius:12px;border:1px solid var(--line,rgba(255,255,255,.14));background:rgba(255,255,255,.05);color:var(--text,#eef1f6);font:inherit;font-size:13px;outline:0';
  var BTN = 'padding:9px 14px;border-radius:12px;border:0;background:var(--gold,#FFC46B);color:#1a1206;font-weight:700;font-size:13px';
  var BTN2 = 'padding:9px 12px;border-radius:12px;border:1px solid var(--line,rgba(255,255,255,.14));background:transparent;color:var(--text,#eef1f6);font-size:12.5px;text-align:left';

  // ---------- Режимы ----------
  var ICF = '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4"/><circle cx="12" cy="12" r="1"/>';
  function ic(k) { try { return (k === 'focus' ? ICF : IC[k]) || ''; } catch (e) { return ''; } }
  var MODES = [
    { m: 'normal', k: 'home', n: 'Обычный', t: 'ЗВУК ВКЛЮЧЁН · ВСЁ КАК ВСЕГДА', a: ['Звонки и уведомления со звуком'] },
    { m: 'sleep', k: 'sleep', n: 'Сон', t: '«НЕ БЕСПОКОИТЬ» · ТИШИНА', a: ['Не беспокоить', 'Тихие уведомления'] },
    { m: 'work', k: 'work', n: 'Работа', t: 'ВИБРАЦИЯ · БЕЗ ЗВУКА', a: ['Вибрация вместо звонка'] },
    { m: 'drive', k: 'car', n: 'Дорога', t: 'ЗВУК · ОТВЕТЫ ГОЛОСОМ', a: ['Звук включён', 'Говори «Джарвис…» без рук'] },
    { m: 'focus', k: 'focus', n: 'Фокус', t: '«НЕ БЕСПОКОИТЬ» · БЕЗ ОТВЛЕЧЕНИЙ', a: ['Не беспокоить', 'Только важное'] }
  ];
  var RING = ['тихо', 'вибро', 'звук'];
  PRO.bind('modes', function (ph) {
    var now = Q(ph, '#mNow'), list = Q(ph, '#mList'), sub = Q(ph, '.sub2');
    if (sub) sub.textContent = 'НАЖМИ — ВКЛЮЧИТЬ РЕЖИМ';
    chip(ph, 'ЗАГРУЖАЮ');
    PRO.q('modes').then(function (j) {
      if (j && j.need) j = { need: j.need, dndAccess: false, mode: 'normal', ringer: -1 };
      var cur = j.mode || 'normal';
      function draw() {
        var m = MODES.filter(function (x) { return x.m === cur; })[0] || MODES[0];
        var st = 'ЗВОНОК: ' + (RING[j.ringer] || '—').toUpperCase() + (j.dnd ? ' · «НЕ БЕСПОКОИТЬ» ВКЛ' : '');
        now.innerHTML = '<small>СЕЙЧАС</small><b>' + E(m.n) + '</b><span class="w">' + E(st) + '</span><div class="ico"><svg viewBox="0 0 24 24">' + ic(m.k) + '</svg></div><div class="acts">' +
          m.a.map(function (x, i) { return '<span style="animation-delay:' + i * .12 + 's">' + E(x) + '</span>'; }).join('') + '</div>';
        if (!j.dndAccess) {
          var b = document.createElement('div');
          b.className = 'real-need';
          b.innerHTML = '<b>Нужен доступ к режиму «Не беспокоить»</b><span>Без него «Сон» и «Фокус» не смогут включить тишину.</span><button>Разрешить</button>';
          b.querySelector('button').onclick = function (e) { e.stopPropagation(); try { JarvisNative.perm('dnd'); } catch (x) {} };
          now.appendChild(b);
        }
        list.innerHTML = MODES.map(function (x) {
          return '<div class="md ' + (x.m === cur ? 'on' : '') + '" data-m="' + x.m + '"><div class="ic"><svg viewBox="0 0 24 24">' + ic(x.k) + '</svg></div><div><b>' + E(x.n) + '</b><span>' + E(x.t) + '</span></div><i class="tg ' + (x.m === cur ? 'on' : '') + '"></i></div>';
        }).join('');
        QA(list, '.md').forEach(function (d) {
          d.onclick = function (e) {
            e.stopPropagation();
            var mm = d.getAttribute('data-m');
            PRO.q('modeSet', { mode: mm }).then(function (r) {
              if (r && r.need) { PRO.toast('Дай доступ к «Не беспокоить» и нажми ещё раз'); return; }
              if (r && r.ok) { cur = r.mode || mm; PRO.refresh(); }
            });
          };
        });
        chip(ph, (MODES.filter(function (x) { return x.m === cur; })[0] || MODES[0]).n.toUpperCase());
      }
      draw();
    });
  });

  // ---------- Цены ----------
  var SHOPS = [
    ['Ozon', 'OZ', '#005bff,#0039a6', 'ru.ozon.app.android', 'https://www.ozon.ru/search/?text='],
    ['Wildberries', 'WB', '#cb11ab,#7d0a6a', 'com.wildberries.ru', 'https://www.wildberries.ru/catalog/0/search.aspx?search='],
    ['Яндекс Маркет', 'ЯМ', '#ffcc00,#c79a00', 'ru.beru.android', 'https://market.yandex.ru/search?text=']
  ];
  var pLast = '', pAns = '';
  PRO.bind('prices', function (ph) {
    var hot = Q(ph, '.hot'), pl = Q(ph, '.pl'), add = Q(ph, '.addp');
    chip(ph, 'ПОИСК ЦЕН');
    if (add) add.innerHTML = '<b>Голосом:</b> «Джарвис, сколько стоит PlayStation 5?»';
    hot.innerHTML = '<small>ДЖАРВИС · НАЙДУ ЦЕНУ</small>' +
      '<div style="display:flex;gap:8px;margin-top:8px"><input id="rpQ" placeholder="Что ищем? Например, наушники Sony" style="' + INP + '"><button id="rpGo" style="' + BTN + '">Найти</button></div>' +
      '<div id="rpHints" style="display:flex;flex-wrap:wrap;gap:6px;margin-top:8px"></div>' +
      '<div id="rpA" style="margin-top:10px;font-size:12.5px;line-height:1.45;color:var(--text2,#aab);white-space:pre-wrap"></div>';
    var inp = Q(hot, '#rpQ'), out = Q(hot, '#rpA'), hints = Q(hot, '#rpHints');
    inp.value = pLast;
    hints.innerHTML = ['iPhone 15', 'PlayStation 5', 'Робот-пылесос', 'Кроссовки Nike'].map(function (h) {
      return '<span style="' + BTN2 + ';padding:6px 10px;font-size:11.5px;cursor:pointer">' + E(h) + '</span>';
    }).join('');
    function shops() {
      var t = inp.value.trim();
      pl.innerHTML = SHOPS.map(function (s, i) {
        return '<div class="pi" data-i="' + i + '" style="cursor:pointer"><div class="ap" style="background:linear-gradient(135deg,' + s[2] + ')">' + s[1] + '</div><div><b>Открыть ' + E(s[0]) + '</b><span>' + (t ? 'Поиск: ' + E(t) : 'Введи товар выше') + '</span></div><div class="v"><em>›</em></div></div>';
      }).join('');
      QA(pl, '.pi').forEach(function (d) {
        d.onclick = function (e) {
          e.stopPropagation();
          var s = SHOPS[+d.getAttribute('data-i')], q = inp.value.trim();
          PRO.q('openApp', { pkg: s[3], url: s[4] + encodeURIComponent(q) });
        };
      });
    }
    function show() { out.innerHTML = pAns ? '<b style="color:var(--text,#eef1f6)">' + E(pLast) + '</b>\n' + E(pAns) : '<div class="real-empty">Напиши товар или нажми подсказку — Джарвис поищет цены в интернете.</div>'; }
    function go() {
      var t = inp.value.trim(); if (!t) return;
      pLast = t; shops(); out.innerHTML = '<span>Ищу цену…</span>';
      PRO.q('tool', { id: 'web.answer', args: { query: 'сколько стоит ' + t + ' в российских магазинах, цены в рублях' } }).then(function (r) {
        pAns = (r && r.msg) ? String(r.msg) : 'Не получилось найти. Проверь интернет и попробуй ещё раз.';
        if (PRO.cur === 'prices') show();
      });
    }
    Q(hot, '#rpGo').onclick = function (e) { e.stopPropagation(); go(); };
    inp.onkeydown = function (e) { if (e.key === 'Enter') go(); };
    inp.oninput = shops;
    QA(hints, 'span').forEach(function (s) { s.onclick = function (e) { e.stopPropagation(); inp.value = s.textContent; go(); }; });
    shops(); show();
  });

  // ---------- Госуслуги ----------
  var GL = [
    ['ГИ', '#2f6bff,#1b3fa8', 'Штрафы ГИБДД', 'Проверить и оплатить на сайте', 'https://www.gosuslugi.ru/pay?categories=fine'],
    ['₽', '#2f6bff,#1b3fa8', 'Налоги', 'Задолженность и уведомления ФНС', 'https://www.gosuslugi.ru/pay?categories=tax'],
    ['✚', '#21a038,#126b22', 'Запись к врачу', 'Выбрать поликлинику и время', 'https://www.gosuslugi.ru/10700/1']
  ];
  PRO.bind('gos', function (ph) {
    var head = Q(ph, '.jhead span'), gl = Q(ph, '.gl'), sub = Q(ph, '.sub2'), docs = Q(ph, '.docs');
    var hb = Q(ph, '.jhead b'); if (hb) hb.textContent = 'ОТКРОЮ И НАПОМНЮ';
    if (head) head.textContent = 'Данные Госуслуг Джарвис не видит: открытого доступа к ним нет. Он откроет нужный раздел и напомнит о сроке.';
    chip(ph, 'БЫСТРЫЕ ССЫЛКИ');
    gl.innerHTML = '<div class="ic3 j"><div class="ap" style="background:linear-gradient(135deg,#2f6bff,#1b3fa8)">ГУ</div><div><b>Открыть Госуслуги</b><span>Приложение, а если его нет — сайт</span></div><button class="g" data-a="app">Открыть</button></div>' +
      GL.map(function (g, i) {
        return '<div class="ic3"><div class="ap" style="background:linear-gradient(135deg,' + g[1] + ')">' + g[0] + '</div><div><b>' + E(g[2]) + '</b><span>' + E(g[3]) + '</span></div><button data-u="' + i + '">Открыть</button></div>';
      }).join('');
    QA(gl, 'button').forEach(function (b) {
      b.onclick = function (e) {
        e.stopPropagation();
        if (b.getAttribute('data-a') === 'app') PRO.q('openApp', { pkg: 'ru.rostel', url: 'https://www.gosuslugi.ru' });
        else PRO.q('web', { url: GL[+b.getAttribute('data-u')][4] });
      };
    });
    if (sub) sub.textContent = 'НАПОМНИТЬ О СРОКЕ';
    if (docs) {
      var d = new Date(Date.now() + 864e5), ds = d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
      docs.style.display = 'block';
      docs.innerHTML = '<div style="display:flex;flex-direction:column;gap:8px;width:100%"><input id="rgT" placeholder="Что? Например, оплатить налог" style="' + INP + '">' +
        '<div style="display:flex;gap:8px"><input id="rgD" type="date" value="' + ds + '" style="' + INP + '"><button id="rgOk" style="' + BTN + '">Напомнить</button></div><span id="rgM" style="font-size:11.5px;color:var(--text2,#aab)">Напомню в 10:00 в выбранный день.</span></div>';
      Q(docs, '#rgOk').onclick = function (e) {
        e.stopPropagation();
        var t = Q(docs, '#rgT').value.trim(), v = Q(docs, '#rgD').value, m = Q(docs, '#rgM');
        if (!t || !v) { m.textContent = 'Напиши, о чём напомнить, и выбери день.'; return; }
        var p = v.split('-'), at = new Date(+p[0], +p[1] - 1, +p[2], 10, 0).getTime();
        if (at < Date.now()) { m.textContent = 'Этот день уже прошёл — выбери другой.'; return; }
        PRO.q('reminderAdd', { text: t, at: at, daily: false }).then(function (r) {
          m.textContent = r && r.ok ? '✓ Напомню ' + PRO.day(at).toLowerCase() + ' в 10:00' : 'Не получилось сохранить напоминание.';
          if (r && r.ok) Q(docs, '#rgT').value = '';
        });
      };
    }
  });

  // ---------- Заметки ----------
  var NL = [['покупки', 'ПОКУПКИ', 'k'], ['дела', 'ДЕЛА', 'c'], ['заметки', 'ЗАМЕТКИ', '']];
  PRO.bind('notes', function (ph) {
    hide(Q(ph, '.ask')); hide(Q(ph, '.ans')); hide(Q(ph, '.qs'));
    var st = Q(ph, '#nState'), said = Q(ph, '#nSaid'), list = Q(ph, '#nList');
    if (st) { st.textContent = 'ТВОИ СПИСКИ'; st.style.color = ''; }
    if (said) said.textContent = 'Скажи: «Джарвис, добавь в покупки молоко» — или впиши ниже.';
    PRO.q('notes').then(function (j) {
      var L = (j && j.lists) || {}, total = 0;
      list.innerHTML = NL.map(function (n) {
        var it = L[n[0]] || []; total += it.length;
        return '<div style="margin:10px 2px 4px;display:flex;justify-content:space-between;align-items:center"><span class="tagj ' + n[2] + '">' + n[1] + '</span><span style="font-size:11px;color:var(--text2,#aab)">' + it.length + '</span></div>' +
          (it.length ? it.map(function (x) {
            return '<div class="nt" data-l="' + E(n[0]) + '" data-v="' + E(x) + '" style="cursor:pointer"><div><b>' + E(x) + '</b><span>НАЖМИ — ВЫЧЕРКНУТЬ</span></div><span class="tagj">✓</span></div>';
          }).join('') : '<div class="real-empty" style="padding:8px 6px">Пока пусто</div>') +
          '<div style="display:flex;gap:8px;margin:6px 0 4px"><input data-add="' + E(n[0]) + '" placeholder="Добавить в «' + E(n[0]) + '»" style="' + INP + '"><button data-addb="' + E(n[0]) + '" style="' + BTN + '">+</button></div>';
      }).join('');
      chip(ph, String(total));
      QA(list, '.nt').forEach(function (d) {
        d.onclick = function (e) {
          e.stopPropagation(); d.style.opacity = .4; d.style.textDecoration = 'line-through';
          PRO.q('noteDel', { list: d.getAttribute('data-l'), item: d.getAttribute('data-v') }).then(function () { PRO.refresh(); });
        };
      });
      function add(l) {
        var i = Q(list, 'input[data-add="' + l + '"]'), v = i.value.trim(); if (!v) return;
        PRO.q('noteAdd', { list: l, item: v }).then(function () { PRO.refresh(); });
      }
      QA(list, 'button[data-addb]').forEach(function (b) { b.onclick = function (e) { e.stopPropagation(); add(b.getAttribute('data-addb')); }; });
      QA(list, 'input[data-add]').forEach(function (i) { i.onkeydown = function (e) { if (e.key === 'Enter') add(i.getAttribute('data-add')); }; });
    });
  });

  // ---------- Расходы ----------
  var WC = ['var(--gold,#ffc46b)', '#7fe8ff', '#ff8a6b', '#c89bff', '#3fe0b0', '#9aa3b2'];
  PRO.bind('spend', function (ph) {
    var tot = Q(ph, '.tot'), cats = Q(ph, '#sCats'), tip = Q(ph, '.tip'), sub = Q(ph, '.sub2'), subs = Q(ph, '.subs'), priv = Q(ph, '.priv');
    var M = ['ЯНВАРЬ', 'ФЕВРАЛЬ', 'МАРТ', 'АПРЕЛЬ', 'МАЙ', 'ИЮНЬ', 'ИЮЛЬ', 'АВГУСТ', 'СЕНТЯБРЬ', 'ОКТЯБРЬ', 'НОЯБРЬ', 'ДЕКАБРЬ'];
    chip(ph, '30 ДНЕЙ');
    if (priv) priv.lastChild.textContent = 'Считаю по SMS банков, только на телефоне. В банк не захожу.';
    PRO.q('spend', { days: 30 }).then(function (j) {
      if (j && j.need) {
        tot.style.display = 'none'; hide(tip); hide(sub); hide(subs);
        PRO.need(cats, j); return;
      }
      tot.style.display = '';
      var it = (j && j.items) || [], ex = it.filter(function (x) { return !x.income; }), inc = it.filter(function (x) { return x.income; });
      var sum = ex.reduce(function (a, x) { return a + Math.abs(+x.sum || 0); }, 0), isum = inc.reduce(function (a, x) { return a + Math.abs(+x.sum || 0); }, 0);
      var lbl = Q(tot, '#sLbl'), ss = Q(tot, '#sSum'), cmp = Q(tot, '#sCmp'), don = Q(tot, '#sDonut');
      if (!it.length) {
        lbl.textContent = 'ЗА 30 ДНЕЙ'; ss.textContent = PRO.money(0); cmp.textContent = 'Трат не нашёл'; cmp.style.color = 'var(--text2)'; don.innerHTML = '';
        cats.innerHTML = '<div class="real-empty">В SMS банков за 30 дней нет списаний. Когда придут сообщения о покупках, Джарвис посчитает их здесь.</div>';
        hide(tip); hide(sub); hide(subs); return;
      }
      // по неделям: 0 — последние 7 дней
      var W = [0, 0, 0, 0, 0], now = Date.now();
      ex.forEach(function (x) { var k = Math.min(4, Math.max(0, Math.floor((now - x.at) / (7 * 864e5)))); W[k] += Math.abs(+x.sum || 0); });
      var WN = ['Эта неделя', 'Прошлая неделя', '2 недели назад', '3 недели назад', 'Раньше'];
      var rows = W.map(function (v, i) { return [WN[i], WC[i], v]; }).filter(function (r) { return r[2] > 0; });
      var sel = -1;
      function draw() {
        var C = 2 * Math.PI * 48, off = 0;
        don.innerHTML = sum ? rows.map(function (c, i) {
          var len = c[2] / sum * C, el = '<circle cx="60" cy="60" r="48" stroke="' + c[1] + '" stroke-dasharray="' + Math.max(0, len - 1.8) + ' ' + C + '" stroke-dashoffset="' + (-off) + '" class="' + (sel >= 0 && sel !== i ? 'dim' : '') + '" style="stroke-width:' + (sel === i ? 17 : 13) + '"/>';
          off += len; return el;
        }).join('') : '';
        cats.innerHTML = rows.length ? rows.map(function (c, i) {
          return '<div class="ct ' + (i === sel ? 'on' : '') + '" data-i="' + i + '"><i style="background:' + c[1] + '"></i><b>' + c[0] + '</b><span>' + PRO.money(c[2]) + '</span></div>';
        }).join('') : '<div class="real-empty">Списаний нет — только поступления.</div>';
        if (sel >= 0) { lbl.textContent = rows[sel][0].toUpperCase(); ss.textContent = PRO.money(rows[sel][2]); cmp.textContent = Math.round(rows[sel][2] / sum * 100) + '% всех трат'; }
        else { lbl.textContent = 'ЗА 30 ДНЕЙ · ' + M[new Date().getMonth()]; ss.textContent = PRO.money(sum); cmp.textContent = ex.length + ' трат' + (isum ? ' · доходы ' + PRO.money(isum) : ''); }
        cmp.style.color = 'var(--text2)';
        QA(cats, '.ct').forEach(function (d) { d.onclick = function (e) { e.stopPropagation(); var i = +d.getAttribute('data-i'); sel = sel === i ? -1 : i; draw(); }; });
      }
      draw();
      var big = ex.slice().sort(function (a, b) { return Math.abs(b.sum) - Math.abs(a.sum); }).slice(0, 3);
      if (tip) {
        tip.style.display = big.length ? '' : 'none';
        tip.innerHTML = '<small>КРУПНЕЙШИЕ ТРАТЫ</small>' + big.map(function (x) {
          return E(x.where || x.bank || 'Покупка') + ' — <b>' + PRO.money(Math.abs(x.sum)) + '</b> · ' + E(PRO.day(x.at).toLowerCase());
        }).join('<br>');
      }
      if (sub) { sub.style.display = ''; sub.textContent = 'ДОХОДЫ · ' + PRO.money(isum); }
      if (subs) {
        subs.style.display = '';
        subs.innerHTML = inc.length ? inc.slice().sort(function (a, b) { return b.at - a.at; }).slice(0, 5).map(function (x) {
          return '<div class="nt"><div><b>+' + PRO.money(Math.abs(x.sum)) + '</b><span>' + E(((x.where || x.bank || 'Поступление') + ' · ' + PRO.day(x.at)).toUpperCase()) + '</span></div><span class="tagj k">ДОХОД</span></div>';
        }).join('') : '<div class="real-empty" style="padding:8px 6px">Поступлений за 30 дней нет</div>';
      }
    });
  });
})();

// Экран «Машина»: вместо выдуманной подсказки — честная (последнее сообщение, на которое можно ответить).
(function () {
  if (!window.PRO || !PRO.on) return;
  var prev = PRO.binds.devices;
  PRO.bind('devices', function (el) {
    if (prev) prev(el);
    var c = el && el.querySelector('#dC');
    if (!c) return;
    var p = c.querySelector('.jp p'), bb = c.querySelector('.jp .bb');
    c.querySelectorAll('.map small').forEach(function (t) { t.style.visibility = 'hidden'; });
    if (bb) bb.style.display = 'none';
    if (!p) return;
    p.textContent = 'В машине скажи «Джарвис» — прочитаю сообщения и отвечу голосом.';
    PRO.q('notifs').then(function (j) {
      var m = j && j.items && j.items.filter(function (x) { return x.canReply; })[0];
      if (m && PRO.cur === 'devices') p.textContent = m.title + ': «' + String(m.text).slice(0, 60) + '» — скажи «Джарвис, ответь…»';
    });
  });
})();
