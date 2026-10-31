/* V Player engine: runs before index.html. It does not change index.html.
   1. Shorts play in the app's own player (smooth, the next and previous ones are ready).
      A video that cannot play there, or is too slow to start, is taken over by the phone's own player (ExoPlayer).
   2. The video and song list is remembered, so the app shows it at once on every open.
      The phone is scanned in the background and new files are added on their own.
   3. The phone Back button goes one step back. On the main screen, press twice to exit. */
(function () {
  'use strict';
  var doc = document;
  function R() { return doc.documentElement; }
  /* small check strip at the top-left, shown only when something goes wrong or the phone's player takes over, hides itself after 7 seconds */
  var DL = [], dT = 0;
  function dbg(m) {
    try {
      DL.push(m); if (DL.length > 9) DL.shift();
      if (!/takeover|FAILED|error|stopped|slow/i.test(m)) return;
      var paint = function () {
        var d = doc.getElementById('engdbg');
        if (!d) {
          d = doc.createElement('div'); d.id = 'engdbg';
          d.style.cssText = 'position:fixed;top:64px;left:6px;z-index:9999;max-width:92%;font:11px/1.35 monospace;color:#9f9;background:rgba(0,0,0,.78);padding:5px 8px;border-radius:8px;pointer-events:none;white-space:pre-wrap;word-break:break-all';
          (doc.body || R()).appendChild(d);
        }
        d.style.display = ''; d.textContent = DL.join('\n');
        clearTimeout(dT); dT = setTimeout(function () { d.style.display = 'none'; }, 7000);
      };
      if (doc.body) paint(); else doc.addEventListener('DOMContentLoaded', paint, { once: true });
    } catch (e) {}
  }
  dbg('engine.js loaded');
  window.addEventListener('error', function (ev) { dbg('JS error: ' + (ev.message || '?')); });
  var Cap = window.Capacitor;
  if (!Cap) { dbg('stopped: Capacitor missing'); return; }
  var isNative = false;
  try { isNative = Cap.isNativePlatform(); } catch (e) {}
  if (!isNative) { dbg('stopped: not a native app'); return; }

  function $(s) { return doc.querySelector(s); }
  function noop() {}

  /* ---------- looks ---------- */
  var st = doc.createElement('style');
  st.textContent =
    'html.eng-on,html.eng-on body{background:transparent!important}' +
    'html.eng-on #app,html.eng-on .panel,html.eng-on #player{visibility:hidden!important}' +
    'html.eng-on #reels{background:transparent!important}' +
    '.reel.eng-live{background:transparent!important}' +
    '.reel video[data-eng]{background:transparent!important}' +
    '#engscan{position:fixed;left:50%;transform:translateX(-50%);z-index:45;background:#1d2540;color:#eef0f7;border:1px solid #273049;border-radius:999px;padding:6px 14px;font:12.5px/1.2 system-ui,sans-serif;display:flex;gap:8px;align-items:center;pointer-events:none;max-width:90%;white-space:nowrap}' +
    '#engscan i{width:12px;height:12px;border-radius:50%;border:2px solid #394468;border-top-color:#ffb52e;animation:engsp .8s linear infinite;flex:none}' +
    '#engscan.done i{display:none}' +
    '@keyframes engsp{to{transform:rotate(360deg)}}';
  function addStyle() { var t = doc.head || R(); if (t) t.appendChild(st); else doc.addEventListener('DOMContentLoaded', addStyle, { once: true }); }
  addStyle();

  /* on this phone the Capacitor object may not have registerPlugin; plugins are then read from Capacitor.Plugins */
  var origReg = (typeof Cap.registerPlugin === 'function')
    ? Cap.registerPlugin.bind(Cap)
    : function (n) { return Cap.Plugins ? Cap.Plugins[n] : undefined; };
  var VE = null;
  try { VE = origReg('VEngine'); } catch (e) { VE = null; }
  var engOK = !!VE;
  dbg('registerPlugin: ' + (typeof Cap.registerPlugin === 'function' ? 'yes' : 'no, using Plugins'));
  dbg('plugin object: ' + (VE ? 'yes' : 'no'));

  function call(name, args) {
    if (!VE) return Promise.reject(new Error('no engine'));
    try { return Promise.resolve(VE[name](args || {})); } catch (e) { return Promise.reject(e); }
  }

  /* ================= 0. shorts in the app's own full screen player (like Instagram) ================= */
  var VS = null;
  try { VS = origReg('VShorts'); } catch (e) { VS = null; }
  var shortsOK = !!(VS && typeof VS.open === 'function');
  dbg('native shorts: ' + (shortsOK ? 'yes' : 'no'));
  var bypass = false;
  function isShortItem(it) {
    if (!it || it.kind === 'audio') return false;
    var d = it.dur || 0;
    return d > 0 && (d <= 60 || (it.h > it.w && d <= 180));
  }
  /* a tap on a short opens the native screen; the old viewer stays as the fallback if it cannot open */
  doc.addEventListener('click', function (ev) {
    if (!shortsOK || bypass) return;
    var a = ev.target && ev.target.closest ? ev.target.closest('[data-act="play"],[data-act="playrecent"]') : null;
    if (!a) return;
    var id = a.getAttribute('data-id'), list = readCache();
    if (!id || !list) return;
    var meta = {};
    try { meta = JSON.parse(localStorage.getItem('vp-meta') || '{}') || {}; } catch (e) {}
    var paths = [], at = -1;
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      if (!it || !it.path || (meta[it.id] && meta[it.id].hidden)) continue;
      if (isShortItem(it)) { if (it.id === id) at = paths.length; paths.push(it.path); }
    }
    if (at < 0) return;
    ev.stopImmediatePropagation(); ev.preventDefault();
    var ms = doc.querySelectorAll('audio,video');
    for (var m = 0; m < ms.length; m++) { try { ms[m].pause(); } catch (e) {} }
    Promise.resolve(VS.open({ paths: paths, index: at })).catch(function (err) {
      dbg('native shorts FAILED: ' + (err && err.message ? err.message : err));
      bypass = true; try { a.click(); } catch (e) {} bypass = false;
    });
  }, true);

  /* ================= 1. list that opens at once + background scan ================= */
  var KEY = 'vp-scan-cache', skipBg = false;
  function readCache() {
    try {
      var s = localStorage.getItem(KEY);
      if (!s) return null;
      var a = JSON.parse(s);
      return (Array.isArray(a) && a.length) ? a : null;
    } catch (e) { return null; }
  }
  function writeCache(a) { try { localStorage.setItem(KEY, JSON.stringify(a || [])); } catch (e) {} }
  function sig(a) {
    var s = a.length + '|';
    for (var i = 0; i < a.length; i++) s += a[i].id + ':' + a[i].name + '|';
    return s;
  }
  function refreshApp() {
    var b = doc.createElement('button');
    b.setAttribute('data-act', 'rescan');
    b.hidden = true;
    (doc.body || R()).appendChild(b);
    b.click();
    b.remove();
  }
  var scanEl = null, scanT = 0;
  function scanBar(msg, spin, hideMs) {
    try {
      if (!scanEl) { scanEl = doc.createElement('div'); scanEl.id = 'engscan'; (doc.body || R()).appendChild(scanEl); }
      var tabs = $('.tabs'), mini = $('#mini'), bottom = 8;
      if (tabs) bottom += tabs.offsetHeight;
      if (mini && !mini.hidden) bottom += mini.offsetHeight;
      scanEl.style.bottom = bottom + 'px';
      scanEl.className = spin ? '' : 'done';
      scanEl.innerHTML = '<i></i><span></span>';
      scanEl.lastChild.textContent = msg;
      scanEl.style.display = '';
      clearTimeout(scanT);
      if (hideMs) scanT = setTimeout(function () { scanEl.style.display = 'none'; }, hideMs);
    } catch (e) {}
  }
  function scanFn(p) {
    var useSkip = skipBg; skipBg = false;
    var c = readCache();
    if (c) {
      if (!useSkip) {
        var s0 = sig(c), had = {};
        for (var k = 0; k < c.length; k++) had[c[k].id] = 1;
        scanBar('Scanning phone…', true, 0);
        Promise.resolve(p.scan()).then(function (r) {
          var it = (r && r.items) || [];
          writeCache(it);
          var added = 0;
          for (var j = 0; j < it.length; j++) if (!had[it[j].id]) added++;
          if (sig(it) !== s0) { skipBg = true; refreshApp(); }
          scanBar(added > 0 ? added + ' new found' : 'Up to date · ' + it.length + ' files', false, 2200);
        }).catch(function () { scanBar('Scan did not finish', false, 2500); });
      }
      return Promise.resolve({ items: c });
    }
    return Promise.resolve(p.scan()).then(function (r) { writeCache((r && r.items) || []); return r; });
  }
  Cap.registerPlugin = function (name) {
    var p = origReg.apply(null, arguments);
    if (name !== 'MediaScan') return p;
    return new Proxy(p, {
      get: function (t, k) { return k === 'scan' ? function () { return scanFn(p); } : t[k]; }
    });
  };

  /* ================= 2. shorts through the phone's player ================= */
  var HM = HTMLMediaElement.prototype;
  var dSrc = Object.getOwnPropertyDescriptor(HM, 'src');
  var dCT = Object.getOwnPropertyDescriptor(HM, 'currentTime');
  var dMuted = Object.getOwnPropertyDescriptor(HM, 'muted');
  var dRate = Object.getOwnPropertyDescriptor(HM, 'playbackRate');
  var dVol = Object.getOwnPropertyDescriptor(HM, 'volume');
  var dDur = Object.getOwnPropertyDescriptor(HM, 'duration');
  var dPaused = Object.getOwnPropertyDescriptor(HM, 'paused');
  var oPlay = HM.play, oPause = HM.pause, oLoad = HM.load;

  var seq = 0, active = null, relT = 0, dragging = false, nFirst = 0, nOpen = 0;
  /* picture shown while a short has no thumbnail yet: never the grey play picture of the phone */
  var BLANK = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

  function parsePath(u) {
    var m = String(u).match(/\/_capacitor_file_(\/.*)$/);
    if (!m) return null;
    try { return decodeURI(m[1]); } catch (e) { return m[1]; }
  }
  function reelOf(e) {
    var n = e.v.parentNode;
    return n && n.classList ? n : null;
  }
  function live(e) {
    e.v.style.visibility = 'hidden';
    var r = reelOf(e); if (r) r.classList.add('eng-live');
    R().classList.add('eng-on');
  }
  function unlive(e) {
    e.v.style.visibility = '';
    var r = reelOf(e); if (r) r.classList.remove('eng-live');
  }
  function stopNative() {
    if (active) return;
    call('stop').catch(noop);
    R().classList.remove('eng-on');
  }
  function activate(e) {
    clearTimeout(relT);
    if (active && active !== e) unlive(active);
    active = e; e.ready = false; e.opened = true; e.pos = e.seek;
    var r = reelOf(e);
    var fit = (r && r.classList.contains('tall')) ? 'cover' : 'contain';
    call('open', {
      id: e.id, path: e.path, fit: fit, pos: Math.round(e.seek * 1000),
      play: !e.paused, muted: e.muted, rate: e.rate, vol: e.vol
    }).then(function () { if (++nOpen <= 3) dbg('open sent'); }, function (err) { dbg('open FAILED: ' + ((err && err.message) || err)); fail(e); });
  }
  function fail(e) {
    if (e.fb) return;
    e.fb = true;
    var wasActive = (active === e);
    if (wasActive) active = null;
    unlive(e);
    e.v.removeAttribute('data-eng');
    if (wasActive) { call('stop').catch(noop); R().classList.remove('eng-on'); }
    dSrc.set.call(e.v, e.url);
    if (!e.paused) { var p = oPlay.call(e.v); if (p && p.catch) p.catch(noop); }
  }
  function manage(v, url, path) {
    var e = {
      v: v, url: url, path: path, id: ++seq, pos: 0, dur: 0, paused: true,
      muted: !!v.muted, rate: 1, vol: 1, ready: false, fb: false, opened: false, seek: 0, w: 0, h: 0
    };
    v._e = e;
    v.setAttribute('data-eng', '1');
    Object.defineProperties(v, {
      play: { configurable: true, writable: true, value: function () {
        if (e.fb) return oPlay.call(v);
        e.paused = false;
        if (active === e && e.opened) { call('play').catch(noop); if (e.ready && !dragging) live(e); }
        else activate(e);
        return Promise.resolve();
      } },
      pause: { configurable: true, writable: true, value: function () {
        if (e.fb) return oPause.call(v);
        e.paused = true;
        if (active === e) call('pause').catch(noop);
      } },
      load: { configurable: true, writable: true, value: function () {
        if (e.fb) return oLoad.call(v);
        if (active === e) { active = null; unlive(e); clearTimeout(relT); relT = setTimeout(stopNative, 120); }
        e.opened = false;
      } },
      currentTime: { configurable: true,
        get: function () { return e.fb ? dCT.get.call(v) : e.pos; },
        set: function (x) {
          if (e.fb) { dCT.set.call(v, x); return; }
          x = Number(x) || 0; e.pos = x; e.seek = x;
          if (active === e && e.opened) call('seek', { ms: Math.round(x * 1000) }).catch(noop);
        } },
      duration: { configurable: true, get: function () { return e.fb ? dDur.get.call(v) : (e.dur > 0 ? e.dur : NaN); } },
      paused: { configurable: true, get: function () { return e.fb ? dPaused.get.call(v) : e.paused; } },
      muted: { configurable: true,
        get: function () { return e.fb ? dMuted.get.call(v) : e.muted; },
        set: function (x) {
          if (e.fb) { dMuted.set.call(v, x); return; }
          e.muted = !!x; if (active === e) call('setMuted', { muted: e.muted }).catch(noop);
        } },
      playbackRate: { configurable: true,
        get: function () { return e.fb ? dRate.get.call(v) : e.rate; },
        set: function (x) {
          if (e.fb) { dRate.set.call(v, x); return; }
          e.rate = Number(x) || 1; if (active === e) call('setRate', { rate: e.rate }).catch(noop);
        } },
      volume: { configurable: true,
        get: function () { return e.fb ? dVol.get.call(v) : e.vol; },
        set: function (x) {
          if (e.fb) { dVol.set.call(v, x); return; }
          e.vol = Math.max(0, Math.min(1, Number(x))); if (active === e) call('setVol', { value: e.vol }).catch(noop);
        } },
      videoWidth: { configurable: true, get: function () { return e.w; } },
      videoHeight: { configurable: true, get: function () { return e.h; } },
      readyState: { configurable: true, get: function () { return e.ready ? 4 : 0; } }
    });
  }
  /* a short plays in the app's own player first; the guard hands it to the phone's player if it cannot play or is too slow */
  function takeover(v, url, path, why) {
    if (!engOK || v._e) return false;
    dbg('native takeover: ' + why);
    var wasPlaying = !v.paused, t = 0;
    try { t = v.currentTime || 0; } catch (x) {}
    var mu = v.muted, rate = v.playbackRate, vo = v.volume;
    try { oPause.call(v); } catch (x) {}
    v.removeAttribute('src');
    try { oLoad.call(v); } catch (x) {}
    manage(v, url, path);
    var e = v._e;
    e.muted = mu; e.rate = rate || 1; e.vol = isNaN(vo) ? 1 : vo; e.seek = t; e.pos = t;
    if (wasPlaying) { e.paused = false; activate(e); }
    return true;
  }
  function guard(v, url, path) {
    var g = { t: 0, ok: false };
    if (!v.getAttribute('poster')) v.poster = BLANK;
    var take = function (why) {
      if (g.ok && why !== 'no picture') return false;
      clearTimeout(g.t);
      return takeover(v, url, path, why);
    };
    v.addEventListener('error', function (ev) {
      if (v._e || !v.getAttribute('src')) return;
      if (take('error')) ev.stopImmediatePropagation();
    });
    v.addEventListener('loadedmetadata', function () { if (!v._e && v.videoWidth === 0) take('no picture'); });
    v.addEventListener('playing', function () {
      if (v._e) return;
      if (v.videoWidth === 0) { take('no picture'); return; }
      g.ok = true; clearTimeout(g.t);
    });
    Object.defineProperty(v, 'play', { configurable: true, writable: true, value: function () {
      var p = oPlay.call(v);
      clearTimeout(g.t);
      if (!g.ok && !v._e) g.t = setTimeout(function () {
        if (!v._e && !g.ok && !v.paused && v.readyState < 3) take('slow start');
      }, 2500);
      return p;
    } });
  }
  Object.defineProperty(HM, 'src', {
    configurable: true,
    get: function () { return this._e ? this._e.url : dSrc.get.call(this); },
    set: function (val) {
      var p;
      if (this._e) { this._e.url = val; if (this._e.fb) dSrc.set.call(this, val); return; }
      dSrc.set.call(this, val);
      if (engOK && this.loop === true && (p = parsePath(val))) guard(this, String(val), p);
    }
  });

  if (VE) {
    try {
      VE.addListener('state', function (s) {
        if (!active || s.id !== active.id) return;
        var e = active;
        e.pos = s.pos / 1000;
        if (s.dur > 0) e.dur = s.dur / 1000;
        if (s.w) { e.w = s.w; e.h = s.h; }
        e.v.dispatchEvent(new Event('timeupdate'));
      });
      VE.addListener('first', function (s) {
        if (!active || s.id !== active.id) return;
        active.ready = true;
        if (++nFirst <= 2) dbg('first frame ok; view vis=' + s.vis + ' box=' + s.bw + 'x' + s.bh + ' tv=' + s.tw + 'x' + s.th + ' tex=' + s.tex + ' in ' + s.par + ' wv=' + s.wv);
        if (!dragging) live(active);
      });
      VE.addListener('error', function (s) {
        if (active && s.id === active.id) { dbg('player error: ' + (s.msg || '?')); fail(active); }
      });
    } catch (e) { engOK = false; }
  }

  /* while the page is dragged, the picture is shown again so it moves with the finger */
  var dg = null;
  doc.addEventListener('pointerdown', function (ev) { dg = { x: ev.clientX, y: ev.clientY, on: false }; }, true);
  doc.addEventListener('pointermove', function (ev) {
    if (!dg || dg.on || !active) return;
    var dx = ev.clientX - dg.x, dy = ev.clientY - dg.y;
    if (Math.abs(dy) > 14 && Math.abs(dy) > Math.abs(dx) && ev.target && ev.target.closest &&
        ev.target.closest('#reels') && !ev.target.closest('.rprog')) {
      dg.on = true; dragging = true; unlive(active);
    }
  }, true);
  function dragEnd() { dg = null; setTimeout(function () { dragging = false; }, 0); }
  doc.addEventListener('pointerup', dragEnd, true);
  doc.addEventListener('pointercancel', dragEnd, true);

  doc.addEventListener('visibilitychange', function () {
    if (!doc.hidden && active && !active.paused && active.opened) call('play').catch(noop);
  });

  /* ================= 3. phone Back button ================= */
  var tt = 0, lastBack = 0;
  function toast(m) {
    var t = $('#toast'); if (!t) return;
    t.textContent = m; t.hidden = false; clearTimeout(tt);
    tt = setTimeout(function () { t.hidden = true; }, 2000);
  }
  function vis(id) { var el = doc.getElementById(id); return !!(el && !el.hidden); }
  function stepBack() {
    var got = false;
    var h = function () { got = true; };
    window.addEventListener('popstate', h, { once: true });
    try { history.back(); } catch (e) {}
    setTimeout(function () {
      window.removeEventListener('popstate', h);
      if (!got) window.dispatchEvent(new PopStateEvent('popstate'));
    }, 150);
  }
  function onBack() {
    dbg('back pressed');
    if (vis('modal')) { doc.getElementById('modal').hidden = true; return; }
    if (vis('sheet')) { doc.getElementById('sheet').hidden = true; return; }
    var L = ['reels', 'player', 'now', 'recent', 'settings'];
    for (var i = 0; i < L.length; i++) if (vis(L[i])) { stepBack(); return; }
    if (vis('srch')) { var b = $('#bSearch'); if (b) b.click(); return; }
    var t = Date.now();
    if (t - lastBack < 2000) { call('exit').catch(noop); }
    else { lastBack = t; toast('Press back again to exit'); }
  }
  if (VE) {
    try {
      VE.addListener('back', onBack);
      Promise.resolve(VE.setBackReady()).then(function () { dbg('back button hooked'); }, function (er) { engOK = false; dbg('back hook FAILED: ' + ((er && er.message) || er)); });
      Promise.resolve(VE.ping()).then(function (r) { dbg('plugin ping ok, android ' + (r && r.sdk)); }, function (er) { dbg('plugin ping FAILED: ' + ((er && er.message) || er)); });
    } catch (e) { engOK = false; }
  }
})();
