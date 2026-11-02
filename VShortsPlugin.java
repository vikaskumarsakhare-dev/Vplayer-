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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    }
    if (paths.size() == 0) {
      call.reject("no paths");
      return;
    }
    Integer st = call.getInt("index", 0);
    final int start = st == null ? 0 : st.intValue();
    final String tmode = call.getString("tmode", "down");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          closeNow();
          Activity act = getActivity();
          origBright = act.getWindow().getAttributes().screenBrightness;
          ViewGroup parent = (ViewGroup) getBridge().getWebView().getParent();
          int s = Math.max(0, Math.min(start, paths.size() - 1));
          pager = new Pager(act, paths, ids, titles, origs, likes, plNames, plOf, s, tmode, new Pager.Cb() {
            @Override
            public void onIndex(int i) {
              JSObject o = new JSObject();
              o.put("index", i);
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
          parent.addView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
          pager.requestApplyInsets();
          backCb = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
              if (pager != null && pager.backStep()) return;
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

  /* the app asks for a picture from the gallery; it comes back as a small picture (text) */
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
      notifyListeners("closed", o);
    }
  }

  @Override
  protected void handleOnPause() {
    super.handleOnPause();
    if (pager != null) pager.pauseNow();
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
      void onIndex(int i);

      void onLike(String id, int count);

      void onHide(String id);

      void onTmode(String m);

      void onRename(String id, String t);

      void onThumb(String id, String data);

      void onGallery(String id);

      void onAction(String id, String act);

      void onPlaylist(String id, String name, String folder);

      void onClose();

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
        hd.postDelayed(this, 100);
      }
    };

    class Page {

      final FrameLayout root;
      final TextureView tv;
      final ExoPlayer pl;
      int vw = 0;
      int vh = 0;
      int loaded = -1;

      Page(Context ctx) {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        tv = new TextureView(ctx);
        root.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
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

    Pager(Activity act, List<String> paths, List<String> ids, List<String> titles, List<String> origs, List<Integer> likes, List<String> plNames, List<String> plOf, int start, String tmode, Cb cb) {
      super(act);
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
      hideBtn = new IconV(act, "trash", 22f, true);
      moreBtn = new IconV(act, "more", 24f, true);
      side.addView(likeBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
      side.addView(likeTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(dp(46), dp(46));
      l2.topMargin = dp(14);
      side.addView(soundBtn, l2);
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

    void pauseNow() {
      pg[1].pl.setPlayWhenReady(false);
    }

    void resumeNow() {
      if (!dead && !userPaused) pg[1].pl.setPlayWhenReady(true);
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
      if (cb != null) cb.onIndex(idx);
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
      l.add("Sleep timer" + (sleepMin > 0 ? "   (" + sleepMin + " min)" : ""));
      a.add(new Runnable() {
        @Override
        public void run() {
          sleepMenu();
        }
      });
      l.add("Share");
      a.add(new Runnable() {
        @Override
        public void run() {
          if (cb != null) cb.onAction(ids.get(idx), "share");
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

    private void sleepMenu() {
      List<String> l = new ArrayList<String>();
      List<Runnable> a = new ArrayList<Runnable>();
      int[] mins = { 0, 10, 15, 30, 45, 60, 90 };
      for (int i = 0; i < mins.length; i++) {
        final int m = mins[i];
        l.add((m == sleepMin ? "\u2713  " : "") + (m == 0 ? "Off" : m + " minutes"));
        a.add(new Runnable() {
          @Override
          public void run() {
            setSleep(m);
          }
        });
      }
      openSheet("Sleep timer", l, a);
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

    private void playlistMenu() {
      List<String> l = new ArrayList<String>();
      List<Runnable> a = new ArrayList<Runnable>();
      final String cur = plOf.get(idx);
      l.add((cur.length() == 0 ? "\u2713  " : "") + "No playlist");
      a.add(new Runnable() {
        @Override
        public void run() {
          setPlaylist("", "");
        }
      });
      for (int i = 0; i < plNames.size(); i++) {
        final String n = plNames.get(i);
        l.add((n.equals(cur) ? "\u2713  " : "") + n);
        a.add(new Runnable() {
          @Override
          public void run() {
            folderMenu(n);
          }
        });
      }
      l.add("+  New playlist");
      a.add(new Runnable() {
        @Override
        public void run() {
          newPlaylist();
        }
      });
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

    /* inside a playlist the shorts are grouped in folders */
    private void folderMenu(final String pl) {
      List<String> l = new ArrayList<String>();
      List<Runnable> a = new ArrayList<Runnable>();
      final String curF = idx < pfOf.size() ? pfOf.get(idx) : "";
      final boolean same = pl.equals(plOf.get(idx));
      l.add((same && curF.length() == 0 ? "\u2713  " : "") + "No folder (only in playlist)");
      a.add(new Runnable() {
        @Override
        public void run() {
          setPlaylist(pl, "");
        }
      });
      List<String> fl = pfMap.get(pl);
      if (fl != null) {
        for (int i = 0; i < fl.size(); i++) {
          final String f = fl.get(i);
          l.add((same && f.equals(curF) ? "\u2713  " : "") + f);
          a.add(new Runnable() {
            @Override
            public void run() {
              setPlaylist(pl, f);
            }
          });
        }
      }
      l.add("+  New folder");
      a.add(new Runnable() {
        @Override
        public void run() {
          newFolder(pl);
        }
      });
      openSheet(pl, l, a);
    }

    private void newFolder(final String pl) {
      final EditText et = new EditText(act);
      et.setInputType(InputType.TYPE_CLASS_TEXT);
      try {
        new AlertDialog.Builder(act).setTitle("New folder in " + pl).setView(et).setPositiveButton("Create", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int which) {
            String n = et.getText().toString().trim();
            if (n.length() == 0) return;
            if (n.length() > 30) n = n.substring(0, 30);
            setPlaylist(pl, n);
          }
        }).setNegativeButton("Cancel", null).show();
      } catch (Throwable t) {
      }
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
        new AlertDialog.Builder(act)
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
          .show();
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

    private void hideMenu() {
      if (idx < 0 || idx >= ids.size()) return;
      try {
        new AlertDialog.Builder(act)
          .setItems(
            new CharSequence[] { "Hide from V Player (you can bring it back in Settings)", "Delete from phone" },
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                  removeCurrent();
                } else {
                  Toast.makeText(act, "Delete from phone comes in a later part. Use Hide for now.", Toast.LENGTH_LONG).show();
                }
              }
            }
          )
          .show();
      } catch (Throwable t) {
      }
    }

    /* the hidden short goes away and the next one (or the one before, if it was the last) is already ready */
    private void removeCurrent() {
      if (dead) return;
      String id = ids.get(idx);
      int at = idx;
      paths.remove(at);
      ids.remove(at);
      titles.remove(at);
      origs.remove(at);
      likes.remove(at);
      for (int i = 0; i < 3; i++) {
        if (pg[i].loaded > at) pg[i].loaded--;
      }
      pg[1].loaded = -1;
      pg[1].pl.setPlayWhenReady(false);
      pg[1].pl.stop();
      pg[1].pl.clearMediaItems();
      if (cb != null) cb.onHide(id);
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
          pg[1].pl.setPlaybackSpeed(1f);
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
