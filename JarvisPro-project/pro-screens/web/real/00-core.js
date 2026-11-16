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
