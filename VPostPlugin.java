package com.vplayer.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/* pictures for posts: the gallery picker (many at once); every picture is copied into the app's own folder, so it stays even if the original is moved */
@CapacitorPlugin(name = "VPost")
public class VPostPlugin extends Plugin {

  static File dir(android.content.Context c) {
    File d = new File(c.getFilesDir(), "vpposts");
    d.mkdirs();
    return d;
  }

  @PluginMethod
  public void pickImages(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
    i.setType("image/*");
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    startActivityForResult(call, i, "pickDone");
  }

  @ActivityCallback
  private void pickDone(final PluginCall call, ActivityResult result) {
    if (call == null) return;
    if (result == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
      call.reject("cancelled");
      return;
    }
    final List<Uri> uris = new ArrayList<Uri>();
    Intent d = result.getData();
    ClipData cd = d.getClipData();
    if (cd != null) {
      for (int k = 0; k < cd.getItemCount(); k++) {
        Uri u = cd.getItemAt(k).getUri();
        if (u != null) uris.add(u);
      }
    } else if (d.getData() != null) {
      uris.add(d.getData());
    }
    if (uris.isEmpty()) {
      call.reject("cancelled");
      return;
    }
    new Thread(new Runnable() {
      @Override
      public void run() {
        JSArray out = new JSArray();
        File base = dir(getContext());
        for (Uri u : uris) {
          try {
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            InputStream in1 = getContext().getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in1, null, bo);
            if (in1 != null) in1.close();
            int sample = 1;
            while (Math.max(bo.outWidth, bo.outHeight) / sample > 2000) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            InputStream in2 = getContext().getContentResolver().openInputStream(u);
            Bitmap b = BitmapFactory.decodeStream(in2, null, o2);
            if (in2 != null) in2.close();
            if (b == null) continue;
            File f = new File(base, "p" + System.currentTimeMillis() + "_" + out.length() + ".jpg");
            FileOutputStream fo = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.JPEG, 90, fo);
            fo.close();
            b.recycle();
            out.put(f.getAbsolutePath());
          } catch (Throwable t) {
          }
        }
        if (out.length() == 0) {
          call.reject("could not read the pictures");
          return;
        }
        JSObject r = new JSObject();
        r.put("paths", out);
        call.resolve(r);
      }
    }).start();
  }

  /* ---- a public YouTube community post, from its link: every picture in it is saved, in the same order ---- */
  private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

  private static byte[] get(String u, boolean page) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
    c.setConnectTimeout(12000);
    c.setReadTimeout(20000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", UA);
    c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
    if (page) c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI");
    if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
    InputStream in = c.getInputStream();
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[16384];
    int n;
    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
    in.close();
    return bo.toByteArray();
  }

  /* the page keeps its data as one big JSON after "ytInitialData"; cut that JSON out, honouring quotes */
  static String cutJson(String html) {
    int k = html.indexOf("ytInitialData");
    if (k < 0) return null;
    int st = html.indexOf('{', k);
    if (st < 0) return null;
    int depth = 0;
    boolean str = false, esc = false;
    for (int i = st; i < html.length(); i++) {
      char ch = html.charAt(i);
      if (str) {
        if (esc) esc = false;
        else if (ch == '\\') esc = true;
        else if (ch == '"') str = false;
      } else if (ch == '"') str = true;
      else if (ch == '{') depth++;
      else if (ch == '}') {
        depth--;
        if (depth == 0) return html.substring(st, i + 1);
      }
    }
    return null;
  }

  /* walks the data in page order: each picture block gives its biggest link; the first writer name is the post name */
  static void walk(Object o, List<String> urls, String[] name) {
    if (o instanceof JSONObject) {
      JSONObject j = (JSONObject) o;
      if (name[1] == null && j.has("contentText")) {
        JSONObject ct = j.optJSONObject("contentText");
        JSONArray cr = ct != null ? ct.optJSONArray("runs") : null;
        StringBuilder sb = new StringBuilder();
        if (cr != null) {
          for (int i = 0; i < cr.length(); i++) {
            JSONObject r = cr.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
          }
        } else if (ct != null) sb.append(ct.optString("simpleText", ""));
        String tx = sb.toString().trim();
        if (tx.length() > 0) name[1] = tx;
      }
      if (name[0] == null && j.has("authorText")) {
        JSONObject at = j.optJSONObject("authorText");
        JSONArray runs = at != null ? at.optJSONArray("runs") : null;
        if (runs != null && runs.length() > 0) name[0] = runs.optJSONObject(0) != null ? runs.optJSONObject(0).optString("text", null) : null;
        else if (at != null && at.optString("simpleText", "").length() > 0) name[0] = at.optString("simpleText");
      }
      JSONObject bi = j.optJSONObject("backstageImageRenderer");
      if (bi != null) {
        JSONObject im = bi.optJSONObject("image");
        JSONArray th = im != null ? im.optJSONArray("thumbnails") : null;
        String best = null;
        int bw = -1;
        if (th != null) {
          for (int i = 0; i < th.length(); i++) {
            JSONObject t = th.optJSONObject(i);
            if (t == null) continue;
            int w = t.optInt("width", 0);
            if (w >= bw && t.optString("url", "").length() > 0) {
              bw = w;
              best = t.optString("url");
            }
          }
        }
        if (best != null && !urls.contains(best)) urls.add(best);
        return;
      }
      java.util.Iterator<String> it = j.keys();
      while (it.hasNext()) {
        String key = it.next();
        walk(j.opt(key), urls, name);
      }
    } else if (o instanceof JSONArray) {
      JSONArray a = (JSONArray) o;
      for (int i = 0; i < a.length(); i++) walk(a.opt(i), urls, name);
    }
  }

  /* Instagram: a public picture post shows its pictures on its "embed" page; every picture there is taken in order */
  static String igUnescape(String u) {
    return u.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
  }

  static String igCode(String link) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("instagram\\.com/(?:[A-Za-z0-9_.]+/)?(p|reel|reels|tv)/([A-Za-z0-9_-]+)").matcher(link);
    if (!m.find()) return null;
    return m.group(1).startsWith("reel") || m.group(1).equals("tv") ? "!" + m.group(2) : m.group(2);
  }

  static void igParse(String html, List<String> urls, String[] nm) {
    java.util.HashSet<String> seen = new java.util.HashSet<String>();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"display_url\"\\s*:\\s*\"(https:[^\"]+)\"").matcher(html);
    while (m.find()) {
      String u = igUnescape(m.group(1));
      String key = u.contains("?") ? u.substring(0, u.indexOf('?')) : u;
      if (seen.add(key)) urls.add(u);
    }
    if (urls.isEmpty()) {
      java.util.regex.Matcher im = java.util.regex.Pattern.compile("class=\"EmbeddedMediaImage\"[^>]*?src=\"([^\"]+)\"").matcher(html);
      while (im.find()) {
        String u = igUnescape(im.group(1));
        String key = u.contains("?") ? u.substring(0, u.indexOf('?')) : u;
        if (seen.add(key)) urls.add(u);
      }
    }
    java.util.regex.Matcher cm = java.util.regex.Pattern.compile("edge_media_to_caption\\\"?\"?\\s*:\\s*\\{\\s*\\\"?\"?edges\\\"?\"?\\s*:\\s*\\[\\s*\\{\\s*\\\"?\"?node\\\"?\"?\\s*:\\s*\\{\\s*\\\"?\"?text\\\"?\"?\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(html);
    if (cm.find()) {
      try {
        nm[1] = new JSONObject("{\"t\":\"" + cm.group(1) + "\"}").optString("t", "");
      } catch (Throwable t) {
      }
    }
    java.util.regex.Matcher um = java.util.regex.Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
    if (um.find()) nm[0] = um.group(1);
  }

  static String postUrl(String raw) {
    String u = raw == null ? "" : raw.trim();
    int k = u.indexOf("lb=");
    if (u.contains("youtube.com") && k >= 0) {
      String id = u.substring(k + 3);
      int e = id.indexOf('&');
      if (e >= 0) id = id.substring(0, e);
      return "https://www.youtube.com/post/" + id;
    }
    if (!u.startsWith("http")) u = "https://" + u;
    return u;
  }

  /* a link to the biggest size of the same picture */
  static String big(String u) {
    int q = u.indexOf('=', u.lastIndexOf('/'));
    if (q > 0) return u.substring(0, q) + "=s2048";
    return u;
  }

  @PluginMethod
  public void fetchPost(final PluginCall call) {
    final String link = call.getString("url", "");
    if (link == null || link.trim().length() < 8) {
      call.reject("no link");
      return;
    }
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          List<String> urls = new ArrayList<String>();
          String[] nm = new String[2];
          final boolean ig = link.contains("instagram.com");
          if (ig) {
            String code = igCode(link);
            if (code == null) {
              call.reject("this is not an Instagram post link");
              return;
            }
            if (code.startsWith("!")) {
              call.reject("this is a reel (video). Only picture posts work here");
              return;
            }
            String html = new String(get("https://www.instagram.com/p/" + code + "/embed/captioned/", false), "UTF-8");
            igParse(html, urls, nm);
            if (urls.isEmpty()) {
              call.reject("no pictures found. The post may be private or needs login");
              return;
            }
          } else {
            String html = new String(get(postUrl(link), true), "UTF-8");
            String js = cutJson(html);
            if (js == null) {
              call.reject("no data in the page");
              return;
            }
            walk(new JSONObject(js), urls, nm);
            if (urls.isEmpty()) {
              call.reject("no pictures in this post");
              return;
            }
          }
          JSArray out = new JSArray();
          File base = dir(getContext());
          long t0 = System.currentTimeMillis();
          for (int i = 0; i < urls.size(); i++) {
            byte[] data = null;
            try {
              data = get(ig ? urls.get(i) : big(urls.get(i)), false);
            } catch (Throwable t) {
            }
            if (data == null) {
              try {
                data = get(urls.get(i), false);
              } catch (Throwable t) {
              }
            }
            if (data == null) continue;
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bo);
            int sample = 1;
            while (Math.max(bo.outWidth, bo.outHeight) / sample > 2000) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (b == null) continue;
            File f = new File(base, "p" + t0 + "_" + i + ".jpg");
            FileOutputStream fo = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.JPEG, 92, fo);
            fo.close();
            b.recycle();
            out.put(f.getAbsolutePath());
          }
          if (out.length() == 0) {
            call.reject("could not save the pictures");
            return;
          }
          JSObject r = new JSObject();
          r.put("paths", out);
          String ttl = nm[1];
          if (ttl != null) {
            int nl = ttl.indexOf('\n');
            if (nl > 0) ttl = ttl.substring(0, nl);
            ttl = ttl.trim();
            if (ttl.length() > 60) ttl = ttl.substring(0, 60).trim() + "…";
          }
          if (ttl == null || ttl.length() == 0) ttl = nm[0] == null ? "" : nm[0];
          r.put("name", ttl);
          r.put("total", urls.size());
          call.resolve(r);
        } catch (Throwable t) {
          call.reject("could not read the post: " + t.getMessage());
        }
      }
    }).start();
  }

  @PluginMethod
  public void deleteFiles(PluginCall call) {
    try {
      JSONArray a = call.getArray("paths");
      File base = dir(getContext());
      if (a != null) {
        for (int i = 0; i < a.length(); i++) {
          File f = new File(a.optString(i, ""));
          if (f.getParentFile() != null && f.getParentFile().equals(base)) f.delete();
        }
      }
    } catch (Throwable t) {
    }
    call.resolve();
  }
}
