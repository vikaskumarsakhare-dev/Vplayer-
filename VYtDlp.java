package com.vplayer.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/* yt-dlp through the youtubedl-android library. Everything is called by reflection, so the app still builds and runs
   (with the older downloader) if the library is not inside the app or its names differ. */
final class VYtDlp {
  interface Cb {
    void onProgress(float pct, String line);
  }

  private static boolean tried = false, ready = false;
  static String initErr = "";
  private static final String P = "com.yausername.youtubedl_android.";

  static boolean installed() {
    try {
      Class.forName(P + "YoutubeDL");
      Class.forName(P + "YoutubeDLRequest");
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  private static Object engine() throws Exception {
    Class<?> y = Class.forName(P + "YoutubeDL");
    try {
      return y.getMethod("getInstance").invoke(null);
    } catch (NoSuchMethodException e) {
      return y.getField("INSTANCE").get(null);
    }
  }

  static String root(Throwable t) {
    Throwable c = t;
    while (c instanceof InvocationTargetException && c.getCause() != null) c = c.getCause();
    String m = c.getMessage();
    if (m == null || m.length() == 0) m = c.toString();
    return m;
  }

  static synchronized boolean init(Context ctx) {
    if (tried) return ready;
    tried = true;
    try {
      Object e = engine();
      e.getClass().getMethod("init", Context.class).invoke(e, ctx.getApplicationContext());
      ready = true;
    } catch (Throwable t) {
      initErr = root(t);
      return false;
    }
    try {
      Class<?> f = Class.forName("com.yausername.ffmpeg.FFmpeg");
      Object fi;
      try {
        fi = f.getMethod("getInstance").invoke(null);
      } catch (NoSuchMethodException e) {
        fi = f.getField("INSTANCE").get(null);
      }
      f.getMethod("init", Context.class).invoke(fi, ctx.getApplicationContext());
    } catch (Throwable t) {
    }
    return ready;
  }

  /* updates yt-dlp itself (YouTube changes often); returns a short text */
  static String update(Context ctx) {
    if (!init(ctx)) return "not ready: " + initErr;
    try {
      Object e = engine();
      Method use = null;
      for (Method m : e.getClass().getMethods()) {
        if (m.getName().equals("updateYoutubeDL")) {
          if (use == null || m.getParameterTypes().length > use.getParameterTypes().length) use = m;
        }
      }
      if (use == null) return "no update call";
      Class<?>[] pt = use.getParameterTypes();
      Object[] a = new Object[pt.length];
      for (int i = 0; i < pt.length; i++) {
        if (Context.class.isAssignableFrom(pt[i])) a[i] = ctx.getApplicationContext();
        else if (pt[i].isEnum()) {
          Object[] vals = pt[i].getEnumConstants();
          Object pick = vals.length > 0 ? vals[0] : null;
          for (Object v : vals) {
            if (v.toString().toUpperCase().contains("NIGHTLY")) pick = v;
          }
          a[i] = pick;
        } else a[i] = null;
      }
      Object r = use.invoke(e, a);
      try {
        ctx.getSharedPreferences("vdl", Context.MODE_PRIVATE).edit().putLong("ytUpd", System.currentTimeMillis()).apply();
      } catch (Throwable x) {
      }
      return "updated " + (r == null ? "" : r.toString()) + " " + version(ctx);
    } catch (Throwable t) {
      return "update failed: " + root(t);
    }
  }

  static String version(Context ctx) {
    try {
      Object e = engine();
      for (Method m : e.getClass().getMethods()) {
        if (m.getName().equals("version")) {
          Class<?>[] pt = m.getParameterTypes();
          Object[] a = new Object[pt.length];
          for (int i = 0; i < pt.length; i++) if (Context.class.isAssignableFrom(pt[i])) a[i] = ctx.getApplicationContext();
          Object r = m.invoke(e, a);
          return r == null ? "" : r.toString();
        }
      }
    } catch (Throwable t) {
    }
    return "";
  }

  static boolean updatedToday(Context ctx) {
    long t = ctx.getSharedPreferences("vdl", Context.MODE_PRIVATE).getLong("ytUpd", 0L);
    return System.currentTimeMillis() - t < 24L * 3600L * 1000L;
  }

  static void cancel(String id) {
    try {
      Object e = engine();
      e.getClass().getMethod("destroyProcessById", String.class).invoke(e, id);
    } catch (Throwable t) {
    }
  }

  static List<String> options(String url, boolean audio, int maxRes, String aFmt, String aQ, File tmp, String client) {
    List<String> o = new ArrayList<String>();
    o.add("--no-playlist");
    o.add("--no-mtime");
    o.add("--no-warnings");
    o.add("--socket-timeout");
    o.add("25");
    o.add("--retries");
    o.add("5");
    o.add("--fragment-retries");
    o.add("5");
    o.add("-N");
    o.add("4");
    o.add("--write-thumbnail");
    o.add("--convert-thumbnails");
    o.add("jpg");
    if (client != null && client.length() > 0 && (url.contains("youtube.com") || url.contains("youtu.be"))) {
      o.add("--extractor-args");
      o.add("youtube:player_client=" + client);
    }
    if (audio) {
      String ext = "opus".equals(aFmt) ? "webm" : "m4a";
      String lim = "low".equals(aQ) ? "[abr<=70]" : ("medium".equals(aQ) ? "[abr<=130]" : "");
      o.add("-f");
      o.add("ba[ext=" + ext + "]" + lim + "/ba[ext=" + ext + "]/ba" + lim + "/ba/b");
      o.add("-x");
      o.add("--audio-format");
      o.add("mp3".equals(aFmt) ? "mp3" : "best");
      if ("mp3".equals(aFmt)) {
        o.add("--audio-quality");
        o.add("low".equals(aQ) ? "7" : ("medium".equals(aQ) ? "4" : "0"));
      }
    } else {
      o.add("-f");
      o.add("bv*+ba/b");
      o.add("-S");
      o.add(maxRes > 0 ? "res:" + maxRes + ",ext:mp4:m4a" : "ext:mp4:m4a");
      o.add("--merge-output-format");
      o.add("mp4");
    }
    o.add("-o");
    o.add(new File(tmp, "%(title).90s.%(ext)s").getAbsolutePath());
    return o;
  }

  /* runs one yt-dlp download. Throws with yt-dlp's own error text. */
  static void run(String id, String url, List<String> opts, final Cb cb) throws Exception {
    Class<?> rc = Class.forName(P + "YoutubeDLRequest");
    Object req = rc.getConstructor(String.class).newInstance(url);
    Method add2 = rc.getMethod("addOption", String.class, String.class);
    Method add1 = rc.getMethod("addOption", String.class);
    for (int i = 0; i < opts.size(); i++) {
      String k = opts.get(i);
      boolean hasVal = isValueFor(k);
      if (hasVal && i + 1 < opts.size()) {
        add2.invoke(req, k, opts.get(i + 1));
        i++;
      } else {
        add1.invoke(req, k);
      }
    }
    Object eng = engine();
    Method use = null;
    for (Method m : eng.getClass().getMethods()) {
      if (!m.getName().equals("execute")) continue;
      Class<?>[] pt = m.getParameterTypes();
      if (pt.length == 0 || !pt[0].isAssignableFrom(rc)) continue;
      boolean hasCb = false;
      for (int i = 1; i < pt.length; i++) if (pt[i].isInterface()) hasCb = true;
      if (!hasCb) continue;
      if (use == null || pt.length > use.getParameterTypes().length) use = m;
    }
    if (use == null) throw new IOException("yt-dlp execute() not found");
    Class<?>[] pt = use.getParameterTypes();
    Object[] a = new Object[pt.length];
    a[0] = req;
    for (int i = 1; i < pt.length; i++) {
      if (pt[i] == String.class) a[i] = id;
      else if (pt[i].isInterface()) {
        a[i] = Proxy.newProxyInstance(pt[i].getClassLoader(), new Class<?>[] {pt[i]}, new InvocationHandler() {
          public Object invoke(Object proxy, Method m, Object[] args) {
            try {
              String n = m.getName();
              if (n.equals("toString")) return "cb";
              if (n.equals("hashCode")) return 1;
              if (n.equals("equals")) return proxy == (args == null ? null : args[0]);
              if (args != null && args.length > 0 && args[0] instanceof Number) {
                String line = args.length > 2 && args[2] != null ? String.valueOf(args[2]) : "";
                cb.onProgress(((Number) args[0]).floatValue(), line);
              }
            } catch (Throwable t) {
            }
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
          }
        });
      } else if (pt[i] == boolean.class) a[i] = Boolean.FALSE;
      else a[i] = null;
    }
    try {
      use.invoke(eng, a);
    } catch (InvocationTargetException e) {
      throw new IOException(root(e));
    }
  }

  /* option names that take a value in options() */
  private static boolean isValueFor(String k) {
    return k.equals("-f") || k.equals("-S") || k.equals("-o") || k.equals("-N") || k.equals("--socket-timeout")
        || k.equals("--retries") || k.equals("--fragment-retries") || k.equals("--convert-thumbnails")
        || k.equals("--audio-quality") || k.equals("--extractor-args") || k.equals("--audio-format") || k.equals("--merge-output-format");
  }
}
