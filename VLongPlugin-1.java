package com.vplayer.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.app.PictureInPictureParams;
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
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Rational;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.PathParser;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;

/* The long video player (like VLC). It is a separate file from the Shorts screen. */
@CapacitorPlugin(name = "VLong")
public class VLongPlugin extends Plugin {

  private LongView view;
  private OnBackPressedCallback backCb;
  private static VLongPlugin inst;

  /* the buttons of the phone notification come here */
  static void remote(final String a) {
    final VLongPlugin p = inst;
    if (p == null || p.getActivity() == null) return;
    p.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (p.view == null) return;
        if ("toggle".equals(a)) p.view.toggleExternal();
        else p.closeNow();
      }
    });
  }

  private void bgCmd(String cmd, String t, boolean playing) {
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
    final String path = call.getString("path", "");
    if (path == null || path.length() == 0) {
      call.reject("no path");
      return;
    }
    final String id = call.getString("id", "");
    final String title = call.getString("title", "");
    Double rs = call.getDouble("resume", 0.0);
    final long resume = rs == null ? 0L : (long) rs.doubleValue();
    Boolean rm = call.getBoolean("rem", false);
    final boolean rem = rm != null && rm.booleanValue();
    final String folder = call.getString("folder", "");
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
      if (Build.VERSION.SDK_INT >= 33 && getContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
          ViewGroup parent = (ViewGroup) getBridge().getWebView().getParent();
          view = new LongView(act, path, id, title, resume, rem, folders, folder, new LongView.Host() {
            @Override
            public void emit(String name, JSObject o) {
              notifyListeners(name, o);
            }

            @Override
            public void close() {
              closeNow();
            }

            @Override
            public void bg(String cmd, String t, boolean playing) {
              bgCmd(cmd, t, playing);
            }
          });
          parent.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
          view.requestApplyInsets();
          backCb = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
              if (view != null && view.backStep()) return;
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

  private void closeNow() {
    if (backCb != null) {
      backCb.remove();
      backCb = null;
    }
    if (view != null) {
      LongView v = view;
      view = null;
      v.release();
      bgCmd("stop", "", false);
      ViewGroup par = (ViewGroup) v.getParent();
      if (par != null) par.removeView(v);
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
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    if (view != null) {
      view.release();
      view = null;
    }
  }

  /* The whole screen: picture, buttons, gestures and the small menus. */
  static class LongView extends FrameLayout {

    interface Host {
      void emit(String name, JSObject o);

      void close();

      void bg(String cmd, String title, boolean playing);
    }

    private static final int YEL = 0xFFFFB52E;

    private final Activity act;
    private final Host host;
    private final String path;
    private final String id;
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
    private View topShade;
    private View botShade;
    private TextView aoTv;
    private ScrollView sheetSv;
    private int sheetPx = 0;
    private final List<String> folders;
    private String folder;
    private boolean bgOn = false;
    private boolean audioOnly = false;
    private long watchedMs = 0L;
    private long lastTick = 0L;
    private long abA = -1L;
    private long abB = -1L;
    private boolean abLoop = false;
    private long sleepEnd = 0L;
    private int sleepMin = 0;

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
    private boolean resumed = false;
    private long resumeAt;
    private long lastPos = 0L;
    private long lastDur = 0L;
    private long lastEmit = 0L;
    private final int origOrient;
    private final int origUi;
    private float origBright;

    /* touch state */
    private float downX = 0f;
    private float downY = 0f;
    private int mode = 0;
    private float gBase = 0f;
    private long seekBase = 0L;
    private long seekTarget = 0L;
    private long lastTapT = 0L;
    private long downT = 0L;

    LongView(Activity a, String path, String id, String title, long resume, boolean rem, List<String> folders, String folder, Host host) {
      super(a);
      this.act = a;
      this.host = host;
      this.path = path;
      this.id = id;
      this.title = title;
      this.rem = rem;
      this.folders = folders;
      this.folder = folder;
      this.resumeAt = resume;
      this.den = a.getResources().getDisplayMetrics().density;
      this.origOrient = a.getRequestedOrientation();
      this.origUi = a.getWindow().getDecorView().getSystemUiVisibility();
      this.origBright = a.getWindow().getAttributes().screenBrightness;
      setBackgroundColor(Color.BLACK);
      setClickable(true);
      setKeepScreenOn(true);
      build(a);
      startPlayer(a);
      try {
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        a.getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
      } catch (Throwable t) {
      }
      hd.postDelayed(autoRot, 5000);
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

      aoTv = new TextView(c);
      aoTv.setBackgroundColor(Color.TRANSPARENT);
      aoTv.setTextColor(0xFF8A93A8);
      aoTv.setTextSize(18f);
      aoTv.setGravity(Gravity.CENTER);
      aoTv.setText("Audio only");
      aoTv.setVisibility(View.GONE);
      addView(aoTv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

      ctl = new FrameLayout(c);
      ctl.setBackgroundColor(Color.TRANSPARENT);
      addView(ctl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

      topShade = new View(c);
      topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0xAA000000, 0x00000000 }));
      ctl.addView(topShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.TOP));
      botShade = new View(c);
      botShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x00000000, 0xCC000000 }));
      ctl.addView(botShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170), Gravity.BOTTOM));

      backBtn = new IconV(c, "back", 26f, false);
      backBtn.setOnClickListener(v -> host.close());
      ctl.addView(backBtn, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));

      titleTv = new TextView(c);
      titleTv.setBackgroundColor(Color.TRANSPARENT);
      titleTv.setText(title);
      titleTv.setTextColor(Color.WHITE);
      titleTv.setTextSize(15f);
      titleTv.setSingleLine(true);
      titleTv.setEllipsize(TextUtils.TruncateAt.END);
      titleTv.setGravity(Gravity.CENTER);
      titleTv.setShadowLayer(4f, 0f, 1f, Color.BLACK);
      ctl.addView(titleTv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP));

      bottomCol = new LinearLayout(c);
      bottomCol.setOrientation(LinearLayout.VERTICAL);
      bottomCol.setBackgroundColor(Color.TRANSPARENT);
      ctl.addView(bottomCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

      LinearLayout trow = new LinearLayout(c);
      trow.setOrientation(LinearLayout.HORIZONTAL);
      trow.setGravity(Gravity.CENTER_VERTICAL);
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
      seek = new SeekV(c);
      trow.addView(timeL, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
      LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(36), 1f);
      sl.setMargins(dp(8), 0, dp(8), 0);
      trow.addView(seek, sl);
      trow.addView(timeR, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
      bottomCol.addView(trow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

      LinearLayout brow = new LinearLayout(c);
      brow.setOrientation(LinearLayout.HORIZONTAL);
      brow.setGravity(Gravity.CENTER);
      brow.setBackgroundColor(Color.TRANSPARENT);
      tracksBtn = new IconV(c, "tracks", 26f, false);
      rotBtn = new IconV(c, "rotate", 26f, false);
      playBtn = new IconV(c, "pause", 30f, true);
      fitBtn = new IconV(c, "fit", 26f, false);
      moreBtn = new IconV(c, "more", 26f, false);
      tracksBtn.setOnClickListener(v -> openTracks());
      rotBtn.setOnClickListener(v -> toggleLock());
      playBtn.setOnClickListener(v -> togglePlay());
      fitBtn.setOnClickListener(v -> openFit());
      moreBtn.setOnClickListener(v -> openMore());
      brow.addView(slot(c, tracksBtn, 48), new LinearLayout.LayoutParams(0, dp(72), 1f));
      brow.addView(slot(c, rotBtn, 48), new LinearLayout.LayoutParams(0, dp(72), 1f));
      brow.addView(slot(c, playBtn, 64), new LinearLayout.LayoutParams(0, dp(72), 1.2f));
      brow.addView(slot(c, fitBtn, 48), new LinearLayout.LayoutParams(0, dp(72), 1f));
      brow.addView(slot(c, moreBtn, 48), new LinearLayout.LayoutParams(0, dp(72), 1f));
      bottomCol.addView(brow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

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
      x.setTextColor(YEL);
      x.setTextSize(13f);
      x.setGravity(Gravity.CENTER);
      return x;
    }

    private FrameLayout slot(Context c, View v, int size) {
      FrameLayout f = new FrameLayout(c);
      f.setBackgroundColor(Color.TRANSPARENT);
      f.addView(v, new FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER));
      return f;
    }

    private void ui() {
      try {
        FrameLayout.LayoutParams a = (FrameLayout.LayoutParams) backBtn.getLayoutParams();
        a.setMargins(dp(6) + insL, dp(2) + insT, 0, 0);
        backBtn.setLayoutParams(a);
        FrameLayout.LayoutParams b = (FrameLayout.LayoutParams) titleTv.getLayoutParams();
        b.setMargins(dp(64) + insL, dp(2) + insT, dp(64) + insR, 0);
        titleTv.setLayoutParams(b);
        FrameLayout.LayoutParams d = (FrameLayout.LayoutParams) bottomCol.getLayoutParams();
        d.setMargins(dp(12) + insL, 0, dp(12) + insR, dp(4) + insB);
        bottomCol.setLayoutParams(d);
        FrameLayout.LayoutParams s = (FrameLayout.LayoutParams) subTv.getLayoutParams();
        s.setMargins(dp(40) + insL, 0, dp(40) + insR, dp(30) + insB);
        subTv.setLayoutParams(s);
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

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
      super.onSizeChanged(w, h, ow, oh);
      W = w;
      H = h;
      fit();
      if (inPip()) hideCtl();
    }

    /* ---------- player ---------- */

    private void startPlayer(Context c) {
      DefaultRenderersFactory rf = new DefaultRenderersFactory(c).setEnableDecoderFallback(true);
      DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(15000, 50000, 500, 1500).build();
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
          }
          if (st == Player.STATE_ENDED) {
            ended = true;
            playBtn.setKind("play");
            showCtl();
            emitProgress(true);
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
          Toast.makeText(act, "This video could not be played", Toast.LENGTH_LONG).show();
          JSObject o = new JSObject();
          o.put("id", id);
          o.put("error", String.valueOf(e.getMessage()));
          host.emit("error", o);
        }
      });
      pl.setMediaItem(buildItem());
      pl.prepare();
      pl.setPlayWhenReady(true);
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
      if (bgOn && pl != null) host.bg("update", title, pl.getPlayWhenReady());
    }

    /* the app goes to the background: the sound keeps playing and a notification with buttons is shown */
    void onLeave() {
      if (pl == null || dead) return;
      emitProgress(false);
      if (inPip()) return;
      bgOn = true;
      host.bg("start", title, pl.getPlayWhenReady());
    }

    /* the app is back: the notification goes away and the picture returns */
    void onBack() {
      if (bgOn) {
        bgOn = false;
        host.bg("stop", "", false);
      }
      if (ctlOn) showCtl();
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
        if (pl != null) {
          pl.setVideoTextureView(null);
          pl.release();
        }
      } catch (Throwable t) {
      }
      pl = null;
      try {
        act.setRequestedOrientation(origOrient);
        act.getWindow().getDecorView().setSystemUiVisibility(origUi);
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
        if (fitMode == 0) {
          if (W / (float) H > ar) tw = Math.round(H * ar);
          else th = Math.round(W / ar);
        } else if (fitMode == 1) {
          if (W / (float) H > ar) th = Math.round(W / ar);
          else tw = Math.round(H * ar);
        } else if (fitMode == 3 || fitMode == 4) {
          float r = fitMode == 3 ? 16f / 9f : 4f / 3f;
          if (W / (float) H > r) tw = Math.round(H * r);
          else th = Math.round(W / r);
        } else if (fitMode == 5) {
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

    private final Runnable autoRot = new Runnable() {
      @Override
      public void run() {
        if (!locked && !dead) {
          try {
            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
          } catch (Throwable t) {
          }
        }
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
            sleepEnd = 0;
            sleepMin = 0;
            pl.setPlayWhenReady(false);
            userPaused = true;
            flash("Sleep timer: stopped");
            bgUpdate();
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
      ctlOn = true;
      ctl.setVisibility(View.VISIBLE);
      ctl.setAlpha(1f);
      refreshTimes();
      scheduleHide();
    }

    private void hideCtl() {
      ctlOn = false;
      ctl.setVisibility(View.GONE);
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

    /* ---------- rotate / lock ---------- */

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
      hd.postDelayed(hudOff, 700);
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
      g.setColor(0xFA141A2B);
      g.setCornerRadius(dp(18));
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

    private void openMore() {
      sheetOpen("More");
      sheetItem("Sleep timer" + (sleepMin > 0 ? "   (" + sleepMin + " min)" : ""), () -> openSleep());
      sheetItem("Playback speed   (" + speedText(speed) + ")", () -> openSpeed());
      sheetItem(audioOnly ? "Play video" : "Play audio only", () -> setAudioOnly(!audioOnly));
      sheetItem(abLabel(), () -> abStep());
      sheetItem("Cut the A-B part (save as new video)", () -> cutPart());
      sheetItem("Use this frame as thumbnail", () -> frameThumb());
      if (Build.VERSION.SDK_INT >= 26) sheetItem("Picture in picture", () -> enterPip());
      sheetItem("Rename", () -> askText("Rename", title, new TextCb() {
        @Override
        public void on(String t) {
          if (t.length() == 0) return;
          title = t;
          titleTv.setText(t);
          JSObject o = new JSObject();
          o.put("id", id);
          o.put("title", t);
          host.emit("rename", o);
        }
      }));
      sheetItem("Move to folder", () -> openFolders());
      sheetItem("Share", () -> sendAction("share"));
      sheetItem("File information", () -> showInfo());
      sheetItem("Delete", () -> confirmDelete());
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
      JSObject o = new JSObject();
      o.put("id", id);
      o.put("name", f);
      host.emit("folder", o);
      flash(f.length() > 0 ? "Moved to " + f : "Removed from folder");
    }

    /* ---------- sleep timer, audio only, A-B repeat, cut, picture in picture ---------- */

    private void openSleep() {
      sheetOpen("Sleep timer");
      int[] mins = { 0, 10, 15, 30, 45, 60, 90 };
      for (int i = 0; i < mins.length; i++) {
        final int m = mins[i];
        sheetRow(m == 0 ? "Off" : m + " minutes", m == sleepMin, () -> {
          sleepMin = m;
          sleepEnd = m > 0 ? SystemClock.uptimeMillis() + m * 60000L : 0L;
          flash(m > 0 ? "Sleep timer " + m + " min" : "Sleep timer off");
        });
      }
    }

    private void setAudioOnly(boolean on) {
      audioOnly = on;
      try {
        pl.setTrackSelectionParameters(pl.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on).build());
      } catch (Throwable t) {
      }
      tv.setVisibility(on ? View.GONE : View.VISIBLE);
      aoTv.setVisibility(on ? View.VISIBLE : View.GONE);
      flash(on ? "Audio only" : "Video on");
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

    private void cutPart() {
      if (abA < 0 || abB <= abA) {
        Toast.makeText(act, "First set A and B with \"Repeat A-B\", then cut", Toast.LENGTH_LONG).show();
        return;
      }
      final long a = abA;
      final long b = abB;
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

    private void openFit() {
      sheetOpen("Screen fit");
      String[] nm = { "Best fit", "Fill screen (crop)", "Stretch", "16:9", "4:3", "Original size" };
      for (int i = 0; i < nm.length; i++) {
        final int m = i;
        sheetRow(nm[i], fitMode == i, () -> {
          fitMode = m;
          fit();
          flash(nm[m]);
        });
      }
    }

    private String trackName(Format f, int n) {
      String s = "";
      if (f.label != null && f.label.length() > 0) s = f.label;
      if (f.language != null && f.language.length() > 0 && !"und".equals(f.language)) {
        String l = new Locale(f.language).getDisplayLanguage();
        if (l != null && l.length() > 0 && !s.toLowerCase(Locale.US).contains(l.toLowerCase(Locale.US))) s = s.length() > 0 ? s + " - " + l : l;
      }
      if (s.length() == 0) s = "Track " + n;
      if (f.channelCount > 2) s = s + "  (" + f.channelCount + "ch)";
      return s;
    }

    private void openTracks() {
      if (pl == null) return;
      sheetOpen("Audio and subtitles");
      Tracks tr = pl.getCurrentTracks();
      sheetHead("AUDIO");
      boolean audioOff = pl.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO);
      int an = 0;
      for (int gi = 0; gi < tr.getGroups().size(); gi++) {
        final Tracks.Group g = tr.getGroups().get(gi);
        if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
        for (int ti = 0; ti < g.length; ti++) {
          final int tj = ti;
          an++;
          sheetRow(trackName(g.getTrackFormat(ti), an), !audioOff && g.isTrackSelected(ti), () -> pickTrack(C.TRACK_TYPE_AUDIO, g, tj));
        }
      }
      sheetRow("Disable", audioOff, () -> disableType(C.TRACK_TYPE_AUDIO));
      sheetHead("SUBTITLES");
      boolean textOff = pl.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
      boolean anySub = false;
      int sn = 0;
      for (int gi = 0; gi < tr.getGroups().size(); gi++) {
        final Tracks.Group g = tr.getGroups().get(gi);
        if (g.getType() != C.TRACK_TYPE_TEXT) continue;
        for (int ti = 0; ti < g.length; ti++) {
          final int tj = ti;
          sn++;
          anySub = true;
          sheetRow(trackName(g.getTrackFormat(ti), sn), !textOff && g.isTrackSelected(ti), () -> pickTrack(C.TRACK_TYPE_TEXT, g, tj));
        }
      }
      if (!anySub) {
        TextView x = new TextView(getContext());
        x.setBackgroundColor(Color.TRANSPARENT);
        x.setText("     No subtitles in this video");
        x.setTextColor(0xFF8A93A8);
        x.setTextSize(14f);
        x.setPadding(dp(20), dp(8), dp(20), dp(8));
        sheetList.addView(x, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheetGrow(dp(40));
      }
      sheetRow("Disable", textOff || !anySub, () -> disableType(C.TRACK_TYPE_TEXT));
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

      void setKind(String k) {
        kind = k;
        strokes.clear();
        fills.clear();
        if ("back".equals(k)) strokes.add(pp("M15 5l-7 7 7 7"));
        else if ("tracks".equals(k)) strokes.add(pp("M3.5 6h17v12h-17zM7 14h3M12.5 14h4.5M7 10h5M14 10h3"));
        else if ("rotate".equals(k)) strokes.add(pp("M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5"));
        else if ("lock".equals(k)) {
          strokes.add(pp("M8 11V8a4 4 0 0 1 8 0v3M6 11h12v9H6z"));
          fills.add(pp("M11 14.5h2v3h-2z"));
        } else if ("fit".equals(k)) strokes.add(pp("M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4"));
        else if ("more".equals(k)) {
          Path a = new Path();
          a.addCircle(12f, 5f, 1.7f, Path.Direction.CW);
          a.addCircle(12f, 12f, 1.7f, Path.Direction.CW);
          a.addCircle(12f, 19f, 1.7f, Path.Direction.CW);
          fills.add(a);
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
        boolean solid = "play".equals(kind) || "pause".equals(kind);
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
