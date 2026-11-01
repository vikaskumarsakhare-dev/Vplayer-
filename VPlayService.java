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
import android.os.Build;
import android.os.IBinder;

/* Keeps the video sound playing when the app is in the background and shows the notification with buttons. */
public class VPlayService extends Service {

  private static final String CH = "vplayer_play";
  private static final String A_TOGGLE = "com.vplayer.app.PLAY_TOGGLE";
  private static final String A_CLOSE = "com.vplayer.app.PLAY_CLOSE";
  private BroadcastReceiver rcv;

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
          if (A_TOGGLE.equals(a)) VLongPlugin.remote("toggle");
          else if (A_CLOSE.equals(a)) VLongPlugin.remote("close");
        }
      };
      IntentFilter f = new IntentFilter();
      f.addAction(A_TOGGLE);
      f.addAction(A_CLOSE);
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(rcv, f);
    } catch (Throwable t) {
    }
  }

  private PendingIntent pi(String a) {
    Intent i = new Intent(a);
    i.setPackage(getPackageName());
    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    return PendingIntent.getBroadcast(this, a.hashCode() & 0xFFFF, i, fl);
  }

  private Notification build(String title, boolean playing) {
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
    b.setSmallIcon(android.R.drawable.ic_media_play);
    b.setContentTitle(title == null || title.length() == 0 ? "V Player" : title);
    b.setContentText(playing ? "Playing" : "Paused");
    b.setOngoing(playing);
    b.setShowWhen(false);
    try {
      Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
      if (open != null) {
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentIntent(PendingIntent.getActivity(this, 1, open, fl));
      }
    } catch (Throwable t) {
    }
    b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", pi(A_TOGGLE));
    b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", pi(A_CLOSE));
    b.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1));
    if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC);
    return b.build();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    String title = i == null ? "" : i.getStringExtra("title");
    boolean playing = i == null || i.getBooleanExtra("playing", true);
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
      stopForeground(true);
    } catch (Throwable t) {
    }
    super.onDestroy();
  }
}
