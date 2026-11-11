package com.vplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Downloads a video or a song from a link (YouTube, Instagram or a plain file link) in the background.
 * The app does not have to be open. Settings come from the "vdl" preferences.
 */
public class VDownService extends Service {

  static final String CH_RUN = "vdl_run";
  static final String CH_ASK = "vdl_ask";
  static final String CH_DONE = "vdl_done";
  static final String A_START = "com.vplayer.app.DL_START";
  static final String A_CANCEL = "com.vplayer.app.DL_CANCEL";
  static final int N_RUN = 7002;
  static final int N_ASK = 7001;

  /* all jobs, newest last; saved to the preferences when a job changes state */
  static final Map<String, JSONObject> JOBS = new LinkedHashMap<String, JSONObject>();
  static final Set<String> CANCELLED = Collections.synchronizedSet(new HashSet<String>());
  static boolean loaded = false;

  private ExecutorService pool;
  private int active = 0;
  private long lastNote = 0L;
  private final Object lock = new Object();

  /* ---------- jobs list ---------- */

  static synchronized void load(Context c) {
    if (loaded) return;
    loaded = true;
    try {
      String s = c.getSharedPreferences("vdl", Context.MODE_PRIVATE).getString("hist", "[]");
      JSONArray a = new JSONArray(s);
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.optJSONObject(i);
        if (o == null) continue;
        String st = o.optString("state", "");
        if ("run".equals(st) || "queued".equals(st)) {
          o.put("state", "error");
          o.put("msg", "Stopped before it finished");
        }
        JOBS.put(o.optString("id", "x" + i), o);
      }
    } catch (Throwable t) {
    }
  }

  static synchronized void save(Context c) {
    try {
      JSONArray a = new JSONArray();
      List<String> keys = new ArrayList<String>(JOBS.keySet());
      int from = Math.max(0, keys.size() - 40);
      for (int i = from; i < keys.size(); i++) a.put(JOBS.get(keys.get(i)));
      c.getSharedPreferences("vdl", Context.MODE_PRIVATE).edit().putString("hist", a.toString()).apply();
    } catch (Throwable t) {
    }
  }

  static synchronized JSONObject addJob(Context c, String id, String url, String mode) {
    load(c);
    JSONObject o = new JSONObject();
    try {
      o.put("id", id);
      o.put("url", url);
      o.put("mode", mode);
      o.put("state", "queued");
      o.put("pct", 0);
      o.put("title", "");
      o.put("res", "");
      o.put("msg", "");
      o.put("mediaId", "");
      o.put("kind", "audio".equals(mode) ? "audio" : "video");
      o.put("time", System.currentTimeMillis());
    } catch (Throwable t) {
    }
    JOBS.put(id, o);
    save(c);
    return o;
  }

  static synchronized JSONArray snapshot(Context c) {
    load(c);
    JSONArray a = new JSONArray();
    List<String> keys = new ArrayList<String>(JOBS.keySet());
    for (int i = keys.size() - 1; i >= 0; i--) a.put(JOBS.get(keys.get(i)));
    return a;
  }

  private void put(JSONObject j, String k, Object v) {
    try {
      j.put(k, v);
    } catch (Throwable t) {
    }
  }

  /* ---------- service ---------- */

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    pool = Executors.newFixedThreadPool(2);
    load(this);
    channels();
  }

  @Override
  public void onDestroy() {
    try {
      pool.shutdownNow();
    } catch (Throwable t) {
    }
    super.onDestroy();
  }

  private void channels() {
    if (Build.VERSION.SDK_INT < 26) return;
    try {
      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Downloads running", NotificationManager.IMPORTANCE_LOW));
      nm.createNotificationChannel(new NotificationChannel(CH_ASK, "Download question", NotificationManager.IMPORTANCE_HIGH));
      nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Downloads finished", NotificationManager.IMPORTANCE_DEFAULT));
    } catch (Throwable t) {
    }
  }

  static Notification.Builder builder(Context c, String ch) {
    if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, ch);
    return new Notification.Builder(c);
  }

  private PendingIntent openApp() {
    try {
      Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
      if (i == null) return null;
      int fl = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
      return PendingIntent.getActivity(this, 0, i, fl);
    } catch (Throwable t) {
      return null;
    }
  }

  private Notification runNote(String text, int pct) {
    Notification.Builder b = builder(this, CH_RUN);
    b.setSmallIcon(android.R.drawable.stat_sys_download);
    b.setContentTitle("V Player download");
    b.setContentText(text);
    b.setOngoing(true);
    b.setOnlyAlertOnce(true);
    if (pct >= 0) b.setProgress(100, pct, false);
    else b.setProgress(0, 0, true);
    try {
      Intent ci = new Intent(this, VDownService.class);
      ci.setAction(A_CANCEL);
      int fl = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
      b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", PendingIntent.getService(this, 7, ci, fl));
    } catch (Throwable t) {
    }
    PendingIntent pi = openApp();
    if (pi != null) b.setContentIntent(pi);
    return b.build();
  }

  private void startFg(String text) {
    Notification n = runNote(text, -1);
    try {
      if (Build.VERSION.SDK_INT >= 29) startForeground(N_RUN, n, 1);
      else startForeground(N_RUN, n);
    } catch (Throwable t) {
      try {
        startForeground(N_RUN, n);
      } catch (Throwable t2) {
      }
    }
  }

  private void note(String text, int pct, boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - lastNote < 700L) return;
    lastNote = now;
    try {
      ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(N_RUN, runNote(text, pct));
    } catch (Throwable t) {
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      finishIfIdle();
      return START_NOT_STICKY;
    }
    String act = intent.getAction();
    if (A_CANCEL.equals(act)) {
      String id = intent.getStringExtra("id");
      synchronized (JOBS) {
        for (Map.Entry<String, JSONObject> e : JOBS.entrySet()) {
          String st = e.getValue().optString("state", "");
          if ((id == null || id.equals(e.getKey())) && ("run".equals(st) || "queued".equals(st))) CANCELLED.add(e.getKey());
        }
      }
      return START_NOT_STICKY;
    }
    if (A_START.equals(act)) {
      final String url = intent.getStringExtra("url");
      String mode = intent.getStringExtra("mode");
      String id = intent.getStringExtra("id");
      if (mode == null) mode = "video";
      if (url == null || url.length() == 0) {
        finishIfIdle();
        return START_NOT_STICKY;
      }
      try {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(N_ASK);
      } catch (Throwable t) {
      }
      if (id == null || !JOBS.containsKey(id)) {
        if (id == null) id = "d" + System.currentTimeMillis() + "x" + (int) (Math.random() * 1000);
        addJob(this, id, url, mode);
      }
      final String jid = id;
      final String jmode = mode;
      synchronized (lock) {
        active++;
      }
      startFg("Starting…");
      pool.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              runJob(jid, url, jmode);
            } finally {
              synchronized (lock) {
                active--;
              }
              finishIfIdle();
            }
          }
        }
      );
    }
    return START_NOT_STICKY;
  }

  private void finishIfIdle() {
    boolean idle;
    synchronized (lock) {
      idle = active <= 0;
    }
    if (idle) {
      try {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
      } catch (Throwable t) {
      }
      stopSelf();
    }
  }

  /* ---------- one job ---------- */

  static class Cancel extends IOException {
    Cancel() {
      super("cancelled");
    }
  }

  static class Stream {
    String url = "";
    String mime = "";
    String codecs = "";
    int w = 0, h = 0, bitrate = 0;
    long size = 0L;
  }

  static class Info {
    String title = "";
    String source = "";
    List<String> thumbs = new ArrayList<String>();
    List<Stream> video = new ArrayList<Stream>(); /* video only (adaptive) */
    List<Stream> audio = new ArrayList<Stream>(); /* sound only (adaptive) */
    List<Stream> both = new ArrayList<Stream>(); /* video with sound */
    String direct = null; /* one file with everything */
    String directMime = "video/mp4";
    int directH = 0;
    boolean audioContent = false;
    String ua = "Mozilla/5.0";
    boolean ranged = false;
    String error = "";
  }

  static class Prog {
    long total = 0L, done = 0L;
    int base = 0, span = 100;
  }

  private void check(String id) throws Cancel {
    if (CANCELLED.contains(id) || Thread.currentThread().isInterrupted()) throw new Cancel();
  }

  private void runJob(String id, String url, String mode) {
    JSONObject j = JOBS.get(id);
    if (j == null) return;
    File tmp = new File(getCacheDir(), "vdl" + id);
    try {
      tmp.mkdirs();
      put(j, "state", "run");
      save(this);
      note("Looking at the link…", -1, true);
      check(id);
      SharedPreferences cfg = getSharedPreferences("vdl", Context.MODE_PRIVATE);
      int maxRes = cfg.getInt("maxRes", 1080);
      String aFmt = cfg.getString("aFmt", "m4a");
      String aQ = cfg.getString("aQ", "high");

      Info info = resolve(url);
      check(id);
      if (info.error.length() > 0 && info.direct == null && info.video.isEmpty() && info.both.isEmpty() && info.audio.isEmpty()) {
        throw new IOException(info.error);
      }
      String title = clean(info.title);
      put(j, "title", title);
      boolean wantAudio = "audio".equals(mode) || info.audioContent;
      put(j, "kind", wantAudio ? "audio" : "video");
      note(title, 0, true);

      Prog pr = new Prog();
      File out;
      String mime;
      String ext;
      String resText;
      String msg = "";
      Stream vs = null, as = null;
      if (info.direct != null) {
        out = new File(tmp, "file.bin");
        pr.total = 0L;
        download(id, info.direct, out, 0L, pr, info.ua, false, title, j);
        mime = info.directMime;
        ext = extOf(mime, info.direct);
        wantAudio = mime.startsWith("audio/");
        put(j, "kind", wantAudio ? "audio" : "video");
        resText = info.directH > 0 ? info.directH + "p" : "Original quality";
      } else if (wantAudio) {
        as = pickAudio(info.audio, aFmt, aQ);
        if (as == null && !info.both.isEmpty()) {
          /* only a video with sound exists: keep it, the player plays the sound */
          vs = pickBoth(info.both, 0);
        }
        if (as == null && vs == null) throw new IOException("No sound found for this link");
        Stream use = as != null ? as : vs;
        out = new File(tmp, "audio.bin");
        pr.total = use.size;
        download(id, use.url, out, use.size, pr, info.ua, true, title, j);
        mime = use.mime.startsWith("audio/webm") ? "audio/webm" : (use.mime.startsWith("audio/") ? "audio/mp4" : "video/mp4");
        ext = mime.equals("audio/webm") ? ".webm" : (mime.equals("audio/mp4") ? ".m4a" : ".mp4");
        resText = use.bitrate > 0 ? (mime.equals("audio/webm") ? "Opus " : "M4A ") + (use.bitrate / 1000) + " kbps" : (mime.equals("audio/webm") ? "Opus" : "M4A");
        if (use == vs) {
          resText = vs.h + "p (sound only is kept as video)";
          put(j, "kind", "video");
        }
      } else {
        vs = pickVideo(info.video, maxRes);
        as = pickAudio(info.audio, "m4a", "high");
        if (vs != null && as != null && Build.VERSION.SDK_INT >= 18) {
          File vf = new File(tmp, "v.bin");
          File af = new File(tmp, "a.bin");
          pr.total = vs.size + as.size;
          download(id, vs.url, vf, vs.size, pr, info.ua, true, title, j);
          download(id, as.url, af, as.size, pr, info.ua, true, title, j);
          check(id);
          note(title + " (joining)", 96, true);
          out = new File(tmp, "out.mp4");
          mux(vf, af, out);
          mime = "video/mp4";
          ext = ".mp4";
          resText = vs.h + "p";
          if (maxRes > 0 && vs.h != maxRes) msg = maxRes + "p is not available, saved in " + vs.h + "p";
        } else {
          Stream b = pickBoth(info.both, maxRes);
          if (b == null) {
            if (vs != null) b = vs;
            else throw new IOException("No video found for this link");
          }
          out = new File(tmp, "prog.bin");
          pr.total = b.size;
          download(id, b.url, out, b.size, pr, info.ua, true, title, j);
          mime = "video/mp4";
          ext = ".mp4";
          resText = b.h > 0 ? b.h + "p" : "Original quality";
          if (maxRes > 0 && b.h > 0 && b.h != maxRes) msg = maxRes + "p is not available, saved in " + b.h + "p";
        }
      }
      check(id);
      put(j, "res", resText);
      if (msg.length() > 0) put(j, "msg", msg);
      note(title + " (saving)", 99, true);

      String mediaId = saveMedia(out, title + ext, mime, title);
      put(j, "mediaId", mediaId);
      try {
        saveThumb(info, mediaId);
      } catch (Throwable t) {
      }
      put(j, "pct", 100);
      put(j, "state", "done");
      save(this);
      done(title, resText, msg, true);
    } catch (Cancel c) {
      put(j, "state", "cancel");
      put(j, "msg", "Cancelled");
      save(this);
      CANCELLED.remove(id);
    } catch (Throwable t) {
      String m = t.getMessage();
      if (m == null || m.length() == 0) m = t.toString();
      put(j, "state", "error");
      put(j, "msg", m.length() > 140 ? m.substring(0, 140) : m);
      save(this);
      done(j.optString("title", "Download"), "", m, false);
    } finally {
      deleteTree(tmp);
    }
  }

  private void done(String title, String res, String msg, boolean ok) {
    try {
      Notification.Builder b = builder(this, CH_DONE);
      b.setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error);
      b.setContentTitle(ok ? "Download finished" : "Download failed");
      String t = (title == null || title.length() == 0 ? "" : title + "\n");
      if (ok) t += "Saved in " + res + (msg.length() > 0 ? "\n" + msg : "");
      else t += msg;
      b.setContentText(t.replace('\n', ' '));
      b.setStyle(new Notification.BigTextStyle().bigText(t));
      b.setAutoCancel(true);
      PendingIntent pi = openApp();
      if (pi != null) b.setContentIntent(pi);
      ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify((int) (System.currentTimeMillis() % 100000) + 8000, b.build());
    } catch (Throwable t) {
    }
  }

  /* ---------- choosing the streams ---------- */

  private int bestHeight(List<Stream> l) {
    int b = 0;
    for (Stream s : l) b = Math.max(b, s.h);
    return b;
  }

  /* the best picture that is not taller than the wish; if all are taller, the smallest one */
  private Stream pickVideo(List<Stream> l, int maxRes) {
    List<Stream> c = new ArrayList<Stream>();
    for (Stream s : l) {
      boolean mp4 = s.mime.startsWith("video/mp4") && (s.codecs.length() == 0 || s.codecs.startsWith("avc1") || s.codecs.startsWith("hev1") || s.codecs.startsWith("hvc1"));
      if (mp4 && s.url.length() > 0) c.add(s);
    }
    if (c.isEmpty()) return null;
    Collections.sort(
      c,
      new Comparator<Stream>() {
        @Override
        public int compare(Stream a, Stream b) {
          if (a.h != b.h) return a.h < b.h ? -1 : 1;
          boolean ah = a.codecs.startsWith("avc1"), bh = b.codecs.startsWith("avc1");
          if (ah != bh) return ah ? 1 : -1;
          return a.bitrate < b.bitrate ? -1 : (a.bitrate > b.bitrate ? 1 : 0);
        }
      }
    );
    if (maxRes <= 0) return c.get(c.size() - 1);
    Stream best = null;
    for (Stream s : c) if (s.h <= maxRes) best = s;
    return best != null ? best : c.get(0);
  }

  private Stream pickBoth(List<Stream> l, int maxRes) {
    Stream best = null, low = null;
    for (Stream s : l) {
      if (s.url.length() == 0) continue;
      if (low == null || s.h < low.h) low = s;
      if (maxRes <= 0 || s.h <= maxRes) if (best == null || s.h > best.h) best = s;
    }
    return best != null ? best : low;
  }

  private Stream pickAudio(List<Stream> l, String fmt, String q) {
    List<Stream> c = new ArrayList<Stream>();
    boolean opus = "opus".equals(fmt);
    for (int pass = 0; pass < 2 && c.isEmpty(); pass++) {
      for (Stream s : l) {
        if (s.url.length() == 0) continue;
        boolean isWebm = s.mime.startsWith("audio/webm");
        boolean isMp4 = s.mime.startsWith("audio/mp4");
        if (pass == 0 ? (opus ? isWebm : isMp4) : (isWebm || isMp4)) c.add(s);
      }
    }
    if (c.isEmpty()) return null;
    Collections.sort(
      c,
      new Comparator<Stream>() {
        @Override
        public int compare(Stream a, Stream b) {
          return a.bitrate < b.bitrate ? -1 : (a.bitrate > b.bitrate ? 1 : 0);
        }
      }
    );
    if ("low".equals(q)) return c.get(0);
    if ("mid".equals(q)) return c.get(c.size() / 2);
    return c.get(c.size() - 1);
  }

  /* ---------- finding the streams ---------- */

  private Info resolve(String url) throws IOException {
    String u = url.trim();
    String host = "";
    try {
      host = new URL(u).getHost().toLowerCase();
    } catch (Throwable t) {
      throw new IOException("This is not a link");
    }
    if (host.contains("youtube.com") || host.contains("youtu.be") || host.contains("youtube-nocookie.com")) return ytResolve(u);
    if (host.contains("instagram.com")) return igResolve(u);
    return directResolve(u);
  }

  private static final String DESK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

  private Info directResolve(String u) throws IOException {
    Info info = new Info();
    info.source = "link";
    HttpURLConnection c = open(u, "HEAD", DESK_UA);
    int code = c.getResponseCode();
    String ct = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
    c.disconnect();
    String path = u;
    try {
      path = new URL(u).getPath();
    } catch (Throwable t) {
    }
    String low = path.toLowerCase();
    boolean ext = low.matches(".*\\.(mp4|m4v|mov|webm|mkv|mp3|m4a|aac|ogg|opus|wav|flac|3gp)$");
    if (code >= 400 && !ext) throw new IOException("Could not open this link (" + code + ")");
    if (!(ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("octet-stream") || ext)) throw new IOException("This link has no video or song");
    String mime = ct.startsWith("video/") || ct.startsWith("audio/") ? ct.split(";")[0].trim() : mimeFromExt(low);
    info.direct = u;
    info.directMime = mime;
    info.audioContent = mime.startsWith("audio/");
    String name = path.substring(path.lastIndexOf('/') + 1);
    try {
      name = java.net.URLDecoder.decode(name, "UTF-8");
    } catch (Throwable t) {
    }
    int d = name.lastIndexOf('.');
    if (d > 0) name = name.substring(0, d);
    info.title = name.length() > 0 ? name : "download";
    info.ua = DESK_UA;
    return info;
  }

  private String mimeFromExt(String low) {
    if (low.endsWith(".mp3")) return "audio/mpeg";
    if (low.endsWith(".m4a") || low.endsWith(".aac")) return "audio/mp4";
    if (low.endsWith(".ogg") || low.endsWith(".opus")) return "audio/ogg";
    if (low.endsWith(".wav")) return "audio/x-wav";
    if (low.endsWith(".flac")) return "audio/flac";
    if (low.endsWith(".webm")) return "video/webm";
    if (low.endsWith(".mkv")) return "video/x-matroska";
    if (low.endsWith(".3gp")) return "video/3gpp";
    return "video/mp4";
  }

  private String extOf(String mime, String url) {
    if (mime.equals("audio/mpeg")) return ".mp3";
    if (mime.equals("audio/mp4")) return ".m4a";
    if (mime.equals("audio/ogg")) return ".ogg";
    if (mime.equals("audio/x-wav")) return ".wav";
    if (mime.equals("audio/flac")) return ".flac";
    if (mime.equals("audio/webm")) return ".webm";
    if (mime.equals("video/webm")) return ".webm";
    if (mime.equals("video/x-matroska")) return ".mkv";
    if (mime.equals("video/3gpp")) return ".3gp";
    return ".mp4";
  }

  /* ----- YouTube: asks the same service the YouTube app asks ----- */

  private static final String[][] YT_CLIENTS = {
    { "ANDROID_VR", "1.65.10", "28", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"androidSdkVersion\":32,\"osName\":\"Android\",\"osVersion\":\"12L\"" },
    { "IOS", "20.10.4", "5", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)", "\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\",\"osVersion\":\"18.3.2.22D82\"" },
    { "ANDROID", "20.10.38", "3", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip", "\"androidSdkVersion\":30,\"osName\":\"Android\",\"osVersion\":\"11\"" },
    { "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85", "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15", "" }
  };


  private String ytId(String u) {
    Matcher m = Pattern.compile("(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/|/v/)([A-Za-z0-9_-]{11})").matcher(u);
    return m.find() ? m.group(1) : "";
  }

  private Info ytResolve(String u) throws IOException {
    String vid = ytId(u);
    if (vid.length() == 0) throw new IOException("This YouTube link has no video");
    String lastErr = "YouTube did not give the video";
    StringBuilder tried = new StringBuilder();
    for (String[] cl : YT_CLIENTS) {
      try {
        String body =
          "{\"context\":{\"client\":{\"clientName\":\"" + cl[0] + "\",\"clientVersion\":\"" + cl[1] + "\",\"hl\":\"en\",\"gl\":\"US\"" + (cl[4].length() > 0 ? "," + cl[4] : "") + "}" +
          (cl[0].startsWith("TVHTML5") ? ",\"thirdParty\":{\"embedUrl\":\"https://www.youtube.com/watch?v=" + vid + "\"}" : "") +
          "},\"videoId\":\"" + vid + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
        HttpURLConnection c = open("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", "POST", cl[3]);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-YouTube-Client-Name", cl[2]);
        c.setRequestProperty("X-YouTube-Client-Version", cl[1]);
        c.setRequestProperty("Origin", "https://www.youtube.com");
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        String txt = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) {
          lastErr = "YouTube said " + code;
          tried.append(cl[0]).append(" api ").append(code).append("; ");
          continue;
        }
        JSONObject r = new JSONObject(txt);
        JSONObject ps = r.optJSONObject("playabilityStatus");
        String st = ps == null ? "" : ps.optString("status", "");
        if (!"OK".equals(st)) {
          String why = ps == null ? "" : ps.optString("reason", st);
          if (why.length() > 0) lastErr = why;
          continue;
        }
        JSONObject sd = r.optJSONObject("streamingData");
        if (sd == null) continue;
        Info info = new Info();
        info.source = "youtube";
        info.ua = cl[3];
        info.ranged = true;
        JSONObject vd = r.optJSONObject("videoDetails");
        info.title = vd == null ? "YouTube video" : vd.optString("title", "YouTube video");
        info.thumbs.add("https://i.ytimg.com/vi/" + vid + "/maxresdefault.jpg");
        info.thumbs.add("https://i.ytimg.com/vi/" + vid + "/sddefault.jpg");
        info.thumbs.add("https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg");
        addFormats(info, sd.optJSONArray("adaptiveFormats"), true);
        addFormats(info, sd.optJSONArray("formats"), false);
        if (info.video.isEmpty() && info.audio.isEmpty() && info.both.isEmpty()) {
          lastErr = "YouTube gave no links for this video";
          tried.append(cl[0]).append(" no links; ");
          continue;
        }
        /* a link that YouTube then refuses (403) is useless: ask the next client instead */
        String bad = "";
        Stream pv = null, pa = null;
        for (Stream x : info.video) if (pv == null || x.h > pv.h) pv = x;
        for (Stream x : info.audio) if (pa == null) pa = x;
        if (pv == null && !info.both.isEmpty()) pv = info.both.get(0);
        int c1 = pv == null ? 200 : probe(pv.url, cl[3]);
        int c2 = pa == null ? 200 : probe(pa.url, cl[3]);
        if (c1 >= 400 || c2 >= 400) {
          tried.append(cl[0]).append(" stream ").append(c1 >= 400 ? c1 : c2).append("; ");
          lastErr = "YouTube refused the download link (" + (c1 >= 400 ? c1 : c2) + ")";
          continue;
        }
        return info;
      } catch (IOException e) {
        lastErr = e.getMessage() == null ? lastErr : e.getMessage();
      } catch (Throwable t) {
        lastErr = "Could not read the YouTube answer";
      }
    }
    throw new IOException(lastErr + (tried.length() > 0 ? " [" + tried.toString().trim() + "]" : ""));
  }

  private int probe(String url, String ua) {
    try {
      HttpURLConnection c = open(url, "GET", ua);
      c.setRequestProperty("Range", "bytes=0-1023");
      int code = c.getResponseCode();
      try {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) in.close();
      } catch (Throwable t) {
      }
      c.disconnect();
      return code;
    } catch (Throwable t) {
      return 200;
    }
  }

  private void addFormats(Info info, JSONArray a, boolean adaptive) {
    if (a == null) return;
    for (int i = 0; i < a.length(); i++) {
      JSONObject f = a.optJSONObject(i);
      if (f == null) continue;
      String url = f.optString("url", "");
      if (url.length() == 0) continue;
      String mt = f.optString("mimeType", "");
      Stream s = new Stream();
      s.url = url;
      int sc = mt.indexOf(';');
      s.mime = (sc > 0 ? mt.substring(0, sc) : mt).trim().toLowerCase();
      int cq = mt.indexOf("codecs=\"");
      if (cq > 0) {
        String cs = mt.substring(cq + 8);
        int e = cs.indexOf('"');
        s.codecs = (e > 0 ? cs.substring(0, e) : cs).toLowerCase();
      }
      s.w = f.optInt("width", 0);
      s.h = f.optInt("height", 0);
      s.bitrate = f.optInt("averageBitrate", f.optInt("bitrate", 0));
      try {
        s.size = Long.parseLong(f.optString("contentLength", "0"));
      } catch (Throwable t) {
        s.size = 0L;
      }
      if (s.mime.startsWith("audio/")) info.audio.add(s);
      else if (s.mime.startsWith("video/")) {
        if (adaptive) info.video.add(s);
        else info.both.add(s);
      }
    }
  }

  /* ----- Instagram: public posts only ----- */

  private static final String FB_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";

  /* gets a page and tells what happened, so a failure can be understood */
  private String igGet(String pg, String ua, StringBuilder why, String tag) {
    try {
      HttpURLConnection c = open(pg, "GET", ua);
      c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
      int code = c.getResponseCode();
      if (code >= 400) {
        why.append(tag).append(' ').append(code).append(' ');
        c.disconnect();
        return "";
      }
      String h = readAll(c.getInputStream());
      c.disconnect();
      why.append(tag).append(" ok ").append(h.length()).append(' ');
      return h;
    } catch (IOException e) {
      why.append(tag).append(" fail ");
      return "";
    }
  }

  private String igClean(String v) {
    String r = v.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&");
    return r.replace("\\\\", "").replace("\\", "");
  }

  private String igFind(String html, String key) {
    Matcher m = Pattern.compile(key + "\\\\*\"\\s*:\\s*\\\\*\"(https?:[^\"]*?)\\\\*\"").matcher(html);
    return m.find() ? igClean(m.group(1)) : "";
  }

  private String ogTag(String html, String prop) {
    Matcher m = Pattern.compile("<meta[^>]+property=[\"']" + prop + "[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
    if (m.find()) return htmlDec(m.group(1));
    m = Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + prop + "[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
    return m.find() ? htmlDec(m.group(1)) : "";
  }

  private Info igResolve(String u) throws IOException {
    Matcher m = Pattern.compile("instagram\\.com/(?:[A-Za-z0-9_.]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)").matcher(u);
    if (!m.find()) throw new IOException("This Instagram link has no video");
    String code = m.group(1);
    StringBuilder why = new StringBuilder();
    String vurl = "", disp = "", user = "", title = "";
    int h = 0, w = 0;
    /* 1. the page that is made for link previews (what chat apps read) */
    String[] a1 = { "https://www.instagram.com/reel/" + code + "/", "https://www.instagram.com/p/" + code + "/" };
    for (int k = 0; k < a1.length && vurl.length() == 0; k++) {
      String html = igGet(a1[k], FB_UA, why, "preview" + k);
      if (html.length() == 0) continue;
      vurl = ogTag(html, "og:video:secure_url");
      if (vurl.length() == 0) vurl = ogTag(html, "og:video");
      if (vurl.length() == 0) vurl = igFind(html, "video_url");
      if (disp.length() == 0) disp = ogTag(html, "og:image");
      if (title.length() == 0) title = ogTag(html, "og:title");
      String ow = ogTag(html, "og:video:width"), oh = ogTag(html, "og:video:height");
      try {
        if (ow.length() > 0) w = Integer.parseInt(ow);
        if (oh.length() > 0) h = Integer.parseInt(oh);
      } catch (Throwable t) {
      }
    }
    /* 2. the small page that Instagram gives for putting a post inside other sites */
    String[] a2 = { "https://www.instagram.com/p/" + code + "/embed/captioned/", "https://www.instagram.com/reel/" + code + "/embed/", "https://www.instagram.com/p/" + code + "/embed/" };
    for (int k = 0; k < a2.length && vurl.length() == 0; k++) {
      String html = igGet(a2[k], DESK_UA, why, "embed" + k);
      if (html.length() == 0) continue;
      vurl = igFind(html, "video_url");
      if (vurl.length() == 0) vurl = ogTag(html, "og:video");
      if (vurl.length() == 0) {
        Matcher src = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        if (src.find()) vurl = htmlDec(src.group(1));
      }
      if (disp.length() == 0) disp = igFind(html, "display_url");
      Matcher dm = Pattern.compile("dimensions\\W+height\\W+(\\d+)\\W+width\\W+(\\d+)").matcher(html);
      if (dm.find()) {
        h = Integer.parseInt(dm.group(1));
        w = Integer.parseInt(dm.group(2));
      }
      Matcher us = Pattern.compile("username\\W+([A-Za-z0-9_.]+)").matcher(html);
      if (us.find()) user = us.group(1);
    }
    if (vurl.length() == 0) {
      throw new IOException("Could not get this Instagram video (" + why.toString().trim() + "). It may be private, a photo, or Instagram blocked the request");
    }
    Info info = new Info();
    info.source = "instagram";
    String nm = title.length() > 0 && title.length() < 80 ? title : ((user.length() > 0 ? "Instagram " + user : "Instagram") + " " + code);
    info.title = nm;
    info.direct = vurl;
    info.directMime = "video/mp4";
    info.directH = Math.min(h, w) > 0 ? Math.min(h, w) : 0;
    info.ua = DESK_UA;
    if (disp.length() > 0) info.thumbs.add(disp);
    return info;
  }

  private String unesc(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '\\' && i + 1 < s.length()) {
        char n = s.charAt(i + 1);
        if (n == 'u' && i + 5 < s.length()) {
          try {
            b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
            i += 5;
            continue;
          } catch (Throwable t) {
          }
        }
        if (n == '/' || n == '\\' || n == '"') {
          b.append(n);
          i++;
          continue;
        }
      }
      b.append(ch);
    }
    return b.toString();
  }

  private String htmlDec(String s) {
    return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'");
  }

  /* ---------- network ---------- */

  private HttpURLConnection open(String u, String method, String ua) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
    c.setRequestMethod(method);
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", ua);
    c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
    return c;
  }

  private String readAll(InputStream in) throws IOException {
    if (in == null) return "";
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] b = new byte[16384];
    int n;
    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
    in.close();
    return bo.toString("UTF-8");
  }

  private byte[] getBytes(String u, String ua) throws IOException {
    HttpURLConnection c = open(u, "GET", ua);
    int code = c.getResponseCode();
    if (code >= 400) {
      c.disconnect();
      throw new IOException("http " + code);
    }
    InputStream in = c.getInputStream();
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] b = new byte[16384];
    int n;
    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
    in.close();
    c.disconnect();
    return bo.toByteArray();
  }

  /* a file is fetched in pieces of about 9 MB (the way YouTube wants it); other links are read in one go */
  private void download(String id, String url, File out, long size, Prog pr, String ua, boolean ranged, String title, JSONObject j) throws IOException {
    FileOutputStream fo = new FileOutputStream(out);
    try {
      if (!ranged || size <= 0L) {
        for (int attempt = 0; ; attempt++) {
          long before = pr.done;
          try {
            HttpURLConnection c = open(url, "GET", ua);
            int code = c.getResponseCode();
            if (code >= 400) {
              c.disconnect();
              throw new IOException("Download failed (" + code + ")");
            }
            long len = c.getContentLength();
            if (len > 0 && pr.total == 0L) pr.total = len;
            InputStream in = c.getInputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
              check(id);
              fo.write(b, 0, n);
              pr.done += n;
              progress(pr, title, j);
            }
            in.close();
            c.disconnect();
            return;
          } catch (Cancel c) {
            throw c;
          } catch (IOException e) {
            if (attempt >= 2) throw e;
            pr.done = before;
            fo.getChannel().truncate(0L);
            fo.getChannel().position(0L);
          }
        }
      }
      long pos = 0L;
      final long CH = 9437184L;
      while (pos < size) {
        check(id);
        long end = Math.min(size - 1, pos + CH - 1);
        byte[] chunk = null;
        for (int attempt = 0; attempt < 3; attempt++) {
          long before = pr.done;
          try {
            String u2 = attempt == 0 ? url : url + (url.contains("?") ? "&" : "?") + "range=" + pos + "-" + end;
            HttpURLConnection c = open(u2, "GET", ua);
            if (attempt == 0) c.setRequestProperty("Range", "bytes=" + pos + "-" + end);
            int code = c.getResponseCode();
            if (code >= 400) {
              c.disconnect();
              throw new IOException("Download failed (" + code + ")");
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
              check(id);
              bo.write(b, 0, n);
              pr.done += n;
              progress(pr, title, j);
            }
            in.close();
            c.disconnect();
            chunk = bo.toByteArray();
            if (code == 200 && pos == 0L && chunk.length >= size) {
              fo.write(chunk);
              return;
            }
            break;
          } catch (Cancel c) {
            throw c;
          } catch (IOException e) {
            pr.done = before;
            if (attempt >= 2) throw e;
          }
        }
        if (chunk == null || chunk.length == 0) throw new IOException("Download stopped");
        fo.write(chunk);
        pos += chunk.length;
      }
    } finally {
      try {
        fo.close();
      } catch (Throwable t) {
      }
    }
  }

  private void progress(Prog pr, String title, JSONObject j) {
    int pct = pr.total > 0L ? (int) Math.min(95L, pr.done * 95L / pr.total) : 0;
    put(j, "pct", pct);
    note(title, pr.total > 0L ? pct : -1, false);
  }

  /* ---------- joining the picture and the sound ---------- */

  private void mux(File vf, File af, File out) throws IOException {
    MediaExtractor ve = new MediaExtractor();
    MediaExtractor ae = new MediaExtractor();
    MediaMuxer mx = null;
    boolean started = false;
    try {
      ve.setDataSource(vf.getPath());
      ae.setDataSource(af.getPath());
      int vi = trackOf(ve, "video/");
      int ai = trackOf(ae, "audio/");
      if (vi < 0 || ai < 0) throw new IOException("Could not join picture and sound");
      ve.selectTrack(vi);
      ae.selectTrack(ai);
      mx = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int vt = mx.addTrack(ve.getTrackFormat(vi));
      int at = mx.addTrack(ae.getTrackFormat(ai));
      mx.start();
      started = true;
      ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
      MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
      copyTrack(ve, mx, vt, buf, bi);
      copyTrack(ae, mx, at, buf, bi);
    } catch (IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new IOException("Could not join picture and sound");
    } finally {
      try {
        if (mx != null) {
          if (started) mx.stop();
          mx.release();
        }
      } catch (Throwable t) {
      }
      try {
        ve.release();
        ae.release();
      } catch (Throwable t) {
      }
    }
  }

  private int trackOf(MediaExtractor e, String prefix) {
    for (int i = 0; i < e.getTrackCount(); i++) {
      String m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
      if (m != null && m.startsWith(prefix)) return i;
    }
    return -1;
  }

  private void copyTrack(MediaExtractor e, MediaMuxer mx, int track, ByteBuffer buf, MediaCodec.BufferInfo bi) {
    while (true) {
      buf.clear();
      int n = e.readSampleData(buf, 0);
      if (n < 0) break;
      bi.offset = 0;
      bi.size = n;
      bi.presentationTimeUs = e.getSampleTime();
      bi.flags = (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
      mx.writeSampleData(track, buf, bi);
      e.advance();
    }
  }

  /* ---------- saving to the phone ---------- */

  private String saveMedia(File f, String fileName, String mime, String title) throws IOException {
    boolean audio = mime.startsWith("audio/");
    String folder = "V Player";
    if (Build.VERSION.SDK_INT >= 29) {
      android.content.ContentResolver cr = getContentResolver();
      Uri base = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
      ContentValues cv = new ContentValues();
      cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
      cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
      cv.put(MediaStore.MediaColumns.TITLE, title);
      cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/" + folder);
      cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
      Uri uri = cr.insert(base, cv);
      if (uri == null) throw new IOException("Could not create the file");
      try {
        OutputStream os = cr.openOutputStream(uri);
        if (os == null) throw new IOException("Could not write the file");
        copy(f, os);
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
      } catch (IOException e) {
        try {
          cr.delete(uri, null, null);
        } catch (Throwable t) {
        }
        throw e;
      }
      return (audio ? "audio" : "video") + uri.getLastPathSegment();
    }
    File dir = new File(Environment.getExternalStoragePublicDirectory(audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES), folder);
    dir.mkdirs();
    File dst = new File(dir, fileName);
    int k = 1;
    while (dst.exists()) {
      int d = fileName.lastIndexOf('.');
      dst = new File(dir, (d > 0 ? fileName.substring(0, d) : fileName) + " (" + (k++) + ")" + (d > 0 ? fileName.substring(d) : ""));
    }
    FileOutputStream os = new FileOutputStream(dst);
    copy(f, os);
    final String[] got = new String[] { "" };
    final Object w = new Object();
    android.media.MediaScannerConnection.scanFile(
      this,
      new String[] { dst.getPath() },
      new String[] { mime },
      new android.media.MediaScannerConnection.OnScanCompletedListener() {
        @Override
        public void onScanCompleted(String path, Uri uri) {
          if (uri != null) got[0] = uri.getLastPathSegment();
          synchronized (w) {
            w.notifyAll();
          }
        }
      }
    );
    synchronized (w) {
      try {
        w.wait(4000);
      } catch (InterruptedException e) {
      }
    }
    return got[0].length() > 0 ? (audio ? "audio" : "video") + got[0] : "";
  }

  private void copy(File f, OutputStream os) throws IOException {
    FileInputStream in = new FileInputStream(f);
    try {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) > 0) os.write(b, 0, n);
      os.flush();
    } finally {
      in.close();
      os.close();
    }
  }

  /* the picture of the video is kept aside; the app puts it on the card the next time it opens */
  private void saveThumb(Info info, String mediaId) {
    if (mediaId == null || mediaId.length() == 0) return;
    for (String t : info.thumbs) {
      try {
        byte[] b = getBytes(t, info.ua.startsWith("com.") ? DESK_UA : info.ua);
        if (b.length < 2500) continue;
        File d = new File(getFilesDir(), "vdlthumbs");
        d.mkdirs();
        FileOutputStream fo = new FileOutputStream(new File(d, mediaId + ".jpg"));
        fo.write(b);
        fo.close();
        return;
      } catch (Throwable e) {
      }
    }
  }

  private String clean(String t) {
    if (t == null) t = "";
    String s = t.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    if (s.length() > 100) s = s.substring(0, 100).trim();
    return s.length() == 0 ? "download" : s;
  }

  private void deleteTree(File f) {
    try {
      File[] l = f.listFiles();
      if (l != null) for (File x : l) deleteTree(x);
      f.delete();
    } catch (Throwable t) {
    }
  }
}
