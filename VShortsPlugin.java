package com.vplayer.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Collections;
import java.util.Locale;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.PathParser;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(name = "VShorts")
public class VShortsPlugin extends Plugin {

  private Pager pager;
  private OnBackPressedCallback backCb;
  private float origBright = -1f;
  private static VShortsPlugin inst;
  static volatile boolean bgActive = false;

  public static boolean active() {
    return bgActive;
  }

  /* the buttons of the notification (play, pause, next, back, seek, close) */
  public static void remote(final String a, final long arg) {
    final VShortsPlugin p = inst;
    if (p == null || p.getActivity() == null) return;
    p.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (p.pager == null) return;
        if ("close".equals(a)) {
          if (p.pager.isMin()) {
            p.closeNow();
          } else {
            p.pager.pauseNow();
            p.bgCmd("stop", "", false, 0L, 0L, "", 1f);
          }
        } else p.pager.remoteCmd(a, arg);
      }
    });
  }

  private void bgCmd(String cmd, String t, boolean playing, long pos, long dur, String path, float speed) {
    try {
      Context c = getContext();
      if ("perm".equals(cmd)) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          getActivity().requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 4713);
        }
        return;
      }
      Intent i = new Intent();
      i.setClassName(c, "com.vplayer.app.VPlayService");
      if ("stop".equals(cmd)) {
        bgActive = false;
        c.stopService(i);
        return;
      }
      i.putExtra("title", t);
      i.putExtra("playing", playing);
      i.putExtra("pos", pos);
      i.putExtra("dur", dur);
      i.putExtra("path", path);
      i.putExtra("speed", speed);
      bgActive = true;
      if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
      else c.startService(i);
    } catch (Throwable e) {
    }
  }

  private Bitmap decodeThumb(String s) {
    try {
      int k = s.indexOf(',');
      if (k < 0) return null;
      byte[] d = Base64.decode(s.substring(k + 1), Base64.DEFAULT);
      return BitmapFactory.decodeByteArray(d, 0, d.length);
    } catch (Throwable t) {
      return null;
    }
  }

  @PluginMethod
  public void open(final PluginCall call) {
    JSONArray arr = call.getArray("items");
    if (arr == null || arr.length() == 0) {
      call.reject("no items");
      return;
    }
    final List<String> paths = new ArrayList<String>();
    final List<String> ids = new ArrayList<String>();
    final List<String> titles = new ArrayList<String>();
    final List<String> origs = new ArrayList<String>();
    final List<Integer> likes = new ArrayList<Integer>();
    final List<String> plOf = new ArrayList<String>();
    final List<String> pfOf = new ArrayList<String>();
    final java.util.Map<String, Bitmap> covers = new java.util.HashMap<String, Bitmap>();
    final java.util.Map<String, List<String>> pfMap = new java.util.HashMap<String, List<String>>();
    try {
      org.json.JSONObject po = call.getObject("playlistFolders");
      if (po != null) {
        java.util.Iterator<String> it = po.keys();
        while (it.hasNext()) {
          String k = it.next();
          List<String> fl = new ArrayList<String>();
          JSONArray fa = po.optJSONArray(k);
          if (fa != null) for (int i = 0; i < fa.length(); i++) if (fa.optString(i, "").length() > 0) fl.add(fa.optString(i, ""));
          pfMap.put(k, fl);
        }
      }
    } catch (Throwable t) {
    }
    final List<String> plNames = new ArrayList<String>();
    JSONArray pa = call.getArray("playlists");
    if (pa != null) {
      for (int i = 0; i < pa.length(); i++) {
        String n = pa.optString(i, "");
        if (n.length() > 0) plNames.add(n);
      }
    }
    final java.util.HashSet<String> pinSet = new java.util.HashSet<String>();
    for (int i = 0; i < arr.length(); i++) {
      JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      String p = o.optString("path", "");
      if (p.length() == 0) continue;
      paths.add(p);
      ids.add(o.optString("id", ""));
      titles.add(o.optString("title", ""));
      origs.add(o.optString("orig", ""));
      likes.add(Integer.valueOf(o.optInt("likes", 0)));
      plOf.add(o.optString("playlist", ""));
      pfOf.add(o.optString("pfolder", ""));
      if (o.optLong("pin", 0L) > 0L) pinSet.add(o.optString("id", ""));
      String th = o.optString("thumb", "");
      if (th.startsWith("data:image")) {
        Bitmap cb0 = decodeThumb(th);
        if (cb0 != null) covers.put(o.optString("id", ""), cb0);
      }
    }
    if (paths.size() == 0) {
      call.reject("no paths");
      return;
    }
    Integer st = call.getInt("index", 0);
    final int start = st == null ? 0 : st.intValue();
    final String tmode = call.getString("tmode", "down");
    Pager.nextLoopShuffle = !"order".equals(call.getString("loop", "shuffle"));
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          closeNow();
          inst = VShortsPlugin.this;
          Activity act = getActivity();
          origBright = act.getWindow().getAttributes().screenBrightness;
          ViewGroup parent = (ViewGroup) getBridge().getWebView().getParent();
          int s = Math.max(0, Math.min(start, paths.size() - 1));
          pager = new Pager(act, paths, ids, titles, origs, likes, plNames, plOf, s, tmode, covers, new Pager.Cb() {
            @Override
            public void bg(String cmd, String t, boolean playing, long pos, long dur, String path, float speed) {
              bgCmd(cmd, t, playing, pos, dur, path, speed);
            }

            @Override
            public void onIndex(int i, String id) {
              JSObject o = new JSObject();
              o.put("index", i);
              o.put("id", id);
              notifyListeners("index", o);
            }

            @Override
            public void onLike(String id, int count) {
              JSObject o = new JSObject();
              o.put("id", id);
              o.put("count", count);
              notifyListeners("like", o);
            }

            @Override
            public void onHide(String id) {
              JSObject o = new JSObject();
              o.put("id", id);
              notifyListeners("hide", o);
            }

            @Override
            public void onTmode(String m) {
              JSObject o = new JSObject();
              o.put("mode", m);
              notifyListeners("tmode", o);
            }

            @Override
            public void onRename(String id, String t) {
              JSObject o = new JSObject();
              o.put("id", id);
              o.put("title", t);
              notifyListeners("rename", o);
            }

            @Override
            public void onThumb(String id, String data) {
              JSObject o = new JSObject();
              o.put("id", id);
              o.put("data", data);
              notifyListeners("thumb", o);
            }

            @Override
            public void onGallery(String id) {
              JSObject o = new JSObject();
              o.put("id", id);
              notifyListeners("gallery", o);
            }

            @Override
            public void onAction(String id, String act) {
              JSObject o = new JSObject();
              o.put("id", id);
              o.put("act", act);
              notifyListeners("action", o);
            }

            @Override
            public void onPlaylist(String id, String name, String folder) {
              JSObject o = new JSObject();
              o.put("id", id);
              o.put("name", name);
              o.put("folder", folder);
              notifyListeners("playlist", o);
            }

            @Override
            public void onClose() {
              closeNow();
            }

            @Override
            public void onMini(String title, boolean playing, float frac) {
              JSObject o = new JSObject();
              o.put("title", title);
              o.put("playing", playing);
              o.put("frac", (double) frac);
              notifyListeners("mstate", o);
            }

            @Override
            public float getBright() {
              return brightNow();
            }

            @Override
            public void setBright(float v) {
              brightSet(v);
            }

            @Override
            public float getVol() {
              return volNow();
            }

            @Override
            public void setVol(float v) {
              volSet(v);
            }
          });
          pager.setPf(pfOf, pfMap);
          pager.setPinned(pinSet);
          parent.addView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
          pager.requestApplyInsets();
          backCb = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
              if (pager != null && pager.backStep()) return;
              if (pager != null && pager.bgOn() && !pager.isMin()) {
                minimizeNow();
                return;
              }
              closeNow();
            }
          };
          getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), backCb);
          call.resolve();
        } catch (Throwable t) {
          closeNow();
          call.reject("open failed: " + t);
        }
      }
    });
  }

  @PluginMethod
  public void close(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        closeNow();
        call.resolve();
      }
    });
  }

  private void minimizeNow() {
    if (pager == null) return;
    pager.minimize();
    if (backCb != null) backCb.setEnabled(false);
    JSObject o = new JSObject();
    o.put("index", pager.index());
    o.put("id", pager.curId());
    notifyListeners("minimized", o);
  }

  /* the small bar of the app sends its buttons here: toggle, restore, close */
  @PluginMethod
  public void command(final PluginCall call) {
    final String c = call.getString("cmd", "");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (pager != null) {
          if ("restore".equals(c)) {
            pager.restore();
            if (backCb != null) backCb.setEnabled(true);
            notifyListeners("restored", new JSObject());
          } else if ("close".equals(c)) {
            closeNow();
          } else {
            pager.remoteCmd(c, 0L);
          }
        }
        call.resolve();
      }
    });
  }

  /* the app asks for a picture from the gallery; it comes back as a small picture (text) */
  /* the file was deleted from the phone: the short goes out of the viewer */
  @PluginMethod
  public void removeItem(final PluginCall call) {
    final String id = call.getString("id", "");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (pager != null) pager.removeById(id);
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void pickImage(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
    i.setType("image/*");
    startActivityForResult(call, i, "pickDone");
  }

  @ActivityCallback
  private void pickDone(PluginCall call, ActivityResult result) {
    if (call == null) return;
    try {
      if (result == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
        call.reject("cancelled");
        return;
      }
      Uri u = result.getData().getData();
      BitmapFactory.Options bo = new BitmapFactory.Options();
      bo.inJustDecodeBounds = true;
      InputStream in1 = getContext().getContentResolver().openInputStream(u);
      BitmapFactory.decodeStream(in1, null, bo);
      if (in1 != null) in1.close();
      int sample = 1;
      while (Math.max(bo.outWidth, bo.outHeight) / sample > 1600) sample *= 2;
      BitmapFactory.Options o2 = new BitmapFactory.Options();
      o2.inSampleSize = sample;
      InputStream in2 = getContext().getContentResolver().openInputStream(u);
      Bitmap b = BitmapFactory.decodeStream(in2, null, o2);
      if (in2 != null) in2.close();
      if (b == null) {
        call.reject("not a picture");
        return;
      }
      JSObject r = new JSObject();
      r.put("data", Pager.smallJpeg(b));
      call.resolve(r);
    } catch (Throwable t) {
      call.reject("could not read: " + t);
    }
  }

  private float brightNow() {
    try {
      float b = getActivity().getWindow().getAttributes().screenBrightness;
      if (b >= 0f) return b;
      int s = android.provider.Settings.System.getInt(getActivity().getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
      return s / 255f;
    } catch (Throwable t) {
      return 0.5f;
    }
  }

  private void brightSet(float v) {
    try {
      Window w = getActivity().getWindow();
      WindowManager.LayoutParams lp = w.getAttributes();
      lp.screenBrightness = Math.max(0.01f, Math.min(1f, v));
      w.setAttributes(lp);
    } catch (Throwable t) {
    }
  }

  private float volNow() {
    try {
      AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
      int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      return max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0.5f;
    } catch (Throwable t) {
      return 0.5f;
    }
  }

  private void volSet(float v) {
    try {
      AudioManager am = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
      int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0f, Math.min(1f, v)) * max), 0);
    } catch (Throwable t) {
    }
  }

  private void closeNow() {
    if (backCb != null) {
      backCb.remove();
      backCb = null;
    }
    if (pager != null) {
      Pager p = pager;
      pager = null;
      int last = p.index();
      String lastId = p.curId();
      bgCmd("stop", "", false, 0L, 0L, "", 1f);
      p.release();
      ViewGroup par = (ViewGroup) p.getParent();
      if (par != null) par.removeView(p);
      brightSet(origBright < 0f ? 0.5f : origBright);
      if (origBright < 0f) {
        try {
          Window w = getActivity().getWindow();
          WindowManager.LayoutParams lp = w.getAttributes();
          lp.screenBrightness = -1f;
          w.setAttributes(lp);
        } catch (Throwable t) {
        }
      }
      JSObject o = new JSObject();
      o.put("index", last);
      o.put("id", lastId);
      notifyListeners("closed", o);
    }
  }

  @Override
  protected void handleOnPause() {
    super.handleOnPause();
    if (pager != null) {
      if (pager.bgOn()) pager.leaveBg();
      else pager.pauseNow();
    }
  }

  @Override
  protected void handleOnResume() {
    super.handleOnResume();
    if (pager != null) pager.resumeNow();
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    if (pager != null) {
      pager.release();
      pager = null;
    }
  }

  /* A full screen stack of three pages (the one before, the one on screen, the one after).
     Every page has its own player, so the neighbours are already loaded and move with the finger.
     The buttons, title, time and yellow bar stay in place on top. */
  static class Pager extends FrameLayout {

    interface Cb {
      void bg(String cmd, String t, boolean playing, long pos, long dur, String path, float speed);

      void onIndex(int i, String id);

      void onLike(String id, int count);

      void onHide(String id);

      void onTmode(String m);

      void onRename(String id, String t);

      void onThumb(String id, String data);

      void onGallery(String id);

      void onAction(String id, String act);

      void onPlaylist(String id, String name, String folder);

      void onClose();

      void onMini(String title, boolean playing, float frac);

      float getBright();

      void setBright(float v);

      float getVol();

      void setVol(float v);
    }

    private final Activity act;
    private final List<String> paths;
    private final List<String> ids;
    private final List<String> titles;
    private final List<String> origs;
    private final List<Integer> likes;
    private final List<String> plNames;
    private final List<String> plOf;
    private List<String> pfOf = new ArrayList<String>();
    private java.util.Map<String, List<String>> pfMap = new java.util.HashMap<String, List<String>>();

    void setPf(List<String> o, java.util.Map<String, List<String>> m) {
      pfOf = o;
      pfMap = m;
    }
    private boolean autoScroll = false;
    private java.util.Set<String> pinned = new java.util.HashSet<String>();

    void setPinned(java.util.Set<String> p) {
      pinned = p;
    }

    static boolean nextLoopShuffle = true;
    private boolean loopShuffle = nextLoopShuffle;

    void setLoopShuffle(boolean on) {
      loopShuffle = on;
    }
    private final java.util.Map<String, Bitmap> covers;
    private float speed = 1f;
    private boolean bgPlay = false;
    private boolean away = false;
    private boolean minimized = false;
    private int miniN = 0;
    private boolean shuffleOn = false;
    private List<String> origIds = null;
    private IconV shuffleBtn;
    private long sleepEnd = 0L;
    private int sleepMin = 0;
    private TextView sleepChip;
    private FrameLayout sheetRoot;
    private final Cb cb;
    private final Page[] pg = new Page[3];
    private final Handler hd = new Handler(Looper.getMainLooper());
    private final float den;
    private int idx;
    private int W = 0;
    private int H = 0;
    private int insT = 0;
    private int insB = 0;
    private float dy = 0f;
    private boolean animating = false;
    private boolean dead = false;
    private VelocityTracker vt;
    private ValueAnimator anim;

    /* touch state */
    private float downX = 0f;
    private float downY = 0f;
    private int zone = 0;
    private int mode = 0;
    private float gBase = 0f;
    private long seekBase = 0L;
    private long seekSpan = 0L;
    private long seekT = 0L;
    private long lastTapT = 0L;

    /* viewer state */
    private boolean liked = false;
    private int likeBase = 0;
    private boolean muted = false;
    private boolean userPaused = false;
    private boolean seekUser = false;
    private String tmode;

    /* screen parts */
    private IconV backBtn;
    private TextView titleTv;
    private TextView timeTv;
    private SeekV seekV;
    private IconV likeBtn;
    private TextView likeTv;
    private IconV soundBtn;
    private IconV hideBtn;
    private IconV moreBtn;
    private LinearLayout side;
    private TextView hud;
    private IconV flash;
    private IconV heart;
    private FrameLayout.LayoutParams lpBack;
    private FrameLayout.LayoutParams lpTitle;
    private FrameLayout.LayoutParams lpTime;
    private FrameLayout.LayoutParams lpSeek;
    private FrameLayout.LayoutParams lpSide;
    private FrameLayout.LayoutParams lpHud;

    private final Runnable holdRun = new Runnable() {
      @Override
      public void run() {
        if (mode == 0 && !dead) {
          mode = 5;
          pg[1].pl.setPlaybackSpeed(2f);
          showHud("2x speed");
        }
      }
    };

    private final Runnable tapRun = new Runnable() {
      @Override
      public void run() {
        lastTapT = 0L;
        togglePause();
      }
    };

    private final Runnable hudRun = new Runnable() {
      @Override
      public void run() {
        hud.setVisibility(View.GONE);
      }
    };

    private final Runnable tickRun = new Runnable() {
      @Override
      public void run() {
        if (dead) return;
        tick();
        if (minimized && cb != null && (++miniN % 5) == 0) miniPush();
        hd.postDelayed(this, 100);
      }
    };

    class Page {

      final FrameLayout root;
      final TextureView tv;
      final ImageView cover;
      final ExoPlayer pl;
      int vw = 0;
      int vh = 0;
      int loaded = -1;

      Page(Context ctx) {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        tv = new TextureView(ctx);
        root.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cover = new ImageView(ctx);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        DefaultRenderersFactory rf = new DefaultRenderersFactory(ctx).setEnableDecoderFallback(true);
        DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(1500, 8000, 250, 500).build();
        pl = new ExoPlayer.Builder(ctx, rf).setLoadControl(lc).build();
        pl.setRepeatMode(Player.REPEAT_MODE_ONE);
        pl.setAudioAttributes(
          new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
          true
        );
        pl.setVideoTextureView(tv);
        pl.addListener(new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(Player.PositionInfo o, Player.PositionInfo n, int reason) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && Page.this == pg[1]) bgUpdate();
          }

          @Override
          public void onRenderedFirstFrame() {
            cover.setVisibility(View.GONE);
          }

          @Override
          public void onVideoSizeChanged(VideoSize s) {
            float par = s.pixelWidthHeightRatio > 0f ? s.pixelWidthHeightRatio : 1f;
            vw = Math.round(s.width * par);
            vh = s.height;
            fit(Page.this);
          }

          @Override
          public void onPlaybackStateChanged(int st) {
            if (st == Player.STATE_ENDED && autoScroll && Page.this == pg[1]) {
              hd.post(new Runnable() {
                @Override
                public void run() {
                  autoNext();
                }
              });
            }
          }
        });
      }
    }

    /* round buttons drawn with the same thin line icons the app already uses */
    class IconV extends View {

      private String kind;
      private final float isz;
      private final boolean bg;
      private int color = Color.WHITE;
      private boolean filled = false;
      private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final List<Path> strokes = new ArrayList<Path>();
      private final List<Path> fills = new ArrayList<Path>();

      IconV(Context c, String kind, float isz, boolean bg) {
        super(c);
        this.isz = isz;
        this.bg = bg;
        setBackgroundColor(Color.TRANSPARENT);
        setKind(kind);
      }

      private Path pp(String d) {
        try {
          return PathParser.createPathFromPathData(d);
        } catch (Throwable t) {
          return new Path();
        }
      }

      void setKind(String k) {
        kind = k;
        strokes.clear();
        fills.clear();
        if ("back".equals(k)) strokes.add(pp("M15 5l-7 7 7 7"));
        else if ("heart".equals(k)) strokes.add(pp("M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"));
        else if ("shuffle".equals(k)) strokes.add(pp("M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5"));
        else if ("trash".equals(k)) strokes.add(pp("M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"));
        else if ("more".equals(k)) {
          Path a = new Path();
          a.addCircle(12f, 5f, 1.7f, Path.Direction.CW);
          a.addCircle(12f, 12f, 1.7f, Path.Direction.CW);
          a.addCircle(12f, 19f, 1.7f, Path.Direction.CW);
          fills.add(a);
        } else if ("vol".equals(k)) {
          fills.add(pp("M4 9.5v5h4l5 4v-13l-5 4z"));
          strokes.add(pp("M16.5 9a4 4 0 0 1 0 6M19 6.5a8 8 0 0 1 0 11"));
        } else if ("mute".equals(k)) {
          fills.add(pp("M4 9.5v5h4l5 4v-13l-5 4z"));
          strokes.add(pp("M17 9l5 6M22 9l-5 6"));
        } else if ("play".equals(k)) fills.add(pp("M8 5.5v13l11-6.5z"));
        else if ("pause".equals(k)) fills.add(pp("M7 5h3.6v14H7zM13.4 5H17v14h-3.6z"));
        invalidate();
      }

      void setLook(int c, boolean f) {
        color = c;
        filled = f;
        invalidate();
      }

      @Override
      protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (bg) {
          pt.setStyle(Paint.Style.FILL);
          pt.setColor(0x73000000);
          c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, pt);
        }
        float s = isz * den / 24f;
        c.save();
        c.translate((w - 24f * s) / 2f, (h - 24f * s) / 2f);
        c.scale(s, s);
        pt.setColor(color);
        pt.setStrokeWidth(1.8f);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeJoin(Paint.Join.ROUND);
        boolean solid = "play".equals(kind) || "pause".equals(kind);
        pt.setStyle(solid ? Paint.Style.FILL : Paint.Style.FILL_AND_STROKE);
        for (int i = 0; i < fills.size(); i++) c.drawPath(fills.get(i), pt);
        pt.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        for (int i = 0; i < strokes.size(); i++) c.drawPath(strokes.get(i), pt);
        c.restore();
      }
    }

    /* the yellow bar at the bottom: drag it to move in the video */
    class SeekV extends View {

      float frac = 0f;
      boolean on = false;
      final Paint pt = new Paint();

      SeekV(Context c) {
        super(c);
      }

      @Override
      protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        float bh = on ? dp(7) : dp(3);
        pt.setColor(0x33FFFFFF);
        c.drawRect(0f, h - bh, (float) w, (float) h, pt);
        pt.setColor(0xFFFFB52E);
        c.drawRect(0f, h - bh, w * frac, (float) h, pt);
      }

      @Override
      public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
          on = true;
          seekUser = true;
          getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
          float f = getWidth() > 0 ? e.getX() / getWidth() : 0f;
          frac = Math.max(0f, Math.min(1f, f));
          invalidate();
          long d = pg[1].pl.getDuration();
          if (d > 0) timeTv.setText(timeText((long) (frac * d), d));
          return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
          on = false;
          seekUser = false;
          invalidate();
          long d = pg[1].pl.getDuration();
          if (a == MotionEvent.ACTION_UP && d > 0) pg[1].pl.seekTo((long) (frac * d));
          return true;
        }
        return true;
      }
    }

    /* a picture shrunk to 480 pixels and turned into text for the app to keep */
    static String smallJpeg(Bitmap b) {
      float sc = Math.min(1f, 480f / Math.max(b.getWidth(), b.getHeight()));
      Bitmap sm = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * sc)), Math.max(1, Math.round(b.getHeight() * sc)), true);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      sm.compress(Bitmap.CompressFormat.JPEG, 82, bos);
      return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private int dp(float v) {
      return Math.round(v * den);
    }

    private FrameLayout.LayoutParams lp(int w, int h, int g) {
      return new FrameLayout.LayoutParams(w, h, g);
    }

    Pager(Activity act, List<String> paths, List<String> ids, List<String> titles, List<String> origs, List<Integer> likes, List<String> plNames, List<String> plOf, int start, String tmode, java.util.Map<String, Bitmap> covers, Cb cb) {
      super(act);
      this.covers = covers;
      this.act = act;
      this.paths = paths;
      this.ids = ids;
      this.titles = titles;
      this.origs = origs;
      this.likes = likes;
      this.plNames = plNames;
      this.plOf = plOf;
      this.idx = start;
      this.cb = cb;
      this.tmode = "up".equals(tmode) ? "up" : "down";
      this.den = act.getResources().getDisplayMetrics().density;
      setBackgroundColor(Color.BLACK);
      for (int i = 0; i < 3; i++) {
        pg[i] = new Page(act);
        addView(pg[i].root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      }

      View shade = new View(act);
      GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00000000, 0xB8000000 });
      shade.setBackground(sg);
      addView(shade, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(190), Gravity.BOTTOM));

      titleTv = new TextView(act);
      titleTv.setBackgroundColor(Color.TRANSPARENT);
      titleTv.setTextColor(Color.WHITE);
      titleTv.setTextSize(15f);
      titleTv.setMaxLines(2);
      titleTv.setTextIsSelectable(false);
      titleTv.setLongClickable(false);
      titleTv.setShadowLayer(6f, 0f, 1f, 0x99000000);
      titleTv.setTypeface(titleTv.getTypeface(), android.graphics.Typeface.BOLD);
      lpTitle = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
      addView(titleTv, lpTitle);

      timeTv = new TextView(act);
      timeTv.setBackgroundColor(Color.TRANSPARENT);
      timeTv.setTextColor(0xC7FFFFFF);
      timeTv.setTextSize(12.5f);
      timeTv.setTextIsSelectable(false);
      timeTv.setLongClickable(false);
      timeTv.setPadding(0, dp(6), dp(20), dp(6));
      lpTime = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
      addView(timeTv, lpTime);
      timeTv.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          Pager.this.tmode = "down".equals(Pager.this.tmode) ? "up" : "down";
          if (Pager.this.cb != null) Pager.this.cb.onTmode(Pager.this.tmode);
          tick();
        }
      });

      seekV = new SeekV(act);
      seekV.setBackgroundColor(Color.TRANSPARENT);
      lpSeek = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(28), Gravity.BOTTOM);
      addView(seekV, lpSeek);

      side = new LinearLayout(act);
      side.setBackgroundColor(Color.TRANSPARENT);
      side.setOrientation(LinearLayout.VERTICAL);
      side.setGravity(Gravity.CENTER_HORIZONTAL);
      likeBtn = new IconV(act, "heart", 26f, true);
      likeTv = new TextView(act);
      likeTv.setBackgroundColor(Color.TRANSPARENT);
      likeTv.setTextColor(Color.WHITE);
      likeTv.setTextSize(12.5f);
      likeTv.setShadowLayer(4f, 0f, 1f, 0xB3000000);
      likeTv.setGravity(Gravity.CENTER);
      soundBtn = new IconV(act, "vol", 24f, true);
      shuffleBtn = new IconV(act, "shuffle", 22f, true);
      hideBtn = new IconV(act, "trash", 22f, true);
      moreBtn = new IconV(act, "more", 24f, true);
      side.addView(likeBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
      side.addView(likeTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(dp(46), dp(46));
      l2.topMargin = dp(14);
      side.addView(soundBtn, l2);
      LinearLayout.LayoutParams l3s = new LinearLayout.LayoutParams(dp(46), dp(46));
      l3s.topMargin = dp(14);
      side.addView(shuffleBtn, l3s);
      LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(dp(46), dp(46));
      l3.topMargin = dp(14);
      side.addView(hideBtn, l3);
      LinearLayout.LayoutParams l4 = new LinearLayout.LayoutParams(dp(46), dp(46));
      l4.topMargin = dp(14);
      side.addView(moreBtn, l4);
      lpSide = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
      addView(side, lpSide);

      backBtn = new IconV(act, "back", 26f, true);
      lpBack = lp(dp(44), dp(44), Gravity.TOP | Gravity.START);
      addView(backBtn, lpBack);

      hud = new TextView(act);
      hud.setTextColor(Color.WHITE);
      hud.setTextSize(14f);
      hud.setTypeface(hud.getTypeface(), android.graphics.Typeface.BOLD);
      hud.setPadding(dp(16), dp(8), dp(16), dp(8));
      GradientDrawable hg = new GradientDrawable();
      hg.setCornerRadius(dp(20));
      hg.setColor(0xA6000000);
      hud.setBackground(hg);
      hud.setVisibility(View.GONE);
      lpHud = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
      addView(hud, lpHud);

      sleepChip = new TextView(act);
      sleepChip.setBackgroundColor(Color.TRANSPARENT);
      sleepChip.setTextColor(Color.WHITE);
      sleepChip.setTextSize(13f);
      sleepChip.setTextIsSelectable(false);
      sleepChip.setPadding(dp(12), dp(6), dp(12), dp(6));
      GradientDrawable sgd = new GradientDrawable();
      sgd.setCornerRadius(dp(16));
      sgd.setColor(0xA6000000);
      sleepChip.setBackground(sgd);
      sleepChip.setVisibility(View.GONE);
      FrameLayout.LayoutParams lpSleep = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
      lpSleep.setMargins(dp(14), dp(60), 0, 0);
      addView(sleepChip, lpSleep);

      flash = new IconV(act, "play", 36f, true);
      flash.setAlpha(0f);
      addView(flash, lp(dp(76), dp(76), Gravity.CENTER));

      heart = new IconV(act, "heart", 96f, false);
      heart.setLook(0xFFFF3B5C, true);
      heart.setAlpha(0f);
      addView(heart, lp(dp(120), dp(120), Gravity.CENTER));

      moreBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          moreMenu();
        }
      });
      likeBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          doLike(false);
        }
      });
      soundBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          toggleMute();
        }
      });
      shuffleBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          setShuffle(!shuffleOn);
        }
      });
      hideBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          hideMenu();
        }
      });
      backBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          if (Pager.this.cb != null) Pager.this.cb.onClose();
        }
      });

      ui();
      assign(pg[1], idx);
      pg[1].pl.setPlayWhenReady(true);
      assign(pg[0], idx - 1);
      assign(pg[2], idx + 1);
      arrive();
      hd.post(tickRun);
    }

    /* places the buttons, keeping clear of the phone's top and bottom bars */
    private void ui() {
      lpBack.setMargins(dp(8), dp(8) + insT, 0, 0);
      lpHud.setMargins(0, dp(70) + insT, 0, 0);
      lpSeek.setMargins(0, 0, 0, insB);
      lpTime.setMargins(dp(14), 0, 0, dp(26) + insB);
      lpTitle.setMargins(dp(14), 0, dp(76), dp(52) + insB);
      lpSide.setMargins(0, 0, dp(10), dp(30) + insB);
      requestLayout();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets ins) {
      try {
        insT = ins.getSystemWindowInsetTop();
        insB = ins.getSystemWindowInsetBottom();
        ui();
      } catch (Throwable t) {
      }
      return super.onApplyWindowInsets(ins);
    }

    int index() {
      return idx;
    }

    String curId() {
      return idx >= 0 && idx < ids.size() ? ids.get(idx) : "";
    }

    void pauseNow() {
      pg[1].pl.setPlayWhenReady(false);
    }

    void resumeNow() {
      if (minimized) return;
      if (away) {
        away = false;
        if (cb != null) cb.bg("stop", "", false, 0L, 0L, "", 1f);
      }
      if (!dead && !userPaused) pg[1].pl.setPlayWhenReady(true);
    }

    boolean bgOn() {
      return bgPlay;
    }

    boolean isMin() {
      return minimized;
    }

    private void miniPush() {
      if (dead || cb == null || idx < 0 || idx >= ids.size()) return;
      ExoPlayer p = pg[1].pl;
      long d = Math.max(0L, p.getDuration());
      float f = d > 0L ? Math.min(1f, Math.max(0f, p.getCurrentPosition() / (float) d)) : 0f;
      cb.onMini(titles.get(idx), p.getPlayWhenReady(), f);
    }

    /* Back with Background play on: the video goes out of sight, the sound goes on and the small bar shows in the app */
    void minimize() {
      if (dead || idx < 0 || idx >= ids.size()) return;
      closeSheetNow();
      minimized = true;
      setVisibility(View.GONE);
      away = true;
      bgUpdate();
      miniPush();
    }

    /* a tap on the small bar: the video is back */
    void restore() {
      if (dead) return;
      minimized = false;
      setVisibility(View.VISIBLE);
      if (away) {
        away = false;
        if (cb != null) cb.bg("stop", "", false, 0L, 0L, "", 1f);
      }
      requestLayout();
    }

    /* the app is left (home, other app, screen off) while Background play is on: the sound goes on and the notification shows */
    void leaveBg() {
      if (dead) return;
      away = true;
      bgUpdate();
    }

    private void bgUpdate() {
      if (!away || !bgPlay || dead || cb == null || idx < 0 || idx >= ids.size()) return;
      ExoPlayer p = pg[1].pl;
      cb.bg("start", titles.get(idx), p.getPlayWhenReady(), Math.max(0L, p.getCurrentPosition()), Math.max(0L, p.getDuration()), paths.get(idx), speed);
    }

    /* commands from the notification */
    void remoteCmd(String a, long arg) {
      if (dead) return;
      ExoPlayer p = pg[1].pl;
      if ("toggle".equals(a)) {
        if (p.getPlayWhenReady()) {
          userPaused = true;
          p.setPlayWhenReady(false);
        } else {
          userPaused = false;
          p.setPlayWhenReady(true);
        }
      } else if ("play".equals(a)) {
        userPaused = false;
        p.setPlayWhenReady(true);
      } else if ("pause".equals(a)) {
        userPaused = true;
        p.setPlayWhenReady(false);
      } else if ("seek".equals(a)) p.seekTo(Math.max(0L, arg));
      else if ("fwd".equals(a)) p.seekTo(Math.min(Math.max(0L, p.getDuration()), p.getCurrentPosition() + 10000L));
      else if ("rew".equals(a)) p.seekTo(Math.max(0L, p.getCurrentPosition() - 10000L));
      else if ("next".equals(a)) {
        if (idx < paths.size() - 1) commit(1);
        else p.seekTo(0L);
      } else if ("prev".equals(a)) {
        if (idx > 0) commit(-1);
        else p.seekTo(0L);
      }
      bgUpdate();
    }

    /* shuffle: the order of the shorts is mixed, the one on the screen stays; pressed again, the first order comes back */
    private void applyOrder(List<Integer> perm) {
      reorder(paths, perm);
      reorder(ids, perm);
      reorder(titles, perm);
      reorder(origs, perm);
      reorder(likes, perm);
      reorder(plOf, perm);
      reorder(pfOf, perm);
    }

    private <T> void reorder(List<T> l, List<Integer> perm) {
      if (l.size() != perm.size()) return;
      List<T> c = new ArrayList<T>(l);
      l.clear();
      for (int k = 0; k < perm.size(); k++) l.add(c.get(perm.get(k).intValue()));
    }

    private void setShuffle(boolean on) {
      if (dead || animating) return;
      int n = ids.size();
      if (n < 2) {
        Toast.makeText(act, "Only one short here", Toast.LENGTH_SHORT).show();
        return;
      }
      String cur = ids.get(idx);
      if (on) {
        origIds = new ArrayList<String>(ids);
        List<Integer> others = new ArrayList<Integer>();
        for (int k = 0; k < n; k++) if (k != idx) others.add(Integer.valueOf(k));
        Collections.shuffle(others);
        List<Integer> perm = new ArrayList<Integer>();
        int o = 0;
        for (int k = 0; k < n; k++) perm.add(k == idx ? Integer.valueOf(idx) : others.get(o++));
        applyOrder(perm);
      } else if (origIds != null) {
        List<Integer> perm = new ArrayList<Integer>();
        for (int k = 0; k < origIds.size(); k++) {
          int at = ids.indexOf(origIds.get(k));
          if (at >= 0) perm.add(Integer.valueOf(at));
        }
        applyOrder(perm);
        origIds = null;
      }
      shuffleOn = on;
      shuffleBtn.setLook(on ? 0xFFFFB52E : Color.WHITE, false);
      int at = ids.indexOf(cur);
      if (at >= 0) idx = at;
      pg[1].loaded = idx;
      pg[0].loaded = -1;
      pg[2].loaded = -1;
      if (idx - 1 < 0) {
        pg[0].pl.stop();
        pg[0].pl.clearMediaItems();
        pg[0].cover.setVisibility(View.GONE);
      } else assign(pg[0], idx - 1);
      if (idx + 1 >= paths.size()) {
        pg[2].pl.stop();
        pg[2].pl.clearMediaItems();
        pg[2].cover.setVisibility(View.GONE);
      } else assign(pg[2], idx + 1);
      if (cb != null) cb.onIndex(idx, ids.get(idx));
      Toast.makeText(act, on ? "Shuffle on" : "Shuffle off", Toast.LENGTH_SHORT).show();
    }

    private void speedMenu() {
      List<String> l = new ArrayList<String>();
      List<Runnable> a = new ArrayList<Runnable>();
      final float[] vs = { 0.25f, 0.5f, 0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f };
      for (int i = 0; i < vs.length; i++) {
        final float v = vs[i];
        String t = (v == 1f ? "Normal" : (v + "x"));
        l.add(t + (Math.abs(speed - v) < 0.01f ? "   (now)" : ""));
        a.add(new Runnable() {
          @Override
          public void run() {
            speed = v;
            pg[1].pl.setPlaybackSpeed(v);
            Toast.makeText(act, "Speed " + (v == 1f ? "normal" : (v + "x")), Toast.LENGTH_SHORT).show();
          }
        });
      }
      openSheet("Playback speed", l, a);
    }

    private void toggleBg() {
      bgPlay = !bgPlay;
      if (bgPlay && cb != null) cb.bg("perm", "", false, 0L, 0L, "", 1f);
      Toast.makeText(act, bgPlay ? "Background play on: the sound keeps playing when you leave the app" : "Background play off", Toast.LENGTH_SHORT).show();
    }

    void release() {
      dead = true;
      hd.removeCallbacksAndMessages(null);
      if (anim != null) anim.cancel();
      for (int i = 0; i < 3; i++) {
        try {
          pg[i].pl.release();
        } catch (Throwable t) {
        }
      }
    }

    private String fmt(long ms) {
      long s = Math.max(0L, ms / 1000L);
      long h = s / 3600L;
      long m = (s % 3600L) / 60L;
      long r = s % 60L;
      if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (r < 10 ? "0" : "") + r;
      return m + ":" + (r < 10 ? "0" : "") + r;
    }

    private String timeText(long pos, long dur) {
      if ("down".equals(tmode)) {
        long left = (long) Math.ceil(Math.max(0L, dur - pos) / 1000.0) * 1000L;
        return "-" + fmt(left) + " / " + fmt(dur);
      }
      return fmt(pos) + " / " + fmt(dur);
    }

    private void tick() {
      ExoPlayer p = pg[1].pl;
      long d = p.getDuration();
      long pos = p.getCurrentPosition();
      if (d > 0 && !seekUser && mode != 4) {
        seekV.frac = Math.max(0f, Math.min(1f, pos / (float) d));
        seekV.invalidate();
        timeTv.setText(timeText(pos, d));
      } else if (d <= 0) {
        timeTv.setText("");
      }
    }

    /* the page on screen changed: title, like count and the like mark start fresh */
    private void arrive() {
      liked = false;
      userPaused = false;
      if (idx >= 0 && idx < likes.size()) {
        likeBase = likes.get(idx).intValue();
        likeTv.setText(String.valueOf(likeBase));
        titleTv.setText(titles.get(idx));
      }
      likeBtn.setLook(Color.WHITE, false);
      seekV.frac = 0f;
      seekV.invalidate();
      for (int i = 0; i < 3; i++) pg[i].pl.setVolume(muted ? 0f : 1f);
      pg[1].pl.setPlaybackSpeed(speed);
      if (cb != null && idx >= 0 && idx < ids.size()) cb.onIndex(idx, ids.get(idx));
      bgUpdate();
      wrapAround();
    }

    /* endless: when the last short is on the screen the list is turned around, so the one on the screen becomes the first and the next ones are ready (mixed again, or in the same order for a chosen set) */
    private void wrapAround() {
      int n = ids.size();
      if (n < 2 || idx != n - 1 || dead) return;
      List<Integer> perm = new ArrayList<Integer>();
      if (loopShuffle) {
        List<Integer> others = new ArrayList<Integer>();
        for (int k = 0; k < n; k++) if (k != idx) others.add(Integer.valueOf(k));
        Collections.shuffle(others);
        perm.add(Integer.valueOf(idx));
        perm.addAll(others);
      } else {
        for (int k = 0; k < n; k++) perm.add(Integer.valueOf((idx + k) % n));
      }
      applyOrder(perm);
      origIds = null;
      idx = 0;
      pg[1].loaded = 0;
      pg[0].loaded = -1;
      pg[2].loaded = -1;
      pg[0].pl.stop();
      pg[0].pl.clearMediaItems();
      pg[0].cover.setVisibility(View.GONE);
      assign(pg[2], 1);
    }

    private void doLike(boolean viaDouble) {
      if (idx < 0 || idx >= likes.size()) return;
      int c = likes.get(idx).intValue();
      if (!liked) {
        liked = true;
        c++;
        if (viaDouble) heartAnim();
      } else {
        liked = false;
        c = Math.max(likeBase, c - 1);
      }
      likes.set(idx, Integer.valueOf(c));
      likeTv.setText(String.valueOf(c));
      likeBtn.setLook(liked ? 0xFFFF3B5C : Color.WHITE, liked);
      if (cb != null) cb.onLike(ids.get(idx), c);
    }

    private void heartAnim() {
      heart.animate().cancel();
      heart.setScaleX(0.5f);
      heart.setScaleY(0.5f);
      heart.setAlpha(1f);
      heart.animate().alpha(0f).scaleX(1.3f).scaleY(1.3f).setDuration(700).start();
    }

    private void flashGlyph(String g) {
      flash.animate().cancel();
      flash.setKind(g);
      flash.setAlpha(1f);
      flash.animate().alpha(0f).setDuration(600).start();
    }

    private void togglePause() {
      ExoPlayer p = pg[1].pl;
      if (p.getPlayWhenReady()) {
        userPaused = true;
        p.setPlayWhenReady(false);
        flashGlyph("pause");
      } else {
        userPaused = false;
        p.setPlayWhenReady(true);
        flashGlyph("play");
      }
    }

    private void toggleMute() {
      muted = !muted;
      for (int i = 0; i < 3; i++) pg[i].pl.setVolume(muted ? 0f : 1f);
      soundBtn.setKind(muted ? "mute" : "vol");
    }

    private void showHud(String t) {
      hd.removeCallbacks(hudRun);
      hud.setText(t);
      hud.setVisibility(View.VISIBLE);
    }

    private void hudSoon() {
      hd.removeCallbacks(hudRun);
      hd.postDelayed(hudRun, 700);
    }

    /* the three dots: the same dark sheet that slides up from the bottom as in the rest of the app */
    private void moreMenu() {
      if (idx < 0 || idx >= ids.size()) return;
      List<String> l = new ArrayList<String>();
      List<Runnable> a = new ArrayList<Runnable>();
      l.add("Rename");
      a.add(new Runnable() {
        @Override
        public void run() {
          askText(true);
        }
      });
      l.add("Edit likes");
      a.add(new Runnable() {
        @Override
        public void run() {
          askText(false);
        }
      });
      l.add("Use this frame as thumbnail");
      a.add(new Runnable() {
        @Override
        public void run() {
          frameThumb();
        }
      });
      l.add("Choose thumbnail from gallery");
      a.add(new Runnable() {
        @Override
        public void run() {
          if (cb != null) cb.onGallery(ids.get(idx));
        }
      });
      l.add("Auto scroll:  " + (autoScroll ? "On" : "Off"));
      a.add(new Runnable() {
        @Override
        public void run() {
          setAuto(!autoScroll);
        }
      });
      l.add("Playback speed" + (speed != 1f ? "   (" + speed + "x)" : ""));
      a.add(new Runnable() {
        @Override
        public void run() {
          speedMenu();
        }
      });
      l.add("Sleep timer" + (sleepMin > 0 ? "   (" + sleepMin + " min)" : ""));
      a.add(new Runnable() {
        @Override
        public void run() {
          sleepMenu();
        }
      });
      l.add("Background play:  " + (bgPlay ? "On" : "Off"));
      a.add(new Runnable() {
        @Override
        public void run() {
          toggleBg();
        }
      });
      l.add("Share");
      a.add(new Runnable() {
        @Override
        public void run() {
          if (cb != null) cb.onAction(ids.get(idx), "share");
        }
      });
      final String pid = ids.get(idx);
      l.add(pinned.contains(pid) ? "Unpin" : "Pin to top");
      a.add(new Runnable() {
        @Override
        public void run() {
          if (pinned.contains(pid)) pinned.remove(pid);
          else pinned.add(pid);
          if (cb != null) cb.onAction(pid, "pin");
        }
      });
      l.add("File information");
      a.add(new Runnable() {
        @Override
        public void run() {
          showInfo();
        }
      });
      l.add("Make playlist");
      a.add(new Runnable() {
        @Override
        public void run() {
          newPlaylist();
        }
      });
      l.add("Move to playlist");
      a.add(new Runnable() {
        @Override
        public void run() {
          playlistMenu();
        }
      });
      if (plOf.get(idx).length() > 0) {
        l.add("Remove from playlist");
        a.add(new Runnable() {
          @Override
          public void run() {
            setPlaylist("", "");
          }
        });
      }
      l.add("Remove from Shorts");
      a.add(new Runnable() {
        @Override
        public void run() {
          removeCurrent(1);
        }
      });
      openSheet(titles.get(idx), l, a);
    }

    private void openSheet(String head, List<String> labels, List<Runnable> acts) {
      closeSheetNow();
      final FrameLayout root = new FrameLayout(act);
      root.setBackgroundColor(0x80000000);
      root.setClickable(true);
      root.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          closeSheet();
        }
      });
      final LinearLayout panel = new LinearLayout(act);
      panel.setOrientation(LinearLayout.VERTICAL);
      GradientDrawable pd = new GradientDrawable();
      pd.setColor(0xFF141A2B);
      pd.setCornerRadii(new float[] { dp(20), dp(20), dp(20), dp(20), 0f, 0f, 0f, 0f });
      panel.setBackground(pd);
      panel.setClickable(true);
      panel.setPadding(dp(16), dp(8), dp(16), dp(14) + insB);
      TextView h = new TextView(act);
      h.setBackgroundColor(Color.TRANSPARENT);
      h.setText(head);
      h.setTextColor(0xFF929AB6);
      h.setTextSize(13f);
      h.setSingleLine(true);
      h.setEllipsize(android.text.TextUtils.TruncateAt.END);
      h.setPadding(dp(4), dp(10), dp(4), dp(6));
      panel.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      ScrollView sv = new ScrollView(act);
      sv.setBackgroundColor(Color.TRANSPARENT);
      LinearLayout col = new LinearLayout(act);
      col.setOrientation(LinearLayout.VERTICAL);
      col.setBackgroundColor(Color.TRANSPARENT);
      sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      int rows = 0;
      for (int i = 0; i < labels.size(); i++) {
        final Runnable r = acts.get(i);
        col.addView(sheetRow(labels.get(i), false, r), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        rows++;
      }
      int avail = Math.max(dp(160), Math.round(getHeight() * 0.62f));
      panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(rows * dp(52), avail)));
      panel.addView(sheetRow("Cancel", true, null), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
      root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
      addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      sheetRoot = root;
      panel.setTranslationY(dp(600));
      panel.animate().translationY(0f).setDuration(230).start();
    }

    private TextView sheetRow(String label, boolean muted, final Runnable r) {
      TextView x = new TextView(act);
      x.setBackgroundColor(Color.TRANSPARENT);
      x.setText(label);
      x.setTextColor(muted ? 0xFF929AB6 : Color.WHITE);
      x.setTextSize(16f);
      x.setGravity(Gravity.CENTER_VERTICAL);
      x.setPadding(dp(4), 0, dp(4), 0);
      x.setTextIsSelectable(false);
      x.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          closeSheetNow();
          if (r != null) r.run();
        }
      });
      return x;
    }

    private void closeSheet() {
      closeSheetNow();
    }

    private void closeSheetNow() {
      if (sheetRoot != null) {
        removeView(sheetRoot);
        sheetRoot = null;
      }
    }

    boolean backStep() {
      if (sheetRoot != null) {
        closeSheetNow();
        return true;
      }
      return false;
    }

    private void setAuto(boolean on) {
      autoScroll = on;
      for (int i = 0; i < 3; i++) pg[i].pl.setRepeatMode(on ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
      Toast.makeText(act, on ? "Auto scroll on: the next short plays when this one ends" : "Auto scroll off", Toast.LENGTH_SHORT).show();
    }

    private void autoNext() {
      if (dead || animating || !autoScroll) return;
      if (idx < ids.size() - 1) {
        commit(1);
      } else {
        pg[1].pl.seekTo(0L);
        pg[1].pl.setPlayWhenReady(true);
      }
    }

    /* the same number pad as in the long video player: type hours and minutes, then OK */
    private void sleepMenu() {
      closeSheetNow();
      final int YEL = 0xFFFFB52E;
      final FrameLayout root = new FrameLayout(act);
      root.setBackgroundColor(0x88000000);
      root.setClickable(true);
      root.setOnClickListener(v -> closeSheet());
      final StringBuilder dg = new StringBuilder();
      LinearLayout card = new LinearLayout(act);
      card.setOrientation(LinearLayout.VERTICAL);
      GradientDrawable g = new GradientDrawable();
      g.setColor(0xFF1C1C1E);
      g.setCornerRadii(new float[] { dp(18), dp(18), dp(18), dp(18), 0f, 0f, 0f, 0f });
      card.setBackground(g);
      card.setClickable(true);
      card.setPadding(dp(16), dp(8), dp(16), dp(10) + insB);
      LinearLayout head = new LinearLayout(act);
      head.setOrientation(LinearLayout.HORIZONTAL);
      head.setGravity(Gravity.CENTER_VERTICAL);
      head.setBackgroundColor(Color.TRANSPARENT);
      TextView ttl = new TextView(act);
      ttl.setBackgroundColor(Color.TRANSPARENT);
      ttl.setText("Sleep in");
      ttl.setTextColor(Color.WHITE);
      ttl.setTextSize(18f);
      head.addView(ttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      final TextView disp = new TextView(act);
      disp.setBackgroundColor(Color.TRANSPARENT);
      disp.setTextColor(YEL);
      disp.setTextSize(34f);
      disp.setText("0:00");
      head.addView(disp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      TextView bk = new TextView(act);
      bk.setBackgroundColor(Color.TRANSPARENT);
      bk.setText("\u232B");
      bk.setTextColor(Color.WHITE);
      bk.setTextSize(24f);
      bk.setGravity(Gravity.CENTER);
      head.addView(bk, new LinearLayout.LayoutParams(dp(48), dp(48)));
      card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
      final Runnable upd = new Runnable() {
        @Override
        public void run() {
          String s = "0000" + dg.toString();
          s = s.substring(s.length() - 4);
          disp.setText(String.format(Locale.US, "%d:%02d", Integer.parseInt(s.substring(0, 2)), Integer.parseInt(s.substring(2))));
        }
      };
      bk.setOnClickListener(v -> {
        if (dg.length() > 0) dg.setLength(dg.length() - 1);
        upd.run();
      });
      String[] keys = { "1", "2", "3", "4", "5", "6", "7", "8", "9", ":00", "0", ":30" };
      for (int rr = 0; rr < 4; rr++) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Color.TRANSPARENT);
        for (int cc = 0; cc < 3; cc++) {
          final String k = keys[rr * 3 + cc];
          TextView b = new TextView(act);
          b.setBackgroundColor(Color.TRANSPARENT);
          b.setText(k);
          b.setTextColor(Color.WHITE);
          b.setTextSize(24f);
          b.setGravity(Gravity.CENTER);
          b.setOnClickListener(v -> {
            dg.append(k.startsWith(":") ? k.substring(1) : k);
            if (dg.length() > 4) dg.delete(0, dg.length() - 4);
            upd.run();
          });
          row.addView(b, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
      }
      LinearLayout btns = new LinearLayout(act);
      btns.setOrientation(LinearLayout.HORIZONTAL);
      btns.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
      btns.setBackgroundColor(Color.TRANSPARENT);
      TextView off = new TextView(act);
      off.setBackgroundColor(Color.TRANSPARENT);
      off.setText("TURN OFF");
      off.setTextColor(YEL);
      off.setTextSize(14f);
      off.setGravity(Gravity.CENTER);
      off.setPadding(dp(14), 0, dp(14), 0);
      off.setOnClickListener(v -> {
        closeSheetNow();
        setSleep(0);
        Toast.makeText(act, "Sleep timer off", Toast.LENGTH_SHORT).show();
      });
      TextView ok = new TextView(act);
      ok.setBackgroundColor(Color.TRANSPARENT);
      ok.setText("OK");
      ok.setTextColor(YEL);
      ok.setTextSize(16f);
      ok.setGravity(Gravity.CENTER);
      ok.setPadding(dp(22), 0, dp(14), 0);
      ok.setOnClickListener(v -> {
        String s = "0000" + dg.toString();
        s = s.substring(s.length() - 4);
        int m = Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(2));
        closeSheetNow();
        setSleep(Math.min(m, 1440));
        if (m > 0) Toast.makeText(act, "Sleep timer: " + m + " min", Toast.LENGTH_SHORT).show();
      });
      btns.addView(off, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
      btns.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
      card.addView(btns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
      root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
      addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      sheetRoot = root;
      card.setTranslationY(dp(500));
      card.animate().translationY(0f).setDuration(220).start();
    }

    private final Runnable sleepRun = new Runnable() {
      @Override
      public void run() {
        if (dead || sleepEnd == 0L) return;
        sleepEnd = 0L;
        sleepMin = 0;
        sleepChip.setVisibility(View.GONE);
        userPaused = true;
        pg[1].pl.setPlayWhenReady(false);
        Toast.makeText(act, "Sleep timer: stopped", Toast.LENGTH_LONG).show();
      }
    };

    private void setSleep(int m) {
      hd.removeCallbacks(sleepRun);
      sleepMin = m;
      if (m <= 0) {
        sleepEnd = 0L;
        sleepChip.setVisibility(View.GONE);
        return;
      }
      sleepEnd = SystemClock.uptimeMillis() + m * 60000L;
      hd.postDelayed(sleepRun, m * 60000L);
      sleepChip.setText("\u23F0  " + new SimpleDateFormat("h:mm a", Locale.US).format(new Date(System.currentTimeMillis() + m * 60000L)));
      sleepChip.setVisibility(View.VISIBLE);
    }

    private void showInfo() {
      try {
        File f = new File(paths.get(idx));
        long d = Math.max(0L, pg[1].pl.getDuration());
        long b = f.length();
        String size = b >= 1073741824L ? String.format(Locale.US, "%.2f GB", b / 1073741824.0) : String.format(Locale.US, "%.1f MB", b / 1048576.0);
        String msg = "Name: " + f.getName() + "\nSize: " + size + "\nLength: " + fmt(d) + (pg[1].vw > 0 ? "\nPicture: " + pg[1].vw + " x " + pg[1].vh : "") + "\nPlace: " + f.getPath();
        new AlertDialog.Builder(act).setTitle("File information").setMessage(msg).setPositiveButton("OK", null).show();
      } catch (Throwable t) {
      }
    }

    /* a playlist works like the folders of the long videos: pick one and the short is in it (no folders inside) */
    private void playlistMenu() {
      if (plNames.size() == 0) {
        Toast.makeText(act, "First make a playlist: three dots, then Make playlist", Toast.LENGTH_LONG).show();
        return;
      }
      List<String> l = new ArrayList<String>();
      List<Runnable> a = new ArrayList<Runnable>();
      final String cur = plOf.get(idx);
      for (int i = 0; i < plNames.size(); i++) {
        final String n = plNames.get(i);
        l.add((n.equals(cur) ? "\u2713  " : "") + n);
        a.add(new Runnable() {
          @Override
          public void run() {
            setPlaylist(n, "");
          }
        });
      }
      openSheet("Move to playlist", l, a);
    }

    private void setPlaylist(String n, String f) {
      plOf.set(idx, n);
      if (idx < pfOf.size()) pfOf.set(idx, f);
      if (n.length() > 0 && f.length() > 0) {
        List<String> fl = pfMap.get(n);
        if (fl == null) {
          fl = new ArrayList<String>();
          pfMap.put(n, fl);
        }
        if (!fl.contains(f)) fl.add(f);
      }
      if (cb != null) cb.onPlaylist(ids.get(idx), n, f);
      Toast.makeText(act, n.length() > 0 ? "Moved to " + n + (f.length() > 0 ? " / " + f : "") : "Removed from playlist", Toast.LENGTH_SHORT).show();
    }

    private void newPlaylist() {
      final EditText et = new EditText(act);
      et.setInputType(InputType.TYPE_CLASS_TEXT);
      try {
        new AlertDialog.Builder(act).setTitle("New playlist").setView(et).setPositiveButton("Create", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int which) {
            String n = et.getText().toString().trim();
            if (n.length() == 0) return;
            if (n.length() > 30) n = n.substring(0, 30);
            if (!plNames.contains(n)) plNames.add(n);
            setPlaylist(n, "");
          }
        }).setNegativeButton("Cancel", null).show();
      } catch (Throwable t) {
      }
    }

    private void askText(final boolean rename) {
      final EditText et = new EditText(act);
      if (rename) {
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(titles.get(idx));
      } else {
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(likes.get(idx).intValue()));
      }
      final int at = idx;
      try {
        AlertDialog dlg = new AlertDialog.Builder(act)
          .setTitle(rename ? "Rename" : "Edit likes")
          .setView(et)
          .setPositiveButton(
            "Save",
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface d, int which) {
                if (dead || at != idx) return;
                String v = et.getText().toString().trim();
                if (rename) {
                  titles.set(at, v.length() > 0 ? v : origs.get(at));
                  titleTv.setText(titles.get(at));
                  if (cb != null) cb.onRename(ids.get(at), v);
                  Toast.makeText(act, v.length() > 0 ? "Renamed" : "Original name restored", Toast.LENGTH_SHORT).show();
                } else {
                  int n = 0;
                  try {
                    n = Integer.parseInt(v);
                  } catch (Throwable t) {
                    n = 0;
                  }
                  n = Math.max(0, Math.min(999999, n));
                  likes.set(at, Integer.valueOf(n));
                  likeBase = n;
                  liked = false;
                  likeTv.setText(String.valueOf(n));
                  likeBtn.setLook(Color.WHITE, false);
                  if (cb != null) cb.onLike(ids.get(at), n);
                  Toast.makeText(act, "Likes changed", Toast.LENGTH_SHORT).show();
                }
              }
            }
          )
          .setNegativeButton("Cancel", null)
          .create();
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dlg.show();
        et.requestFocus();
        et.selectAll();
      } catch (Throwable t) {
      }
    }

    /* takes the picture that is on the screen right now and keeps it as the thumbnail */
    private void frameThumb() {
      try {
        Bitmap b = pg[1].tv.getBitmap();
        if (b == null) {
          Toast.makeText(act, "Play the short first, then try again", Toast.LENGTH_SHORT).show();
          return;
        }
        if (cb != null) cb.onThumb(ids.get(idx), smallJpeg(b));
        Toast.makeText(act, "Thumbnail changed", Toast.LENGTH_SHORT).show();
      } catch (Throwable t) {
        Toast.makeText(act, "Could not take the frame", Toast.LENGTH_SHORT).show();
      }
    }

    /* the trash button goes straight to the question "Delete this file?" (then the phone's own permission step) */
    private void hideMenu() {
      confirmDelete();
    }

    /* the file is deleted from the phone for good (the app part does the delete, then the short goes out of the viewer) */
    private void confirmDelete() {
      if (idx < 0 || idx >= ids.size()) return;
      final String id = ids.get(idx);
      try {
        new AlertDialog.Builder(act)
          .setTitle("Delete this file?")
          .setMessage("\"" + titles.get(idx) + "\" will be deleted from your phone for good.")
          .setPositiveButton(
            "Delete",
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface d, int which) {
                if (cb != null) cb.onAction(id, "delete");
              }
            }
          )
          .setNegativeButton("Cancel", null)
          .show();
      } catch (Throwable t) {
      }
    }

    void removeById(String id) {
      if (dead) return;
      int at = ids.indexOf(id);
      if (at < 0) return;
      if (at == idx) {
        removeCurrent(2);
        return;
      }
      paths.remove(at);
      ids.remove(at);
      titles.remove(at);
      origs.remove(at);
      likes.remove(at);
      if (at < plOf.size()) plOf.remove(at);
      if (at < pfOf.size()) pfOf.remove(at);
      if (at < idx) idx--;
      pg[1].loaded = idx;
      pg[0].loaded = -1;
      pg[2].loaded = -1;
      if (idx - 1 < 0) {
        pg[0].pl.stop();
        pg[0].pl.clearMediaItems();
        pg[0].cover.setVisibility(View.GONE);
      } else assign(pg[0], idx - 1);
      if (idx + 1 >= paths.size()) {
        pg[2].pl.stop();
        pg[2].pl.clearMediaItems();
        pg[2].cover.setVisibility(View.GONE);
      } else assign(pg[2], idx + 1);
    }

    /* the hidden short goes away and the next one (or the one before, if it was the last) is already ready */
    private void removeCurrent(int mode) {
      if (dead) return;
      String id = ids.get(idx);
      int at = idx;
      paths.remove(at);
      ids.remove(at);
      titles.remove(at);
      origs.remove(at);
      likes.remove(at);
      if (at < plOf.size()) plOf.remove(at);
      if (at < pfOf.size()) pfOf.remove(at);
      for (int i = 0; i < 3; i++) {
        if (pg[i].loaded > at) pg[i].loaded--;
      }
      pg[1].loaded = -1;
      pg[1].pl.setPlayWhenReady(false);
      pg[1].pl.stop();
      pg[1].pl.clearMediaItems();
      if (cb != null) {
        if (mode == 0) cb.onHide(id);
        else if (mode == 1) cb.onAction(id, "unshort");
      }
      if (paths.size() == 0) {
        if (cb != null) cb.onClose();
        return;
      }
      if (at < paths.size()) {
        Page t = pg[0];
        pg[0] = pg[1];
        pg[1] = pg[2];
        pg[2] = t;
        assign(pg[0], -1);
        assign(pg[2], idx + 1);
      } else {
        Page t = pg[2];
        pg[2] = pg[1];
        pg[1] = pg[0];
        pg[0] = t;
        idx--;
        assign(pg[2], -1);
        assign(pg[0], idx - 1);
      }
      place(0f);
      pg[0].pl.setPlayWhenReady(false);
      pg[2].pl.setPlayWhenReady(false);
      pg[1].pl.setPlayWhenReady(true);
      arrive();
    }

    private void assign(Page p, int index) {
      if (index < 0 || index >= paths.size()) {
        if (p.loaded != -1) {
          p.pl.stop();
          p.pl.clearMediaItems();
        }
        p.loaded = -1;
        p.vw = 0;
        p.vh = 0;
        p.pl.setPlayWhenReady(false);
        return;
      }
      if (p.loaded == index) return;
      p.loaded = index;
      p.vw = 0;
      p.vh = 0;
      p.pl.setPlaybackSpeed(1f);
      Bitmap cv = covers == null ? null : covers.get(ids.get(index));
      if (cv != null) {
        p.cover.setImageBitmap(cv);
        p.cover.setVisibility(View.VISIBLE);
      } else p.cover.setVisibility(View.GONE);
      p.pl.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(paths.get(index)))));
      p.pl.prepare();
      p.pl.setPlayWhenReady(false);
    }

    private void fit(Page p) {
      if (W == 0 || H == 0 || p.vw == 0 || p.vh == 0) return;
      float sx = (float) W / p.vw;
      float sy = (float) H / p.vh;
      float s = p.vh > p.vw ? Math.max(sx, sy) : Math.min(sx, sy);
      p.tv.setLayoutParams(new FrameLayout.LayoutParams(Math.round(p.vw * s), Math.round(p.vh * s), Gravity.CENTER));
    }

    private void place(float d) {
      for (int i = 0; i < 3; i++) {
        pg[i].root.setTranslationY((i - 1) * H + d);
      }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
      super.onSizeChanged(w, h, ow, oh);
      W = w;
      H = h;
      place(dy);
      for (int i = 0; i < 3; i++) fit(pg[i]);
    }

    private float clamp(float v, float a, float b) {
      return Math.max(a, Math.min(b, v));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      if (animating || dead) return true;
      int a = e.getActionMasked();
      float slop = dp(12);
      if (a == MotionEvent.ACTION_DOWN) {
        downX = e.getRawX();
        downY = e.getRawY();
        dy = 0f;
        mode = 0;
        zone = downY < H * 0.40f ? 0 : (downY < H * 0.65f ? 1 : 2);
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        vt.addMovement(e);
        hd.removeCallbacks(holdRun);
        hd.postDelayed(holdRun, 450);
        return true;
      }
      if (vt == null) return true;
      if (a == MotionEvent.ACTION_MOVE) {
        vt.addMovement(e);
        float mx = e.getRawX() - downX;
        float my = e.getRawY() - downY;
        if (mode == 0) {
          if (Math.abs(mx) < slop && Math.abs(my) < slop) return true;
          hd.removeCallbacks(holdRun);
          if (Math.abs(mx) > Math.abs(my)) {
            mode = zone == 0 ? 2 : (zone == 1 ? 3 : 4);
            if (mode == 2) gBase = cb.getBright();
            if (mode == 3) gBase = cb.getVol();
            if (mode == 4) {
              long d = pg[1].pl.getDuration();
              if (d <= 0) {
                mode = 6;
              } else {
                seekBase = pg[1].pl.getCurrentPosition();
                seekSpan = Math.min(d, 30000L);
                seekT = seekBase;
              }
            }
          } else {
            mode = 1;
          }
        }
        if (mode == 1) {
          dy = my;
          place(shown(dy));
        } else if (mode == 2 || mode == 3) {
          float val = clamp(gBase + mx / (W * 0.75f), 0f, 1f);
          if (mode == 2) {
            cb.setBright(val);
            showHud("Brightness " + Math.round(val * 100f) + "%");
          } else {
            cb.setVol(val);
            showHud("Volume " + Math.round(val * 100f) + "%");
          }
        } else if (mode == 4) {
          long d = pg[1].pl.getDuration();
          long t = seekBase + (long) (mx / W * seekSpan);
          seekT = Math.max(0L, Math.min(d, t));
          long df = Math.round((seekT - seekBase) / 1000.0);
          showHud((df >= 0 ? "+" : "") + df + "s  (" + fmt(seekT) + ")");
        }
        return true;
      }
      if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
        vt.addMovement(e);
        vt.computeCurrentVelocity(1000);
        float vy = vt.getYVelocity();
        vt.recycle();
        vt = null;
        hd.removeCallbacks(holdRun);
        boolean cancel = a == MotionEvent.ACTION_CANCEL;
        if (mode == 5) {
          pg[1].pl.setPlaybackSpeed(speed);
          hudSoon();
        } else if (mode == 1) {
          settle(cancel ? 0f : vy);
        } else if (mode == 4) {
          if (!cancel) pg[1].pl.seekTo(seekT);
          hudSoon();
        } else if (mode != 0) {
          hudSoon();
        } else if (!cancel) {
          long now = SystemClock.uptimeMillis();
          if (lastTapT != 0L && now - lastTapT < 260L) {
            hd.removeCallbacks(tapRun);
            lastTapT = 0L;
            doubleTap(downX, downY);
          } else {
            lastTapT = now;
            hd.postDelayed(tapRun, 230);
          }
        }
        mode = 0;
      }
      return true;
    }

    /* two quick taps: low on the screen = 10 seconds back (left) or forward (right); anywhere else = like */
    private void doubleTap(float x, float y) {
      if (y > H * 0.65f) {
        ExoPlayer p = pg[1].pl;
        long d = p.getDuration();
        if (d <= 0) return;
        long step = x < W / 2f ? -10000L : 10000L;
        long t = Math.max(0L, Math.min(d, p.getCurrentPosition() + step));
        p.seekTo(t);
        showHud(step < 0 ? "-10s" : "+10s");
        hudSoon();
      } else {
        doLike(true);
      }
    }

    /* at the first and last video the page only moves a little, like a rubber band */
    private float shown(float d) {
      if ((idx == 0 && d > 0f) || (idx == paths.size() - 1 && d < 0f)) return d / 3f;
      return d;
    }

    private void settle(float vy) {
      float d = dy;
      int dir = 0;
      if (d < 0f && idx < paths.size() - 1 && (-d > H * 0.18f || vy < -900f)) dir = 1;
      else if (d > 0f && idx > 0 && (d > H * 0.18f || vy > 900f)) dir = -1;
      float from = shown(d);
      float target = dir > 0 ? -H : (dir < 0 ? H : 0f);
      final int fdir = dir;
      float frac = Math.abs(target - from) / Math.max(1, H);
      animating = true;
      anim = ValueAnimator.ofFloat(from, target);
      anim.setDuration(Math.max(120, Math.round(240f * frac)));
      anim.setInterpolator(new DecelerateInterpolator());
      anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator va) {
          place(((Float) va.getAnimatedValue()).floatValue());
        }
      });
      anim.addListener(new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator an) {
          animating = false;
          if (dead) return;
          dy = 0f;
          if (fdir != 0) commit(fdir);
          else place(0f);
        }
      });
      anim.start();
    }

    /* the next (or previous) page is now the one on screen: turn the stack around by one */
    private void commit(int dir) {
      Page old = pg[1];
      old.pl.setPlayWhenReady(false);
      old.pl.setPlaybackSpeed(1f);
      old.pl.seekTo(0);
      if (dir > 0) {
        Page t = pg[0];
        pg[0] = pg[1];
        pg[1] = pg[2];
        pg[2] = t;
        idx++;
        assign(pg[2], idx + 1);
      } else {
        Page t = pg[2];
        pg[2] = pg[1];
        pg[1] = pg[0];
        pg[0] = t;
        idx--;
        assign(pg[0], idx - 1);
      }
      place(0f);
      pg[0].pl.setPlayWhenReady(false);
      pg[2].pl.setPlayWhenReady(false);
      pg[1].pl.setPlayWhenReady(true);
      arrive();
    }
  }
}
