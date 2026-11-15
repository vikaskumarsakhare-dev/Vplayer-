package com.vplayer.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;

/* shows on the lock screen when the alarm rings: the video, and one Stop button above the navigation buttons */
public class VAlarmActivity extends Activity {
  static VAlarmActivity current;
  private SurfaceView sv;
  private final Handler h = new Handler(Looper.getMainLooper());
  private boolean attached = false;
  private int tries = 0;
  private FrameLayout root;
  private Player.Listener vl;

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    current = this;
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    } else {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    sv = new SurfaceView(this);
    root.addView(sv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    Button stop = new Button(this);
    stop.setText("Stop");
    stop.setAllCaps(false);
    stop.setTextSize(18f);
    stop.setTextColor(Color.WHITE);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.parseColor("#E53935"));
    bg.setCornerRadius(dp(28));
    stop.setBackground(bg);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(180), dp(56), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    lp.bottomMargin = dp(28);
    root.addView(stop, lp);
    stop.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        Intent si = new Intent(VAlarmActivity.this, VAlarmService.class);
        si.setAction("stop");
        startService(si);
        finish();
      }
    });
    setContentView(root);
    root.setFitsSystemWindows(true);
    tryAttach();
  }

  /* the player lives in the service; the picture is hung on this screen once the player is ready */
  private void tryAttach() {
    if (attached) return;
    final androidx.media3.exoplayer.ExoPlayer p = VAlarmService.player;
    if (p == null) {
      if (!VAlarmService.ringing && tries > 3) {
        finish();
        return;
      }
      if (++tries < 40) h.postDelayed(new Runnable() {
        @Override
        public void run() {
          tryAttach();
        }
      }, 200);
      return;
    }
    attached = true;
    try {
      p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build());
      vl = new Player.Listener() {
        @Override
        public void onVideoSizeChanged(VideoSize vs) {
          fit(vs);
        }
      };
      p.addListener(vl);
      p.setVideoSurfaceView(sv);
      fit(p.getVideoSize());
    } catch (Throwable t) {
    }
  }

  /* the whole video is shown, never cut */
  private void fit(VideoSize vs) {
    if (vs == null || vs.width <= 0 || vs.height <= 0 || root.getWidth() <= 0) {
      root.post(new Runnable() {
        @Override
        public void run() {
          androidx.media3.exoplayer.ExoPlayer p = VAlarmService.player;
          if (p != null && p.getVideoSize().width > 0) fit(p.getVideoSize());
        }
      });
      return;
    }
    float ar = vs.width * vs.pixelWidthHeightRatio / vs.height;
    int rw = root.getWidth(), rh = root.getHeight();
    int w = rw, hh = Math.round(rw / ar);
    if (hh > rh) {
      hh = rh;
      w = Math.round(rh * ar);
    }
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hh, Gravity.CENTER);
    sv.setLayoutParams(lp);
  }

  @Override
  protected void onDestroy() {
    try {
      androidx.media3.exoplayer.ExoPlayer p = VAlarmService.player;
      if (p != null) {
        if (vl != null) p.removeListener(vl);
        p.clearVideoSurfaceView(sv);
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
      }
    } catch (Throwable t) {
    }
    if (current == this) current = null;
    super.onDestroy();
  }
}
