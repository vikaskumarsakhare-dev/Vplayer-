package com.vplayer.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Rational;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.PathParser;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/* The long video player (like VLC). It is a separate file from the Shorts screen. */
@CapacitorPlugin(name = "VLong")
public class VLongPlugin extends Plugin {

  private LongView view;
  private OnBackPressedCallback backCb;
  private static VLongPlugin inst;

  /* the buttons of the phone notification come here */
  static void remote(final String a, final long arg) {
    final VLongPlugin p = inst;
    if (p == null || p.getActivity() == null) return;
    p.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (p.view == null) return;
        if ("close".equals(a)) p.closeNow();
        else p.view.remoteCmd(a, arg);
      }
    });
  }

  private void bgCmd(String cmd, String t, boolean playing, long pos, long dur, String path, float speed) {
    try {
      Context c = getContext();
      Intent i = new Intent();
      i.setClassName(c, "com.vplayer.app.VPlayService");
      if ("stop".equals(cmd)) {
        c.stopService(i);
        return;
      }
      i.putExtra("title", t);
      i.putExtra("playing", playing);
      i.putExtra("pos", pos);
      i.putExtra("dur", dur);
      i.putExtra("path", path);
      i.putExtra("speed", speed);
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
      else c.startService(i);
    } catch (Throwable e) {
    }
  }

  @PluginMethod
  public void toast(final PluginCall call) {
    final String m = call.getString("msg", "");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(getActivity(), m, Toast.LENGTH_LONG).show();
      }
    });
    call.resolve();
  }

  @PluginMethod
  public void open(final PluginCall call) {
    final List<String> qIds = new ArrayList<String>();
    final List<String> qPaths = new ArrayList<String>();
    final List<String> qTitles = new ArrayList<String>();
    final List<String> qFolders = new ArrayList<String>();
    final List<Long> qRes = new ArrayList<Long>();
    JSONArray qa = call.getArray("queue");
    if (qa != null) {
      for (int i = 0; i < qa.length(); i++) {
        JSONObject o = qa.optJSONObject(i);
        if (o == null) continue;
        String p = o.optString("path", "");
        if (p.length() == 0) continue;
        qIds.add(o.optString("id", ""));
        qPaths.add(p);
        qTitles.add(o.optString("title", ""));
        qFolders.add(o.optString("folder", ""));
        qRes.add(Long.valueOf((long) o.optDouble("resume", 0.0)));
      }
    }
    String path = call.getString("path", "");
    String id = call.getString("id", "");
    int at = -1;
    for (int i = 0; i < qIds.size(); i++) if (qIds.get(i).equals(id)) at = i;
    if (at < 0) {
      if (path == null || path.length() == 0) {
        call.reject("no path");
        return;
      }
      Double rs0 = call.getDouble("resume", 0.0);
      qIds.add(id);
      qPaths.add(path);
      qTitles.add(call.getString("title", ""));
      qFolders.add(call.getString("folder", ""));
      qRes.add(Long.valueOf(rs0 == null ? 0L : (long) rs0.doubleValue()));
      at = qIds.size() - 1;
    } else {
      Double rs1 = call.getDouble("resume", null);
      if (rs1 != null) qRes.set(at, Long.valueOf((long) rs1.doubleValue()));
    }
    final int start = at;
    Boolean rm = call.getBoolean("rem", false);
    final boolean rem = rm != null && rm.booleanValue();
    Boolean st = call.getBoolean("showTitle", true);
    final boolean showTitle = st == null || st.booleanValue();
    Boolean au = call.getBoolean("audio", false);
    final boolean audio = au != null && au.booleanValue();
    final List<String> folders = new ArrayList<String>();
    JSONArray fa = call.getArray("folders");
    if (fa != null) {
      for (int i = 0; i < fa.length(); i++) {
        String f = fa.optString(i, "");
        if (f.length() > 0) folders.add(f);
      }
    }
    inst = this;
    try {
      android.content.SharedPreferences sp = getContext().getSharedPreferences("vlong", Context.MODE_PRIVATE);
      if (Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("asked", false) && getContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        sp.edit().putBoolean("asked", true).apply();
        getActivity().requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 4712);
      }
    } catch (Throwable t) {
    }
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          closeNow();
          Activity act = getActivity();
          ViewGroup parent = (ViewGroup) act.getWindow().getDecorView();
          view = new LongView(act, qIds, qPaths, qTitles, qFolders, qRes, start, rem, showTitle, folders, new LongView.Host() {
            @Override
            public void emit(String name, JSObject o) {
              notifyListeners(name, o);
            }

            @Override
            public void close() {
              closeNow();
            }

            @Override
            public void bg(String cmd, String t, boolean playing, long pos, long dur, String path, float speed) {
              bgCmd(cmd, t, playing, pos, dur, path, speed);
            }

            @Override
            public void min(boolean on) {
              if (backCb != null) backCb.setEnabled(!on);
            }
          });
          parent.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
          view.requestApplyInsets();
          backCb = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
              if (view != null && view.backStep()) return;
              if (view != null && view.audioOnlyOn()) {
                view.minimize();
                return;
              }
              closeNow();
            }
          };
          getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), backCb);
          if (audio) view.startAudio();
          call.resolve();
        } catch (Throwable t) {
          closeNow();
          call.reject("open failed: " + t);
        }
      }
    });
  }

  /* the small bar of the app (toggle play, show the video again, close) */
  @PluginMethod
  public void command(final PluginCall call) {
    final String c = call.getString("cmd", "");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (view != null) {
          if ("close".equals(c)) closeNow();
          else if ("restore".equals(c)) view.restore();
          else view.remoteCmd(c, 0L);
        }
        call.resolve();
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

  private void closeNow() {
    if (backCb != null) {
      backCb.remove();
      backCb = null;
    }
    if (view != null) {
      LongView v = view;
      view = null;
      v.release();
      bgCmd("stop", "", false, 0L, 0L, "", 1f);
      ViewGroup par = (ViewGroup) v.getParent();
      if (par != null) par.removeView(v);
      freeBars();
      JSObject o = new JSObject();
      o.put("id", v.vid());
      o.put("pos", v.lastPos());
      o.put("dur", v.lastDur());
      o.put("watched", v.watched());
      notifyListeners("closed", o);
    }
  }

  @Override
  protected void handleOnPause() {
    super.handleOnPause();
    if (view != null) view.onLeave();
  }

  @Override
  protected void handleOnResume() {
    super.handleOnResume();
    if (view != null) view.onBack();
    freeBars();
    try {
      new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        new Runnable() {
          @Override
          public void run() {
            freeBars();
          }
        },
        350
      );
    } catch (Throwable t) {
    }
  }

  /* no player on the screen (or only the small bar): the top bar and the 3 buttons are always visible */
  private void freeBars() {
    try {
      if (view != null && !view.isMin()) {
        view.bars2();
        return;
      }
      View d = getActivity().getWindow().getDecorView();
      int f = d.getSystemUiVisibility();
      int g = f & ~(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | 2048);
      if (g != f) d.setSystemUiVisibility(g);
    } catch (Throwable t) {
    }
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    if (view != null) {
      view.release();
      view = null;
    }
  }


  static class LongView extends FrameLayout {

    interface Host {
      void emit(String name, JSObject o);

      void close();

      void bg(String cmd, String title, boolean playing, long pos, long dur, String path, float speed);

      void min(boolean on);
    }

    private static final int YEL = 0xFFFFB52E;
    private static final int GRAY = 0xFF9AA0AE;

    private final Activity act;
    private final Host host;
    private final List<String> qIds;
    private final List<String> qPaths;
    private final List<String> qTitles;
    private final List<String> qFold;
    private final List<Long> qRes;
    private int qi;
    private String path;
    private String id;
    private String title;
    private final Handler hd = new Handler(Looper.getMainLooper());
    private final float den;
    private ExoPlayer pl;
    private TextureView tv;
    private TextView subTv;
    private TextView titleTv;
    private TextView timeL;
    private TextView timeR;
    private TextView hud;
    private SeekV seek;
    private IconV backBtn;
    private IconV tracksBtn;
    private IconV rotBtn;
    private IconV playBtn;
    private IconV fitBtn;
    private IconV moreBtn;
    private FrameLayout ctl;
    private FrameLayout sheetRoot;
    private LinearLayout sheetList;
    private LinearLayout bottomCol;
    private LinearLayout sleepChip;
    private TextView sleepChipTv;
    private View topShade;
    private View botShade;
    private FrameLayout aoBox;
    private boolean vOff = false;
    private ScrollView sheetSv;
    private int sheetPx = 0;
    private final List<String> folders;
    private String folder;
    private boolean titleOn;
    private boolean bgOn = false;
    private boolean audioOnly = false;
    private long watchedMs = 0L;
    private long lastTick = 0L;
    private long abA = -1L;
    private long abB = -1L;
    private boolean abLoop = false;
    private long sleepEnd = 0L;
    private long sleepTotal = 0L;
    private boolean sleepWait = false;
    private boolean sleepReset = false;
    private boolean sleepPending = false;
    private Equalizer eq = null;
    private boolean eqOn = true;
    private boolean errShown = false;

    private int W = 0;
    private int H = 0;
    private int insT = 0;
    private int insB = 0;
    private int insL = 0;
    private int insR = 0;
    private int vw = 0;
    private int vh = 0;
    private int fitMode = 0;
    private float speed = 1f;
    private boolean ctlOn = true;
    private boolean locked = false;
    private boolean rem;
    private boolean userPaused = false;
    private boolean dead = false;
    private boolean ended = false;
    private boolean keepOnBack = false;
    private boolean resumed = false;
    private long resumeAt;
    private long lastPos = 0L;
    private long lastDur = 0L;
    private long lastEmit = 0L;
    private final int origOrient;
    private final int origUi;
    private float origBright;
    private int origStatus = 0;
    private int origNav = 0;
    private boolean fitAuto = true;
    private boolean minimized = false;
    private boolean loopAll = true;
    private long lastState = 0L;

    /* touch state */
    private float downX = 0f;
    private float downY = 0f;
    private int mode = 0;
    private float gBase = 0f;
    private long seekBase = 0L;
    private long seekTarget = 0L;
    private long lastTapT = 0L;
    private long downT = 0L;

    LongView(Activity a, List<String> ids, List<String> paths, List<String> titles, List<String> fold, List<Long> res, int start, boolean rem, boolean showTitle, List<String> folders, Host host) {
      super(a);
      this.act = a;
      this.host = host;
      this.qIds = ids;
      this.qPaths = paths;
      this.qTitles = titles;
      this.qFold = fold;
      this.qRes = res;
      this.qi = start;
      this.path = paths.get(start);
      this.id = ids.get(start);
      this.title = titles.get(start);
      this.folder = fold.get(start);
      this.resumeAt = res.get(start).longValue();
      this.titleOn = showTitle;
      this.rem = rem;
      this.folders = folders;
      this.den = a.getResources().getDisplayMetrics().density;
      this.origOrient = a.getRequestedOrientation();
      this.origUi = a.getWindow().getDecorView().getSystemUiVisibility();
      this.origBright = a.getWindow().getAttributes().screenBrightness;
      try {
        origStatus = a.getWindow().getStatusBarColor();
        origNav = a.getWindow().getNavigationBarColor();
        a.getWindow().setStatusBarColor(Color.TRANSPARENT);
        a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
          a.getWindow().setNavigationBarContrastEnforced(false);
          a.getWindow().setStatusBarContrastEnforced(false);
        }
      } catch (Throwable t) {
      }
      setBackgroundColor(Color.BLACK);
      setClickable(true);
      setKeepScreenOn(true);
      build(a);
      startPlayer(a);
      try {
        /* opens in the way the phone is held now, then follows the tilt */
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
      } catch (Throwable t) {
      }
      bars(true);
      hd.post(ticker);
      scheduleHide();
    }

    String vid() {
      return id;
    }

    long lastPos() {
      return lastPos;
    }

    long lastDur() {
      return lastDur;
    }

    long watched() {
      return watchedMs;
    }

    private int dp(float v) {
      return Math.round(v * den);
    }

    /* the phone's top bar (time, battery) and the 3 buttons show with the controls, and hide with them.
       They never change while the video is only a small bar or closed (that was the reason they went away in the app). */
    private void bars(boolean show) {
      if (minimized || dead) return;
      try {
        int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!show) f = f | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        act.getWindow().getDecorView().setSystemUiVisibility(f);
      } catch (Throwable t) {
      }
    }

    /* ---------- screen parts ---------- */

    private void build(Context c) {
      tv = new TextureView(c);
      addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

      subTv = new TextView(c);
      subTv.setBackgroundColor(Color.TRANSPARENT);
      subTv.setTextColor(Color.WHITE);
      subTv.setTextSize(17f);
      subTv.setGravity(Gravity.CENTER);
      subTv.setShadowLayer(5f, 0f, 0f, Color.BLACK);
      addView(subTv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

      /* the picture of a song (same as the mp3 screen) while only the sound plays */
      aoBox = new FrameLayout(c);
      GradientDrawable ag = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF2F3B9E, 0xFF65207A });
      ag.setCornerRadius(dp(26));
      aoBox.setBackground(ag);
      IconV note = new IconV(c, "note", 96f, false);
      aoBox.addView(note, new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
      aoBox.setVisibility(View.GONE);
      addView(aoBox, new FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER));

      ctl = new FrameLayout(c);
      ctl.setBackgroundColor(Color.TRANSPARENT);
      addView(ctl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

      topShade = new View(c);
      topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0xAA000000, 0x00000000 }));
      ctl.addView(topShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110), Gravity.TOP));
      botShade = new View(c);
      botShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00000000, 0xCC000000 }));
      ctl.addView(botShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190), Gravity.BOTTOM));

      backBtn = new IconV(c, "back", 26f, false);
      backBtn.setOnClickListener(v -> {
        if (audioOnly) minimize();
        else host.close();
      });
      ctl.addView(backBtn, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));

      titleTv = new TextView(c);
      titleTv.setBackgroundColor(Color.TRANSPARENT);
      titleTv.setText(title);
      titleTv.setTextColor(Color.WHITE);
      titleTv.setTextSize(15f);
      titleTv.setSingleLine(true);
      titleTv.setEllipsize(TextUtils.TruncateAt.END);
      titleTv.setGravity(Gravity.CENTER_VERTICAL);
      titleTv.setShadowLayer(4f, 0f, 1f, Color.BLACK);
      titleTv.setVisibility(titleOn ? View.VISIBLE : View.INVISIBLE);
      ctl.addView(titleTv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP));

      bottomCol = new LinearLayout(c);
      bottomCol.setOrientation(LinearLayout.VERTICAL);
      bottomCol.setBackgroundColor(Color.TRANSPARENT);
      ctl.addView(bottomCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

      /* times sit above both ends of the bar */
      FrameLayout trow = new FrameLayout(c);
      trow.setBackgroundColor(Color.TRANSPARENT);
      timeL = timeView(c, "0:00");
      timeR = timeView(c, "0:00");
      timeR.setOnClickListener(v -> {
        rem = !rem;
        JSObject o = new JSObject();
        o.put("rem", rem);
        host.emit("rem", o);
        refreshTimes();
      });
      trow.addView(timeL, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26), Gravity.START | Gravity.CENTER_VERTICAL));
      trow.addView(timeR, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26), Gravity.END | Gravity.CENTER_VERTICAL));
      bottomCol.addView(trow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
      seek = new SeekV(c);
      bottomCol.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

      FrameLayout brow = new FrameLayout(c);
      brow.setBackgroundColor(Color.TRANSPARENT);
      tracksBtn = new IconV(c, "tracks", 26f, false);
      rotBtn = new IconV(c, "rotate", 26f, false);
      playBtn = new IconV(c, "pause", 30f, true);
      fitBtn = new IconV(c, "fit", 26f, false);
      moreBtn = new IconV(c, "more", 26f, false);
      tracksBtn.setOnClickListener(v -> openTracks());
      rotBtn.setOnClickListener(v -> toggleLock());
      playBtn.setOnClickListener(v -> togglePlay());
      fitBtn.setOnClickListener(v -> nextFit());
      moreBtn.setOnClickListener(v -> openMore());
      LinearLayout left = new LinearLayout(c);
      left.setOrientation(LinearLayout.HORIZONTAL);
      left.setGravity(Gravity.CENTER_VERTICAL);
      left.setBackgroundColor(Color.TRANSPARENT);
      left.addView(tracksBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
      left.addView(rotBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
      LinearLayout right = new LinearLayout(c);
      right.setOrientation(LinearLayout.HORIZONTAL);
      right.setGravity(Gravity.CENTER_VERTICAL);
      right.setBackgroundColor(Color.TRANSPARENT);
      right.addView(fitBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
      right.addView(moreBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
      brow.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64), Gravity.START | Gravity.CENTER_VERTICAL));
      brow.addView(playBtn, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
      brow.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64), Gravity.END | Gravity.CENTER_VERTICAL));
      bottomCol.addView(brow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

      /* the small timer pill (shows the time the video will stop) */
      sleepChip = new LinearLayout(c);
      sleepChip.setOrientation(LinearLayout.HORIZONTAL);
      sleepChip.setGravity(Gravity.CENTER_VERTICAL);
      GradientDrawable cg = new GradientDrawable();
      cg.setColor(0xB3000000);
      cg.setCornerRadius(dp(14));
      sleepChip.setBackground(cg);
      sleepChip.setPadding(dp(8), dp(3), dp(10), dp(3));
      IconV ci = new IconV(c, "sleep", 16f, false);
      sleepChip.addView(ci, new LinearLayout.LayoutParams(dp(18), dp(18)));
      sleepChipTv = new TextView(c);
      sleepChipTv.setBackgroundColor(Color.TRANSPARENT);
      sleepChipTv.setTextColor(Color.WHITE);
      sleepChipTv.setTextSize(12f);
      sleepChipTv.setPadding(dp(5), 0, 0, 0);
      sleepChip.addView(sleepChipTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      sleepChip.setVisibility(View.GONE);
      ctl.addView(sleepChip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

      hud = new TextView(c);
      hud.setBackgroundColor(Color.TRANSPARENT);
      hud.setTextColor(Color.WHITE);
      hud.setTextSize(22f);
      hud.setGravity(Gravity.CENTER);
      hud.setShadowLayer(6f, 0f, 0f, Color.BLACK);
      hud.setVisibility(View.GONE);
      addView(hud, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
      ui();
    }

    private TextView timeView(Context c, String t) {
      TextView x = new TextView(c);
      x.setBackgroundColor(Color.TRANSPARENT);
      x.setText(t);
      x.setTextColor(Color.WHITE);
      x.setTextSize(13f);
      x.setGravity(Gravity.CENTER);
      return x;
    }

    private void ui() {
      try {
        FrameLayout.LayoutParams a = (FrameLayout.LayoutParams) backBtn.getLayoutParams();
        a.setMargins(dp(6) + insL, dp(2) + insT, 0, 0);
        backBtn.setLayoutParams(a);
        FrameLayout.LayoutParams b = (FrameLayout.LayoutParams) titleTv.getLayoutParams();
        b.setMargins(dp(58) + insL, dp(2) + insT, dp(16) + insR, 0);
        titleTv.setLayoutParams(b);
        FrameLayout.LayoutParams d = (FrameLayout.LayoutParams) bottomCol.getLayoutParams();
        d.setMargins(dp(14) + insL, 0, dp(14) + insR, dp(2) + insB);
        bottomCol.setLayoutParams(d);
        FrameLayout.LayoutParams s = (FrameLayout.LayoutParams) subTv.getLayoutParams();
        s.setMargins(dp(40) + insL, 0, dp(40) + insR, dp(30) + insB);
        subTv.setLayoutParams(s);
        FrameLayout.LayoutParams k = (FrameLayout.LayoutParams) sleepChip.getLayoutParams();
        k.setMargins(dp(14) + insL, dp(52) + insT, 0, 0);
        sleepChip.setLayoutParams(k);
      } catch (Throwable t) {
      }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets ins) {
      try {
        insT = ins.getSystemWindowInsetTop();
        insB = ins.getSystemWindowInsetBottom();
        insL = ins.getSystemWindowInsetLeft();
        insR = ins.getSystemWindowInsetRight();
        ui();
      } catch (Throwable t) {
      }
      return super.onApplyWindowInsets(ins);
    }

    /* reads the bar sizes straight from the window, so it works wherever the view sits */
    private void readInsets() {
      try {
        WindowInsets r = getRootWindowInsets();
        if (r == null) return;
        insT = r.getSystemWindowInsetTop();
        insB = r.getSystemWindowInsetBottom();
        insL = r.getSystemWindowInsetLeft();
        insR = r.getSystemWindowInsetRight();
        ui();
      } catch (Throwable t) {
      }
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      readInsets();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
      super.onSizeChanged(w, h, ow, oh);
      W = w;
      H = h;
      readInsets();
      fit();
      try {
        int a = Math.round(Math.min(w, h) * 0.56f);
        ViewGroup.LayoutParams alp = aoBox.getLayoutParams();
        if (alp != null && alp.width != a) {
          alp.width = a;
          alp.height = a;
          aoBox.setLayoutParams(alp);
        }
      } catch (Throwable t) {
      }
      if (!minimized && !dead) bars(ctlOn);
      if (inPip()) hideCtl();
    }

    /* any touch restarts the sleep timer when "Reset on any interaction" is on */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
      if (sleepReset && sleepEnd > 0L && e.getActionMasked() == MotionEvent.ACTION_DOWN) sleepEnd = SystemClock.uptimeMillis() + sleepTotal;
      return super.dispatchTouchEvent(e);
    }

    /* ---------- player ---------- */

    private void startPlayer(Context c) {
      DefaultRenderersFactory rf = new DefaultRenderersFactory(c).setEnableDecoderFallback(true);
      DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(15000, 50000, 250, 500).build();
      pl = new ExoPlayer.Builder(c, rf).setLoadControl(lc).build();
      pl.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
      pl.setVideoTextureView(tv);
      pl.addListener(new Player.Listener() {
        @Override
        public void onVideoSizeChanged(VideoSize s) {
          float par = s.pixelWidthHeightRatio > 0f ? s.pixelWidthHeightRatio : 1f;
          vw = Math.round(s.width * par);
          vh = s.height;
          fit();
        }

        @Override
        public void onIsPlayingChanged(boolean p) {
          if (p) scheduleHide();
          else if (pl != null && !pl.getPlayWhenReady()) showCtl();
        }

        @Override
        public void onPlayWhenReadyChanged(boolean w, int reason) {
          playBtn.setKind(w ? "pause" : "play");
          bgUpdate();
        }

        @Override
        public void onPlaybackStateChanged(int st) {
          if (st == Player.STATE_READY && !resumed) {
            resumed = true;
            long d = pl.getDuration();
            if (resumeAt > 3000 && d > 0 && resumeAt < d - 5000) pl.seekTo(resumeAt);
            bgUpdate();
          }
          if (st == Player.STATE_ENDED && (abLoop || loopAll)) {
            pl.seekTo(abLoop ? abA : 0L);
            pl.setPlayWhenReady(true);
            return;
          }
          if (st == Player.STATE_ENDED) {
            ended = true;
            playBtn.setKind("play");
            showCtl();
            emitProgress(true);
            if (sleepPending) {
              sleepPending = false;
              sleepEnd = 0L;
              updateChip();
            }
          }
        }

        @Override
        public void onCues(CueGroup g) {
          StringBuilder sb = new StringBuilder();
          for (int i = 0; i < g.cues.size(); i++) {
            Cue q = g.cues.get(i);
            if (q.text != null && q.text.length() > 0) {
              if (sb.length() > 0) sb.append("\n");
              sb.append(q.text.toString());
            }
          }
          subTv.setText(sb.toString());
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException e) {
          JSObject o = new JSObject();
          o.put("id", id);
          o.put("error", String.valueOf(e.getMessage()));
          host.emit("error", o);
          askOtherPlayer();
        }
      });
      pl.setMediaItem(buildItem());
      pl.prepare();
      pl.setPlayWhenReady(true);
    }

    /* some files cannot be opened by this player: offer another player installed on the phone */
    private void askOtherPlayer() {
      if (errShown || dead) return;
      errShown = true;
      try {
        new AlertDialog.Builder(act).setTitle("Cannot play this video").setMessage("V Player cannot open this file format. You can open it with another player on your phone.").setPositiveButton("Open with another player", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int w) {
            openOther();
          }
        }).setNegativeButton("Close", null).show();
      } catch (Throwable t) {
        Toast.makeText(act, "This video could not be played", Toast.LENGTH_LONG).show();
      }
    }

    private Uri mediaUri() {
      try {
        String digits = id.replaceAll("[^0-9]", "");
        if (digits.length() > 0) {
          boolean au = id.startsWith("audio");
          Uri base = au ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
          return ContentUris.withAppendedId(base, Long.parseLong(digits));
        }
      } catch (Throwable t) {
      }
      return null;
    }

    private void openOther() {
      try {
        Uri u = mediaUri();
        if (u == null) {
          Toast.makeText(act, "Could not open", Toast.LENGTH_SHORT).show();
          return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        act.startActivity(Intent.createChooser(i, "Open with"));
      } catch (Throwable t) {
        Toast.makeText(act, "No other player found", Toast.LENGTH_SHORT).show();
      }
    }

    /* picks up a subtitle file with the same name (.srt .vtt .ass .ssa) next to the video */
    private MediaItem buildItem() {
      File f = new File(path);
      MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.fromFile(f));
      try {
        String nm = f.getName();
        int dot = nm.lastIndexOf('.');
        String base = dot > 0 ? nm.substring(0, dot) : nm;
        File dir = f.getParentFile();
        String[] ext = { "srt", "vtt", "ass", "ssa" };
        String[] mime = { "application/x-subrip", "text/vtt", "text/x-ssa", "text/x-ssa" };
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<MediaItem.SubtitleConfiguration>();
        for (int i = 0; i < ext.length && dir != null; i++) {
          File s = new File(dir, base + "." + ext[i]);
          if (s.exists() && s.length() > 0) {
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s)).setMimeType(mime[i]).setLanguage("und").setLabel("Subtitle file (" + ext[i] + ")").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
          }
        }
        if (subs.size() > 0) mb.setSubtitleConfigurations(subs);
      } catch (Throwable t) {
      }
      return mb.build();
    }

    private boolean inPip() {
      try {
        return Build.VERSION.SDK_INT >= 24 && act.isInPictureInPictureMode();
      } catch (Throwable t) {
        return false;
      }
    }

    private void bgUpdate() {
      if (bgOn && pl != null) host.bg("update", title, pl.getPlayWhenReady(), Math.max(0L, pl.getCurrentPosition()), Math.max(0L, pl.getDuration()), path, speed);
    }

    /* the app goes to the background: the sound keeps playing and a notification with buttons is shown */
    void onLeave() {
      if (pl == null || dead) return;
      emitProgress(false);
      if (inPip()) return;
      if (audioOnly) videoTrackOff(true);
      bgOn = true;
      host.bg("start", title, pl.getPlayWhenReady(), Math.max(0L, pl.getCurrentPosition()), Math.max(0L, pl.getDuration()), path, speed);
    }

    /* the app is back: the notification goes away and the picture returns */
    private void videoTrackOff(boolean off) {
      if (pl == null || vOff == off) return;
      vOff = off;
      try {
        pl.setTrackSelectionParameters(pl.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off).build());
      } catch (Throwable t) {
      }
    }

    void onBack() {
      videoTrackOff(false);
      boolean was = bgOn;
      if (bgOn) {
        bgOn = false;
        host.bg("stop", "", false, 0L, 0L, "", 1f);
      }
      /* coming back to the app while it was playing: it goes on as a small bar at the bottom */
      if (was && !minimized && !keepOnBack && pl != null && pl.getPlayWhenReady()) {
        minimize();
        return;
      }
      keepOnBack = false;
      if (!minimized && ctlOn) showCtl();
    }

    private int shown(int f) {
      return f & ~(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | 2048);
    }

    boolean audioOnlyOn() {
      return audioOnly;
    }

    boolean isMin() {
      return minimized;
    }

    /* "Play as audio" from a card: sound only, and the app shows the small bar like a song */
    void startAudio() {
      setAudioOnly(true);
      minimize();
    }

    /* the phone's top bar and the 3 buttons must come back whenever no video is on the screen */
    void bars2() {
      bars(ctlOn);
    }

    /* the picture goes away, the sound goes on and the app shows a small bar (like a song) */
    void minimize() {
      if (minimized || dead) return;
      minimized = true;
      hd.removeCallbacks(hideRun);
      closeSheet();
      setVisibility(View.GONE);
      try {
        act.setRequestedOrientation(origOrient);
        act.getWindow().getDecorView().setSystemUiVisibility(shown(origUi));
        act.getWindow().setStatusBarColor(origStatus);
        act.getWindow().setNavigationBarColor(origNav);
      } catch (Throwable t) {
      }
      host.min(true);
      emitState("minimized");
    }

    /* tap on the small bar: the video comes back */
    void restore() {
      if (!minimized || dead) return;
      minimized = false;
      setVisibility(View.VISIBLE);
      try {
        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        act.getWindow().setStatusBarColor(Color.TRANSPARENT);
        act.getWindow().setNavigationBarColor(Color.TRANSPARENT);
      } catch (Throwable t) {
      }
      host.min(false);
      showCtl();
      bars(true);
      requestLayout();
      emitState("restored");
    }

    private void emitState(String name) {
      try {
        if (pl == null) return;
        JSObject o = new JSObject();
        o.put("id", id);
        o.put("title", title);
        o.put("playing", pl.getPlayWhenReady());
        o.put("pos", Math.max(0L, pl.getCurrentPosition()));
        o.put("dur", Math.max(0L, pl.getDuration()));
        host.emit(name, o);
      } catch (Throwable t) {
      }
    }

    void toggleExternal() {
      if (pl == null) return;
      boolean on = pl.getPlayWhenReady();
      userPaused = on;
      pl.setPlayWhenReady(!on);
      if (ended && !on) {
        ended = false;
        pl.seekTo(0L);
      }
    }

    /* buttons of the phone notification */
    void remoteCmd(String a, long arg) {
      if (pl == null || dead) return;
      if ("toggle".equals(a)) {
        toggleExternal();
      } else if ("play".equals(a)) {
        if (ended) {
          ended = false;
          pl.seekTo(0L);
        }
        pl.setPlayWhenReady(true);
      } else if ("pause".equals(a)) {
        pl.setPlayWhenReady(false);
      } else if ("next".equals(a)) {
        switchTo(qi + 1);
      } else if ("prev".equals(a)) {
        if (pl.getCurrentPosition() > 4000L) pl.seekTo(0L);
        else switchTo(qi - 1);
      } else if ("seek".equals(a)) {
        pl.seekTo(Math.max(0L, arg));
        ended = false;
      } else if ("fwd".equals(a)) {
        pl.seekTo(Math.min(Math.max(0L, pl.getDuration()), pl.getCurrentPosition() + 10000L));
      } else if ("rew".equals(a)) {
        pl.seekTo(Math.max(0L, pl.getCurrentPosition() - 10000L));
      }
      bgUpdate();
    }

    /* goes to another video of the same list */
    private void switchTo(int ni) {
      if (pl == null || ni < 0 || ni >= qIds.size() || ni == qi) {
        if (ni != qi) flash(ni < 0 ? "This is the first video" : "This is the last video");
        return;
      }
      emitProgress(false);
      qi = ni;
      path = qPaths.get(ni);
      id = qIds.get(ni);
      title = qTitles.get(ni);
      folder = qFold.get(ni);
      resumeAt = qRes.get(ni).longValue();
      resumed = false;
      ended = false;
      errShown = false;
      watchedMs = 0L;
      lastPos = 0L;
      lastDur = 0L;
      vw = 0;
      vh = 0;
      abA = -1L;
      abB = -1L;
      abLoop = false;
      seek.setMarks(-1f, -1f);
      subTv.setText("");
      titleTv.setText(title);
      JSObject o = new JSObject();
      o.put("id", id);
      host.emit("switched", o);
      pl.setMediaItem(buildItem());
      pl.prepare();
      pl.setPlayWhenReady(true);
      playBtn.setKind("pause");
      flash(title);
      bgUpdate();
    }

    void release() {
      if (dead) return;
      dead = true;
      try {
        if (pl != null) {
          lastPos = pl.getCurrentPosition();
          long d = pl.getDuration();
          if (d > 0) lastDur = d;
          if (ended) lastPos = 0L;
        }
      } catch (Throwable t) {
      }
      hd.removeCallbacksAndMessages(null);
      try {
        if (eq != null) eq.release();
      } catch (Throwable t) {
      }
      eq = null;
      try {
        if (pl != null) {
          pl.setVideoTextureView(null);
          pl.release();
        }
      } catch (Throwable t) {
      }
      pl = null;
      try {
        act.setRequestedOrientation(origOrient);
        act.getWindow().getDecorView().setSystemUiVisibility(shown(origUi));
        act.getWindow().setStatusBarColor(origStatus);
        act.getWindow().setNavigationBarColor(origNav);
        WindowManager.LayoutParams lp = act.getWindow().getAttributes();
        lp.screenBrightness = origBright;
        act.getWindow().setAttributes(lp);
      } catch (Throwable t) {
      }
    }

    private void emitProgress(boolean fin) {
      try {
        if (pl == null) return;
        long d = pl.getDuration();
        long p = pl.getCurrentPosition();
        if (d > 0) lastDur = d;
        lastPos = fin ? 0L : p;
        JSObject o = new JSObject();
        o.put("id", id);
        o.put("pos", lastPos);
        o.put("dur", lastDur);
        o.put("watched", watchedMs);
        host.emit("progress", o);
      } catch (Throwable t) {
      }
    }

    /* ---------- picture size ---------- */

    private void fit() {
      if (W <= 0 || H <= 0 || tv == null) return;
      int tw = W;
      int th = H;
      if (vw > 0 && vh > 0) {
        float ar = vw / (float) vh;
        int fm = fitMode;
        if (fitAuto) fm = (W > H) ? 1 : 0;
        if (fm == 0) {
          if (W / (float) H > ar) tw = Math.round(H * ar);
          else th = Math.round(W / ar);
        } else if (fm == 1) {
          if (W / (float) H > ar) th = Math.round(W / ar);
          else tw = Math.round(H * ar);
        } else if (fm == 3 || fm == 4) {
          float r = fm == 3 ? 16f / 9f : 4f / 3f;
          if (W / (float) H > r) tw = Math.round(H * r);
          else th = Math.round(W / r);
        } else if (fm == 5) {
          tw = vw;
          th = vh;
          float k = Math.min(1f, Math.min(W / (float) vw, H / (float) vh));
          tw = Math.round(vw * k);
          th = Math.round(vh * k);
        }
      }
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(tw, th, Gravity.CENTER);
      tv.setLayoutParams(lp);
    }

    /* ---------- controls show / hide ---------- */

    private final Runnable hideRun = new Runnable() {
      @Override
      public void run() {
        if (sheetRoot == null && pl != null && pl.isPlaying()) hideCtl();
      }
    };

    private final Runnable ticker = new Runnable() {
      @Override
      public void run() {
        if (dead || pl == null) return;
        try {
          long d = pl.getDuration();
          long p = pl.getCurrentPosition();
          if (d > 0) lastDur = d;
          lastPos = p;
          if (!seek.on) seek.setFrac(d > 0 ? p / (float) d : 0f, pl.getBufferedPosition() / (float) Math.max(1L, d));
          if (ctlOn) refreshTimes();
          long now = SystemClock.uptimeMillis();
          if (pl.isPlaying()) {
            long dt = now - lastTick;
            if (dt > 0 && dt < 2000) watchedMs += dt;
          }
          lastTick = now;
          if (abLoop && abB > abA && p >= abB) pl.seekTo(abA);
          if (d > 0) seek.setMarks(abA >= 0 ? abA / (float) d : -1f, abB >= 0 ? abB / (float) d : -1f);
          if (sleepEnd > 0 && now >= sleepEnd) {
            if (sleepWait && pl.isPlaying() && !ended) {
              sleepPending = true;
              sleepEnd = 0L;
            } else {
              sleepEnd = 0L;
              pl.setPlayWhenReady(false);
              userPaused = true;
              flash("Sleep timer: stopped");
              bgUpdate();
            }
          }
          updateChip();
          if (minimized && now - lastState > 1000) {
            lastState = now;
            emitState("state");
          }
          if (now - lastEmit > 5000 && pl.isPlaying()) {
            lastEmit = now;
            emitProgress(false);
          }
        } catch (Throwable t) {
        }
        hd.postDelayed(this, abLoop ? 100 : (ctlOn ? 250 : 1000));
      }
    };

    private String chipText = "";

    private void updateChip() {
      String t = "";
      if (sleepEnd > 0L) {
        long left = Math.max(0L, sleepEnd - SystemClock.uptimeMillis());
        t = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(System.currentTimeMillis() + left)).toLowerCase(Locale.US);
      } else if (sleepPending) {
        t = "at end";
      }
      if (!t.equals(chipText)) {
        chipText = t;
        sleepChipTv.setText(t);
        sleepChip.setVisibility(t.length() > 0 ? View.VISIBLE : View.GONE);
      }
    }

    private void refreshTimes() {
      if (pl == null) return;
      long d = Math.max(0L, pl.getDuration());
      long p = seek.on ? (long) (seek.frac * d) : pl.getCurrentPosition();
      timeL.setText(fmt(p));
      timeR.setText(rem ? "-" + fmt(Math.max(0L, d - p)) : fmt(d));
    }

    private String fmt(long ms) {
      long s = Math.max(0L, ms) / 1000L;
      long h = s / 3600L;
      long m = (s % 3600L) / 60L;
      long x = s % 60L;
      if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, x);
      return String.format(Locale.US, "%d:%02d", m, x);
    }

    private void scheduleHide() {
      hd.removeCallbacks(hideRun);
      hd.postDelayed(hideRun, 3500);
    }

    private void showCtl() {
      boolean was = ctlOn && ctl.getVisibility() == View.VISIBLE;
      ctlOn = true;
      ctl.setVisibility(View.VISIBLE);
      ctl.setAlpha(1f);
      if (!was) bars(true);
      refreshTimes();
      scheduleHide();
    }

    private void hideCtl() {
      if (minimized || dead) return;
      ctlOn = false;
      ctl.setVisibility(View.GONE);
      bars(false);
    }

    private void togglePlay() {
      if (pl == null) return;
      if (ended) {
        ended = false;
        pl.seekTo(0L);
        pl.setPlayWhenReady(true);
        userPaused = false;
        return;
      }
      boolean on = pl.getPlayWhenReady();
      userPaused = on;
      pl.setPlayWhenReady(!on);
      showCtl();
    }

    /* ---------- rotate lock, screen fit ---------- */

    private void toggleLock() {
      locked = !locked;
      try {
        act.setRequestedOrientation(locked ? ActivityInfo.SCREEN_ORIENTATION_LOCKED : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
      } catch (Throwable t) {
      }
      rotBtn.setKind(locked ? "lock" : "rotate");
      rotBtn.setLook(locked ? YEL : Color.WHITE, false);
      flash(locked ? "Screen locked" : "Auto rotate");
      showCtl();
    }

    private final String[] fitNames = { "Best fit", "Fill", "Stretch", "16:9", "4:3", "Original" };

    private void nextFit() {
      if (fitAuto) {
        fitAuto = false;
        fitMode = (W > H) ? 1 : 0;
      }
      fitMode = (fitMode + 1) % fitNames.length;
      fit();
      flash(fitNames[fitMode]);
      showCtl();
    }

    /* ---------- little text in the middle ---------- */

    private final Runnable hudOff = new Runnable() {
      @Override
      public void run() {
        hud.setVisibility(View.GONE);
      }
    };

    private void flash(String t) {
      hud.setText(t);
      hud.setVisibility(View.VISIBLE);
      hd.removeCallbacks(hudOff);
      hd.postDelayed(hudOff, 900);
    }

    /* ---------- menus ---------- */

    private void sheetOpen(String ttl) {
      closeSheet();
      Context c = getContext();
      sheetRoot = new FrameLayout(c);
      sheetRoot.setBackgroundColor(0x88000000);
      sheetRoot.setClickable(true);
      sheetRoot.setOnClickListener(v -> closeSheet());
      LinearLayout card = new LinearLayout(c);
      card.setOrientation(LinearLayout.VERTICAL);
      GradientDrawable g = new GradientDrawable();
      g.setColor(0xFA1C1C1E);
      g.setCornerRadius(dp(14));
      card.setBackground(g);
      card.setClickable(true);
      TextView t = new TextView(c);
      t.setBackgroundColor(Color.TRANSPARENT);
      t.setText(ttl);
      t.setTextColor(Color.WHITE);
      t.setTextSize(16f);
      t.setPadding(dp(20), dp(14), dp(20), dp(8));
      card.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      ScrollView sv = new ScrollView(c);
      sv.setBackgroundColor(Color.TRANSPARENT);
      sheetList = new LinearLayout(c);
      sheetList.setOrientation(LinearLayout.VERTICAL);
      sheetList.setBackgroundColor(Color.TRANSPARENT);
      sv.addView(sheetList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      sheetSv = sv;
      sheetPx = 0;
      card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
      int cw = Math.min(dp(340), Math.round(W * 0.8f));
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
      lp.setMargins(0, dp(12) + insT, 0, dp(12) + insB);
      sheetRoot.addView(card, lp);
      addView(sheetRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      hd.removeCallbacks(hideRun);
    }

    private void sheetHead(String t) {
      TextView x = new TextView(getContext());
      x.setBackgroundColor(Color.TRANSPARENT);
      x.setText(t);
      x.setTextColor(0xFF8A93A8);
      x.setTextSize(12f);
      x.setPadding(dp(20), dp(10), dp(20), dp(4));
      sheetList.addView(x, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      sheetGrow(dp(34));
    }

    private void sheetGrow(int px) {
      sheetPx += px;
      int avail = Math.max(dp(120), H - dp(24) - insT - insB - dp(52));
      if (sheetSv != null) sheetSv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(sheetPx, avail)));
    }

    private void sheetRow(String label, boolean on, final Runnable act1) {
      TextView x = new TextView(getContext());
      x.setBackgroundColor(Color.TRANSPARENT);
      x.setText((on ? "✓  " : "     ") + label);
      x.setTextColor(on ? YEL : Color.WHITE);
      x.setTextSize(15f);
      x.setPadding(dp(20), 0, dp(20), 0);
      x.setGravity(Gravity.CENTER_VERTICAL);
      x.setSingleLine(true);
      x.setEllipsize(TextUtils.TruncateAt.END);
      x.setOnClickListener(v -> {
        closeSheet();
        act1.run();
      });
      sheetList.addView(x, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
      sheetGrow(dp(46));
    }

    private void sheetItem(String label, final Runnable r) {
      TextView x = new TextView(getContext());
      x.setBackgroundColor(Color.TRANSPARENT);
      x.setText(label);
      x.setTextColor(Color.WHITE);
      x.setTextSize(15f);
      x.setPadding(dp(20), 0, dp(20), 0);
      x.setGravity(Gravity.CENTER_VERTICAL);
      x.setSingleLine(true);
      x.setEllipsize(TextUtils.TruncateAt.END);
      x.setOnClickListener(v -> {
        closeSheet();
        r.run();
      });
      sheetList.addView(x, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
      sheetGrow(dp(46));
    }

    private void closeSheet() {
      if (sheetRoot != null) {
        removeView(sheetRoot);
        sheetRoot = null;
        sheetList = null;
        sheetSv = null;
        scheduleHide();
      }
    }

    /* Back button: first closes a menu, then the player */
    boolean backStep() {
      if (sheetRoot != null) {
        closeSheet();
        return true;
      }
      return false;
    }

    /* an empty full-screen layer that closes when the dark part is tapped */
    private FrameLayout layer(int scrim) {
      closeSheet();
      FrameLayout r = new FrameLayout(getContext());
      r.setBackgroundColor(scrim);
      r.setClickable(true);
      r.setOnClickListener(v -> closeSheet());
      sheetRoot = r;
      addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      hd.removeCallbacks(hideRun);
      return r;
    }

    private GradientDrawable box(int color, float radius) {
      GradientDrawable g = new GradientDrawable();
      g.setColor(color);
      g.setCornerRadius(dp(radius));
      return g;
    }

    /* the right side panel of the three dots (slides in from the right) */
    private void panelItem(LinearLayout col, String icon, String label, final Runnable r) {
      Context c = getContext();
      LinearLayout row = new LinearLayout(c);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setBackgroundColor(Color.TRANSPARENT);
      row.setPadding(dp(18), 0, dp(14), 0);
      IconV ic = new IconV(c, icon, 20f, false);
      row.addView(ic, new LinearLayout.LayoutParams(dp(26), dp(26)));
      TextView t = new TextView(c);
      t.setBackgroundColor(Color.TRANSPARENT);
      t.setText(label);
      t.setTextColor(Color.WHITE);
      t.setTextSize(15f);
      t.setSingleLine(true);
      t.setEllipsize(TextUtils.TruncateAt.END);
      t.setPadding(dp(16), 0, 0, 0);
      row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      row.setOnClickListener(v -> {
        closeSheet();
        r.run();
      });
      col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
    }

    private void openMore() {
      FrameLayout r = layer(0x55000000);
      Context c = getContext();
      final int pw = Math.min(dp(290), Math.round(W * 0.8f));
      ScrollView sv = new ScrollView(c);
      sv.setBackgroundColor(0xF2141414);
      sv.setClickable(true);
      LinearLayout col = new LinearLayout(c);
      col.setOrientation(LinearLayout.VERTICAL);
      col.setBackgroundColor(Color.TRANSPARENT);
      col.setPadding(0, dp(8) + insT, 0, dp(8) + insB);
      panelItem(col, "sleep", sleepEnd > 0L ? "Sleep timer  (on)" : "Sleep timer", () -> openSleep());
      panelItem(col, "speed", "Playback speed  (" + speedText(speed) + ")", () -> openSpeed());
      panelItem(col, "jump", "Jump to Time", () -> jumpToTime());
      panelItem(col, "equalizer", "Equalizer", () -> openEq());
      panelItem(col, audioOnly ? "video" : "audio", audioOnly ? "Play video" : "Play audio only", () -> setAudioOnly(!audioOnly));
      panelItem(col, "repeat", abLoop ? "Repeat A-B  (stop)" : (abA >= 0 ? "Repeat A-B  (set B)" : "Repeat A-B"), () -> abStep());
      panelItem(col, "repeat", loopAll ? "Repeat video: on" : "Repeat video: off", () -> {
        loopAll = !loopAll;
        flash(loopAll ? "Repeat video on" : "Repeat video off");
      });
      panelItem(col, "cut", "Cut", () -> cutPart());
      panelItem(col, "image", "Use this frame as thumbnail", () -> frameThumb());
      panelItem(col, "image", "Choose thumbnail from gallery", () -> {
        keepOnBack = true;
        sendAction("pickThumb");
      });
      if (Build.VERSION.SDK_INT >= 26) panelItem(col, "pip", "Picture in picture", () -> enterPip());
      panelItem(col, "title", titleOn ? "Title off" : "Title on", () -> toggleTitle());
      panelItem(col, "pencil", "Rename", () -> askText("Rename", title, new TextCb() {
        @Override
        public void on(String t) {
          if (t.length() == 0) return;
          title = t;
          qTitles.set(qi, t);
          titleTv.setText(t);
          JSObject o = new JSObject();
          o.put("id", id);
          o.put("title", t);
          host.emit("rename", o);
        }
      }));
      panelItem(col, "shorts", "Move to Shorts", () -> {
        sendAction("short");
        flash("Moved to Shorts");
      });
      panelItem(col, "folder", "Move to folder", () -> openFolders());
      panelItem(col, "share", "Share", () -> {
        keepOnBack = true;
        sendAction("share");
      });
      panelItem(col, "info", "File information", () -> showInfo());
      panelItem(col, "trash", "Delete", () -> confirmDelete());
      sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      r.addView(sv, new FrameLayout.LayoutParams(pw, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
      sv.setTranslationX(pw);
      sv.animate().translationX(0f).setDuration(220).start();
    }

    private void toggleTitle() {
      titleOn = !titleOn;
      titleTv.setVisibility(titleOn ? View.VISIBLE : View.INVISIBLE);
      JSObject o = new JSObject();
      o.put("on", titleOn);
      host.emit("titleOn", o);
      flash(titleOn ? "Title on" : "Title off");
    }

    private void sendAction(String a) {
      JSObject o = new JSObject();
      o.put("id", id);
      o.put("act", a);
      host.emit("action", o);
    }

    interface TextCb {
      void on(String s);
    }

    private void askText(String ttl, String cur, final TextCb cb) {
      try {
        final EditText et = new EditText(act);
        et.setText(cur);
        et.setSingleLine(true);
        new AlertDialog.Builder(act).setTitle(ttl).setView(et).setPositiveButton("OK", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int w) {
            cb.on(et.getText().toString().trim());
          }
        }).setNegativeButton("Cancel", null).show();
      } catch (Throwable t) {
      }
    }

    private void jumpToTime() {
      askText("Jump to time  (mm:ss or h:mm:ss)", "", new TextCb() {
        @Override
        public void on(String t) {
          if (pl == null || t.length() == 0) return;
          try {
            String[] p = t.split(":");
            long s = 0L;
            for (int i = 0; i < p.length; i++) s = s * 60L + Long.parseLong(p[i].trim());
            long d = Math.max(0L, pl.getDuration());
            long ms = Math.max(0L, s * 1000L);
            if (d > 0L && ms > d) ms = Math.max(0L, d - 1000L);
            pl.seekTo(ms);
            ended = false;
            flash(fmt(ms));
          } catch (Throwable e) {
            Toast.makeText(act, "Write the time like 12:30", Toast.LENGTH_SHORT).show();
          }
        }
      });
    }

    private void confirmDelete() {
      try {
        new AlertDialog.Builder(act).setTitle("Delete this video?").setMessage("The file will be deleted from your phone.").setPositiveButton("Delete", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface d, int w) {
            sendAction("delete");
          }
        }).setNegativeButton("Cancel", null).show();
      } catch (Throwable t) {
      }
    }

    private void showInfo() {
      try {
        File f = new File(path);
        long d = pl == null ? 0L : Math.max(0L, pl.getDuration());
        String msg = "Name: " + f.getName() + "\nSize: " + sizeText(f.length()) + "\nLength: " + fmt(d) + (vw > 0 ? "\nPicture: " + vw + " x " + vh : "") + "\nPlace: " + path;
        new AlertDialog.Builder(act).setTitle("File information").setMessage(msg).setPositiveButton("OK", null).show();
      } catch (Throwable t) {
      }
    }

    private String sizeText(long b) {
      if (b >= 1073741824L) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
      return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    private void openFolders() {
      sheetOpen("Move to folder");
      sheetRow("No folder", folder == null || folder.length() == 0, () -> setFolder(""));
      for (int i = 0; i < folders.size(); i++) {
        final String f = folders.get(i);
        sheetRow(f, f.equals(folder), () -> setFolder(f));
      }
      sheetItem("+  New folder", () -> askText("New folder", "", new TextCb() {
        @Override
        public void on(String t) {
          if (t.length() == 0) return;
          if (!folders.contains(t)) folders.add(t);
          setFolder(t);
        }
      }));
    }

    private void setFolder(String f) {
      folder = f;
      qFold.set(qi, f);
      JSObject o = new JSObject();
      o.put("id", id);
      o.put("name", f);
      host.emit("folder", o);
      flash(f.length() > 0 ? "Moved to " + f : "Removed from folder");
    }

    /* ---------- sleep timer (number pad like VLC) ---------- */

    private void openSleep() {
      FrameLayout r = layer(0x88000000);
      Context c = getContext();
      final boolean land = W > H;
      final int kh = land ? dp(34) : dp(52);
      final StringBuilder dg = new StringBuilder();
      final boolean[] opt = { sleepWait, sleepReset };
      LinearLayout card = new LinearLayout(c);
      card.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
      GradientDrawable g = new GradientDrawable();
      g.setColor(0xFF1C1C1E);
      if (land) g.setCornerRadius(dp(14));
      else g.setCornerRadii(new float[] { dp(18), dp(18), dp(18), dp(18), 0f, 0f, 0f, 0f });
      card.setBackground(g);
      card.setClickable(true);
      card.setPadding(dp(16), dp(8), dp(16), land ? dp(8) : dp(10) + insB);

      LinearLayout keysCol = new LinearLayout(c);
      keysCol.setOrientation(LinearLayout.VERTICAL);
      keysCol.setBackgroundColor(Color.TRANSPARENT);
      LinearLayout head = new LinearLayout(c);
      head.setOrientation(LinearLayout.HORIZONTAL);
      head.setGravity(Gravity.CENTER_VERTICAL);
      head.setBackgroundColor(Color.TRANSPARENT);
      TextView ttl = new TextView(c);
      ttl.setBackgroundColor(Color.TRANSPARENT);
      ttl.setText("Sleep in");
      ttl.setTextColor(Color.WHITE);
      ttl.setTextSize(land ? 16f : 18f);
      head.addView(ttl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      final TextView disp = new TextView(c);
      disp.setBackgroundColor(Color.TRANSPARENT);
      disp.setTextColor(YEL);
      disp.setTextSize(land ? 26f : 34f);
      disp.setText("0:00");
      head.addView(disp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      IconV bk = new IconV(c, "backspace", 22f, false);
      head.addView(bk, new LinearLayout.LayoutParams(dp(44), dp(44)));
      keysCol.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, land ? dp(44) : dp(56)));

      final Runnable upd = new Runnable() {
        @Override
        public void run() {
          String s = ("0000" + dg.toString());
          s = s.substring(s.length() - 4);
          int hh = Integer.parseInt(s.substring(0, 2));
          int mm = Integer.parseInt(s.substring(2));
          disp.setText(String.format(Locale.US, "%d:%02d", hh, mm));
        }
      };
      bk.setOnClickListener(v -> {
        if (dg.length() > 0) dg.setLength(dg.length() - 1);
        upd.run();
      });
      String[] keys = { "1", "2", "3", "4", "5", "6", "7", "8", "9", ":00", "0", ":30" };
      for (int rr = 0; rr < 4; rr++) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Color.TRANSPARENT);
        for (int cc = 0; cc < 3; cc++) {
          final String k = keys[rr * 3 + cc];
          TextView b = new TextView(c);
          b.setBackgroundColor(Color.TRANSPARENT);
          b.setText(k);
          b.setTextColor(Color.WHITE);
          b.setTextSize(land ? 19f : 24f);
          b.setGravity(Gravity.CENTER);
          b.setOnClickListener(v -> {
            String add = k.startsWith(":") ? k.substring(1) : k;
            dg.append(add);
            if (dg.length() > 4) dg.delete(0, dg.length() - 4);
            upd.run();
          });
          row.addView(b, new LinearLayout.LayoutParams(0, kh, 1f));
        }
        keysCol.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kh));
      }

      LinearLayout optCol = new LinearLayout(c);
      optCol.setOrientation(LinearLayout.VERTICAL);
      optCol.setBackgroundColor(Color.TRANSPARENT);
      optCol.setGravity(Gravity.CENTER_VERTICAL);
      final String[] on = { "Wait for current media item to finish first", "Reset on any interaction" };
      for (int i = 0; i < 2; i++) {
        final int ix = i;
        final TextView cb = new TextView(c);
        cb.setBackgroundColor(Color.TRANSPARENT);
        cb.setTextColor(Color.WHITE);
        cb.setTextSize(land ? 14f : 15f);
        cb.setGravity(Gravity.CENTER_VERTICAL);
        cb.setMinHeight(dp(44));
        cb.setText((opt[ix] ? "☑  " : "☐  ") + on[ix]);
        cb.setOnClickListener(v -> {
          opt[ix] = !opt[ix];
          cb.setText((opt[ix] ? "☑  " : "☐  ") + on[ix]);
        });
        optCol.addView(cb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      }
      LinearLayout btns = new LinearLayout(c);
      btns.setOrientation(LinearLayout.HORIZONTAL);
      btns.setGravity(Gravity.CENTER_VERTICAL);
      btns.setBackgroundColor(Color.TRANSPARENT);
      TextView rm = new TextView(c);
      rm.setBackgroundColor(Color.TRANSPARENT);
      rm.setText("REMOVE CURRENT");
      rm.setTextColor(YEL);
      rm.setTextSize(14f);
      rm.setGravity(Gravity.CENTER);
      rm.setPadding(dp(8), 0, dp(12), 0);
      rm.setOnClickListener(v -> {
        sleepEnd = 0L;
        sleepTotal = 0L;
        sleepPending = false;
        updateChip();
        closeSheet();
        flash("Sleep timer off");
      });
      TextView ok = new TextView(c);
      ok.setBackgroundColor(Color.TRANSPARENT);
      ok.setText("OK");
      ok.setTextColor(YEL);
      ok.setTextSize(14f);
      ok.setGravity(Gravity.CENTER);
      ok.setPadding(dp(20), 0, dp(8), 0);
      ok.setOnClickListener(v -> {
        String s = ("0000" + dg.toString());
        s = s.substring(s.length() - 4);
        int total = Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(2));
        if (total <= 0) {
          Toast.makeText(act, "Enter a time first", Toast.LENGTH_SHORT).show();
          return;
        }
        sleepWait = opt[0];
        sleepReset = opt[1];
        sleepTotal = total * 60000L;
        sleepEnd = SystemClock.uptimeMillis() + sleepTotal;
        sleepPending = false;
        updateChip();
        closeSheet();
        flash("Sleep timer  " + disp.getText());
      });
      View sp = new View(c);
      sp.setBackgroundColor(Color.TRANSPARENT);
      btns.addView(rm, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
      btns.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
      btns.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
      optCol.addView(btns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

      if (land) {
        card.addView(keysCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ol.setMargins(dp(16), 0, 0, 0);
        card.addView(optCol, ol);
      } else {
        card.addView(keysCol, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(optCol, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      }
      int cw = land ? Math.min(W - dp(24), dp(580)) : Math.min(W, dp(420));
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ViewGroup.LayoutParams.WRAP_CONTENT, land ? Gravity.CENTER : (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
      r.addView(card, lp);
      if (!land) {
        card.setTranslationY(dp(400));
        card.animate().translationY(0f).setDuration(200).start();
      }
    }

    /* ---------- equalizer ---------- */

    private void openEq() {
      if (pl == null) return;
      try {
        if (eq == null) {
          eq = new Equalizer(0, pl.getAudioSessionId());
          eq.setEnabled(eqOn);
        }
      } catch (Throwable t) {
        eq = null;
        Toast.makeText(act, "Equalizer is not available on this phone", Toast.LENGTH_LONG).show();
        return;
      }
      try {
        sheetOpen("Equalizer");
        sheetRow(eqOn ? "Equalizer is on  (tap to turn off)" : "Equalizer is off  (tap to turn on)", eqOn, () -> {
          eqOn = !eqOn;
          try {
            eq.setEnabled(eqOn);
          } catch (Throwable t) {
          }
          openEq();
        });
        sheetHead("PRESETS");
        short np = eq.getNumberOfPresets();
        short cur = -1;
        try {
          cur = eq.getCurrentPreset();
        } catch (Throwable t) {
        }
        for (int i = 0; i < np; i++) {
          final short pi = (short) i;
          sheetRow(eq.getPresetName(pi), pi == cur, () -> {
            try {
              eq.usePreset(pi);
            } catch (Throwable t) {
            }
            openEq();
          });
        }
        sheetHead("BANDS");
        final short[] range = eq.getBandLevelRange();
        final short min = range[0];
        for (int i = 0; i < eq.getNumberOfBands(); i++) {
          final short b = (short) i;
          int hz = eq.getCenterFreq(b) / 1000;
          final String nm = hz >= 1000 ? String.format(Locale.US, "%.1f kHz", hz / 1000f) : hz + " Hz";
          LinearLayout bx = new LinearLayout(getContext());
          bx.setOrientation(LinearLayout.VERTICAL);
          bx.setBackgroundColor(Color.TRANSPARENT);
          bx.setPadding(dp(20), dp(4), dp(20), dp(4));
          final TextView lb = new TextView(getContext());
          lb.setBackgroundColor(Color.TRANSPARENT);
          lb.setTextColor(Color.WHITE);
          lb.setTextSize(13f);
          int lv = eq.getBandLevel(b);
          lb.setText(nm + "    " + (lv >= 0 ? "+" : "") + (lv / 100) + " dB");
          android.widget.SeekBar sb = new android.widget.SeekBar(getContext());
          sb.setMax(range[1] - range[0]);
          sb.setProgress(lv - min);
          try {
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(YEL));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(YEL));
          } catch (Throwable t) {
          }
          sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
              if (!u) return;
              try {
                short v = (short) (min + p);
                eq.setBandLevel(b, v);
                lb.setText(nm + "    " + (v >= 0 ? "+" : "") + (v / 100) + " dB");
              } catch (Throwable t) {
              }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
          });
          bx.addView(lb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
          bx.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
          sheetList.addView(bx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
          sheetGrow(dp(64));
        }
      } catch (Throwable t) {
        closeSheet();
        Toast.makeText(act, "Equalizer is not available on this phone", Toast.LENGTH_LONG).show();
      }
    }

    /* ---------- audio and subtitles (two columns like VLC) ---------- */

    private String trackName(Format f, int n) {
      String lang = "";
      if (f.language != null && f.language.length() > 0 && !"und".equals(f.language)) {
        lang = new Locale(f.language).getDisplayLanguage();
        if (lang == null) lang = "";
      }
      String lab = f.label != null ? f.label : "";
      String s;
      if (lab.length() > 0 && lang.length() > 0) s = lab + " - [" + lang + "]";
      else if (lang.length() > 0) s = lang + " - [" + lang + "]";
      else if (lab.length() > 0) s = lab;
      else s = "Track " + n;
      if (f.channelCount > 2) s = s + "  (" + f.channelCount + "ch)";
      return s;
    }

    private LinearLayout trackCol(String head, List<String> names, List<Boolean> sel, List<Runnable> acts, int rowsMax) {
      Context c = getContext();
      LinearLayout col = new LinearLayout(c);
      col.setOrientation(LinearLayout.VERTICAL);
      col.setBackgroundColor(Color.TRANSPARENT);
      TextView h = new TextView(c);
      h.setBackgroundColor(Color.TRANSPARENT);
      h.setText(head + " ▾");
      h.setTextColor(Color.WHITE);
      h.setTextSize(16f);
      h.setGravity(Gravity.CENTER_VERTICAL);
      h.setPadding(dp(12), 0, dp(12), 0);
      col.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
      ScrollView sv = new ScrollView(c);
      sv.setBackgroundColor(Color.TRANSPARENT);
      LinearLayout list = new LinearLayout(c);
      list.setOrientation(LinearLayout.VERTICAL);
      list.setBackgroundColor(Color.TRANSPARENT);
      for (int i = 0; i < names.size(); i++) {
        final Runnable a = acts.get(i);
        boolean on = sel.get(i).booleanValue();
        TextView x = new TextView(c);
        x.setBackgroundColor(Color.TRANSPARENT);
        x.setText((on ? "✓ " : "    ") + names.get(i));
        x.setTextColor(on ? Color.WHITE : GRAY);
        x.setTextSize(14f);
        x.setGravity(Gravity.CENTER_VERTICAL);
        x.setPadding(dp(12), 0, dp(8), 0);
        x.setSingleLine(true);
        x.setEllipsize(TextUtils.TruncateAt.END);
        x.setOnClickListener(v -> {
          closeSheet();
          a.run();
        });
        list.addView(x, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
      }
      sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      int avail = Math.max(dp(88), H - dp(24) - insT - insB - dp(90));
      col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(rowsMax * dp(44), avail)));
      return col;
    }

    private void openTracks() {
      if (pl == null) return;
      FrameLayout r = layer(0x88000000);
      Context c = getContext();
      Tracks tr = pl.getCurrentTracks();
      List<String> an = new ArrayList<String>();
      List<Boolean> as = new ArrayList<Boolean>();
      List<Runnable> aa = new ArrayList<Runnable>();
      List<String> sn = new ArrayList<String>();
      List<Boolean> ss = new ArrayList<Boolean>();
      List<Runnable> sa = new ArrayList<Runnable>();
      boolean audioOff = pl.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO);
      boolean textOff = pl.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
      an.add("Disable track");
      as.add(Boolean.valueOf(audioOff));
      aa.add(() -> disableType(C.TRACK_TYPE_AUDIO));
      sn.add("Disable track");
      ss.add(Boolean.valueOf(false));
      sa.add(() -> disableType(C.TRACK_TYPE_TEXT));
      int na = 0;
      int ns = 0;
      boolean anySel = false;
      for (int gi = 0; gi < tr.getGroups().size(); gi++) {
        final Tracks.Group g = tr.getGroups().get(gi);
        int ty = g.getType();
        if (ty != C.TRACK_TYPE_AUDIO && ty != C.TRACK_TYPE_TEXT) continue;
        for (int ti = 0; ti < g.length; ti++) {
          final int tj = ti;
          final int fty = ty;
          if (ty == C.TRACK_TYPE_AUDIO) {
            na++;
            an.add(trackName(g.getTrackFormat(ti), na));
            as.add(Boolean.valueOf(!audioOff && g.isTrackSelected(ti)));
            aa.add(() -> pickTrack(fty, g, tj));
          } else {
            ns++;
            sn.add(trackName(g.getTrackFormat(ti), ns));
            boolean on = !textOff && g.isTrackSelected(ti);
            if (on) anySel = true;
            ss.add(Boolean.valueOf(on));
            sa.add(() -> pickTrack(fty, g, tj));
          }
        }
      }
      ss.set(0, Boolean.valueOf(textOff || !anySel));
      int rows = Math.max(an.size(), sn.size());
      LinearLayout card = new LinearLayout(c);
      card.setOrientation(LinearLayout.HORIZONTAL);
      card.setBackground(box(0xFA1C1C1E, 12f));
      card.setClickable(true);
      card.setPadding(dp(6), dp(4), dp(6), dp(8));
      card.addView(trackCol("Audio", an, as, aa, rows), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      card.addView(trackCol("Subtitles", sn, ss, sa, rows), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      int cw = Math.min(dp(520), Math.round(W * 0.94f));
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
      lp.setMargins(0, dp(12) + insT, 0, dp(12) + insB);
      r.addView(card, lp);
    }

    private void pickTrack(int type, Tracks.Group g, int j) {
      try {
        pl.setTrackSelectionParameters(
          pl.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(type, false).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), j)).build()
        );
        if (type == C.TRACK_TYPE_TEXT) flash("Subtitle on");
        else flash("Audio changed");
      } catch (Throwable t) {
        Toast.makeText(act, "Could not change the track", Toast.LENGTH_SHORT).show();
      }
    }

    private void disableType(int type) {
      try {
        pl.setTrackSelectionParameters(pl.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(type, true).build());
        if (type == C.TRACK_TYPE_TEXT) subTv.setText("");
        flash(type == C.TRACK_TYPE_TEXT ? "Subtitles off" : "Audio off");
      } catch (Throwable t) {
      }
    }

    private void setAudioOnly(boolean on) {
      audioOnly = on;
      tv.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
      aoBox.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private String abLabel() {
      if (abLoop) return "Repeat A-B: stop   (" + fmt(abA) + " - " + fmt(abB) + ")";
      if (abA >= 0) return "Repeat A-B: set end point B   (A = " + fmt(abA) + ")";
      return "Repeat A-B: set start point A";
    }

    private void abStep() {
      if (pl == null) return;
      long p = pl.getCurrentPosition();
      if (abLoop) {
        abLoop = false;
        abA = -1L;
        abB = -1L;
        seek.setMarks(-1f, -1f);
        flash("Repeat off");
      } else if (abA < 0) {
        abA = p;
        flash("A  " + fmt(p));
      } else if (p <= abA + 1000L) {
        flash("B must be after A");
      } else {
        abB = p;
        abLoop = true;
        pl.seekTo(abA);
        flash("Repeat A-B on");
      }
    }

    private void enterPip() {
      try {
        int w = vw > 0 ? vw : 16;
        int h = vh > 0 ? vh : 9;
        float r = w / (float) h;
        if (r > 2.39f) {
          w = 239;
          h = 100;
        } else if (r < 0.42f) {
          w = 100;
          h = 239;
        }
        hideCtl();
        act.enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(w, h)).build());
      } catch (Throwable t) {
        Toast.makeText(act, "Picture in picture is not available", Toast.LENGTH_SHORT).show();
      }
    }

    /* Cut: its own screen. Choose the start and end (slide, or take the time that is playing now), then save */
    private void cutPart() {
      if (pl == null) return;
      final long dur = pl.getDuration();
      if (dur <= 0L) {
        Toast.makeText(act, "Wait for the video to load", Toast.LENGTH_SHORT).show();
        return;
      }
      pl.setPlayWhenReady(false);
      FrameLayout r = layer(0x66000000);
      Context c = getContext();
      final boolean land = W > H;
      final long[] t = { Math.max(0L, pl.getCurrentPosition()), 0L };
      t[1] = Math.min(dur, t[0] + 60000L);
      if (t[1] - t[0] < 1000L) {
        t[0] = Math.max(0L, dur - 60000L);
        t[1] = dur;
      }
      final TextView lenTv = new TextView(c);
      final TextView[] tt = new TextView[2];
      final android.widget.SeekBar[] sb = new android.widget.SeekBar[2];
      final Runnable upd = new Runnable() {
        @Override
        public void run() {
          tt[0].setText(fmt(t[0]));
          tt[1].setText(fmt(t[1]));
          lenTv.setText(t[1] > t[0] ? "Length  " + fmt(t[1] - t[0]) : "End must be after Start");
        }
      };
      LinearLayout card = new LinearLayout(c);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setBackground(box(0xFA1C1C1E, 14f));
      card.setClickable(true);
      card.setPadding(dp(16), dp(10), dp(16), dp(8));
      TextView ttl = new TextView(c);
      ttl.setBackgroundColor(Color.TRANSPARENT);
      ttl.setText("Cut video");
      ttl.setTextColor(Color.WHITE);
      ttl.setTextSize(17f);
      card.addView(ttl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
      LinearLayout cols = new LinearLayout(c);
      cols.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
      cols.setBackgroundColor(Color.TRANSPARENT);
      String[] nm = { "Start", "End" };
      for (int k = 0; k < 2; k++) {
        final int ix = k;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout hr = new LinearLayout(c);
        hr.setOrientation(LinearLayout.HORIZONTAL);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        hr.setBackgroundColor(Color.TRANSPARENT);
        TextView lb = new TextView(c);
        lb.setBackgroundColor(Color.TRANSPARENT);
        lb.setText(nm[k]);
        lb.setTextColor(0xFF9AA0AE);
        lb.setTextSize(14f);
        hr.addView(lb, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        tt[k] = new TextView(c);
        tt[k].setBackgroundColor(Color.TRANSPARENT);
        tt[k].setTextColor(YEL);
        tt[k].setTextSize(18f);
        hr.addView(tt[k], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView now = new TextView(c);
        now.setBackgroundColor(Color.TRANSPARENT);
        now.setText("USE CURRENT");
        now.setTextColor(YEL);
        now.setTextSize(12f);
        now.setGravity(Gravity.CENTER);
        now.setPadding(dp(8), 0, dp(4), 0);
        hr.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        col.addView(hr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        sb[k] = new android.widget.SeekBar(c);
        sb[k].setMax((int) (dur / 1000L));
        sb[k].setProgress((int) (t[k] / 1000L));
        try {
          sb[k].setProgressTintList(android.content.res.ColorStateList.valueOf(YEL));
          sb[k].setThumbTintList(android.content.res.ColorStateList.valueOf(YEL));
        } catch (Throwable e) {
        }
        sb[k].setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
            if (!u || pl == null) return;
            t[ix] = p * 1000L;
            pl.seekTo(t[ix]);
            upd.run();
          }

          @Override
          public void onStartTrackingTouch(android.widget.SeekBar s) {
          }

          @Override
          public void onStopTrackingTouch(android.widget.SeekBar s) {
          }
        });
        col.addView(sb[k], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        now.setOnClickListener(v -> {
          if (pl == null) return;
          t[ix] = Math.max(0L, Math.min(dur, pl.getCurrentPosition()));
          sb[ix].setProgress((int) (t[ix] / 1000L));
          upd.run();
        });
        LinearLayout.LayoutParams cl = land ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (land && k == 1) cl.setMargins(dp(16), 0, 0, 0);
        cols.addView(col, cl);
      }
      card.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      LinearLayout foot = new LinearLayout(c);
      foot.setOrientation(LinearLayout.HORIZONTAL);
      foot.setGravity(Gravity.CENTER_VERTICAL);
      foot.setBackgroundColor(Color.TRANSPARENT);
      lenTv.setBackgroundColor(Color.TRANSPARENT);
      lenTv.setTextColor(Color.WHITE);
      lenTv.setTextSize(13f);
      foot.addView(lenTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      TextView pv = new TextView(c);
      pv.setBackgroundColor(Color.TRANSPARENT);
      pv.setText("PREVIEW");
      pv.setTextColor(YEL);
      pv.setTextSize(13f);
      pv.setGravity(Gravity.CENTER);
      pv.setPadding(dp(10), 0, dp(10), 0);
      pv.setOnClickListener(v -> {
        if (pl == null) return;
        pl.seekTo(t[0]);
        pl.setPlayWhenReady(!pl.getPlayWhenReady());
      });
      TextView cn = new TextView(c);
      cn.setBackgroundColor(Color.TRANSPARENT);
      cn.setText("CANCEL");
      cn.setTextColor(YEL);
      cn.setTextSize(13f);
      cn.setGravity(Gravity.CENTER);
      cn.setPadding(dp(10), 0, dp(10), 0);
      cn.setOnClickListener(v -> closeSheet());
      TextView sv = new TextView(c);
      sv.setBackgroundColor(Color.TRANSPARENT);
      sv.setText("SAVE");
      sv.setTextColor(YEL);
      sv.setTextSize(13f);
      sv.setGravity(Gravity.CENTER);
      sv.setPadding(dp(10), 0, dp(4), 0);
      sv.setOnClickListener(v -> {
        if (t[1] - t[0] < 1000L) {
          Toast.makeText(act, "End must be after Start", Toast.LENGTH_SHORT).show();
          return;
        }
        closeSheet();
        runCut(t[0], t[1]);
      });
      foot.addView(pv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
      foot.addView(cn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
      foot.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
      card.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
      upd.run();
      int cw = Math.min(W - dp(24), land ? dp(600) : dp(420));
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ViewGroup.LayoutParams.WRAP_CONTENT, land ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
      lp.setMargins(0, dp(8), 0, dp(8) + insB);
      r.addView(card, lp);
    }

    private void runCut(final long a, final long b) {
      final String src = path;
      final String ttl = title;
      Toast.makeText(act, "Cutting... please wait", Toast.LENGTH_SHORT).show();
      new Thread(new Runnable() {
        @Override
        public void run() {
          final String msg = doCut(src, a * 1000L, b * 1000L, ttl, a, b);
          act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
              Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
            }
          });
        }
      }).start();
    }

    /* fast cut: the picture and sound are copied as they are (no re-encoding),
       so it starts from the nearest earlier key frame */
    private String doCut(String src, long sUs, long eUs, String ttl, long aMs, long bMs) {
      MediaExtractor ex = new MediaExtractor();
      MediaMuxer mx = null;
      File tmp = null;
      boolean started = false;
      try {
        ex.setDataSource(src);
        boolean webm = src.toLowerCase(Locale.US).endsWith(".webm");
        int n = ex.getTrackCount();
        int[] map = new int[n];
        tmp = new File(act.getCacheDir(), "cut_" + System.currentTimeMillis() + (webm ? ".webm" : ".mp4"));
        mx = new MediaMuxer(tmp.getAbsolutePath(), webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int cnt = 0;
        int maxIn = 4 << 20;
        for (int i = 0; i < n; i++) {
          MediaFormat f = ex.getTrackFormat(i);
          String m = f.getString(MediaFormat.KEY_MIME);
          if (m != null && (m.startsWith("video/") || m.startsWith("audio/"))) {
            map[i] = mx.addTrack(f);
            ex.selectTrack(i);
            cnt++;
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = Math.max(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
          } else {
            map[i] = -1;
          }
        }
        if (cnt == 0) return "Nothing to cut in this video";
        try {
          MediaMetadataRetriever mr = new MediaMetadataRetriever();
          mr.setDataSource(src);
          String rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
          mr.release();
          if (rot != null) mx.setOrientationHint(Integer.parseInt(rot));
        } catch (Throwable t) {
        }
        mx.start();
        started = true;
        ex.seekTo(sUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        ByteBuffer buf = ByteBuffer.allocate(Math.min(maxIn, 16 << 20));
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long base = -1L;
        while (true) {
          int sz = ex.readSampleData(buf, 0);
          if (sz < 0) break;
          long t = ex.getSampleTime();
          if (t > eUs) break;
          int ti = ex.getSampleTrackIndex();
          if (base < 0L) base = t;
          if (t >= base && ti >= 0 && ti < n && map[ti] >= 0) {
            bi.set(0, sz, t - base, (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            mx.writeSampleData(map[ti], buf, bi);
          }
          ex.advance();
        }
        mx.stop();
        started = false;
        mx.release();
        mx = null;
        String nm = ttl.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
        if (nm.length() == 0) nm = "video";
        nm = nm + " (cut " + fmt(aMs).replace(':', '.') + "-" + fmt(bMs).replace(':', '.') + ")" + (webm ? ".webm" : ".mp4");
        InputStreamHolder h = new InputStreamHolder(tmp);
        if (Build.VERSION.SDK_INT >= 29) {
          ContentValues cv = new ContentValues();
          cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nm);
          cv.put(MediaStore.MediaColumns.MIME_TYPE, webm ? "video/webm" : "video/mp4");
          cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/V Player");
          android.net.Uri u = act.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
          if (u == null) return "Could not save the cut video";
          OutputStream os = act.getContentResolver().openOutputStream(u);
          h.copyTo(os);
        } else {
          File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "V Player");
          dir.mkdirs();
          h.copyTo(new java.io.FileOutputStream(new File(dir, nm)));
        }
        return "Saved in Movies / V Player:  " + nm;
      } catch (Throwable t) {
        return "Could not cut this video (this format cannot be cut quickly)";
      } finally {
        try {
          if (mx != null) {
            if (started) mx.stop();
            mx.release();
          }
        } catch (Throwable t) {
        }
        try {
          ex.release();
        } catch (Throwable t) {
        }
        if (tmp != null) tmp.delete();
      }
    }

    private static class InputStreamHolder {

      private final File f;

      InputStreamHolder(File f) {
        this.f = f;
      }

      void copyTo(OutputStream os) throws java.io.IOException {
        FileInputStream in = new FileInputStream(f);
        try {
          byte[] b = new byte[1 << 16];
          int r;
          while ((r = in.read(b)) > 0) os.write(b, 0, r);
          os.flush();
        } finally {
          in.close();
          os.close();
        }
      }
    }

    private String speedText(float s) {
      String t = s == (int) s ? String.valueOf((int) s) : String.valueOf(s);
      return t + "x";
    }

    private void openSpeed() {
      sheetOpen("Playback speed");
      float[] sp = { 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f };
      for (int i = 0; i < sp.length; i++) {
        final float v = sp[i];
        sheetRow(v == 1f ? "Normal" : speedText(v), Math.abs(v - speed) < 0.01f, () -> {
          speed = v;
          if (pl != null) pl.setPlaybackParameters(new PlaybackParameters(v));
          flash(speedText(v));
        });
      }
    }

    /* keeps the picture that is on the screen now as the video's thumbnail */
    private void frameThumb() {
      try {
        Bitmap b = tv.getBitmap();
        if (b == null) {
          Toast.makeText(act, "Play the video first, then try again", Toast.LENGTH_SHORT).show();
          return;
        }
        int w = b.getWidth();
        int h = b.getHeight();
        float k = Math.min(1f, 480f / Math.max(w, h));
        Bitmap sm = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        sm.compress(Bitmap.CompressFormat.JPEG, 82, bo);
        JSObject o = new JSObject();
        o.put("id", id);
        o.put("data", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
        host.emit("thumb", o);
        Toast.makeText(act, "Thumbnail changed", Toast.LENGTH_SHORT).show();
      } catch (Throwable t) {
        Toast.makeText(act, "Could not take the frame", Toast.LENGTH_SHORT).show();
      }
    }

    /* ---------- brightness / volume ---------- */

    private float brightNow() {
      try {
        float b = act.getWindow().getAttributes().screenBrightness;
        if (b >= 0f) return b;
        int s = android.provider.Settings.System.getInt(act.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
        return s / 255f;
      } catch (Throwable t) {
        return 0.5f;
      }
    }

    private void brightSet(float v) {
      try {
        Window w = act.getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = Math.max(0.01f, Math.min(1f, v));
        w.setAttributes(lp);
      } catch (Throwable t) {
      }
    }

    private float volNow() {
      try {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0.5f;
      } catch (Throwable t) {
        return 0.5f;
      }
    }

    private void volSet(float v) {
      try {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0f, Math.min(1f, v)) * max), 0);
      } catch (Throwable t) {
      }
    }

    /* ---------- touch: tap, double tap, drag ---------- */

    private final Runnable tapRun = new Runnable() {
      @Override
      public void run() {
        if (ctlOn) hideCtl();
        else showCtl();
      }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      if (sheetRoot != null || pl == null) return true;
      int a = e.getActionMasked();
      if (a == MotionEvent.ACTION_DOWN) {
        downX = e.getX();
        downY = e.getY();
        downT = SystemClock.uptimeMillis();
        mode = 0;
        return true;
      }
      if (a == MotionEvent.ACTION_MOVE) {
        float dx = e.getX() - downX;
        float dy = e.getY() - downY;
        if (mode == 0 && (Math.abs(dx) > dp(16) || Math.abs(dy) > dp(16))) {
          hd.removeCallbacks(tapRun);
          if (Math.abs(dx) > Math.abs(dy)) {
            mode = 4;
            seekBase = pl.getCurrentPosition();
          } else if (downX < W / 2f) {
            mode = 2;
            gBase = brightNow();
          } else {
            mode = 3;
            gBase = volNow();
          }
        }
        if (mode == 4) {
          long d = Math.max(0L, pl.getDuration());
          seekTarget = Math.max(0L, Math.min(d, seekBase + (long) (dx / Math.max(1, W) * 90000f)));
          long diff = (seekTarget - seekBase) / 1000L;
          flashKeep((diff >= 0 ? "+" : "-") + Math.abs(diff) + "s\n" + fmt(seekTarget) + " / " + fmt(d));
        } else if (mode == 2) {
          float v = Math.max(0.01f, Math.min(1f, gBase - dy / (H * 0.8f)));
          brightSet(v);
          flashKeep("Brightness  " + Math.round(v * 100f) + "%");
        } else if (mode == 3) {
          float v = Math.max(0f, Math.min(1f, gBase - dy / (H * 0.8f)));
          volSet(v);
          flashKeep("Volume  " + Math.round(v * 100f) + "%");
        }
        return true;
      }
      if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
        if (mode == 4 && a == MotionEvent.ACTION_UP) {
          pl.seekTo(seekTarget);
          ended = false;
        }
        if (mode != 0) {
          hd.removeCallbacks(hudOff);
          hd.postDelayed(hudOff, 600);
          mode = 0;
          return true;
        }
        if (a == MotionEvent.ACTION_UP) tapped(e.getX());
        return true;
      }
      return true;
    }

    private void flashKeep(String t) {
      hud.setText(t);
      hud.setVisibility(View.VISIBLE);
      hd.removeCallbacks(hudOff);
    }

    private void tapped(float x) {
      long now = SystemClock.uptimeMillis();
      if (now - lastTapT < 300) {
        hd.removeCallbacks(tapRun);
        lastTapT = now;
        if (x < W / 3f) jump(-10000L);
        else if (x > W * 2f / 3f) jump(10000L);
        else togglePlay();
      } else {
        lastTapT = now;
        hd.postDelayed(tapRun, 300);
      }
    }

    private void jump(long d) {
      long dur = Math.max(0L, pl.getDuration());
      long t = Math.max(0L, Math.min(dur, pl.getCurrentPosition() + d));
      pl.seekTo(t);
      ended = false;
      flash(d < 0 ? "« 10s" : "10s »");
    }

    /* ---------- round line icons (same style as the rest of the app) ---------- */

    class IconV extends View {

      private String kind;
      private final float isz;
      private final boolean ring;
      private int color = Color.WHITE;
      private boolean filled = false;
      private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
      private final List<Path> strokes = new ArrayList<Path>();
      private final List<Path> fills = new ArrayList<Path>();

      IconV(Context c, String kind, float isz, boolean ring) {
        super(c);
        this.isz = isz;
        this.ring = ring;
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

      private Path circ(float x, float y, float r) {
        Path p = new Path();
        p.addCircle(x, y, r, Path.Direction.CW);
        return p;
      }

      void setKind(String k) {
        kind = k;
        strokes.clear();
        fills.clear();
        if ("back".equals(k)) strokes.add(pp("M15 5l-7 7 7 7"));
        else if ("tracks".equals(k)) strokes.add(pp("M3.5 6h17v12h-17zM7 14h3M12.5 14h4.5M7 10h5M14 10h3"));
        else if ("rotate".equals(k)) {
          strokes.add(pp("M7 4.5h10A2.5 2.5 0 0 1 19.5 7v10a2.5 2.5 0 0 1-2.5 2.5H7A2.5 2.5 0 0 1 4.5 17V7A2.5 2.5 0 0 1 7 4.5z"));
          strokes.add(pp("M15.3 10.2a3.6 3.6 0 1 0 .9 3.1M15.6 7.8v2.6h-2.6"));
        } else if ("lock".equals(k)) {
          strokes.add(pp("M7 4.5h10A2.5 2.5 0 0 1 19.5 7v10a2.5 2.5 0 0 1-2.5 2.5H7A2.5 2.5 0 0 1 4.5 17V7A2.5 2.5 0 0 1 7 4.5z"));
          strokes.add(pp("M9.5 11V9.5a2.5 2.5 0 0 1 5 0V11M8.5 11h7v5h-7z"));
        }
        else if ("fit".equals(k)) strokes.add(pp("M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4"));
        else if ("more".equals(k)) {
          Path a = new Path();
          a.addCircle(12f, 5f, 1.7f, Path.Direction.CW);
          a.addCircle(12f, 12f, 1.7f, Path.Direction.CW);
          a.addCircle(12f, 19f, 1.7f, Path.Direction.CW);
          fills.add(a);
        } else if ("play".equals(k)) fills.add(pp("M8 5.5v13l11-6.5z"));
        else if ("pause".equals(k)) fills.add(pp("M7 5h3.6v14H7zM13.4 5H17v14h-3.6z"));
        else if ("sleep".equals(k)) {
          strokes.add(circ(12f, 13f, 7f));
          strokes.add(pp("M12 10v3l2 1.5M4 6l3-2.5M20 6l-3-2.5"));
        } else if ("speed".equals(k)) strokes.add(pp("M4 17a8 8 0 1 1 16 0M12 17l4-5M7 17h.01M12 9v.01M17 17h.01"));
        else if ("jump".equals(k)) strokes.add(pp("M12 7v5l3 2M4 12a8 8 0 1 0 2.5-5.8M4 4v4h4"));
        else if ("equalizer".equals(k)) strokes.add(pp("M6 4v16M12 4v16M18 4v16M4 9h4M10 15h4M16 8h4"));
        else if ("audio".equals(k)) strokes.add(pp("M4 15v-3a8 8 0 0 1 16 0v3M4 14h3v6H4zM17 14h3v6h-3z"));
        else if ("note".equals(k)) {
          strokes.add(pp("M9 18V6l10-2v12"));
          strokes.add(circ(6.5f, 18f, 2.5f));
          strokes.add(circ(16.5f, 16f, 2.5f));
        }
        else if ("video".equals(k)) strokes.add(pp("M3.5 5.5h17v13h-17zM10 9.5v5l4.5-2.5z"));
        else if ("repeat".equals(k)) strokes.add(pp("M4 11V9a3 3 0 0 1 3-3h11l-3-3M20 13v2a3 3 0 0 1-3 3H6l3 3"));
        else if ("cut".equals(k)) {
          strokes.add(circ(6f, 6f, 2.5f));
          strokes.add(circ(6f, 18f, 2.5f));
          strokes.add(pp("M8 7.5l12 9M8 16.5l12-9"));
        } else if ("image".equals(k)) strokes.add(pp("M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9.5v.01"));
        else if ("pip".equals(k)) strokes.add(pp("M3.5 5h17v14h-17zM12.5 12h6v4.5h-6z"));
        else if ("title".equals(k)) strokes.add(pp("M5 6V4.5h14V6M12 4.5v15M9 19.5h6"));
        else if ("pencil".equals(k)) strokes.add(pp("M4 20l1-4L16 5l3 3L8 19zM14 7l3 3"));
        else if ("folder".equals(k)) strokes.add(pp("M3 6.5h6l2 2.5h10v9.5H3z"));
        else if ("shorts".equals(k)) strokes.add(pp("M7.5 3h9v18h-9zM10.5 9.5v5l4-2.5z"));
        else if ("share".equals(k)) {
          strokes.add(circ(18f, 5.5f, 2.5f));
          strokes.add(circ(6f, 12f, 2.5f));
          strokes.add(circ(18f, 18.5f, 2.5f));
          strokes.add(pp("M8.2 10.8l7.6-3.6M8.2 13.2l7.6 3.6"));
        } else if ("info".equals(k)) {
          strokes.add(circ(12f, 12f, 9f));
          strokes.add(pp("M12 11v6M12 7.5v.01"));
        } else if ("trash".equals(k)) strokes.add(pp("M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13M10 11v6M14 11v6"));
        else if ("backspace".equals(k)) strokes.add(pp("M9 5h11v14H9l-6-7zM12.5 9.5l5 5M17.5 9.5l-5 5"));
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
        if (ring) {
          pt.setStyle(Paint.Style.FILL);
          pt.setColor(0x66000000);
          c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - dp(2), pt);
          pt.setStyle(Paint.Style.STROKE);
          pt.setStrokeWidth(dp(2));
          pt.setColor(Color.WHITE);
          c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - dp(2), pt);
        }
        float s = isz * den / 24f;
        c.save();
        c.translate((w - 24f * s) / 2f, (h - 24f * s) / 2f);
        c.scale(s, s);
        pt.setColor(color);
        pt.setStrokeWidth(1.8f);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeJoin(Paint.Join.ROUND);
        boolean solid = "play".equals(kind) || "pause".equals(kind) || "more".equals(kind);
        pt.setStyle(solid ? Paint.Style.FILL : Paint.Style.FILL_AND_STROKE);
        for (int i = 0; i < fills.size(); i++) c.drawPath(fills.get(i), pt);
        pt.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        for (int i = 0; i < strokes.size(); i++) c.drawPath(strokes.get(i), pt);
        c.restore();
      }
    }

    /* the yellow bar: drag the round dot to move in the video */
    class SeekV extends View {

      float frac = 0f;
      float buf = 0f;
      float ma = -1f;
      float mb = -1f;
      boolean on = false;
      final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);

      SeekV(Context c) {
        super(c);
        setBackgroundColor(Color.TRANSPARENT);
      }

      void setFrac(float f, float b) {
        frac = Math.max(0f, Math.min(1f, f));
        buf = Math.max(0f, Math.min(1f, b));
        invalidate();
      }

      void setMarks(float a, float b) {
        if (a != ma || b != mb) {
          ma = a;
          mb = b;
          invalidate();
        }
      }

      @Override
      protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        float cy = h / 2f;
        float th = on ? dp(5) : dp(3);
        pt.setStyle(Paint.Style.FILL);
        pt.setColor(0x44FFFFFF);
        c.drawRect(0f, cy - th / 2f, (float) w, cy + th / 2f, pt);
        pt.setColor(0x66FFFFFF);
        c.drawRect(0f, cy - th / 2f, w * buf, cy + th / 2f, pt);
        pt.setColor(YEL);
        c.drawRect(0f, cy - th / 2f, w * frac, cy + th / 2f, pt);
        pt.setColor(Color.WHITE);
        if (ma >= 0f) c.drawRect(w * ma - dp(1), cy - dp(8), w * ma + dp(1), cy + dp(8), pt);
        if (mb >= 0f) c.drawRect(w * mb - dp(1), cy - dp(8), w * mb + dp(1), cy + dp(8), pt);
        pt.setColor(YEL);
        c.drawCircle(w * frac, cy, on ? dp(9) : dp(6), pt);
      }

      @Override
      public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
          on = true;
          getParent().requestDisallowInterceptTouchEvent(true);
          hd.removeCallbacks(hideRun);
        }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
          float f = getWidth() > 0 ? e.getX() / getWidth() : 0f;
          frac = Math.max(0f, Math.min(1f, f));
          invalidate();
          refreshTimes();
          return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
          on = false;
          invalidate();
          long d = pl == null ? 0L : pl.getDuration();
          if (a == MotionEvent.ACTION_UP && d > 0) {
            pl.seekTo((long) (frac * d));
            ended = false;
          }
          scheduleHide();
          return true;
        }
        return true;
      }
    }
  }
}
