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
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;

/* rings the alarm like a song: the chosen part of the video plays again and again with its sound, in the phone's own media card
   (Pause and Play only). The volume is the normal media volume. Tapping the card opens the video. */
public class VAlarmService extends Service {
  static final String CH = "vp_alarm_play";
  static final int NID = 7202;
  private static final String A_TOGGLE = "com.vplayer.app.ALARM_TOGGLE";
  private static final String A_CLOSE = "com.vplayer.app.ALARM_CLOSE";
  static boolean ringing = false;
  /* where the alarm is now, so the video can go on from the same place when the card is tapped */
  static volatile long curPos = 0L, curStart = 0L;
  private final Handler hnd = new Handler(Looper.getMainLooper());
  private final Runnable tickRun = new Runnable() {
    @Override
    public void run() {
      if (!ringing || !playing) return;
      pushState();
      hnd.postDelayed(this, 1000);
    }
  };
  private ExoPlayer player;
  private Ringtone fallback;
  private MediaSession session;
  private BroadcastReceiver rcv;
  private Bitmap art;
  private String title = "", vid = "", path = "";
  private long startMs = 0L;
  private boolean playing = true;
  private boolean inFg = false;
  private Vibrator vibr;

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
        nm.createNotificationChannel(new NotificationChannel(CH, "Alarm", NotificationManager.IMPORTANCE_LOW));
      }
      rcv = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          String a = i == null ? null : i.getAction();
          if (A_TOGGLE.equals(a)) setPlaying(!playing);
          else if (A_CLOSE.equals(a)) stopAlarm();
        }
      };
      IntentFilter f = new IntentFilter();
      f.addAction(A_TOGGLE);
      f.addAction(A_CLOSE);
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(rcv, f);
    } catch (Throwable t) {
    }
    try {
      session = new MediaSession(this, "VAlarm");
      session.setCallback(
        new MediaSession.Callback() {
          @Override
          public void onPlay() {
            setPlaying(true);
          }

          @Override
          public void onPause() {
            setPlaying(false);
          }

          @Override
          public void onStop() {
            stopAlarm();
          }
        }
      );
      session.setActive(true);
    } catch (Throwable t) {
      session = null;
    }
  }

  private PendingIntent pi(String a) {
    Intent i = new Intent(a);
    i.setPackage(getPackageName());
    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    return PendingIntent.getBroadcast(this, a.hashCode() & 0xFFFF, i, fl);
  }

  @Override
  public int onStartCommand(Intent in, int flags, int startId) {
    String act = in == null ? null : in.getAction();
    if ("pause".equals(act)) {
      if (ringing) setPlaying(false);
      else if (!inFg) stopSelf();
      return START_NOT_STICKY;
    }
    if ("close".equals(act)) {
      stopAlarm();
      return START_NOT_STICKY;
    }
    if (in == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    startRinging(in);
    return START_NOT_STICKY;
  }

  private void startRinging(Intent in) {
    releasePlayer();
    path = in.getStringExtra("path");
    title = in.getStringExtra("title");
    vid = in.getStringExtra("vid");
    if (path == null) path = "";
    if (title == null) title = "";
    if (vid == null) vid = "";
    long s = in.getLongExtra("s", 0L), e = in.getLongExtra("e", 0L);
    startMs = s;
    curStart = s;
    curPos = 0L;
    ringing = true;
    playing = true;
    art = null;
    shake(true);
    render();
    loadArt();
    /* the volume it starts with, from the Alarm settings */
    try {
      int v = VAlarmStore.startVol(this);
      if (v >= 0) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(mx * Math.min(100, v) / 100f), 0);
      }
    } catch (Throwable t) {
    }
    boolean ok = false;
    try {
      if (path.length() > 0 && new File(path).exists()) {
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
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

          /* the length is known once it is ready, and the line starts again each time the part repeats */
          @Override
          public void onPlaybackStateChanged(int st) {
            if (st == Player.STATE_READY && ringing) render();
          }

          @Override
          public void onPositionDiscontinuity(Player.PositionInfo a, Player.PositionInfo b, int reason) {
            if (ringing && playing) render();
          }

          /* a phone call or another app took the sound: the card shows Play */
          @Override
          public void onPlayWhenReadyChanged(boolean pw, int reason) {
            if (pw != playing && ringing && fallback == null) {
              playing = pw;
              render();
            }
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
  }

  private void ringFallback() {
    try {
      if (fallback != null) return;
      Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
      if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
      fallback = RingtoneManager.getRingtone(this, u);
      if (fallback != null) {
        if (Build.VERSION.SDK_INT >= 28) fallback.setLooping(true);
        fallback.play();
      }
    } catch (Throwable t) {
    }
  }

  /* shakes again and again while the alarm is playing, if it is switched on in the settings */
  private void shake(boolean on) {
    try {
      if (vibr == null) vibr = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      if (vibr == null) return;
      if (!on || !VAlarmStore.vib(this)) {
        vibr.cancel();
        return;
      }
      long[] pat = new long[] {0, 700, 500};
      if (Build.VERSION.SDK_INT >= 26) {
        android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build();
        vibr.vibrate(VibrationEffect.createWaveform(pat, 0), aa);
      } else vibr.vibrate(pat, 0);
    } catch (Throwable t) {
    }
  }

  private void setPlaying(boolean p) {
    if (!ringing) return;
    playing = p;
    shake(p);
    try {
      if (player != null) player.setPlayWhenReady(p);
    } catch (Throwable t) {
    }
    try {
      if (fallback != null) {
        if (p) fallback.play();
        else fallback.stop();
      }
    } catch (Throwable t) {
    }
    render();
  }

  private void loadArt() {
    final String p = path;
    if (p.length() == 0) return;
    new Thread(
      new Runnable() {
        @Override
        public void run() {
          try {
            MediaMetadataRetriever mr = new MediaMetadataRetriever();
            mr.setDataSource(p);
            Bitmap b = mr.getFrameAtTime(startMs * 1000L);
            mr.release();
            if (b != null) {
              float k = Math.min(1f, 320f / Math.max(b.getWidth(), b.getHeight()));
              art = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
              if (ringing && p.equals(path)) render();
            }
          } catch (Throwable t) {
          }
        }
      }
    ).start();
  }

  private Notification build() {
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm);
    b.setContentTitle(title.length() == 0 ? "Alarm" : title);
    b.setContentText(playing ? "Alarm" : "Alarm paused");
    b.setOngoing(playing);
    b.setShowWhen(false);
    if (art != null) b.setLargeIcon(art);
    try {
      /* tapping the card opens the app on this video */
      Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
      if (open != null) {
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        open.putExtra("vp_alarm_vid", vid);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentIntent(PendingIntent.getActivity(this, 21, open, fl));
      }
    } catch (Throwable t) {
    }
    /* taking the card away ends the alarm */
    b.setDeleteIntent(pi(A_CLOSE));
    b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", pi(A_TOGGLE));
    Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0);
    try {
      if (session != null) st.setMediaSession(session.getSessionToken());
    } catch (Throwable t) {
    }
    b.setStyle(st);
    if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC);
    return b.build();
  }

  /* tells the phone where the alarm is now (the line in the card), every second while it plays */
  private void pushState() {
    long pos = 0L;
    try {
      if (player != null) pos = Math.max(0L, player.getCurrentPosition());
    } catch (Throwable t) {
    }
    curPos = pos;
    try {
      if (session == null) return;
      PlaybackState.Builder pb = new PlaybackState.Builder();
      pb.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP);
      pb.setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f, SystemClock.elapsedRealtime());
      session.setPlaybackState(pb.build());
    } catch (Throwable t) {
    }
  }

  /* shows the card; while it plays it is held by the service, once paused it can be swiped away */
  private void render() {
    try {
      if (session != null) {
        MediaMetadata.Builder mb = new MediaMetadata.Builder();
        mb.putString(MediaMetadata.METADATA_KEY_TITLE, title.length() == 0 ? "Alarm" : title);
        mb.putString(MediaMetadata.METADATA_KEY_ALBUM, "Alarm");
        if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        long dur = 0L, pos = 0L;
        try {
          if (player != null) {
            dur = player.getDuration();
            pos = player.getCurrentPosition();
          }
        } catch (Throwable t0) {
        }
        if (dur > 0L) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        session.setMetadata(mb.build());
        pushState();
      }
    } catch (Throwable t) {
    }
    hnd.removeCallbacks(tickRun);
    if (playing && ringing) hnd.postDelayed(tickRun, 1000);
    try {
      Notification n = build();
      if (playing) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NID, n);
        inFg = true;
      } else {
        if (inFg) {
          if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH);
          else stopForeground(false);
          inFg = false;
        }
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NID, n);
      }
    } catch (Throwable t) {
      if (playing) stopSelf();
    }
  }

  private void releasePlayer() {
    try {
      if (player != null) player.release();
    } catch (Throwable t) {
    }
    player = null;
    try {
      if (fallback != null) fallback.stop();
    } catch (Throwable t) {
    }
    fallback = null;
  }

  private void stopAlarm() {
    hnd.removeCallbacks(tickRun);
    shake(false);
    releasePlayer();
    ringing = false;
    try {
      stopForeground(true);
    } catch (Throwable t) {
    }
    inFg = false;
    try {
      ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID);
    } catch (Throwable t) {
    }
    stopSelf();
  }

  @Override
  public void onDestroy() {
    hnd.removeCallbacks(tickRun);
    shake(false);
    releasePlayer();
    ringing = false;
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
    super.onDestroy();
  }
}
