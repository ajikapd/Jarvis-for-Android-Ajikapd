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
