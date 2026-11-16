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
