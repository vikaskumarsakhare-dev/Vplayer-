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
import org.json.JSONArray;

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
