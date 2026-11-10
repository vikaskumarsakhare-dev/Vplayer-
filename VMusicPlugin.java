package com.vplayer.app;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/* The music player for the Songs tab. It plays the songs with ExoPlayer, keeps its own list, so it goes on in the background,
   and shows the phone's media card (notification). The screen part is in index.html. It is a separate file from the video players. */
@CapacitorPlugin(name = "VMusic")
public class VMusicPlugin extends Plugin {

  private static VMusicPlugin inst;
  private static final Handler H = new Handler(Looper.getMainLooper());

  private static class Item {
    String id = "";
    String path = "";
    String title = "";
  }

  private ExoPlayer pl;
  private final ArrayList<Item> q = new ArrayList<Item>();
  private int cur = -1;
  /* 0 = in list order, 1 = shuffle, 2 = repeat one */
  private int mode = 0;
  private final ArrayList<Integer> order = new ArrayList<Integer>();
  private int ordPos = 0;
  private float speed = 1f;
  private boolean fg = true;
  private int errRun = 0;
  private boolean tickOn = false;
  private final Random rnd = new Random();

  private long sleepEnd = 0L;
  private long sleepTotal = 0L;
  private boolean sleepWait = false;
  private boolean sleepReset = false;
  private boolean sleepPending = false;

  private int sid = 0;
  private Equalizer eq;
  private BassBoost bb;
  private boolean eqOn = false;
  private int[] eqLv = null;
  private int bassStr = 0;

  private String lastBgKey = "";

  /* the buttons of the phone notification come here */
  static void remote(final String a, final long arg) {
    H.post(
      new Runnable() {
        @Override
        public void run() {
          VMusicPlugin p = inst;
          if (p != null) p.doRemote(a, arg);
        }
      }
    );
  }

  private void doRemote(String a, long arg) {
    if (pl == null) return;
    touch();
    if ("close".equals(a)) closeNow();
    else if ("toggle".equals(a)) toggle();
    else if ("play".equals(a)) pl.setPlayWhenReady(true);
    else if ("pause".equals(a)) pl.setPlayWhenReady(false);
    else if ("next".equals(a)) step(1, false);
    else if ("prev".equals(a)) prevAct();
    else if ("seek".equals(a)) pl.seekTo(Math.max(0L, arg));
    else if ("fwd".equals(a)) seekBy(10000L);
    else if ("rew".equals(a)) seekBy(-10000L);
    startTick();
    push();
    bg(true);
  }

  private void ui(Runnable r) {
    H.post(r);
  }

  @Override
  protected void handleOnResume() {
    super.handleOnResume();
    fg = true;
    if (pl != null) {
      startTick();
      push();
    }
  }

  @Override
  protected void handleOnPause() {
    super.handleOnPause();
    fg = false;
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    try {
      closeNow();
    } catch (Throwable t) {
    }
  }

  /* ---------- the player ---------- */

  private int sessionId() {
    if (sid == 0) {
      try {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        sid = am.generateAudioSessionId();
      } catch (Throwable t) {
        sid = 0;
      }
    }
    return sid;
  }

  private void ensure() {
    if (pl != null) return;
    Context c = getContext();
    try {
      DefaultRenderersFactory rf = new DefaultRenderersFactory(c).setEnableDecoderFallback(true);
      DefaultExtractorsFactory ef = new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
      pl = new ExoPlayer.Builder(c, rf).setMediaSourceFactory(new DefaultMediaSourceFactory(c, ef)).build();
    } catch (Throwable t) {
      pl = new ExoPlayer.Builder(c).build();
    }
    pl.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
    try {
      pl.setHandleAudioBecomingNoisy(false);
    } catch (Throwable t) {
    }
    try {
      pl.setWakeMode(C.WAKE_MODE_LOCAL);
    } catch (Throwable t) {
    }
    try {
      int s = sessionId();
      if (s != 0) pl.setAudioSessionId(s);
    } catch (Throwable t) {
    }
    pl.addListener(
      new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int st) {
          if (st == Player.STATE_READY) errRun = 0;
          if (st == Player.STATE_ENDED) onEnded();
          push();
        }

        @Override
        public void onIsPlayingChanged(boolean playing) {
          startTick();
          push();
          bg(false);
        }

        @Override
        public void onPlayWhenReadyChanged(boolean pw, int reason) {
          startTick();
          push();
          bg(false);
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo o, Player.PositionInfo n, int reason) {
          if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            if (sleepPending) {
              sleepPending = false;
              if (pl != null) pl.setPlayWhenReady(false);
            }
            bg(true);
          }
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException e) {
          JSObject o = new JSObject();
          o.put("msg", "This song cannot be played.");
          notifyListeners("merr", o);
          errRun++;
          if (q.size() > 1 && errRun < q.size()) {
            H.postDelayed(
              new Runnable() {
                @Override
                public void run() {
                  if (pl != null) step(1, true);
                }
              },
              350L
            );
          } else if (pl != null) {
            pl.setPlayWhenReady(false);
            push();
          }
        }
      }
    );
    applyFx();
  }

  private void loadCurrent(boolean autoPlay) {
    if (cur < 0 || cur >= q.size()) return;
    ensure();
    Item it = q.get(cur);
    try {
      pl.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(it.path))));
      pl.setRepeatMode(mode == 2 ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
      pl.setPlaybackParameters(new PlaybackParameters(speed, 1f));
      pl.prepare();
      pl.setPlayWhenReady(autoPlay);
    } catch (Throwable t) {
    }
    startTick();
    push();
    bg(true);
  }

  private void onEnded() {
    if (pl == null || q.isEmpty()) return;
    if (sleepPending) {
      sleepPending = false;
      pl.setPlayWhenReady(false);
      push();
      return;
    }
    if (q.size() == 1) {
      pl.seekTo(0L);
      pl.setPlayWhenReady(false);
      push();
      bg(true);
      return;
    }
    step(1, true);
  }

  private void buildOrder(int next) {
    order.clear();
    List<Integer> rest = new ArrayList<Integer>();
    for (int i = 0; i < q.size(); i++) if (i != cur && i != next) rest.add(Integer.valueOf(i));
    Collections.shuffle(rest, rnd);
    if (cur >= 0) order.add(Integer.valueOf(cur));
    if (next >= 0 && next != cur) order.add(Integer.valueOf(next));
    order.addAll(rest);
    ordPos = 0;
  }

  private int shuffleStep(int d) {
    if (order.size() != q.size() || !order.contains(Integer.valueOf(cur))) buildOrder(-1);
    int p = ordPos + d;
    if (p >= order.size()) {
      buildOrder(-1);
      p = order.size() > 1 ? 1 : 0;
    } else if (p < 0) {
      p = order.size() - 1;
    }
    ordPos = p;
    return order.get(p).intValue();
  }

  private void step(int d, boolean auto) {
    if (q.isEmpty()) return;
    int n;
    if (mode == 1) {
      n = shuffleStep(d);
    } else {
      n = cur + d;
      if (n >= q.size()) n = 0;
      if (n < 0) n = q.size() - 1;
    }
    cur = n;
    loadCurrent(true);
  }

  private void prevAct() {
    if (pl == null) return;
    if (pl.getCurrentPosition() > 3000L) {
      pl.seekTo(0L);
      push();
      bg(true);
    } else {
      step(-1, false);
    }
  }

  private void toggle() {
    if (pl == null) return;
    if (pl.getPlaybackState() == Player.STATE_ENDED) {
      pl.seekTo(0L);
      pl.setPlayWhenReady(true);
    } else {
      pl.setPlayWhenReady(!pl.getPlayWhenReady());
    }
  }

  private void seekBy(long ms) {
    if (pl == null) return;
    long d = pl.getDuration();
    long t = pl.getCurrentPosition() + ms;
    if (t < 0L) t = 0L;
    if (d > 0L && t > d) t = d;
    pl.seekTo(t);
  }

  private void closeNow() {
    sleepEnd = 0L;
    sleepPending = false;
    tickOn = false;
    H.removeCallbacks(tick);
    try {
      if (pl != null) {
        pl.stop();
        pl.release();
      }
    } catch (Throwable t) {
    }
    pl = null;
    q.clear();
    order.clear();
    cur = -1;
    releaseFx();
    lastBgKey = "";
    bgStop();
    JSObject o = new JSObject();
    o.put("closed", true);
    try {
      notifyListeners("mstate", o);
    } catch (Throwable t) {
    }
  }

  /* ---------- state for the screen ---------- */

  private JSObject stateObj() {
    JSObject o = new JSObject();
    o.put("idx", cur);
    o.put("id", cur >= 0 && cur < q.size() ? q.get(cur).id : "");
    boolean playing = pl != null && pl.getPlayWhenReady() && pl.getPlaybackState() != Player.STATE_ENDED;
    o.put("playing", playing);
    long pos = pl == null ? 0L : Math.max(0L, pl.getCurrentPosition());
    long dur = pl == null ? 0L : pl.getDuration();
    if (dur < 0L) dur = 0L;
    o.put("pos", pos);
    o.put("dur", dur);
    o.put("mode", mode);
    o.put("speed", (double) speed);
    o.put("count", q.size());
    long left = 0L;
    if (sleepEnd > 0L) left = Math.max(1L, sleepEnd - SystemClock.uptimeMillis());
    o.put("sleepLeft", left);
    o.put("sleepPend", sleepPending);
    o.put("sleepOn", sleepEnd > 0L || sleepPending);
    return o;
  }

  private void push() {
    if (pl == null) return;
    try {
      notifyListeners("mstate", stateObj());
    } catch (Throwable t) {
    }
  }

  private void startTick() {
    if (tickOn || pl == null) return;
    tickOn = true;
    H.removeCallbacks(tick);
    H.postDelayed(tick, 500L);
  }

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      if (pl == null) {
        tickOn = false;
        return;
      }
      checkSleep();
      if (fg) push();
      if (pl != null && (pl.getPlayWhenReady() || sleepEnd > 0L || fg)) {
        H.postDelayed(tick, 500L);
      } else {
        tickOn = false;
      }
    }
  };

  /* ---------- the phone notification ---------- */

  private void bg(boolean force) {
    if (pl == null || cur < 0 || cur >= q.size()) return;
    Item it = q.get(cur);
    boolean playing = pl.getPlayWhenReady() && pl.getPlaybackState() != Player.STATE_ENDED;
    String key = it.id + "|" + playing + "|" + speed + "|" + it.title;
    if (!force && key.equals(lastBgKey)) return;
    lastBgKey = key;
    try {
      Context c = getContext();
      Intent i = new Intent();
      i.setClassName(c, "com.vplayer.app.VPlayService");
      i.putExtra("title", it.title);
      i.putExtra("playing", playing);
      i.putExtra("pos", Math.max(0L, pl.getCurrentPosition()));
      long d = pl.getDuration();
      i.putExtra("dur", d < 0L ? 0L : d);
      i.putExtra("path", it.path);
      i.putExtra("speed", speed);
      i.putExtra("audio", true);
      i.putExtra("src", "music");
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
      else c.startService(i);
    } catch (Throwable t) {
    }
  }

  private void bgStop() {
    try {
      Context c = getContext();
      Intent i = new Intent();
      i.setClassName(c, "com.vplayer.app.VPlayService");
      c.stopService(i);
    } catch (Throwable t) {
    }
  }

  private void askNotif() {
    try {
      android.content.SharedPreferences sp = getContext().getSharedPreferences("vmusic", Context.MODE_PRIVATE);
      if (Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("asked", false) && getContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        sp.edit().putBoolean("asked", true).apply();
        getActivity().requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 4713);
      }
    } catch (Throwable t) {
    }
  }

  /* ---------- sleep timer ---------- */

  private void touch() {
    if (sleepReset && sleepEnd > 0L) sleepEnd = SystemClock.uptimeMillis() + sleepTotal;
  }

  private void checkSleep() {
    if (sleepEnd > 0L && SystemClock.uptimeMillis() >= sleepEnd) {
      sleepEnd = 0L;
      if (sleepWait && pl != null && pl.isPlaying()) {
        sleepPending = true;
      } else {
        sleepPending = false;
        if (pl != null) pl.setPlayWhenReady(false);
        push();
        bg(true);
        JSObject o = new JSObject();
        o.put("msg", "Sleep timer: stopped");
        try {
          notifyListeners("merr", o);
        } catch (Throwable t) {
        }
      }
    }
  }

  /* ---------- equalizer ---------- */

  private void fx() {
    if (eq == null) {
      try {
        eq = new Equalizer(0, sessionId());
      } catch (Throwable t) {
        eq = null;
      }
    }
    if (bb == null) {
      try {
        bb = new BassBoost(0, sessionId());
      } catch (Throwable t) {
        bb = null;
      }
    }
  }

  private void releaseFx() {
    try {
      if (eq != null) eq.release();
    } catch (Throwable t) {
    }
    try {
      if (bb != null) bb.release();
    } catch (Throwable t) {
    }
    eq = null;
    bb = null;
  }

  private void applyFx() {
    if (!eqOn && eq == null && bb == null) return;
    fx();
    try {
      if (eq != null) {
        eq.setEnabled(eqOn);
        if (eqLv != null) {
          int n = eq.getNumberOfBands();
          for (int i = 0; i < n && i < eqLv.length; i++) eq.setBandLevel((short) i, (short) eqLv[i]);
        }
      }
    } catch (Throwable t) {
    }
    try {
      if (bb != null) {
        bb.setEnabled(eqOn && bassStr > 0);
        if (bb.getStrengthSupported()) bb.setStrength((short) Math.max(0, Math.min(1000, bassStr)));
      }
    } catch (Throwable t) {
    }
  }

  /* ---------- calls from the screen ---------- */

  private Item itemFrom(JSONObject o) {
    if (o == null) return null;
    String p = o.optString("path", "");
    if (p.length() == 0) return null;
    Item it = new Item();
    it.id = o.optString("id", "");
    it.path = p;
    it.title = o.optString("title", "");
    return it;
  }

  @PluginMethod
  public void play(final PluginCall call) {
    final List<Item> list = new ArrayList<Item>();
    JSONArray a = call.getArray("items");
    if (a != null) {
      for (int i = 0; i < a.length(); i++) {
        Item it = itemFrom(a.optJSONObject(i));
        if (it != null) list.add(it);
      }
    }
    if (list.isEmpty()) {
      call.reject("no songs");
      return;
    }
    final int idx0 = call.getInt("index", 0) == null ? 0 : call.getInt("index", 0).intValue();
    final Integer m = call.getInt("mode");
    final Double sp = call.getDouble("speed");
    inst = this;
    askNotif();
    ui(
      new Runnable() {
        @Override
        public void run() {
          try {
            q.clear();
            q.addAll(list);
            cur = Math.max(0, Math.min(list.size() - 1, idx0));
            if (m != null) mode = Math.max(0, Math.min(2, m.intValue()));
            if (sp != null && sp.doubleValue() > 0.1) speed = (float) sp.doubleValue();
            if (mode == 1) buildOrder(-1);
            errRun = 0;
            loadCurrent(true);
            call.resolve(stateObj());
          } catch (Throwable t) {
            call.reject("play failed: " + t);
          }
        }
      }
    );
  }

  @PluginMethod
  public void state(final PluginCall call) {
    ui(
      new Runnable() {
        @Override
        public void run() {
          JSObject o = stateObj();
          o.put("on", pl != null);
          call.resolve(o);
        }
      }
    );
  }

  @PluginMethod
  public void toggle(final PluginCall call) {
    ui(
      new Runnable() {
        @Override
        public void run() {
          touch();
          toggle();
          startTick();
          push();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void next(final PluginCall call) {
    ui(
      new Runnable() {
        @Override
        public void run() {
          touch();
          step(1, false);
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void prev(final PluginCall call) {
    ui(
      new Runnable() {
        @Override
        public void run() {
          touch();
          prevAct();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void seek(final PluginCall call) {
    final Double p = call.getDouble("pos", 0.0);
    ui(
      new Runnable() {
        @Override
        public void run() {
          if (pl != null) {
            touch();
            pl.seekTo(Math.max(0L, (long) (p == null ? 0.0 : p.doubleValue())));
            bg(true);
          }
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void seekBy(final PluginCall call) {
    final Double p = call.getDouble("ms", 0.0);
    ui(
      new Runnable() {
        @Override
        public void run() {
          touch();
          seekBy((long) (p == null ? 0.0 : p.doubleValue()));
          push();
          bg(true);
          call.resolve();
        }
      }
    );
  }

  /* the list of songs that comes next is replaced; the song that plays now goes on without a break */
  @PluginMethod
  public void setQueue(final PluginCall call) {
    final List<Item> list = new ArrayList<Item>();
    JSONArray a = call.getArray("items");
    if (a != null) {
      for (int i = 0; i < a.length(); i++) {
        Item it = itemFrom(a.optJSONObject(i));
        if (it != null) list.add(it);
      }
    }
    ui(
      new Runnable() {
        @Override
        public void run() {
          try {
            if (pl == null || q.isEmpty() || cur < 0 || cur >= q.size() || list.isEmpty()) {
              call.resolve();
              return;
            }
            String id = q.get(cur).id;
            int j = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) j = i;
            if (j < 0) {
              call.resolve();
              return;
            }
            q.clear();
            q.addAll(list);
            cur = j;
            if (mode == 1) buildOrder(-1);
            push();
            call.resolve(stateObj());
          } catch (Throwable t) {
            call.reject("queue failed: " + t);
          }
        }
      }
    );
  }

  @PluginMethod
  public void setMode(final PluginCall call) {
    final int m = call.getInt("mode", 0) == null ? 0 : call.getInt("mode", 0).intValue();
    ui(
      new Runnable() {
        @Override
        public void run() {
          mode = Math.max(0, Math.min(2, m));
          if (mode == 1) buildOrder(-1);
          if (pl != null) pl.setRepeatMode(mode == 2 ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
          push();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void setSpeed(final PluginCall call) {
    final Double s = call.getDouble("speed", 1.0);
    ui(
      new Runnable() {
        @Override
        public void run() {
          float v = (float) (s == null ? 1.0 : s.doubleValue());
          if (v < 0.25f) v = 0.25f;
          if (v > 4f) v = 4f;
          speed = v;
          if (pl != null) pl.setPlaybackParameters(new PlaybackParameters(speed, 1f));
          push();
          bg(true);
          call.resolve();
        }
      }
    );
  }

  /* put a song right after the one that plays now */
  @PluginMethod
  public void playNext(final PluginCall call) {
    final Item it = itemFrom(call.getObject("item"));
    if (it == null) {
      call.reject("no song");
      return;
    }
    inst = this;
    ui(
      new Runnable() {
        @Override
        public void run() {
          if (pl == null || q.isEmpty() || cur < 0) {
            q.clear();
            q.add(it);
            cur = 0;
            if (mode == 1) buildOrder(-1);
            loadCurrent(true);
            call.resolve(stateObj());
            return;
          }
          int j = -1;
          for (int i = 0; i < q.size(); i++) if (q.get(i).id.equals(it.id)) j = i;
          if (j == cur) {
            call.resolve(stateObj());
            return;
          }
          if (j >= 0) {
            q.remove(j);
            if (j < cur) cur--;
          }
          q.add(cur + 1, it);
          if (mode == 1) buildOrder(cur + 1);
          push();
          call.resolve(stateObj());
        }
      }
    );
  }

  /* a deleted song leaves the list */
  @PluginMethod
  public void remove(final PluginCall call) {
    final String id = call.getString("id", "");
    ui(
      new Runnable() {
        @Override
        public void run() {
          int j = -1;
          for (int i = 0; i < q.size(); i++) if (q.get(i).id.equals(id)) j = i;
          if (j < 0 || pl == null) {
            call.resolve();
            return;
          }
          boolean was = pl.getPlayWhenReady();
          q.remove(j);
          if (q.isEmpty()) {
            closeNow();
            call.resolve();
            return;
          }
          if (j < cur) {
            cur--;
          } else if (j == cur) {
            if (cur >= q.size()) cur = 0;
            if (mode == 1) buildOrder(-1);
            loadCurrent(was);
            call.resolve();
            return;
          }
          if (mode == 1) buildOrder(-1);
          push();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void retitle(final PluginCall call) {
    final String id = call.getString("id", "");
    final String t = call.getString("title", "");
    ui(
      new Runnable() {
        @Override
        public void run() {
          for (int i = 0; i < q.size(); i++) if (q.get(i).id.equals(id)) q.get(i).title = t;
          bg(false);
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void close(final PluginCall call) {
    ui(
      new Runnable() {
        @Override
        public void run() {
          closeNow();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void sleep(final PluginCall call) {
    final Double ms = call.getDouble("ms", 0.0);
    final Boolean w = call.getBoolean("wait", false);
    final Boolean r = call.getBoolean("reset", false);
    ui(
      new Runnable() {
        @Override
        public void run() {
          long t = (long) (ms == null ? 0.0 : ms.doubleValue());
          if (t <= 0L) {
            sleepEnd = 0L;
            sleepTotal = 0L;
            sleepPending = false;
          } else {
            sleepTotal = t;
            sleepWait = w != null && w.booleanValue();
            sleepReset = r != null && r.booleanValue();
            sleepEnd = SystemClock.uptimeMillis() + t;
            sleepPending = false;
          }
          startTick();
          push();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void eqInfo(final PluginCall call) {
    ui(
      new Runnable() {
        @Override
        public void run() {
          JSObject o = new JSObject();
          try {
            fx();
            if (eq == null) {
              o.put("ok", false);
              call.resolve(o);
              return;
            }
            short[] rg = eq.getBandLevelRange();
            int n = eq.getNumberOfBands();
            JSArray fr = new JSArray();
            for (int i = 0; i < n; i++) fr.put(eq.getCenterFreq((short) i) / 1000);
            o.put("ok", true);
            o.put("bands", n);
            o.put("min", (int) rg[0]);
            o.put("max", (int) rg[1]);
            o.put("freqs", fr);
            o.put("bass", bb != null && bb.getStrengthSupported());
          } catch (Throwable t) {
            o.put("ok", false);
          }
          call.resolve(o);
        }
      }
    );
  }

  @PluginMethod
  public void eqSet(final PluginCall call) {
    final Boolean on = call.getBoolean("on", false);
    final JSONArray lv = call.getArray("levels");
    final Integer bs = call.getInt("bass", 0);
    ui(
      new Runnable() {
        @Override
        public void run() {
          eqOn = on != null && on.booleanValue();
          if (lv != null) {
            int[] a = new int[lv.length()];
            for (int i = 0; i < a.length; i++) a[i] = lv.optInt(i, 0);
            eqLv = a;
          }
          bassStr = bs == null ? 0 : bs.intValue();
          applyFx();
          call.resolve();
        }
      }
    );
  }

  @PluginMethod
  public void ringtone(final PluginCall call) {
    final String id = call.getString("id", "");
    final Context c = getContext();
    try {
      if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(c)) {
        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        call.reject("permission");
        return;
      }
      String digits = id == null ? "" : id.replaceAll("[^0-9]", "");
      if (digits.length() == 0) {
        call.reject("no file");
        return;
      }
      Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Long.parseLong(digits));
      RingtoneManager.setActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE, u);
      call.resolve();
    } catch (Throwable t) {
      call.reject("ringtone failed: " + t);
    }
  }
}
