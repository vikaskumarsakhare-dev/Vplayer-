package com.vplayer.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * "Share" from Instagram or YouTube ends here. This screen is invisible: it reads the link,
 * starts the download in the background and closes at once, so the other app is not disturbed.
 */
public class VDownShare extends Activity {

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    try {
      handle(getIntent());
    } catch (Throwable t) {
    }
    finish();
    overridePendingTransition(0, 0);
  }

  private void handle(Intent in) {
    if (in == null) return;
    String text = in.getStringExtra(Intent.EXTRA_TEXT);
    if (text == null) text = "";
    Matcher m = Pattern.compile("https?://[^\\s]+").matcher(text);
    if (!m.find()) {
      Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show();
      return;
    }
    String url = m.group();
    String host = "";
    try {
      host = new java.net.URL(url).getHost().toLowerCase();
    } catch (Throwable t) {
    }
    boolean yt = host.contains("youtube.com") || host.contains("youtu.be");
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    boolean canNote = true;
    try {
      canNote = Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled();
    } catch (Throwable t) {
    }
    if (yt && canNote) {
      ask(url, nm);
      Toast.makeText(this, "V Player: choose Video or Audio in the notification", Toast.LENGTH_SHORT).show();
    } else {
      Intent i = new Intent(this, VDownService.class);
      i.setAction(VDownService.A_START);
      i.putExtra("url", url);
      i.putExtra("mode", "video");
      VDownPlugin.startService(this, i);
      Toast.makeText(this, "V Player: download started", Toast.LENGTH_SHORT).show();
    }
  }

  private PendingIntent pi(String url, String mode, int rc) {
    Intent i = new Intent(this, VDownService.class);
    i.setAction(VDownService.A_START);
    i.putExtra("url", url);
    i.putExtra("mode", mode);
    int fl = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
    if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, rc, i, fl);
    return PendingIntent.getService(this, rc, i, fl);
  }

  private void ask(String url, NotificationManager nm) {
    if (Build.VERSION.SDK_INT >= 26) {
      try {
        nm.createNotificationChannel(new NotificationChannel(VDownService.CH_ASK, "Download question", NotificationManager.IMPORTANCE_HIGH));
      } catch (Throwable t) {
      }
    }
    Notification.Builder b = VDownService.builder(this, VDownService.CH_ASK);
    b.setSmallIcon(android.R.drawable.stat_sys_download);
    b.setContentTitle("Download from YouTube");
    b.setContentText("What do you want to save?");
    b.setAutoCancel(true);
    if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
    b.addAction(android.R.drawable.ic_menu_slideshow, "Video", pi(url, "video", 1));
    b.addAction(android.R.drawable.ic_media_play, "Audio", pi(url, "audio", 2));
    if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(120000L);
    nm.notify(VDownService.N_ASK, b.build());
  }
}
