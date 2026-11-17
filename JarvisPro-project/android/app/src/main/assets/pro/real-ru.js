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
  var CATS = { 'Люди': /люд|people|person|contact|семь|family|мам|пап|друг/i, 'Привычки': /привыч|habit|routine|кажд|по вторн|зал|кофе/i, 'Места': /мест|place|addr|адрес|loc|дом|работ/i,
    'Вкусы': /вкус|taste|pref|like|любит|предпочит/i, 'Договорённости': /догов|promise|agree|обещ|task|дел|напомн|вернуть/i };
  function cat(kind, text) {
    var blob = (kind || '') + ' ' + (text || '');
    for (var c in CATS) if (CATS[c].test(blob)) return c;
    return '';
  }
  PRO.bind('memory', function (el) {
    var ph = phone(el, 'mem'), box = $('#facts', ph); if (!box) return;
    PRO.q('memory').then(function (j) {
      var items = (j && j.items) || [], cnt = {}, filter = ph.dataset.memFilter || '';
      $$('.tbar .chip', ph).forEach(function (c) { c.innerHTML = '<i></i>' + items.length + ' ' + ru(items.length, 'ФАКТ', 'ФАКТА', 'ФАКТОВ'); });
      items.forEach(function (f) { var c = cat(f.kind, f.text); cnt[c] = (cnt[c] || 0) + 1; });
      $$('.cat', ph).forEach(function (c) {
        var b = c.querySelector('b'); if (b) b.textContent = cnt[c.dataset.c] || 0;
        c.classList.toggle('on', filter && filter === c.dataset.c);
        if (c.dataset.wired) return; c.dataset.wired = '1';
        c.addEventListener('click', function () {
          ph.dataset.memFilter = (ph.dataset.memFilter === c.dataset.c) ? '' : (c.dataset.c || '');
          PRO.refresh();
        });
      });
      var shown = filter ? items.filter(function (f) { return cat(f.kind, f.text) === filter; }) : items;
      var h = shown.map(function (f) {
        var c = cat(f.kind, f.text), lab = (c || f.kind || 'ФАКТ') + (f.at ? ' · ' + PRO.day(f.at) : '');
        return '<div class="fact" data-c="' + E(c) + '" data-id="' + E(f.id) + '"><i class="d"></i><div><b>' + E(f.text) + '</b><span>' + E(lab.toUpperCase()) + '</span></div><button>Забыть</button></div>';
      }).join('');
      if (!items.length) h = empty('Пока ничего не помню.<br>Скажи: «Джарвис, запомни, что я пью кофе без сахара».');
      else if (!shown.length) h = empty('В категории «' + E(filter) + '» пока пусто.');
      box.innerHTML = h;
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
      add.onclick = function () {
        var ed = ph.querySelector('.real-editor');
        if (ed) ed.scrollIntoView({ block: 'nearest' });
      };
      var sm = add.querySelector('small'); if (sm) sm.textContent = 'Фраза и шаги — внизу этого же экрана';
    }
    PRO.q('routines').then(function (j) {
      var items = (j && j.items) || [], cc = $('#scCount', ph);
      if (cc) cc.textContent = items.length + ' ' + ru(items.length, 'СЦЕНАРИЙ', 'СЦЕНАРИЯ', 'СЦЕНАРИЕВ');
      if (!items.length) { box.innerHTML = empty('Своих команд пока нет.<br>Добавь фразу ниже — или запусти готовую цепочку.'); if (window.proScenMore) window.proScenMore(ph, []); return; }
      box.innerHTML = items.map(function (r, i) {
        var st = (r.steps || []).map(function (s) { return '<span>' + E(s) + '</span>'; }).join('<i>›</i>');
        return '<div class="sc" data-i="' + i + '"><div class="h"><div><b>' + E(r.name) + '</b><span class="tr">ПО ФРАЗЕ · «' + E(String(r.name).toUpperCase()) + '»</span></div>' +
          '<button class="go">ПУСК</button><button class="go" data-edit style="margin-left:6px;opacity:.85">ИЗМ</button><button class="go" data-del style="margin-left:6px;opacity:.7">✕</button></div><div class="chain">' + st + '</div></div>';
      }).join('');
      $$('.sc', box).forEach(function (sc) {
        var r = items[+sc.dataset.i];
        sc.querySelector('.go:not([data-del]):not([data-edit])').onclick = function () {
          if (sc.classList.contains('run')) return;
          sc.classList.add('run'); PRO.send(r.name);
          var st = $$('.chain span', sc), k = 0, done = false;
          function mark(i) { st.forEach(function (s, j) { s.className = j < i ? 'done' : (j === i ? 'now' : ''); }); }
          mark(0);
          function finish() {
            if (done) return; done = true; PRO._scenWait = null;
            st.forEach(function (s) { s.className = 'done'; });
            setTimeout(function () { st.forEach(function (s) { s.className = ''; }); sc.classList.remove('run'); }, 900);
          }
          PRO._scenWait = function (ev) {
            if (!ev || done) return;
            if (ev.t === 'tool' && !ev.failed) { k = Math.min(k + 1, st.length - 1); mark(k); }
            if (ev.t === 'response' || (ev.t === 'tool' && ev.failed)) finish();
          };
          setTimeout(function () { if (!done) finish(); }, 45000);
        };
        var editBtn = sc.querySelector('[data-edit]');
        if (editBtn) editBtn.onclick = function () {
          if (window.proScenEdit) window.proScenEdit(ph, r);
        };
        sc.querySelector('[data-del]').onclick = function () {
          var b = this; // первый тап — спросить, второй — удалить (confirm() в WebView ненадёжен)
          if (!b.dataset.sure) { b.dataset.sure = '1'; b.textContent = 'УДАЛИТЬ?'; setTimeout(function () { delete b.dataset.sure; b.textContent = '✕'; }, 3000); return; }
          PRO.q('routineDel', { name: r.name }).then(function (x) { if (x && x.ok) PRO.refresh(); else PRO.toast('Не получилось удалить'); });
        };
      });
      if (window.proScenMore) window.proScenMore(ph, items);
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
    yes.onclick = function (e) {
      e.stopPropagation();
      PRO.q('status').then(function (st) {
        // без overlay Live-шар не появится поверх приложений
        PRO.q('panelSet', { key: 'live', value: true }).then(function (r) {
          if (r && r.err) { PRO.toast(r.err); return; }
          try { JarvisNative.perm('overlay'); } catch (x) {}
          PRO.toast('Live включён. Разреши «поверх приложений», если ещё не дал');
        });
      });
    };
    no.onclick = function (e) { e.stopPropagation(); ph.classList.remove('open'); var pl = $('#ovPill', ph); if (pl) pl.textContent = 'НАЖМИ НА МЕНЯ'; };
  });

  // ---------- Голоса ----------
  PRO.bind('voices', function (el) {
    var ph = phone(el, 'vox'), list = $('#vList', ph); if (!list) return;
    var mk = $('#vMake', ph); if (mk) mk.onclick = function () { var o = $('#vOwn', ph); if (o) o.scrollIntoView({ block: 'nearest' }); };
    PRO.q('voices').then(function (j) {
      var items = (j && j.items) || [];
      if (!items.length) { list.innerHTML = empty('Голоса не найдены.<br>Скачай голос кнопкой ниже или проверь сеть.'); return; }
      var cur = items.filter(function (v) { return v.on; })[0];
      if (cur) { $('#vChip', ph).textContent = String(cur.name).toUpperCase(); $('#vState', ph).textContent = 'ГОЛОС ВЫБРАН'; $('#vLine', ph).textContent = 'Сейчас я говорю голосом «' + cur.name + '».'; }
      list.innerHTML = items.map(function (v, i) {
        var right = v.ready ? '<div class="wv">' + '<i></i>'.repeat(14) + '</div>' :
          '<button data-dl style="margin-left:auto;padding:6px 12px;border-radius:10px;border:1px solid var(--gold,#FFC46B);background:transparent;color:var(--gold,#FFC46B);font-size:12px">Скачать</button>';
        return '<div class="vc ' + (v.on ? 'on' : '') + '" data-i="' + i + '"><div class="pl">' + (v.on ? '✓' : '▶') + '</div><div><b>' + E(v.name) + '</b><span>' + E(v.about || (v.ready ? '' : 'Нужно скачать')) + '</span></div>' + right + '</div>';
      }).join('');
      $$('.vc', list).forEach(function (c) {
        var v = items[+c.dataset.i];
        var dl = c.querySelector('[data-dl]');
        if (dl) dl.onclick = function (e) {
          e.stopPropagation();
          if (!v.download) { PRO.toast('Этот голос уже в приложении'); return; }
          dl.textContent = 'Качаю…';
          PRO.q('panelModel', { id: v.download, action: 'download' }).then(function (r) {
            if (r && r.err) { dl.textContent = 'Скачать'; PRO.toast(r.err); return; }
            PRO.toast('Загрузка началась'); setTimeout(PRO.refresh, 1200);
          });
        };
        c.onclick = function () {
          if (!v.ready) { if (dl) dl.click(); return; }
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
  var agBusy = false, agTools = 0, agUi = null;
  function agResetSlots(ph) {
    $$('.stage .ov .arc, .stage .ov .wait, .stage .ov .flow', ph).forEach(function (x) { x.style.opacity = 0; });
    $$('.stage .sk', ph).forEach(function (s, i) { s.classList.remove('act'); s.textContent = '0' + (i + 1) + ' · СВОБОДНО'; });
  }
  function agPaint(st) {
    if (!agUi) return;
    var chip = agUi.chip, sk = agUi.sk, ok = agUi.ok;
    if (st === 'start') {
      agBusy = true; agTools = 0;
      if (chip) chip.textContent = 'В РАБОТЕ';
      agResetSlots(agUi.ph);
      if (sk[0]) { sk[0].classList.add('act'); sk[0].textContent = '01 · СЛУШАЮ'; }
      if (ok) ok.style.display = 'none';
      return;
    }
    if (st === 'think') {
      if (chip) chip.textContent = 'ДУМАЮ';
      if (sk[0]) { sk[0].classList.remove('act'); sk[0].textContent = '01 · ПРИНЯЛ'; }
      if (sk[1]) { sk[1].classList.add('act'); sk[1].textContent = '02 · ДУМАЮ'; }
      return;
    }
    if (st === 'tool') {
      agTools++;
      if (chip) chip.textContent = agTools + ' ШАГ' + (agTools === 1 ? '' : (agTools < 5 ? 'А' : 'ОВ'));
      if (sk[1]) { sk[1].classList.remove('act'); sk[1].textContent = '02 · ПЛАН'; }
      var slot = sk[Math.min(1 + agTools, sk.length - 1)];
      if (slot) { slot.classList.add('act'); slot.textContent = '0' + (Math.min(2 + agTools, 5)) + ' · ДЕЛАЮ'; }
      return;
    }
    if (st === 'confirm') {
      if (chip) chip.textContent = 'ЖДУ «ОК»';
      if (ok) { ok.style.display = ''; ok.textContent = 'Нужно подтверждение — скажи «да» или нажми внизу'; }
      return;
    }
    if (st === 'fail') {
      agBusy = false;
      if (chip) chip.textContent = 'СБОЙ';
      if (ok) { ok.style.display = ''; ok.textContent = 'Шаг не удался — смотри ответ в диалоге'; }
      return;
    }
    if (st === 'done') {
      agBusy = false;
      if (chip) chip.textContent = agTools ? ('ГОТОВО · ' + agTools) : 'ГОТОВО';
      sk.forEach(function (s) { s.classList.remove('act'); });
      if (ok) { ok.style.display = ''; ok.textContent = 'Задание закрыто — ответ в диалоге'; }
    }
  }
  PRO.agentTick = function (ev) {
    if (!agBusy && !(ev && (ev.t === 'request' || ev.t === 'wake'))) return;
    if (!ev) return;
    if (ev.t === 'request' || ev.t === 'wake') agPaint('start');
    else if (ev.t === 'intent') agPaint('think');
    else if (ev.t === 'tool') {
      if (ev.pending) agPaint('confirm');
      else if (ev.failed) agPaint('fail');
      else agPaint('tool');
    }
    else if (ev.t === 'response') agPaint(ev.pending ? 'confirm' : 'done');
  };
  PRO.agentState = function (st) {
    if (!agBusy) return;
    if (st === 'THINKING' || st === 'EXECUTING') agPaint('think');
    if (st === 'IDLE' || st === 'READY') { if (agBusy) agPaint('done'); }
  };
  PRO.bind('agent', function (el) {
    var ph = phone(el, 'agent'); if (!ph) return;
    var chip = $('#agChip', ph); if (chip && !agBusy) chip.textContent = 'ГОТОВ';
    agResetSlots(ph);
    var ok = $('#okbar', ph); if (ok && !agBusy) ok.style.display = 'none';
    agUi = { ph: ph, chip: chip, sk: $$('.stage .sk', ph), ok: ok };
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
        PRO.send(t); inp.value = ''; agPaint('start'); PRO.toast('Агент взялся: ' + t);
      };
      $('#agGo', task).onclick = go;
      inp.onkeydown = function (e) { if (e.key === 'Enter') go(); };
    }
    var rows = $('.rows', ph);
    if (rows) {
      rows.innerHTML = TIPS.map(function (t, i) { return '<div class="row2" data-i="' + i + '" style="cursor:pointer"><b>›</b>' + E(t) + '<em>ПУСК</em></div>'; }).join('');
      $$('.row2', rows).forEach(function (r) { r.onclick = function () { var t = TIPS[+r.dataset.i]; PRO.send(t); agPaint('start'); PRO.toast('Агент взялся: ' + t); }; });
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
    return p.then(function (j) { PRO.toast(j && j.ok && j.removed !== false ? 'Готово: ' + it.text : 'Не получилось'); PRO.refresh(); });
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
  /** Контент между шапкой и нижней панелью — в прокрутку, чтобы nav не закрывал кнопки. */
  function phoneScroll(ph) {
    var ui = ph && ph.querySelector('.ui'); if (!ui) return;
    if (ui.querySelector(':scope > .real-scroll')) return;
    var box = document.createElement('div'); box.className = 'real-scroll';
    var nav = ui.querySelector('.navw');
    [].slice.call(ui.children).forEach(function (k) {
      if (k.classList.contains('sb') || k.classList.contains('navw') || k.classList.contains('tbar') || k.classList.contains('vign') || k.classList.contains('real-scroll')) return;
      box.appendChild(k);
    });
    if (nav) ui.insertBefore(box, nav); else ui.appendChild(box);
  }

  // ---------- Брифинг ----------
  PRO.bind('brief', function () {
    phoneScroll($1('#brief'));
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
      bDraw();
      document.querySelectorAll('#bList .bc').forEach(function (c, i) {
        if (BR[bK].items[i] && BR[bK].items[i][3] === 'ОТВЕТЫ') { c.style.cursor = 'pointer'; c.onclick = function () { location.hash = 'reply'; }; }
      });
      function speakBrief() {
        var b = BR[bK], lines = [b.hi].concat((b.items || []).map(function (x) { return x[4] || x[1]; }));
        var text = lines.filter(Boolean).join(' ');
        var st = $1('#bState'), line = $1('#bLine'), bar = $1('#bBar');
        if (st) { st.textContent = 'ГОВОРЮ'; st.style.color = 'var(--gold)'; }
        if (line) line.textContent = b.hi;
        if (bar) { bar.style.transition = 'none'; bar.style.width = '0'; setTimeout(function () { bar.style.transition = 'width 2s linear'; bar.style.width = '100%'; }, 30); }
        var say = text.length > 500 ? text.slice(0, 498) + '…' : text;
        try { JarvisNative.speak(say); } catch (e) { PRO.send(say); }
        setTimeout(function () {
          if (st) { st.textContent = 'ГОТОВО'; st.style.color = 'var(--ok)'; }
          if (line) line.textContent = bK === 'm' ? 'Хорошего дня. Если что — я рядом.' : 'Спокойной ночи, сэр.';
        }, Math.min(12000, 1800 + say.length * 40));
      }
      var again = $1('#bAgain');
      if (again) again.onclick = function () { bDraw(); speakBrief(); };
      var only = $1('#bText');
      if (only) only.onclick = function () {
        var st = $1('#bState'), line = $1('#bLine'), bar = $1('#bBar');
        if (st) { st.textContent = 'ТЕКСТОМ'; st.style.color = 'var(--text2)'; }
        if (line) line.textContent = 'Всё на карточках ниже.';
        if (bar) bar.style.width = '100%';
        document.querySelectorAll('#bList .bc').forEach(function (c) { c.classList.remove('on'); c.classList.add('done'); });
      };
      document.querySelectorAll('#bSeg span').forEach(function (s) {
        s.onclick = function () {
          bK = s.dataset.k;
          document.querySelectorAll('#bSeg span').forEach(function (x) { x.classList.toggle('on', x === s); });
          bDraw();
        };
      });
      // расписание утро/вечер
      var host = $1('#brief .btn2') || $1('#brief');
      if (host && !document.getElementById('bSched')) {
        var box = document.createElement('div');
        box.id = 'bSched';
        box.className = 'b-sched';
        box.innerHTML = '<div class="bs-h"><b>Расписание брифинга</b><label class="bs-tog"><input id="bOn" type="checkbox"> Авто</label></div>' +
          '<div class="bs-row"><label class="bs-t">УТРО<input id="bM" type="time" value="08:00"></label>' +
          '<label class="bs-t">ВЕЧЕР<input id="bE" type="time" value="21:00"></label></div>' +
          '<button type="button" class="bs-save" id="bSave">Сохранить</button><div class="bs-msg" id="bSchedMsg"></div>';
        host.parentNode.insertBefore(box, host.nextSibling);
        PRO.q('briefGet').then(function (j) {
          if (!j || !j.ok) return;
          var on = document.getElementById('bOn'), m = document.getElementById('bM'), e = document.getElementById('bE');
          if (on) on.checked = !!j.on;
          if (m && j.morning) m.value = j.morning;
          if (e && j.evening) e.value = j.evening;
        });
        document.getElementById('bSave').onclick = function () {
          var on = document.getElementById('bOn').checked;
          var m = document.getElementById('bM').value || '08:00';
          var e = document.getElementById('bE').value || '21:00';
          PRO.q('briefSet', { on: on, morning: m, evening: e }).then(function (r) {
            var msg = document.getElementById('bSchedMsg');
            if (msg) msg.textContent = r && r.ok ? (on ? 'Буду говорить в ' + m + ' и ' + e : 'Авто-брифинг выключен') : 'Не сохранилось';
          });
        };
      }
      // не автопроигрываем анимацию — только по кнопке
      var st0 = $1('#bState'); if (st0) { st0.textContent = 'ГОТОВ'; st0.style.color = ''; }
    });
  });

  // ---------- Ответы ----------
  var TPL = ['Перезвоню позже', 'Ок, понял', 'Скоро буду'];
  PRO.bind('reply', function () {
    phoneScroll($1('#rpl'));
    var rl = $1('#rpl .rl'); if (!rl) return;
    var sub = $1('#rpl .jhead span'), chip = $1('#rpl .tbar .chip');
    PRO.q('notifs').then(function (j) {
      if (PRO.need(rl, j)) return;
      var it = arr(j).filter(function (x) { return x.canReply; });
      if (chip) chip.innerHTML = '<i></i>' + (it.length ? it.length + ' ЖДУТ' : 'ВСЁ ТИХО');
      if (sub) sub.textContent = it.length ? 'Ждут ответа: ' + it.length + '. Шаблон, свой текст или «Сгенерировать» — потом «Отправить».' : 'Новых сообщений нет.';
      if (!it.length) { empty(rl, 'Никто не ждёт ответа', 'Сообщения из мессенджеров с кнопкой «Ответить» появятся здесь.'); return; }
      rl.innerHTML = it.map(function (x, i) {
        return '<div class="rc" data-n="' + i + '"><div class="h"><div class="av" style="background:' + grad(x.title) + '">' + E(ini(x.title)) + '</div><div><b>' + E(x.title || x.app) + '</b><small>' +
          E(String(x.app || '').toUpperCase() + ' · ' + PRO.ago(x.time).toUpperCase()) + '</small></div><span class="tagj c">ЖДЁТ</span></div>' +
          '<div class="in">' + E(x.text) + '</div><div class="dr"><small>ТВОЙ ОТВЕТ</small>' +
          '<textarea class="tx" rows="2" style="width:100%;margin-top:6px;padding:10px 12px;border-radius:12px;border:1px solid var(--line,rgba(255,255,255,.18));background:rgba(0,0,0,.28);color:var(--text,#eef1f6);font:inherit;font-size:13.5px;line-height:1.4;resize:vertical;outline:0">' + E(TPL[0]) + '</textarea></div>' +
          '<div class="alt">' + TPL.map(function (t, k) { return '<span' + (k ? '' : ' class="on"') + ' data-t="' + t + '">' + t + '</span>'; }).join('') +
            '<span data-gen style="opacity:.85">Сгенерировать</span></div>' +
          '<div class="btn2"><button class="sk">Позже</button><button class="g go">Отправить</button></div></div>';
      }).join('');
      rl.querySelectorAll('.rc').forEach(function (rc) {
        var x = it[+rc.dataset.n];
        var tx = rc.querySelector('.tx');
        function setText(t) {
          if (!tx) return;
          tx.value = t;
          rc.querySelectorAll('.alt span').forEach(function (y) { y.classList.toggle('on', y.dataset.t === t); });
        }
        tx && tx.addEventListener('input', function () {
          rc.querySelectorAll('.alt span').forEach(function (y) { y.classList.remove('on'); });
        });
        rc.querySelectorAll('.alt span').forEach(function (s) {
          s.onclick = function () {
            if (rc.classList.contains('sent')) return;
            if (s.hasAttribute('data-gen')) {
              if (tx) tx.value = 'Думаю…';
              var from = x.title || x.app || 'собеседник';
              var msg = x.text || '';
              var q = 'Напиши короткий ответ от первого лица (я / мне), как будто я сам пишу в мессенджер. Без кавычек, без пояснений, без «Джарвис», без «могу ответить». Только текст сообщения. Собеседник: ' + from + '. Их сообщение: «' + msg + '»';
              PRO.q('tool', { id: 'web.answer', args: { query: q } }).then(function (r) {
                var ans = (r && r.msg) ? String(r.msg).replace(/\s+/g, ' ').trim() : TPL[0];
                ans = ans.replace(/^["«]|["»]$/g, '').replace(/^(вот|ответ|можно так)[:\s-]*/i, '').trim().slice(0, 160);
                if (!ans) ans = TPL[0];
                setText(ans);
                rc.querySelectorAll('.alt span').forEach(function (y) { y.classList.toggle('on', y === s); });
              });
              return;
            }
            setText(s.dataset.t);
          };
        });
        rc.querySelector('.go').onclick = function () {
          if (rc.classList.contains('sent')) return;
          var text = (tx && tx.value || '').trim();
          if (!text) { if (tx) tx.focus(); toast('Напиши ответ'); return; }
          var b = this; b.textContent = '…';
          PRO.q('notifReply', { key: x.key, text: text }).then(function (r) {
            if (r && r.ok && r.sent === true) {
              rc.classList.add('sent'); rc.querySelector('.dr small').textContent = 'ОТПРАВЛЕНО · СЕЙЧАС';
              var t = rc.querySelector('.h .tagj'); t.textContent = 'ОТВЕТИЛ'; t.className = 'tagj k';
              if (tx) { tx.disabled = true; }
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
    phoneScroll($1('#imp'));
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
    if (one) { one.style.display = ''; one.textContent = 'Добавить'; one.onclick = function () { var ed = ph.querySelector('.real-editor, .real-pack'); if (ed) ed.scrollIntoView({ block: 'nearest' }); }; }
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
        var e = els[sel], name = it[sel].name;
        PRO.send(name);
        e.classList.remove('ok'); e.classList.add('wait');
        var t = e.querySelector('.tagj'); t.textContent = 'В РАБОТЕ'; t.className = 'tagj w';
        all.textContent = 'Жду ответ…'; all.className = 'g'; all.disabled = true;
        var doneOnce = false;
        function finish(ok, label) {
          if (doneOnce) return; doneOnce = true; PRO._chainWait = null;
          e.classList.remove('wait'); e.classList.toggle('ok', !!ok);
          t.textContent = label; t.className = 'tagj ' + (ok ? 'k' : 'w');
          all.textContent = ok ? '✓ ' + label : label; all.className = ok ? 'ok' : 'g'; all.disabled = false;
        }
        PRO._chainWait = function (ev) {
          if (!ev || doneOnce) return;
          if (ev.t === 'response') finish(true, 'ГОТОВО');
          else if (ev.t === 'tool' && ev.failed) finish(false, 'СБОЙ');
          else if (ev.t === 'tool' && ev.pending) { t.textContent = 'ЖДУ «ОК»'; t.className = 'tagj w'; all.textContent = 'Нужно подтверждение'; }
        };
        setTimeout(function () { if (!doneOnce) finish(false, 'НЕТ ОТВЕТА'); }, 45000);
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
            if (p.near) {
              PRO.q('smsTo', { phone: p.phone, text: p.msg });
              g.textContent = '✓ Черновик SMS'; g.className = 'ok';
              PRO.toast('Открыл черновик поздравления — отправь сам');
            } else {
              PRO.q('dial', { phone: p.phone });
              g.textContent = '✓ Открыл'; g.className = 'ok';
            }
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
    { m: 'focus', k: 'focus', n: 'Фокус', t: 'ПРИОРИТЕТ · ТОЛЬКО ВАЖНОЕ', a: ['Режим приоритета', 'Звонки и отмеченные'] }
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
  PRO.bind('prices', function (ph) {
    var hot = Q(ph, '.hot'), pl = Q(ph, '.pl'), add = Q(ph, '.addp');
    chip(ph, 'СЛЕЖУ');
    if (add) add.innerHTML = '<b>Локальный список:</b> без API магазинов — проверю по запросу и напомню, когда цена около цели.';
    hot.innerHTML = '<small>WATCHLIST · НА ТЕЛЕФОНЕ</small>' +
      '<div style="display:flex;flex-direction:column;gap:8px;margin-top:8px">' +
      '<input id="rpName" placeholder="Товар" style="' + INP + '">' +
      '<div style="display:flex;gap:8px"><input id="rpTarget" type="number" placeholder="Цель ₽" style="' + INP + '"><button id="rpAdd" style="' + BTN + '">Следить</button></div>' +
      '<button id="rpCheck" style="' + BTN2 + '">Проверить сейчас</button></div>' +
      '<div id="rpList" style="margin-top:10px"></div>';
    function drawWatch(items) {
      var box = Q(hot, '#rpList');
      if (!items || !items.length) {
        box.innerHTML = '<div class="real-empty">Пока пусто. Добавь товар и целевую цену — проверю раз в несколько часов.</div>';
        return;
      }
      box.innerHTML = items.map(function (x) {
        var price = x.lastPrice != null ? Math.round(x.lastPrice) + ' ₽' : 'ещё не проверял';
        return '<div class="pi" data-id="' + E(x.id) + '"><div class="ap" style="background:linear-gradient(135deg,var(--gold,#ffc46b),#a67c3a)">₽</div>' +
          '<div><b>' + E(x.name) + '</b><span>Цель ' + Math.round(x.target) + ' ₽ · сейчас ' + E(price) + (x.hit ? ' · ЦЕЛЬ' : '') + '</span></div>' +
          '<button data-del="' + E(x.id) + '" style="padding:6px 10px;border-radius:10px;border:1px solid var(--line);background:transparent;color:var(--text2)">✕</button></div>';
      }).join('');
      QA(box, '[data-del]').forEach(function (b) {
        b.onclick = function (e) {
          e.stopPropagation();
          PRO.q('pricesDel', { id: b.getAttribute('data-del') }).then(function () { PRO.refresh(); });
        };
      });
    }
    function reload() {
      PRO.q('pricesWatch').then(function (j) { drawWatch((j && j.items) || []); });
    }
    Q(hot, '#rpAdd').onclick = function (e) {
      e.stopPropagation();
      var name = Q(hot, '#rpName').value.trim(), target = +Q(hot, '#rpTarget').value;
      PRO.q('pricesAdd', { name: name, query: name, target: target }).then(function (r) {
        if (r && r.ok) { Q(hot, '#rpName').value = ''; Q(hot, '#rpTarget').value = ''; reload(); PRO.toast('Слежу за «' + name + '»'); }
        else PRO.toast((r && r.err) || 'Не добавилось');
      });
    };
    Q(hot, '#rpCheck').onclick = function (e) {
      e.stopPropagation();
      Q(hot, '#rpCheck').textContent = 'Проверяю…';
      PRO.q('pricesCheck').then(function (r) {
        Q(hot, '#rpCheck').textContent = 'Проверить сейчас';
        drawWatch((r && r.items) || []);
        PRO.toast(r && r.ok ? ('Проверил: ' + (r.checked || 0)) : 'Не удалось проверить');
      });
    };
    pl.innerHTML = SHOPS.map(function (s, i) {
      return '<div class="pi" data-i="' + i + '" style="cursor:pointer"><div class="ap" style="background:linear-gradient(135deg,' + s[2] + ')">' + s[1] + '</div><div><b>Открыть ' + E(s[0]) + '</b><span>Поиск вручную в магазине</span></div><div class="v"><em>›</em></div></div>';
    }).join('');
    QA(pl, '.pi').forEach(function (d) {
      d.onclick = function (e) {
        e.stopPropagation();
        var s = SHOPS[+d.getAttribute('data-i')], q = (Q(hot, '#rpName').value || '').trim();
        PRO.q('openApp', { pkg: s[3], url: s[4] + encodeURIComponent(q) });
      };
    });
    reload();
  });

  // ---------- Госуслуги ----------
  var GL = [
    ['ГИ', '#2f6bff,#1b3fa8', 'Штрафы ГИБДД', 'Открою сайт — оплату и долги сам не вижу', 'https://www.gosuslugi.ru/pay?categories=fine'],
    ['₽', '#2f6bff,#1b3fa8', 'Налоги', 'Ссылка на раздел ФНС, без доступа к счёту', 'https://www.gosuslugi.ru/pay?categories=tax'],
    ['✚', '#21a038,#126b22', 'Запись к врачу', 'Открою запись — документы из аккаунта не читаю', 'https://www.gosuslugi.ru/10700/1']
  ];
  PRO.bind('gos', function (ph) {
    var head = Q(ph, '.jhead span'), gl = Q(ph, '.gl'), sub = Q(ph, '.sub2'), docs = Q(ph, '.docs');
    var hb = Q(ph, '.jhead b'); if (hb) hb.textContent = 'ОТКРОЮ И НАПОМНЮ';
    if (head) head.textContent = 'Джарвис не заходит в твой аккаунт Госуслуг: только открывает приложение/сайт и ставит локальные напоминания.';
    chip(ph, 'ССЫЛКИ · НАПОМИНАНИЯ');
    gl.innerHTML = '<div class="ic3 j"><div class="ap" style="background:linear-gradient(135deg,#2f6bff,#1b3fa8)">ГУ</div><div><b>Открыть Госуслуги</b><span>Приложение, а если его нет — сайт</span></div><button class="g" data-a="app">Открыть</button></div>' +
      GL.map(function (g, i) {
        return '<div class="ic3"><div class="ap" style="background:linear-gradient(135deg,' + g[1] + ')">' + g[0] + '</div><div><b>' + E(g[2]) + '</b><span>' + E(g[3]) + '</span></div><button data-u="' + i + '">Открыть</button></div>';
      }).join('');
    QA(gl, 'button').forEach(function (b) {
      b.onclick = function (e) {
        e.stopPropagation();
        if (b.getAttribute('data-a') === 'app') PRO.q('openApp', { pkg: 'ru.gosuslugi.mobile,ru.rostel,ru.gosuslugi', url: 'https://www.gosuslugi.ru' });
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
    chip(ph, 'ПО НЕДЕЛЯМ');
    if (priv) priv.lastChild.textContent = 'Считаю по SMS банков за 30 дней, группы — по неделям. В банк не захожу.';
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

// Разделы, которые раньше открывались чужой страницей, живут внутри тех же экранов Pro.
(function () {
  if (!window.PRO || !PRO.on) return;
  var E = PRO.esc;
  function phone(el, id) { return (el && (el.id === id ? el : el.querySelector('#' + id))) || document.getElementById(id); }
  function scrollOf(ph) {
    var ui = ph.querySelector('.ui'); if (!ui) return ph;
    var box = ui.querySelector(':scope > .real-scroll');
    if (box) return box;
    box = document.createElement('div'); box.className = 'real-scroll';
    var nav = ui.querySelector('.navw');
    [].slice.call(ui.children).forEach(function (k) {
      if (k.classList.contains('sb') || k.classList.contains('navw') || k.classList.contains('tbar') || k.classList.contains('vign') || k.classList.contains('hero-core') || k.classList.contains('dyn-core') || k.classList.contains('real-scroll')) return;
      box.appendChild(k);
    });
    if (nav) ui.insertBefore(box, nav); else ui.appendChild(box);
    return box;
  }
  function setKey(key, value) {
    return PRO.q('panelSet', { key: key, value: value }).then(function (r) {
      if (r && r.err) { PRO.toast(r.err); return false; }
      if (r && r.restart) PRO.toast('Слух перезапускается');
      return true;
    });
  }
  function group(parent, title) {
    var s = document.createElement('div'); s.className = 'sub2'; s.textContent = title;
    var g = document.createElement('div'); g.className = 'grp';
    parent.appendChild(s); parent.appendChild(g); return g;
  }
  function row(g, title, sub) {
    var d = document.createElement('div'); d.className = 'row3';
    d.innerHTML = '<div>' + E(title) + (sub ? '<small>' + E(sub) + '</small>' : '') + '</div>';
    g.appendChild(d); return d;
  }
  function toggle(g, title, sub, on, key, invert) {
    var d = row(g, title, sub);
    var shown = invert ? !on : !!on;
    var i = document.createElement('i'); i.className = 'tg' + (shown ? ' on' : '');
    i.onclick = function (e) {
      e.stopPropagation();
      var wantShown = !i.classList.contains('on');
      var stored = invert ? !wantShown : wantShown;
      setKey(key, stored).then(function (ok) { if (ok) i.classList.toggle('on', wantShown); });
    };
    d.appendChild(i);
  }
  function labelOf(choices, value) {
    var v = String(value == null ? '' : value);
    for (var i = 0; i < choices.length; i++) if (String(choices[i][0]) === v) return choices[i][1];
    return choices[0] ? choices[0][1] : '';
  }
  function openSheet(title, choices, value, onPick) {
    var old = document.getElementById('pickSheet'); if (old) old.remove();
    var wrap = document.createElement('div');
    wrap.id = 'pickSheet'; wrap.className = 'pick-sheet appui';
    wrap.innerHTML = '<div class="pick-bg"></div><div class="pick-card"><div class="pick-h"></div><div class="pick-list"></div><button type="button" class="pick-x">Отмена</button></div>';
    wrap.querySelector('.pick-h').textContent = title;
    var list = wrap.querySelector('.pick-list');
    var cur = String(value == null ? (choices[0] && choices[0][0]) : value);
    choices.forEach(function (c) {
      var b = document.createElement('button'); b.type = 'button'; b.className = 'pick-opt' + (String(c[0]) === cur ? ' on' : '');
      b.innerHTML = '<span></span><i></i>';
      b.querySelector('span').textContent = c[1];
      b.onclick = function (e) {
        e.stopPropagation();
        wrap.classList.remove('on');
        setTimeout(function () { wrap.remove(); }, 220);
        onPick(String(c[0]), c[1]);
      };
      list.appendChild(b);
    });
    function close() { wrap.classList.remove('on'); setTimeout(function () { wrap.remove(); }, 220); }
    wrap.querySelector('.pick-bg').onclick = close;
    wrap.querySelector('.pick-x').onclick = close;
    document.body.appendChild(wrap);
    requestAnimationFrame(function () { wrap.classList.add('on'); });
  }
  function makePick(title, choices, value, onChange) {
    var btn = document.createElement('button'); btn.type = 'button'; btn.className = 'real-pick';
    var v = String(value == null ? (choices[0] && choices[0][0]) : value);
    btn.innerHTML = '<span></span><svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg>';
    btn.querySelector('span').textContent = labelOf(choices, v);
    btn.setAttribute('data-v', v);
    btn.onclick = function (e) {
      e.stopPropagation();
      openSheet(title, choices, btn.getAttribute('data-v'), function (nv, lab) {
        var prev = btn.getAttribute('data-v');
        onChange(nv, function (ok) {
          if (!ok) return;
          btn.setAttribute('data-v', nv);
          btn.querySelector('span').textContent = lab;
        }, prev);
      });
    };
    return btn;
  }
  function pick(g, title, sub, key, value, choices) {
    var d = row(g, title, sub); d.classList.add('col');
    d.appendChild(makePick(title, choices, value, function (nv, done) {
      setKey(key, nv).then(function (ok) { done(ok); });
    }));
  }
  function text(g, title, key, value) {
    var d = row(g, title, ''); d.classList.add('col');
    var input = document.createElement('input'); input.className = 'real-in'; input.value = value || '';
    input.onchange = function () { setKey(key, input.value); };
    d.appendChild(input);
  }
  function goBtn(d, label, primary, fn) {
    var b = document.createElement('button'); b.type = 'button'; b.className = 'real-go' + (primary ? ' fill' : ''); b.textContent = label;
    b.onclick = function (e) { e.stopPropagation(); fn(b); };
    d.appendChild(b); return b;
  }
  function findRow(ph, start) {
    var rows = ph.querySelectorAll('.row3');
    for (var i = 0; i < rows.length; i++) {
      var div = rows[i].querySelector(':scope > div');
      var t = div && div.childNodes[0] ? String(div.childNodes[0].textContent || '').trim() : '';
      if (t.indexOf(start) === 0) return rows[i];
    }
    return null;
  }
  function ownToggle(rowEl, on, key, invert) {
    var tg = rowEl.querySelector('.tg'); if (!tg) return;
    var n = tg.cloneNode(true); tg.replaceWith(n);
    var shown = invert ? !on : !!on;
    n.classList.toggle('on', shown);
    n.addEventListener('click', function (e) {
      e.stopPropagation(); e.preventDefault();
      var wantShown = !n.classList.contains('on');
      setKey(key, invert ? !wantShown : wantShown).then(function (ok) { if (ok) n.classList.toggle('on', wantShown); });
    });
  }
  function chain(route, id, fn) {
    var prev = PRO.binds[route];
    PRO.bind(route, function (el) {
      if (prev) prev(el);
      var ph = id ? (phone(el, id) || el) : el;
      if (ph) fn(ph);
    });
  }
  function fresh(ph) {
    var old = ph.querySelector('.real-extra'); if (old) old.remove();
    var box = document.createElement('div'); box.className = 'real-extra';
    scrollOf(ph).appendChild(box); return box;
  }

  chain('memory', 'mem', function (ph) {
    var box = fresh(ph);
    PRO.q('panelSettings').then(function (j) {
      var s = (j && j.items) || {};
      var g = group(box, 'БЕЗОПАСНОСТЬ');
      toggle(g, 'Запоминать факты', 'Долгосрочная память на телефоне', s.memory, 'memory');
      toggle(g, 'Подтверждать важное', 'Звонки, сообщения и удаление', s.confirmSensitive, 'confirmSensitive');
      var facts = group(box, 'СОХРАНЁННЫЕ ФАКТЫ');
      PRO.q('panelMemoryFacts').then(function (mf) {
        var n = (mf && mf.count) || 0;
        var list = (mf && mf.items) || [];
        if (!list.length) row(facts, 'Пока пусто', 'Скажи: «запомни, что…»');
        else list.slice(0, 12).forEach(function (f) { row(facts, f.text || 'Факт', f.category || ''); });
        if (n > 12) row(facts, 'Ещё факты', 'Всего ' + n);
      });
      var d = row(g, 'Очистить память', 'Удалит все сохранённые факты');
      goBtn(d, 'Очистить', false, function (b) {
        if (b.dataset.sure !== '1') { b.dataset.sure = '1'; b.textContent = 'Точно очистить?'; setTimeout(function () { b.dataset.sure = ''; b.textContent = 'Очистить'; }, 2500); return; }
        PRO.q('panelMemoryClear').then(function (r) { if (r && r.err) PRO.toast(r.err); else { PRO.toast('Память очищена'); PRO.refresh(); } });
      });
    });
  });

  chain('voices', 'vox', function (ph) {
    var box = fresh(ph); box.id = 'vOwn';
    PRO.q('panelSettings').then(function (st) {
      var s = (st && st.items) || {};
      return PRO.q('panelVoice').then(function (v) {
        var g = group(box, 'СЛУХ');
        toggle(g, 'Слово «Джарвис»', 'Команда начинается с обращения', s.wake, 'wake');
        pick(g, 'Чувствительность', 'Чем выше, тем чаще срабатывает', 'sensitivity', s.sensitivity, [['0', 'Низкая'], ['1', 'Средняя'], ['2', 'Высокая']]);
        toggle(g, 'Сигнал активации', 'Короткий звук после обращения', s.chime, 'chime');
        var detail = !v.ready ? 'Скачайте модель узнавания в настройках, блок «ИИ»' : !v.enrolled ? 'Запишите «Джарвис»: ' + (v.wordSamples || 0) + ' из ' + (v.wordNeeded || 10) : !v.phrases ? 'Прочитайте: «' + (v.nextPhrase || 'фразу') + '»' : 'Образец записан. Выучено: ' + (v.learned || 0);
        var own = row(g, 'Образец владельца', detail);
        if (v.ready && !v.enrolled) goBtn(own, 'Записать «Джарвис»', true, function () { record('word'); });
        else if (v.ready && !v.phrases) goBtn(own, 'Записать фразу', true, function () { record('phrase'); });
        else if (v.ready) goBtn(own, 'Записать заново', false, function (b) {
          if (b.dataset.sure !== '1') { b.dataset.sure = '1'; b.textContent = 'Стереть образец?'; setTimeout(function () { b.dataset.sure = ''; b.textContent = 'Записать заново'; }, 2500); return; }
          PRO.q('panelVoiceForget').then(function (r) { if (r && r.err) PRO.toast(r.err); else PRO.refresh(); });
        });
        toggle(g, 'Только мой голос', v.ready ? 'Чужое «Джарвис» не принимается' : 'Модель узнавания не загружена', s.voiceLock, 'voiceLock');
        var secDetail = !v.secondReady
          ? 'Скачайте ERes2Net в блоке «ИИ» (~68 МБ) — точнее отсекает чужие голоса'
          : !v.enrolled
            ? 'Сначала запишите образец владельца'
            : 'Выучено: ваших «Джарвис» ' + (v.secondOwn || 0) + ', чужих ' + (v.secondImp || 0);
        var sec = row(g, 'Точная проверка голоса', secDetail);
        if (!v.secondReady) goBtn(sec, 'Модели', false, function () { if (window.proOpen) window.proOpen('settings'); });
        var vol = row(g, 'Громкость голоса', String(s.volume == null ? 100 : s.volume)); vol.classList.add('col');
        var range = document.createElement('input'); range.className = 'real-in'; range.type = 'range'; range.min = '10'; range.max = '100'; range.value = s.volume || 100;
        range.oninput = function () { var sm = vol.querySelector('small'); if (sm) sm.textContent = range.value; };
        range.onchange = function () { setKey('volume', range.value).then(function (ok) { if (ok) try { JarvisNative.previewVoice(); } catch (e) {} }); };
        vol.appendChild(range);
        toggle(g, 'Громкость как у музыки', '', s.followMedia, 'followMedia');
        var talk = group(box, 'РАЗГОВОР И РАСПОЗНАВАНИЕ');
        toggle(talk, 'Продолжать разговор', 'Без повторного обращения', s.conversation, 'conversation');
        toggle(talk, 'Подстраивать слух', '', s.adaptive, 'adaptive');
        toggle(talk, 'Беречь батарею ниже 20%', '', s.lowPower, 'lowPower');
        pick(talk, 'Распознавание', '', 'asrMode', s.asrMode, [['AUTO', 'Авто'], ['CLOUD_PREFERRED', 'Облако'], ['LOCAL_ONLY', 'Локально']]);
        text(talk, 'Адрес сервера распознавания', 'asrUrl', s.asrUrl);
        pick(talk, 'Синтез', '', 'ttsMode', s.ttsMode, [['AUTO', 'Авто'], ['CLOUD_PREFERRED', 'Облако'], ['LOCAL_ONLY', 'Локально']]);
        text(talk, 'Адрес сервера синтеза', 'ttsUrl', s.ttsUrl);
      });
    });
    function record(type) {
      PRO.toast('Говорите. Не закрывайте экран.');
      PRO.q('panelRecord', { type: type }).then(function (r) { if (r && r.err) PRO.toast(r.err); else { PRO.toast((r && r.msg) || 'Запись сохранена'); PRO.refresh(); } });
    }
  });

  function stripSection(ph, title) {
    var subs = ph.querySelectorAll('.sub2');
    for (var i = 0; i < subs.length; i++) {
      if (String(subs[i].textContent || '').trim() !== title) continue;
      var g = subs[i].nextElementSibling;
      subs[i].remove();
      if (g && g.classList.contains('grp')) g.remove();
      return true;
    }
    return false;
  }

  chain('settings', 'set', function (ph) {
    scrollOf(ph);
    // Голос и слух живут во вкладке «Голос»; память и подтверждения — в «Память».
    stripSection(ph, 'ГОЛОС И СЛУХ');
    var memRow = findRow(ph, 'Память только'); if (memRow) memRow.remove();
    var themeRow = findRow(ph, 'Тема');
    if (themeRow && !themeRow.dataset.jump) {
      themeRow.dataset.jump = '1';
      themeRow.addEventListener('click', function (e) {
        if (e.target.closest('[data-th]')) return;
        if (window.proOpen) window.proOpen('themes');
      });
    }
    PRO.q('panelSettings').then(function (j) {
      var s = (j && j.items) || {};
      var net = findRow(ph, 'Интернет');
      if (net) {
        var netTitle = net.querySelector(':scope > div');
        if (netTitle && netTitle.childNodes[0]) netTitle.childNodes[0].textContent = 'Предпочитать облако';
        var netSmall = net.querySelector('small');
        if (netSmall) netSmall.textContent = 'Есть сеть и ключ — облако, иначе локально';
        ownToggle(net, s.preferCloud, 'preferCloud', false);
      }
      var seg = ph.querySelector('#mdl');
      if (seg) {
        var map = { 'Быстро': 'FAST', 'Баланс': 'BALANCED', 'Качество': 'QUALITY' };
        [].slice.call(seg.querySelectorAll('span')).forEach(function (sp) {
          if (sp.dataset.wired) return; sp.dataset.wired = '1';
          var n = sp.cloneNode(true); sp.replaceWith(n); n.dataset.wired = '1';
          n.classList.toggle('on', map[n.textContent.trim()] === s.chatProfile);
          n.onclick = function (e) {
            e.stopPropagation();
            setKey('chatProfile', map[n.textContent.trim()]).then(function (ok) {
              if (!ok) return;
              [].forEach.call(seg.querySelectorAll('span'), function (x) { x.classList.toggle('on', x === n); });
              var hint = ph.querySelector('#mdlHint'); if (hint) hint.textContent = n.dataset.h || '';
            });
          };
        });
      }
      var box = fresh(ph);
      var jump = group(box, 'ГОЛОС И СЛУХ');
      var openV = row(jump, 'Голос, слово и слух', 'Выбор голоса, образец владельца, распознавание');
      goBtn(openV, 'Открыть', true, function () { if (window.proOpen) window.proOpen('voices'); });
      var openM = row(jump, 'Память и подтверждения', 'Факты, безопасность, очистка');
      goBtn(openM, 'Открыть', false, function () { if (window.proOpen) window.proOpen('memory'); });
      var who = group(box, 'ХАРАКТЕР');
      pick(who, 'Персона', '', 'persona', s.persona, [['CLASSIC', 'Джарвис'], ['FRIENDLY', 'Друг'], ['MINIMAL', 'Кратко'], ['DEVELOPER', 'Инженер']]);
      var conf = row(who, 'Порог уверенности', String(Math.round((+s.confidence || 0.55) * 100)) + '%'); conf.classList.add('col');
      var cr = document.createElement('input'); cr.className = 'real-in'; cr.type = 'range'; cr.min = '30'; cr.max = '90'; cr.step = '5'; cr.value = Math.round((+s.confidence || 0.55) * 100);
      cr.oninput = function () { var sm = conf.querySelector('small'); if (sm) sm.textContent = cr.value + '%'; };
      cr.onchange = function () { setKey('confidence', (cr.value / 100).toFixed(2)); };
      conf.appendChild(cr);
      var look = group(box, 'ОФОРМЛЕНИЕ');
      toggle(look, 'Звуки интерфейса', 'Сигнал активации и эффекты', s.hudSfx, 'hudSfx');
      toggle(look, 'Орб прилипает к краю', 'Плавающая кнопка поверх приложений', s.orbSnap, 'orbSnap');
      var mind = group(box, 'ИИ И МОДЕЛИ');
      pick(mind, 'Где думать', '', 'router', s.router, [['HYBRID', 'Гибрид'], ['LOCAL', 'Локально'], ['CLOUD', 'Облако']]);
      toggle(mind, 'Использовать ИИ-модель', '', s.llm, 'llm');
      toggle(mind, 'Предзагрузка в память', 'Модель готова до первой фразы', s.preload, 'preload');
      text(mind, 'Адрес своего сервера', 'cloudUrl', s.cloudUrl);
      if (s.llamaOff) {
        var llama = row(mind, 'Локальная модель отключена', 'Ранее она уронила приложение. Без неё работают команды и облако.');
        goBtn(llama, 'Включить снова', false, function () {
          PRO.q('panelLlamaReset').then(function (r) { if (r && r.err) PRO.toast(r.err); else { PRO.toast('Локальная модель включена'); PRO.refresh(); } });
        });
      }
      keys(mind);
      models(box);
      var here = group(box, 'ПРИСУТСТВИЕ');
      text(here, 'Город по умолчанию', 'city', s.city);
      toggle(here, 'Проактивность', '', s.proactive, 'proactive');
      toggle(here, 'Фоновые уведомления', '', s.bgNotify, 'bgNotify');
      toggle(here, 'Тихие часы', '23:00–07:00', s.quiet, 'quiet');
      toggle(here, 'Объявлять звонки', '', s.announceCalls, 'announceCalls');
      toggle(here, 'Объявлять сообщения', '', s.announceMessages, 'announceMessages');
      toggle(here, 'Встречи и сводка дня', 'За 15 минут и после будильника', s.announceHints, 'announceHints');
      var usage = row(here, 'Статистика приложений', 'Системный доступ для присутствия');
      goBtn(usage, 'Открыть', false, function () { try { JarvisNative.perm('usage'); } catch (e) {} });
      var apps = group(box, 'ПРИЛОЖЕНИЯ');
      aliases(apps);
    });
  });

  function keys(g) {
    PRO.q('panelKeys').then(function (j) {
      if (!j || j.err) return;
      var names = { groq: 'Groq', gemini: 'Gemini', gigachat: 'GigaChat', safev: 'SafeV · Nemotron', custom: 'Свой сервер', custom_asr: 'Ключ распознавания', custom_tts: 'Ключ синтеза' };
      Object.keys(names).forEach(function (id) {
        var hint = id === 'gigachat'
          ? (j.items && j.items[id] ? 'Ключ сохранён' : 'developers.sber.ru → Сбер ID → GigaChat API')
          : (j.items && j.items[id] ? 'Ключ сохранён' : 'Ключ не задан');
        var d = row(g, names[id], hint); d.classList.add('col');
        var input = document.createElement('input'); input.className = 'real-in'; input.type = 'password'; input.placeholder = 'Вставьте ключ';
        d.appendChild(input);
        goBtn(d, 'Сохранить', true, function () {
          PRO.q('panelKey', { id: id, action: 'save', key: input.value }).then(function (r) {
            input.value = ''; if (r && r.err) PRO.toast(r.err); else PRO.toast(r && r.saved ? 'Сохранено' : 'Не сохранилось');
          });
        });
      });
    });
  }
  function models(parent) {
    var g = group(parent, 'ЛОКАЛЬНЫЕ МОДЕЛИ');
    function draw() {
      PRO.q('panelModels').then(function (j) {
        g.querySelectorAll('.row3').forEach(function (n) { n.remove(); });
        ((j && j.items) || []).forEach(function (m) {
          var d = row(g, m.title, m.about + ' · ' + m.mb + ' МБ · ' + m.state);
          if (m.state === 'DOWNLOADING') goBtn(d, 'Отменить', false, function () { PRO.q('panelModel', { id: m.id, action: 'cancel' }).then(draw); });
          else if (m.state !== 'READY') goBtn(d, 'Скачать', true, function () { PRO.q('panelModel', { id: m.id, action: 'download' }).then(function () { PRO.toast('Загрузка началась'); draw(); }); });
        });
      });
    }
    draw();
  }
  function aliases(g) {
    PRO.q('panelAliases').then(function (j) {
      if (!j || j.err) { if (j && j.err) PRO.toast(j.err); return; }
      (j.items || []).forEach(function (x) {
        var d = row(g, x.alias, '→ ' + x.app);
        goBtn(d, 'Удалить', false, function (b) {
          if (b.dataset.sure !== '1') { b.dataset.sure = '1'; b.textContent = 'Точно?'; setTimeout(function () { b.dataset.sure = ''; b.textContent = 'Удалить'; }, 2000); return; }
          PRO.q('panelAlias', { action: 'delete', alias: x.alias }).then(function () { PRO.refresh(); });
        });
      });
      var d = row(g, 'Новый синоним', 'Слово, которым открывать приложение'); d.classList.add('col');
      var apps = (j.apps || []).map(function (a) { return [a.pkg, a.name]; });
      var pkg = apps[0] ? apps[0][0] : '';
      var appPick = makePick('Приложение', apps.length ? apps : [['', 'Нет приложений']], pkg, function (nv, done) { pkg = nv; done(true); });
      var input = document.createElement('input'); input.className = 'real-in'; input.placeholder = 'Например: телега';
      d.appendChild(appPick); d.appendChild(input);
      goBtn(d, 'Сохранить', true, function () {
        PRO.q('panelAlias', { action: 'add', alias: input.value, pkg: pkg }).then(function (r) { if (r && r.err) PRO.toast(r.err); else PRO.refresh(); });
      });
    });
  }

  window.proScenEdit = function (ph, r) {
    var pack = ph.querySelector('.real-pack');
    if (!pack && window.proScenMore) window.proScenMore(ph, []);
    pack = ph.querySelector('.real-pack');
    if (!pack) return;
    var name = pack.querySelector('input.real-in');
    var body = pack.querySelector('textarea.real-in');
    if (name) name.value = r.name || '';
    if (body) body.value = (r.steps && r.steps.length) ? r.steps.join('\n') : (r.body || '');
    pack.dataset.editOld = r.name || '';
    if (pack.scrollIntoView) pack.scrollIntoView({ block: 'nearest' });
    PRO.toast('Редактирование «' + (r.name || '') + '»');
  };

  window.proScenMore = function (ph, items) {
    scrollOf(ph);
    var old = ph.querySelector('.real-pack'); if (old) old.remove();
    var pack = document.createElement('div'); pack.className = 'real-pack';
    var anchor = ph.querySelector('.addsc'); if (anchor) anchor.after(pack); else scrollOf(ph).appendChild(pack);
    var g = group(pack, 'ГОТОВЫЕ ЦЕПОЧКИ');
    [['Режим сна', 'режим сна'], ['Режим кино', 'режим кино'], ['Режим работы', 'режим работы'], ['Я ухожу', 'я ухожу'], ['Я проснулся', 'я проснулся']].forEach(function (x) {
      var d = row(g, x[0], 'Встроенная фраза');
      goBtn(d, 'Пуск', false, function () { PRO.send(x[1]); PRO.toast('Команда отправлена'); });
    });
    var ed = group(pack, 'СВОЯ КОМАНДА');
    var form = row(ed, 'Новая фраза', 'Каждый шаг с новой строки. Уже сохранённые — карточками выше, «ИЗМ» — правка.'); form.classList.add('col');
    var name = document.createElement('input'); name.className = 'real-in'; name.placeholder = 'Например: я дома';
    var modeVal = 'CHAIN';
    var modePick = makePick('Тип команды', [['CHAIN', 'Цепочка'], ['AGENT', 'Задание агенту']], modeVal, function (nv, done) { modeVal = nv; done(true); });
    var body = document.createElement('textarea'); body.className = 'real-in'; body.placeholder = 'Включи свет\nВключи музыку';
    form.appendChild(name); form.appendChild(modePick); form.appendChild(body);
    PRO.q('panelRoutines').then(function (j) {
      var templates = (j && j.templates) || [];
      if (!templates.length) return;
      var choices = [['', 'Готовый шаг…']].concat(templates.map(function (t) { return [t, t]; }));
      form.appendChild(makePick('Готовый шаг', choices, '', function (nv, done) {
        if (nv) body.value += (body.value.trim() ? '\n' : '') + nv;
        done(false);
      }));
    });
    goBtn(form, 'Сохранить', true, function () {
      var oldName = pack.dataset.editOld || '';
      PRO.q('panelRoutine', { action: 'save', old: oldName, name: name.value, mode: modeVal, body: body.value }).then(function (r) {
        if (r && r.err) PRO.toast(r.err); else { pack.dataset.editOld = ''; PRO.toast('Команда сохранена'); PRO.refresh(); }
      });
    });
    var ev = group(pack, 'ПО СОБЫТИЯМ');
    PRO.q('panelEvents').then(function (j) {
      ((j && j.items) || []).forEach(function (x) {
        var d = row(ev, x.title + (x.arg ? ' · ' + x.arg : ''), x.hasSteps ? 'Команда: «' + x.name + '»' : 'Нет шагов');
        goBtn(d, 'Удалить', false, function () {
          PRO.q('panelEvent', { action: 'delete', trigger: x.trigger, arg: x.arg || '' }).then(function () { PRO.refresh(); });
        });
      });
      var formE = row(ev, 'Новое событие', 'Запуск без голоса'); formE.classList.add('col');
      var trig = (j && j.triggers && j.triggers[0] && j.triggers[0].id) || 'HEADPHONES_ON';
      var trigChoices = ((j && j.triggers) || []).map(function (t) { return [t.id, t.title]; });
      if (!trigChoices.length) trigChoices = [['HEADPHONES_ON', 'подключены наушники']];
      formE.appendChild(makePick('Событие', trigChoices, trig, function (nv, done) { trig = nv; done(true); }));
      var argIn = document.createElement('input'); argIn.className = 'real-in'; argIn.placeholder = 'Приложение (только для «открыто приложение»)';
      var stepsIn = document.createElement('textarea'); stepsIn.className = 'real-in'; stepsIn.placeholder = 'Шаги с новой строки';
      formE.appendChild(argIn); formE.appendChild(stepsIn);
      goBtn(formE, 'Сохранить', true, function () {
        PRO.q('panelEvent', { action: 'save', trigger: trig, arg: argIn.value, body: stepsIn.value }).then(function (r) {
          if (r && r.err) PRO.toast(r.err); else { PRO.toast('Событие сохранено'); PRO.refresh(); }
        });
      });
    });
  };

  chain('skills', '', function (ph) {
    var box = ph.querySelector('.real-scroll'); if (!box) return;
    box.innerHTML = '';
    PRO.q('panelSkills').then(function (j) {
      var groups = {};
      ((j && j.items) || []).forEach(function (x) { (groups[x.category] || (groups[x.category] = [])).push(x); });
      Object.keys(groups).forEach(function (name) {
        var g = group(box, name.toUpperCase());
        groups[name].forEach(function (x) {
          var d = row(g, x.title, x.description + (x.example ? ' · «' + x.example + '»' : ''));
          if (x.example && (x.risk === 'SAFE' || x.risk === 'ONLINE')) goBtn(d, 'Попробовать', false, function () { PRO.send(x.example); if (window.proOpen) window.proOpen('dialog'); });
        });
      });
    });
  });

  chain('system', '', function (ph) { fillSystem(ph, false); });
  chain('setup', '', function (ph) { fillSystem(ph, true); });
  function fillSystem(ph, setup) {
    var box = ph.querySelector('.real-scroll'); if (!box) return;
    box.innerHTML = '';
    function ring(pct, label) {
      var p = Math.max(0, Math.min(100, +pct || 0));
      var C = 2 * Math.PI * 42;
      var off = C * (1 - p / 100);
      return '<div class="sys-ring"><svg viewBox="0 0 100 100"><circle class="bg" cx="50" cy="50" r="42"/><circle class="fg" cx="50" cy="50" r="42" stroke-dasharray="' + C.toFixed(1) + '" stroke-dashoffset="' + off.toFixed(1) + '"/></svg><b>' + p + '%</b><small>' + label + '</small></div>';
    }
    function bar(label, value, pct) {
      var p = Math.max(0, Math.min(100, +pct || 0));
      return '<div class="sys-bar"><div class="hb"><span>' + label + '</span><b>' + value + '</b></div><div class="track"><i style="width:' + p + '%"></i></div></div>';
    }
    PRO.q('panelSettings').then(function (st) {
      var s = (st && st.items) || {};
      if (setup) {
        var p = group(box, 'НАЧАЛО РАБОТЫ');
        var mic = row(p, 'Микрофон', s.micGranted ? 'Разрешён' : 'Нужен, чтобы слышать вас');
        goBtn(mic, s.micGranted ? 'Готово' : 'Разрешить', true, function () { try { JarvisNative.perm('setup'); } catch (e) {} });
        var note = row(p, 'Уведомления', s.notificationsGranted ? 'Разрешены' : 'Нужны для голосовой службы');
        goBtn(note, s.notificationsGranted ? 'Готово' : 'Разрешить', true, function () { try { JarvisNative.perm('postNotifications'); } catch (e) {} });
        var go = row(p, 'Запустить Джарвиса', 'После микрофона и уведомлений');
        goBtn(go, 'Начать', true, function () { try { JarvisNative.finishSetup(); } catch (e) {} });
      }
      PRO.q('panelSystem').then(function (j) {
        if (!j || j.err) return;
        var g = group(box, 'СОСТОЯНИЕ');
        var viz = document.createElement('div');
        viz.className = 'sys-grid';
        viz.innerHTML = ring(j.battery, 'ЗАРЯД') + ring(j.cpu, 'CPU') + ring(j.ram, 'ПАМЯТЬ') + ring(Math.min(100, Math.round((+j.temperature || 0) * 2)), 'ТЕМП');
        g.appendChild(viz);
        var tb = document.createElement('div');
        tb.innerHTML = bar('Температура', (+j.temperature || 0) + '°C', Math.min(100, Math.round((+j.temperature || 0) * 2))) +
          bar('Свободно', (+j.storage || 0) + ' ГБ', Math.min(100, Math.max(8, 100 - (+j.storage || 0) * 2)));
        g.appendChild(tb);
        var meta = group(box, 'СТАТУС');
        [['Сеть', j.network || '—'], ['Голосовая служба', j.voice ? 'работает' : 'выключена'], ['Live', j.live ? 'включён' : 'выключен']].forEach(function (x) { row(meta, x[0], String(x[1])); });
        var lat = group(box, 'ЗАДЕРЖКА ОТВЕТА');
        ((j.latency) || []).slice(0, 6).forEach(function (line) { row(lat, 'Метрика', String(line)); });
        if (!(j.latency || []).length) row(lat, 'Пока нет замеров', 'Появятся после голосовых команд');
        var sit = group(box, 'ТЕКУЩАЯ СИТУАЦИЯ');
        var sitRow = row(sit, 'Погода, календарь, уведомления', 'Как на экране «Анализ»');
        goBtn(sitRow, 'Обновить', true, function () {
          PRO.q('panelScan').then(function (sc) {
            if (!sc || sc.err) { PRO.toast((sc && sc.err) || 'Не удалось'); return; }
            sit.querySelectorAll('.row3').forEach(function (n) { if (n !== sitRow) n.remove(); });
            row(sit, 'Погода', sc.temp ? (sc.temp + '°') : (sc.weather || 'нет данных'));
            row(sit, 'Календарь', sc.calendarEmpty ? 'свободно' : (sc.calendar || 'есть события'));
            row(sit, 'Уведомления', String(sc.notifs || 0) + ' новых');
            var brief = row(sit, 'Сводка дня голосом', 'Прочитать вслух');
            goBtn(brief, 'Сказать', false, function () { PRO.send('сводка на день'); if (window.proOpen) window.proOpen('dialog'); });
          });
        });
        var a = group(box, 'РАЗРЕШЕНИЯ');
        [['Микрофон', 'setup'], ['Уведомления (служба)', 'postNotifications'], ['Чтение уведомлений', 'notifications'], ['Статистика приложений', 'usage'], ['Спец. возможности', 'accessibility'], ['Поверх других приложений', 'overlay'], ['Изменение настроек', 'write'], ['Цифровой помощник', 'assistant'], ['Автозапуск', 'autostart'], ['Работа в фоне', 'battery']].forEach(function (x) {
          var d = row(a, x[0], 'Системный доступ');
          goBtn(d, 'Открыть', false, function () { try { JarvisNative.perm(x[1]); } catch (e) {} });
        });
        var h = group(box, 'ПОМОЩЬ');
        var guide = row(h, 'Как пользоваться', 'Короткий гид');
        goBtn(guide, 'Открыть', false, function () { try { JarvisNative.openGuide(); } catch (e) { if (window.proOpen) window.proOpen('tourAgain'); } });
        var rep = row(h, 'Отчёт об ошибке', 'Модель телефона и техническая часть, без ваших команд');
        goBtn(rep, 'Поделиться', true, function () { try { JarvisNative.shareReport(); } catch (e) {} });
        var tour = row(h, 'Экскурсия', 'Как устроен Pro');
        goBtn(tour, 'Открыть', false, function () { if (window.proOpen) window.proOpen('tourAgain'); });
        var isDebug = false;
        try { isDebug = !!(JarvisNative.debug && JarvisNative.debug()); } catch (e) {}
        if (isDebug) {
          var cons = row(h, 'Консоль', 'Проверка команд · только debug');
          goBtn(cons, 'Открыть', false, function () { try { JarvisNative.openConsole(); } catch (e) { PRO.toast('Консоль недоступна'); } });
        }
      });
    });
  }
})();
