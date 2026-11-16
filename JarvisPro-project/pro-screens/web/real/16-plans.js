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
