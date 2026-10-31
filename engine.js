/* V Player engine: runs before index.html. It does not change index.html.
   1. Shorts play through the phone's own player (ExoPlayer) when it is available.
      If anything fails, the old in-app player is used for that video.
   2. The video and song list is remembered, so the app shows it at once on every open.
      The phone is scanned in the background and new files are added on their own.
   3. The phone Back button goes one step back. On the main screen, press twice to exit. */
(function () {
  'use strict';
  var doc = document;
  function R() { return doc.documentElement; }
  /* small check strip at the top-left, hides itself after 25 seconds */
  var DL = [], dT = 0;
  function dbg(m) {
    try {
      DL.push(m); if (DL.length > 9) DL.shift();
      var paint = function () {
        var d = doc.getElementById('engdbg');
        if (!d) {
          d = doc.createElement('div'); d.id = 'engdbg';
          d.style.cssText = 'position:fixed;top:64px;left:6px;z-index:9999;max-width:92%;font:11px/1.35 monospace;color:#9f9;background:rgba(0,0,0,.78);padding:5px 8px;border-radius:8px;pointer-events:none;white-space:pre-wrap;word-break:break-all';
          (doc.body || R()).appendChild(d);
        }
        d.style.display = ''; d.textContent = DL.join('\n');
        clearTimeout(dT); dT = setTimeout(function () { d.style.display = 'none'; }, 25000);
      };
      if (doc.body) paint(); else doc.addEventListener('DOMContentLoaded', paint, { once: true });
    } catch (e) {}
  }
  dbg('engine.js loaded');
  window.addEventListener('error', function (ev) { dbg('JS error: ' + (ev.message || '?')); });
  var Cap = window.Capacitor;
  if (!Cap) { dbg('stopped: Capacitor missing'); return; }
  var isNative = false;
