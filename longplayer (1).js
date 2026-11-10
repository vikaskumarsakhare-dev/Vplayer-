/* Long video player (VLC style). Separate from the Shorts / engine files. */
(function () {
  'use strict';
  var doc = document, Cap = window.Capacitor;
  if (!Cap) return;
  var VL = null;
  try { if (Cap.Plugins && Cap.Plugins.VLong) VL = Cap.Plugins.VLong; } catch (e) {}
  if (!VL) { try { if (typeof Cap.registerPlugin === 'function') VL = Cap.registerPlugin('VLong'); } catch (e) {} }
  if (!VL || typeof VL.open !== 'function') return;
  var bypass = false, changed = false, viewDone = false, longMini = false;

  function readCache() {
    try { var a = JSON.parse(localStorage.getItem('vp-scan-cache') || 'null'); return Array.isArray(a) ? a : null; } catch (e) { return null; }
  }
  function readMeta() {
    try { return JSON.parse(localStorage.getItem('vp-meta') || '{}') || {}; } catch (e) { return {}; }
  }
  function vidShort(it){
    if(!it||it.kind==='audio')return false;
    var d=it.dur||0;if(!(d>0&&d<=300))return false;
    var p=(it.path||'')+'/'+(it.name||'');
    if(/(\/dcim\/|\/camera\/|screen[ _-]?record)/i.test(p)&&!/\/V Player\//.test(it.path||''))return false;
    return /(^|[^a-z])vid[ _-]?\d/i.test(String(it.name||''));
  }
  function isShort(it, m) {
    if (m && m.short === true) return true;
    if (m && m.short === false) return false;
    var d = it.dur || 0;
    return (d > 0 && (d <= 60 || (it.h > it.w && d <= 180))) || vidShort(it);
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
  /* a view is counted once per opening, after 15 minutes of real watching (or half of a shorter video) */
  function countView(d) {
    if (viewDone || !d || !d.id || !d.watched || !d.dur) return;
    var need = d.dur > 900000 ? 900000 : d.dur * 0.5;
    if (d.watched >= need) {
      viewDone = true; changed = true;
      try { if (vp().addView) vp().addView(d.id); } catch (e) {}
    }
  }
  function say(m) { try { VL.toast({ msg: m }); } catch (e) {} }

  try {
    VL.addListener('progress', function (d) { if (d && d.id) { saveResume(d.id, d.pos, d.dur); countView(d); } });
    VL.addListener('rem', function (d) { try { localStorage.setItem('vp-lrem', d && d.rem ? '1' : '0'); } catch (e) {} });
    VL.addListener('thumb', function (d) {
      if (!d || !d.id || !d.data) return;
      changed = true;
      if (vp().setThumb) vp().setThumb(d.id, d.data);
    });
    VL.addListener('rename', function (d) { if (d && d.id && vp().rename) { changed = true; vp().rename(d.id, d.title); } });
    VL.addListener('folder', function (d) { if (d && d.id && vp().folderSet) { changed = true; vp().folderSet(d.id, d.name || ''); } });
    VL.addListener('action', function (d) {
      if (!d || !d.id) return;
      if (d.act === 'share' && vp().doShare) vp().doShare(d.id);
      else if (d.act === 'short' && vp().setShort) { changed = true; vp().setShort(d.id, true); }
      else if (d.act === 'pickThumb' && vp().pickThumb) { changed = true; vp().pickThumb(d.id); }
      else if (d.act === 'delete' && vp().doDelete) {
        Promise.resolve(vp().doDelete(d.id)).then(function (r) {
          if (r === 'cancel') return;
          say(r === 'deleted' ? 'Deleted from your phone' : 'Could not delete the file, so it is hidden from V Player');
          try { VL.close(); } catch (e) {}
        });
      }
    });
    VL.addListener('switched', function (d) {
      if (!d || !d.id) return;
      viewDone = false; changed = true;
      var v = vp();
      try { if (v.recent && v.item) v.recent(v.item(d.id)); } catch (e) {}
      try { if (v.watched) v.watched(d.id); } catch (e) {}
    });
    function miniShow(d) {
      longMini = true;
      var frac = d && d.dur > 0 ? Math.min(1, (d.pos || 0) / d.dur) : 0;
      if (vp().mini) vp().mini({ title: d && d.title || '', playing: !!(d && d.playing), frac: frac });
    }
    VL.addListener('minimized', miniShow);
    VL.addListener('state', function (d) { if (longMini) miniShow(d); });
    VL.addListener('restored', function () { longMini = false; if (vp().mini) vp().mini(null); });
    VL.addListener('titleOn', function (d) { try { localStorage.setItem('vp-ltitle', d && d.on ? '1' : '0'); } catch (e) {} });
    VL.addListener('error', function () { /* the player already shows a message */ });
    VL.addListener('closed', function (d) {
      if (longMini) { longMini = false; if (vp().mini) vp().mini(null); }
      if (d && d.id) { saveResume(d.id, d.pos, d.dur); countView(d); }
      changed = false;
      try { if (vp().render) vp().render(); } catch (e) {}
      try { if (vp().focus) vp().focus(); } catch (e) {}
    });
  } catch (e) {}

  /* the small bar at the bottom while the video plays like a song */
  doc.addEventListener('click', function (ev) {
    if (!longMini) return;
    var a = ev.target && ev.target.closest ? ev.target.closest('#mini [data-act]') : null;
    if (!a) return;
    var act = a.getAttribute('data-act');
    if (act !== 'mplay' && act !== 'mclose' && act !== 'nowopen') return;
    ev.stopImmediatePropagation(); ev.preventDefault();
    try {
      if (act === 'mplay') VL.command({ cmd: 'toggle' });
      else if (act === 'mclose') VL.command({ cmd: 'close' });
      else VL.command({ cmd: 'restore' });
    } catch (e) {}
  }, true);

  /* a tap on a long video opens the new player; if it cannot open, the old player runs as before */
  doc.addEventListener('click', function (ev) {
    if (bypass) return;
    if (ev.target && ev.target.closest && ev.target.closest('[data-act="cmenu"]')) return;
    var a = ev.target && ev.target.closest ? ev.target.closest('[data-act="play"],[data-act="playrecent"]') : null;
    if (!a) return;
    var id = a.getAttribute('data-id');
    if (!startLong(id, false, a)) return;
    ev.stopImmediatePropagation(); ev.preventDefault();
  }, true);

  /* "Play as audio" in the menu of a card: sound only, with the small bar at the bottom */
  window.__vpPlayAudio = function (id) { return startLong(id, true, null); };

  window.__vpStopLong = function () {
    if (!longMini) return;
    try { VL.command({ cmd: 'close' }); } catch (e) {}
    longMini = false;
  };

  function startLong(id, audio, a) {
    var list = readCache();
    if (!id || !list) return false;
    var it = null, meta = readMeta();
    for (var i = 0; i < list.length; i++) if (list[i] && list[i].id === id) { it = list[i]; break; }
    if (!it || !it.path || it.kind === 'audio' || isShort(it, meta[id])) return false;
    var ms = doc.querySelectorAll('audio,video');
    for (var m = 0; m < ms.length; m++) { try { ms[m].pause(); } catch (e) {} }
    var v = vp(), ttl = v.title ? v.title(it) : String(it.name || '').replace(/\.[^.]+$/, '').replace(/_+/g, ' ');
    try { if (v.recent && v.item) v.recent(v.item(id)); } catch (e) {}
    try { if (v.watched) v.watched(id); } catch (e) {}
    changed = false; viewDone = false;
    var r = resumeMap()[id], rem = false, folders = [], folder = '';
    try { rem = localStorage.getItem('vp-lrem') === '1'; } catch (e) {}
    try { if (v.folderNames) folders = v.folderNames(); if (v.folderOf) folder = v.folderOf(id); } catch (e) {}
    var queue = [], rm = resumeMap(), showTitle = true;
    try { showTitle = localStorage.getItem('vp-ltitle') !== '0'; } catch (e) {}
    try {
      var ql = v.queueFor ? v.queueFor(id) : [];
      for (var q = 0; q < ql.length; q++) {
        var qi = ql[q];
        queue.push({ id: qi.id, path: qi.path, title: qi.title, folder: qi.folder || '', resume: rm[qi.id] ? rm[qi.id].pos : 0 });
      }
    } catch (e) { queue = []; }
    Promise.resolve(VL.open({ id: id, path: it.path, title: ttl, resume: r ? r.pos : 0, rem: rem, folders: folders, folder: folder, queue: queue, showTitle: showTitle, audio: !!audio })).catch(function () {
      if (!a) return;
      bypass = true; try { a.click(); } catch (e) {} bypass = false;
    });
    return true;
  }
})();
