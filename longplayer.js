/* Long video player (VLC style). Separate from the Shorts / engine files. */
(function () {
  'use strict';
  var doc = document, Cap = window.Capacitor;
  if (!Cap) return;
  var VL = null;
  try { if (Cap.Plugins && Cap.Plugins.VLong) VL = Cap.Plugins.VLong; } catch (e) {}
  if (!VL) { try { if (typeof Cap.registerPlugin === 'function') VL = Cap.registerPlugin('VLong'); } catch (e) {} }
  if (!VL || typeof VL.open !== 'function') return;
  var bypass = false, changed = false, curId = null;

  function readCache() {
    try { var a = JSON.parse(localStorage.getItem('vp-scan-cache') || 'null'); return Array.isArray(a) ? a : null; } catch (e) { return null; }
  }
  function isShort(it) {
    var d = it.dur || 0;
    return d > 0 && (d <= 60 || (it.h > it.w && d <= 180));
  }
  function resumeMap() { try { return JSON.parse(localStorage.getItem('vp-resume') || '{}') || {}; } catch (e) { return {}; } }
  function saveResume(id, pos, dur) {
    if (!id) return;
    try {
      var m = resumeMap();
      if (!pos || pos < 3000 || (dur && pos > dur - 5000)) delete m[id]; else m[id] = { pos: pos, dur: dur || 0 };
      localStorage.setItem('vp-resume', JSON.stringify(m));
    } catch (e) {}
  }
  function vp() { return window.__vp || {}; }

  try {
    VL.addListener('progress', function (d) { if (d && d.id) saveResume(d.id, d.pos, d.dur); });
    VL.addListener('rem', function (d) { try { localStorage.setItem('vp-lrem', d && d.rem ? '1' : '0'); } catch (e) {} });
    VL.addListener('thumb', function (d) {
      if (!d || !d.id || !d.data) return;
      changed = true;
      if (vp().setThumb) vp().setThumb(d.id, d.data);
    });
    VL.addListener('error', function () { /* the player already shows a message */ });
    VL.addListener('closed', function (d) {
      if (d && d.id) saveResume(d.id, d.pos, d.dur);
      var ch = changed; changed = false;
      try { if (ch && vp().render) vp().render(); } catch (e) {}
      try { if (vp().focus) vp().focus(); } catch (e) {}
    });
  } catch (e) {}

  /* a tap on a long video opens the new player; if it cannot open, the old player runs as before */
  doc.addEventListener('click', function (ev) {
    if (bypass) return;
    var a = ev.target && ev.target.closest ? ev.target.closest('[data-act="play"],[data-act="playrecent"]') : null;
    if (!a) return;
    var id = a.getAttribute('data-id'), list = readCache();
    if (!id || !list) return;
    var it = null;
    for (var i = 0; i < list.length; i++) if (list[i] && list[i].id === id) { it = list[i]; break; }
    if (!it || !it.path || it.kind === 'audio' || isShort(it)) return;
    ev.stopImmediatePropagation(); ev.preventDefault();
    var ms = doc.querySelectorAll('audio,video');
    for (var m = 0; m < ms.length; m++) { try { ms[m].pause(); } catch (e) {} }
    var v = vp(), ttl = v.title ? v.title(it) : String(it.name || '').replace(/\.[^.]+$/, '').replace(/_+/g, ' ');
    try { if (v.recent && v.item) v.recent(v.item(id)); } catch (e) {}
    try { if (v.watched) v.watched(id); } catch (e) {}
    changed = false;
    var r = resumeMap()[id], rem = false;
    try { rem = localStorage.getItem('vp-lrem') === '1'; } catch (e) {}
    Promise.resolve(VL.open({ id: id, path: it.path, title: ttl, resume: r ? r.pos : 0, rem: rem })).catch(function () {
      bypass = true; try { a.click(); } catch (e) {} bypass = false;
    });
  }, true);
})();
