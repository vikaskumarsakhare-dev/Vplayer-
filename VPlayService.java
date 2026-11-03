package com.vplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

/* Keeps the video sound playing when the app is in the background.
   It shows the phone's own media card (picture, title, buttons and a seek bar) in the notification area. */
public class VPlayService extends Service {

  private static final String CH = "vplayer_play";
  private static final String A_TOGGLE = "com.vplayer.app.PLAY_TOGGLE";
  private static final String A_CLOSE = "com.vplayer.app.PLAY_CLOSE";
  private static final String A_PREV = "com.vplayer.app.PLAY_PREV";
  private static final String A_NEXT = "com.vplayer.app.PLAY_NEXT";
  private BroadcastReceiver rcv;
  private MediaSession session;
  private String lastPath = "";
  private Bitmap art = null;

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    try {
      if (Build.VERSION.SDK_INT >= 26) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Playing", NotificationManager.IMPORTANCE_LOW));
      }
      rcv = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          String a = i == null ? null : i.getAction();
          if (A_TOGGLE.equals(a)) route("toggle", 0L);
          else if (A_CLOSE.equals(a)) route("close", 0L);
          else if (A_PREV.equals(a)) route("prev", 0L);
          else if (A_NEXT.equals(a)) route("next", 0L);
        }
      };
      IntentFilter f = new IntentFilter();
      f.addAction(A_TOGGLE);
      f.addAction(A_CLOSE);
      f.addAction(A_PREV);
      f.addAction(A_NEXT);
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(rcv, f);
    } catch (Throwable t) {
    }
    try {
      session = new MediaSession(this, "VPlayer");
      session.setCallback(
        new MediaSession.Callback() {
          @Override
          public void onPlay() {
            route("play", 0L);
          }

          @Override
          public void onPause() {
            route("pause", 0L);
          }

          @Override
          public void onSkipToNext() {
            route("next", 0L);
          }

          @Override
          public void onSkipToPrevious() {
            route("prev", 0L);
          }

          @Override
          public void onSeekTo(long pos) {
            route("seek", pos);
          }

          @Override
          public void onFastForward() {
            route("fwd", 0L);
          }

          @Override
          public void onRewind() {
            route("rew", 0L);
          }

          @Override
          public void onStop() {
            route("close", 0L);
          }

          @Override
          public void onCustomAction(String action, Bundle extras) {
            if ("rew10".equals(action)) route("rew", 0L);
            else if ("fwd10".equals(action)) route("fwd", 0L);
          }
        }
      );
      session.setActive(true);
    } catch (Throwable t) {
      session = null;
    }
  }

  /* the buttons go to the short video screen when that one plays in the background, otherwise to the long video player */
  private void route(String cmd, long arg) {
    if (VShortsPlugin.active()) VShortsPlugin.remote(cmd, arg);
    else VLongPlugin.remote(cmd, arg);
  }

  private PendingIntent pi(String a) {
    Intent i = new Intent(a);
    i.setPackage(getPackageName());
    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    return PendingIntent.getBroadcast(this, a.hashCode() & 0xFFFF, i, fl);
  }

  private void loadArt(final String path) {
    if (path == null || path.length() == 0 || path.equals(lastPath)) return;
    lastPath = path;
    art = null;
    new Thread(
      new Runnable() {
        @Override
        public void run() {
          try {
            MediaMetadataRetriever mr = new MediaMetadataRetriever();
            mr.setDataSource(path);
            Bitmap b = mr.getFrameAtTime(3000000L);
            mr.release();
            if (b != null) {
              int w = b.getWidth();
              int h = b.getHeight();
              float k = Math.min(1f, 320f / Math.max(w, h));
              art = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
              if (path.equals(lastPath)) refreshSession(lastTitle, lastPlaying, lastPos, lastDur, lastSpeed);
            }
          } catch (Throwable t) {
          }
        }
      }
    ).start();
  }

  private String lastTitle = "";
  private boolean lastPlaying = true;
  private long lastPos = 0L;
  private long lastDur = 0L;
  private float lastSpeed = 1f;

  private void refreshSession(String title, boolean playing, long pos, long dur, float speed) {
    if (session == null) return;
    try {
      MediaMetadata.Builder mb = new MediaMetadata.Builder();
      mb.putString(MediaMetadata.METADATA_KEY_TITLE, title == null || title.length() == 0 ? "V Player" : title);
      mb.putString(MediaMetadata.METADATA_KEY_ALBUM, "V Player");
      if (dur > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
      if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
      session.setMetadata(mb.build());
      long acts =
        PlaybackState.ACTION_PLAY |
        PlaybackState.ACTION_PAUSE |
        PlaybackState.ACTION_PLAY_PAUSE |
        PlaybackState.ACTION_SEEK_TO |
        PlaybackState.ACTION_SKIP_TO_NEXT |
        PlaybackState.ACTION_SKIP_TO_PREVIOUS |
        PlaybackState.ACTION_STOP;
      PlaybackState.Builder pb = new PlaybackState.Builder();
      pb.setActions(acts);
      pb.addCustomAction(new PlaybackState.CustomAction.Builder("rew10", "Back 10 seconds", R.drawable.ic_rew10).build());
      pb.addCustomAction(new PlaybackState.CustomAction.Builder("fwd10", "Forward 10 seconds", R.drawable.ic_fwd10).build());
      pb.setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? speed : 0f, SystemClock.elapsedRealtime());
      session.setPlaybackState(pb.build());
    } catch (Throwable t) {
    }
  }

  private Notification build(String title, boolean playing) {
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
    b.setSmallIcon(android.R.drawable.ic_media_play);
    b.setContentTitle(title == null || title.length() == 0 ? "V Player" : title);
    b.setContentText(playing ? "Playing" : "Paused");
    b.setOngoing(playing);
    b.setShowWhen(false);
    if (art != null) b.setLargeIcon(art);
    try {
      Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
      if (open != null) {
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentIntent(PendingIntent.getActivity(this, 1, open, fl));
      }
    } catch (Throwable t) {
    }
    b.setDeleteIntent(pi(A_CLOSE));
    b.addAction(android.R.drawable.ic_media_previous, "Previous", pi(A_PREV));
    b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", pi(A_TOGGLE));
    b.addAction(android.R.drawable.ic_media_next, "Next", pi(A_NEXT));
    Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
    try {
      if (session != null) st.setMediaSession(session.getSessionToken());
    } catch (Throwable t) {
    }
    b.setStyle(st);
    if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC);
    return b.build();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    String title = i == null ? "" : i.getStringExtra("title");
    boolean playing = i == null || i.getBooleanExtra("playing", true);
    long pos = i == null ? 0L : i.getLongExtra("pos", 0L);
    long dur = i == null ? 0L : i.getLongExtra("dur", 0L);
    float speed = i == null ? 1f : i.getFloatExtra("speed", 1f);
    String path = i == null ? "" : i.getStringExtra("path");
    lastTitle = title;
    lastPlaying = playing;
    lastPos = pos;
    lastDur = dur;
    lastSpeed = speed;
    loadArt(path);
    refreshSession(title, playing, pos, dur, speed);
    try {
      Notification n = build(title, playing);
      if (Build.VERSION.SDK_INT >= 29) startForeground(7101, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      else startForeground(7101, n);
    } catch (Throwable t) {
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    try {
      if (rcv != null) unregisterReceiver(rcv);
    } catch (Throwable t) {
    }
    try {
      if (session != null) {
        session.setActive(false);
        session.release();
      }
    } catch (Throwable t) {
    }
    try {
      stopForeground(true);
    } catch (Throwable t) {
    }
    super.onDestroy();
  }
}
