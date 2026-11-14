package com.vplayer.app;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/* cuts a video exactly at the chosen start and end (the picture is made again, so the cut is on the exact frame) and saves it as a new copy next to the original */
@CapacitorPlugin(name = "VTrim")
public class VTrimPlugin extends Plugin {

  static String lastMsg = "";

  @PluginMethod
  public void trim(final PluginCall call) {
    final String path = call.getString("path", "");
    Double sd = call.getDouble("startMs", 0.0);
    Double ed = call.getDouble("endMs", 0.0);
    long start = sd == null ? 0L : Math.round(sd.doubleValue());
    long end = ed == null ? 0L : Math.round(ed.doubleValue());
    cut(getActivity(), path, start, end,
      new Runnable() {
        public void run() {
          JSObject r = new JSObject();
          r.put("name", lastMsg);
          call.resolve(r);
        }
      },
      new Runnable() {
        public void run() {
          call.reject(lastMsg);
        }
      });
  }

  /* also called straight from the Shorts screen (by name, so the app still builds without this file) */
  public static void cut(final android.app.Activity act, final String path, final long start, final long end, final Runnable ok, final Runnable fail) {
    if (path == null || path.length() == 0 || !new File(path).exists()) {
      lastMsg = "The video file was not found";
      fail.run();
      return;
    }
    if (end <= start) {
      lastMsg = "The end must be after the start";
      fail.run();
      return;
    }
    act.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          final File tmp = new File(act.getCacheDir(), "vtrim_" + System.currentTimeMillis() + ".mp4");
          MediaItem.ClippingConfiguration cc = new MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(start)
            .setEndPositionMs(end)
            .build();
          MediaItem mi = new MediaItem.Builder().setUri(Uri.fromFile(new File(path))).setClippingConfiguration(cc).build();
          EditedMediaItem emi = new EditedMediaItem.Builder(mi).build();
          Transformer t = new Transformer.Builder(act)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
              new Transformer.Listener() {
                @Override
                public void onCompleted(Composition composition, ExportResult exportResult) {
                  finish(act, tmp, path, ok, fail);
                }

                @Override
                public void onError(Composition composition, ExportResult exportResult, ExportException e) {
                  tmp.delete();
                  lastMsg = "Could not cut: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                  fail.run();
                }
              }
            )
            .build();
          t.start(emi, tmp.getAbsolutePath());
        } catch (Throwable e) {
          lastMsg = "Could not cut: " + e;
          fail.run();
        }
      }
    });
  }

  private static void finish(final android.app.Activity act, final File tmp, final String orig, final Runnable ok, final Runnable fail) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          File of = new File(orig);
          String nm = of.getName();
          int d = nm.lastIndexOf('.');
          String base = d > 0 ? nm.substring(0, d) : nm;
          String name = base + " (cut).mp4";
          String root = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
          String par = of.getParent() == null ? "" : of.getParent();
          String rel = par.startsWith(root) ? par.substring(root.length()) : "";
          String top = rel.indexOf('/') > 0 ? rel.substring(0, rel.indexOf('/')) : rel;
          if (!(top.equals("Movies") || top.equals("DCIM"))) rel = "Movies/V Player";
          String shown = name;
          if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentResolver cr = act.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new java.io.IOException("could not make the new file");
            OutputStream os = cr.openOutputStream(u);
            copy(tmp, os);
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, done, null, null);
          } else {
            File dir = new File(root + rel);
            dir.mkdirs();
            File out = new File(dir, name);
            copy(tmp, new FileOutputStream(out));
          }
          tmp.delete();
          lastMsg = shown;
          ok.run();
        } catch (Throwable e) {
          tmp.delete();
          lastMsg = "Could not save the cut video: " + e;
          fail.run();
        }
      }
    }).start();
  }

  private static void copy(File f, OutputStream os) throws java.io.IOException {
    InputStream in = new FileInputStream(f);
    byte[] b = new byte[65536];
    int n;
    while ((n = in.read(b)) > 0) os.write(b, 0, n);
    os.close();
    in.close();
  }
}
