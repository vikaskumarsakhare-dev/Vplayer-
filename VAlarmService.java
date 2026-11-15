package com.vplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;

/* rings the alarm: plays the chosen part of the video again and again with its sound, and keeps one notification with a Stop button */
public class VAlarmService extends Service {
  static final String CH = "vp_alarm";
  static ExoPlayer player;
  static boolean ringing = false;
  private Ringtone fallback;
  private PowerManager.WakeLock wl;
  private final Handler h = new Handler(Looper.getMainLooper());
  private final Runnable giveUp = new Runnable() {
    @Override
    public void run() {
      stopAlarm();
    }
  };

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public int onStartCommand(Intent in, int flags, int startId) {
    if (in != null && "stop".equals(in.getAction())) {
      stopAlarm();
      return START_NOT_STICKY;
    }
    if (in == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (ringing) return START_NOT_STICKY;
    ringing = true;
    String path = in.getStringExtra("path");
    String title = in.getStringExtra("title");
    long s = in.getLongExtra("s", 0L), e = in.getLongExtra("e", 0L);
    showNotification(title == null ? "" : title);
    /* the video comes on the full screen even while the phone is in use (needs "show over other apps"); Stop then goes back to what was open before */
    try {
      if (Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)) {
        Intent ai = new Intent(this, VAlarmActivity.class);
        ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(ai);
      }
    } catch (Throwable t) {
    }
    try {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vplayer:alarm");
      wl.acquire(11 * 60 * 1000L);
    } catch (Throwable t) {
    }
    boolean ok = false;
    try {
      if (path != null && path.length() > 0 && new File(path).exists()) {
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
        MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.fromFile(new File(path)));
        if (e > s) {
          mb.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s).setEndPositionMs(e).build());
        } else if (s > 0) {
          mb.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s).build());
        }
        player.setMediaItem(mb.build());
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(1f);
        player.addListener(new Player.Listener() {
          @Override
          public void onPlayerError(PlaybackException err) {
            ringFallback();
          }
        });
        player.prepare();
        player.setPlayWhenReady(true);
        ok = true;
      }
    } catch (Throwable t) {
      ok = false;
    }
    /* the video is missing or cannot play: the phone's normal alarm sound rings instead, so the alarm is never missed */
    if (!ok) ringFallback();
    h.postDelayed(giveUp, 10 * 60 * 1000L);
    return START_NOT_STICKY;
  }

  private void ringFallback() {
    try {
      if (fallback != null) return;
      Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
      if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
      fallback = RingtoneManager.getRingtone(this, u);
      if (fallback != null) {
        if (Build.VERSION.SDK_INT >= 28) fallback.setLooping(true);
        if (Build.VERSION.SDK_INT >= 21) {
          fallback.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
        }
        fallback.play();
      }
    } catch (Throwable t) {
    }
  }

  private void showNotification(String title) {
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = new NotificationChannel(CH, "Alarm", NotificationManager.IMPORTANCE_HIGH);
      ch.setSound(null, null);
      ch.enableVibration(false);
      ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      nm.createNotificationChannel(ch);
    }
    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    Intent ai = new Intent(this, VAlarmActivity.class);
    ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent full = PendingIntent.getActivity(this, 11, ai, fl);
    Intent si = new Intent(this, VAlarmService.class);
    si.setAction("stop");
    PendingIntent stop = PendingIntent.getService(this, 12, si, fl);
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm);
    b.setContentTitle("Alarm");
    b.setContentText(title);
    b.setOngoing(true);
    b.setCategory(Notification.CATEGORY_ALARM);
    b.setContentIntent(full);
    b.setFullScreenIntent(full, true);
    b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build());
    if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX);
    Notification n = b.build();
    if (Build.VERSION.SDK_INT >= 29) startForeground(4711, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    else startForeground(4711, n);
  }

  private void stopAlarm() {
    h.removeCallbacks(giveUp);
    try {
      if (player != null) {
        player.release();
      }
    } catch (Throwable t) {
    }
    player = null;
    try {
      if (fallback != null) fallback.stop();
    } catch (Throwable t) {
    }
    fallback = null;
    try {
      if (wl != null && wl.isHeld()) wl.release();
    } catch (Throwable t) {
    }
    ringing = false;
    VAlarmActivity cur = VAlarmActivity.current;
    if (cur != null) cur.finish();
    try {
      stopForeground(true);
    } catch (Throwable t) {
    }
    stopSelf();
  }

  @Override
  public void onDestroy() {
    if (ringing) stopAlarm();
    super.onDestroy();
  }
}
