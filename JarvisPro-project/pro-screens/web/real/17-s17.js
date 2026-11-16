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
