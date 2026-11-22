/* One connection-status source for every page.
 * All tabs poll the same tiny /api/status every 2 seconds; heavy business
 * requests (题库 / 错题本) never decide whether the box is "connected".
 */
window.TVCTL = (function () {
  'use strict';
  var handlers = [], online = false, timer = null;

  function paint(isOn, text) {
    var d = document.getElementById('dot');
    var c = document.getElementById('conn');
    if (d) d.className = 'dot' + (isOn ? ' on' : '');
    if (c) c.textContent = text;
  }

  function poll() {
    fetch('/api/status', { cache: 'no-store' })
      .then(function (r) {
        if (!r.ok) throw new Error('http ' + r.status);
        return r.json();
      })
      .then(function (s) {
        online = true;
        paint(true, '已连接');
        var v = document.getElementById('ver');
        if (v && s.app_version) v.textContent = '· v' + s.app_version;
        var ck = document.getElementById('clock');
        if (ck && s.clock) ck.textContent = s.clock;
        for (var i = 0; i < handlers.length; i++) {
          try { handlers[i](s); } catch (e) {
            if (window.console) console.error('TVCTL render failed', e);
          }
        }
      })
      .catch(function (e) {
        if (window.console) console.error('TVCTL poll failed', e);
        online = false;
        paint(false, '未连接 · 检查 Wi-Fi');
      });
  }

  return {
    init: function (cb) {
      if (cb) handlers.push(cb);
      if (timer) return;
      poll();
      timer = setInterval(poll, 2000);
      document.addEventListener('visibilitychange', function () {
        if (!document.hidden) poll();
      });
    },
    isOnline: function () { return online; },
    pollNow: function () { poll(); }
  };
})();
