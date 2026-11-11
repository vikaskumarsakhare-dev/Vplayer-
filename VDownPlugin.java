package com.vplayer.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/* The Download tab talks to this. The work itself is done by VDownService. */
@CapacitorPlugin(name = "VDown")
public class VDownPlugin extends Plugin {

  static void startService(Context c, Intent i) {
    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
    else c.startService(i);
  }

  @PluginMethod
  public void start(PluginCall call) {
    String url = call.getString("url", "");
    String mode = call.getString("mode", "video");
    if (url == null || url.trim().length() == 0) {
      call.reject("No link");
      return;
    }
    Context c = getContext();
    String id = "d" + System.currentTimeMillis() + "x" + (int) (Math.random() * 1000);
    VDownService.addJob(c, id, url.trim(), mode);
    try {
      Intent i = new Intent(c, VDownService.class);
      i.setAction(VDownService.A_START);
      i.putExtra("url", url.trim());
      i.putExtra("mode", mode);
      i.putExtra("id", id);
      startService(c, i);
      JSObject r = new JSObject();
      r.put("id", id);
      call.resolve(r);
    } catch (Throwable t) {
      call.reject("Could not start: " + t);
    }
  }

  @PluginMethod
  public void cancel(PluginCall call) {
    try {
      Intent i = new Intent(getContext(), VDownService.class);
      i.setAction(VDownService.A_CANCEL);
      String id = call.getString("id");
      if (id != null) i.putExtra("id", id);
      getContext().startService(i);
    } catch (Throwable t) {
    }
    call.resolve();
  }

  @PluginMethod
  public void status(PluginCall call) {
    JSObject r = new JSObject();
    try {
      JSONArray a = VDownService.snapshot(getContext());
      r.put("jobs", a);
    } catch (Throwable t) {
      r.put("jobs", new JSONArray());
    }
    call.resolve(r);
  }

  @PluginMethod
  public void remove(PluginCall call) {
    String id = call.getString("id");
    synchronized (VDownService.class) {
      VDownService.load(getContext());
      if (id == null) {
        java.util.List<String> del = new java.util.ArrayList<String>();
        for (java.util.Map.Entry<String, JSONObject> e : VDownService.JOBS.entrySet()) {
          String st = e.getValue().optString("state", "");
          if (!"run".equals(st) && !"queued".equals(st)) del.add(e.getKey());
        }
        for (String k : del) VDownService.JOBS.remove(k);
      } else {
        JSONObject o = VDownService.JOBS.get(id);
        if (o != null) {
          String st = o.optString("state", "");
          if (!"run".equals(st) && !"queued".equals(st)) VDownService.JOBS.remove(id);
        }
      }
    }
    VDownService.save(getContext());
    call.resolve();
  }

  @PluginMethod
  public void setConfig(PluginCall call) {
    SharedPreferences.Editor e = getContext().getSharedPreferences("vdl", Context.MODE_PRIVATE).edit();
    Integer mr = call.getInt("maxRes");
    if (mr != null) e.putInt("maxRes", mr.intValue());
    String af = call.getString("aFmt");
    if (af != null) e.putString("aFmt", af);
    String aq = call.getString("aQ");
    if (aq != null) e.putString("aQ", aq);
    e.apply();
    call.resolve();
  }

  @PluginMethod
  public void getConfig(PluginCall call) {
    SharedPreferences sp = getContext().getSharedPreferences("vdl", Context.MODE_PRIVATE);
    JSObject r = new JSObject();
    r.put("maxRes", sp.getInt("maxRes", 1080));
    r.put("aFmt", sp.getString("aFmt", "m4a"));
    r.put("aQ", sp.getString("aQ", "high"));
    call.resolve(r);
  }

  /* pictures of downloaded videos that are waiting to be put on the cards */
  @PluginMethod
  public void pending(PluginCall call) {
    JSArray out = new JSArray();
    try {
      File d = new File(getContext().getFilesDir(), "vdlthumbs");
      File[] l = d.listFiles();
      int n = 0;
      if (l != null) {
        for (File f : l) {
          if (n >= 6) break;
          String nm = f.getName();
          if (!nm.endsWith(".jpg")) continue;
          FileInputStream in = new FileInputStream(f);
          ByteArrayOutputStream bo = new ByteArrayOutputStream();
          byte[] b = new byte[16384];
          int k;
          while ((k = in.read(b)) > 0) bo.write(b, 0, k);
          in.close();
          JSObject o = new JSObject();
          o.put("id", nm.substring(0, nm.length() - 4));
          o.put("b64", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
          out.put(o);
          n++;
        }
      }
    } catch (Throwable t) {
    }
    JSObject r = new JSObject();
    r.put("items", out);
    call.resolve(r);
  }

  @PluginMethod
  public void clearPending(PluginCall call) {
    try {
      JSArray a = call.getArray("ids");
      File d = new File(getContext().getFilesDir(), "vdlthumbs");
      for (int i = 0; a != null && i < a.length(); i++) {
        String id = a.getString(i).replaceAll("[^A-Za-z0-9]", "");
        new File(d, id + ".jpg").delete();
      }
    } catch (Throwable t) {
    }
    call.resolve();
  }

  @PluginMethod
  public void askNotif(PluginCall call) {
    try {
      if (Build.VERSION.SDK_INT >= 33 && getContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        getActivity().requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 4714);
      }
    } catch (Throwable t) {
    }
    call.resolve();
  }
}
