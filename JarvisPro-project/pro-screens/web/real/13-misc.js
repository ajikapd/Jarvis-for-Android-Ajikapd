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
