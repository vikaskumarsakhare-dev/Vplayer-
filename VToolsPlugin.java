package com.vplayer.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/* Share and delete for the cards (separate from the players). */
@CapacitorPlugin(name = "VTools", requestCodes = { 9911 })
public class VToolsPlugin extends Plugin {

  private Uri uriOf(String id, String kind) {
    try {
      String digits = id == null ? "" : id.replaceAll("[^0-9]", "");
      if (digits.length() == 0) return null;
      long n = Long.parseLong(digits);
      Uri base = "audio".equals(kind) ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
      return ContentUris.withAppendedId(base, n);
    } catch (Throwable t) {
      return null;
    }
  }

  private String mimeOf(String path, String kind) {
    try {
      String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(path == null ? "" : path.replace(" ", "_"));
      if (ext == null || ext.length() == 0) {
        int d = path == null ? -1 : path.lastIndexOf('.');
        ext = d >= 0 ? path.substring(d + 1) : "";
      }
      String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(java.util.Locale.US));
      if (m != null && m.length() > 0) return m;
    } catch (Throwable t) {
    }
    return "audio".equals(kind) ? "audio/*" : "video/*";
  }

  /* the file is opened for the other app in the safest way: first the app's own file link, then the phone's media link */
  @PluginMethod
  public void share(final PluginCall call) {
    final String kind = call.getString("kind", "video");
    final String path = call.getString("path", "");
    final Uri media = uriOf(call.getString("id", ""), kind);
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Uri u = null;
        String err = "";
        if (path != null && path.length() > 0) {
          try {
            File f = new File(path);
            if (f.exists()) u = androidx.core.content.FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
          } catch (Throwable t) {
            err = "file link: " + t;
            u = null;
          }
        }
        if (u == null) u = media;
        if (u == null) {
          call.reject("no file " + err);
          return;
        }
        try {
          Intent i = new Intent(Intent.ACTION_SEND);
          i.setType(mimeOf(path, kind));
          i.putExtra(Intent.EXTRA_STREAM, u);
          i.setClipData(android.content.ClipData.newRawUri("", u));
          i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          Intent ch = Intent.createChooser(i, "Share");
          ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          getActivity().startActivity(ch);
          call.resolve();
        } catch (Throwable t) {
          /* last try with the phone's media link */
          try {
            if (media != null && !media.equals(u)) {
              Intent i2 = new Intent(Intent.ACTION_SEND);
              i2.setType("audio".equals(kind) ? "audio/*" : "video/*");
              i2.putExtra(Intent.EXTRA_STREAM, media);
              i2.setClipData(android.content.ClipData.newRawUri("", media));
              i2.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              getActivity().startActivity(Intent.createChooser(i2, "Share"));
              call.resolve();
              return;
            }
          } catch (Throwable t2) {
          }
          call.reject("share failed: " + t);
        }
      }
    });
  }

  /* downloads the pictures of a YouTube video (best first) and gives back the first one that really exists; nothing is saved in the gallery */
  private byte[] download(String u) {
    java.net.HttpURLConnection c = null;
    try {
      c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
      c.setConnectTimeout(6000);
      c.setReadTimeout(10000);
      c.setRequestProperty("User-Agent", "Mozilla/5.0");
      if (c.getResponseCode() != 200) return null;
      java.io.InputStream in = c.getInputStream();
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) {
        bo.write(buf, 0, n);
        if (bo.size() > 6000000) return null;
      }
      in.close();
      byte[] d = bo.toByteArray();
      android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
      op.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length, op);
      /* YouTube sends a small grey picture (120 x 90) when the real one does not exist */
      if (op.outWidth <= 120) return null;
      return d;
    } catch (Throwable t) {
      return null;
    } finally {
      try {
        if (c != null) c.disconnect();
      } catch (Throwable t) {
      }
    }
  }

  @PluginMethod
  public void fetchImage(final PluginCall call) {
    final org.json.JSONArray ua = call.getArray("urls");
    if (ua == null || ua.length() == 0) {
      call.reject("no link");
      return;
    }
    new Thread(
      new Runnable() {
        @Override
        public void run() {
          final int n = ua.length();
          final byte[][] res = new byte[n][];
          Thread[] th = new Thread[n];
          for (int i = 0; i < n; i++) {
            final int k = i;
            final String u = ua.optString(i, "");
            th[i] =
              new Thread(
                new Runnable() {
                  @Override
                  public void run() {
                    res[k] = download(u);
                  }
                }
              );
            th[i].start();
          }
          long end = System.currentTimeMillis() + 14000L;
          for (int i = 0; i < n; i++) {
            try {
              th[i].join(Math.max(1L, end - System.currentTimeMillis()));
            } catch (Throwable t) {
            }
            if (res[i] != null) {
              android.graphics.BitmapFactory.Options op = new android.graphics.BitmapFactory.Options();
              op.inJustDecodeBounds = true;
              android.graphics.BitmapFactory.decodeByteArray(res[i], 0, res[i].length, op);
              JSObject r = new JSObject();
              r.put("data", "data:image/jpeg;base64," + android.util.Base64.encodeToString(res[i], android.util.Base64.NO_WRAP));
              r.put("w", op.outWidth);
              r.put("h", op.outHeight);
              r.put("n", i);
              call.resolve(r);
              return;
            }
          }
          call.reject("no picture");
        }
      }
    ).start();
  }

  @PluginMethod
  public void delete(final PluginCall call) {
    String kind = call.getString("kind", "video");
    String path = call.getString("path", "");
    Uri u = uriOf(call.getString("id", ""), kind);
    ContentResolver cr = getContext().getContentResolver();
    try {
      if (path != null && path.length() > 0) {
        File f = new File(path);
        if (f.exists() && f.delete()) {
          try {
            if (u != null) cr.delete(u, null, null);
          } catch (Throwable t) {
          }
          JSObject r = new JSObject();
          r.put("ok", true);
          call.resolve(r);
          return;
        }
      }
    } catch (Throwable t) {
    }
    if (u == null) {
      call.reject("no file");
      return;
    }
    try {
      int n = cr.delete(u, null, null);
      if (n > 0) {
        JSObject r = new JSObject();
        r.put("ok", true);
        call.resolve(r);
        return;
      }
    } catch (Throwable t) {
    }
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        List<Uri> l = new ArrayList<Uri>();
        l.add(u);
        PendingIntent pi = MediaStore.createDeleteRequest(cr, l);
        saveCall(call);
        getActivity().startIntentSenderForResult(pi.getIntentSender(), 9911, null, 0, 0, 0);
        return;
      } catch (Throwable t) {
        call.reject("delete failed: " + t);
        return;
      }
    }
    call.reject("delete failed");
  }

  @Override
  protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
    super.handleOnActivityResult(requestCode, resultCode, data);
    if (requestCode != 9911) return;
    PluginCall c = getSavedCall();
    if (c == null) return;
    if (resultCode == Activity.RESULT_OK) {
      JSObject r = new JSObject();
      r.put("ok", true);
      c.resolve(r);
    } else {
      c.reject("cancelled");
    }
    freeSavedCall();
  }
}
