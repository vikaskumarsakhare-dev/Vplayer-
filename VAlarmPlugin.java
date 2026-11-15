package com.vplayer.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

/* alarms that ring with a video: set, list, switch on or off, remove, and the phone permissions they need */
@CapacitorPlugin(name = "VAlarm")
public class VAlarmPlugin extends Plugin {

  @PluginMethod
  public void save(PluginCall call) {
    try {
      Context c = getContext();
      String id = call.getString("id", "");
      if (id.length() == 0) {
        call.reject("no id");
        return;
      }
      JSONObject o = new JSONObject();
      o.put("id", id);
      o.put("vid", call.getString("vid", ""));
      o.put("path", call.getString("path", ""));
      o.put("title", call.getString("title", ""));
      o.put("h", call.getInt("h", 7));
      o.put("m", call.getInt("m", 0));
      JSArray dd = call.getArray("days");
      o.put("days", dd == null ? new JSONArray() : new JSONArray(dd.toString()));
      Long s = call.getLong("s", 0L), e = call.getLong("e", 0L);
      o.put("s", s == null ? 0L : s);
      o.put("e", e == null ? 0L : e);
      o.put("full", call.getLong("full", 0L));
      o.put("on", call.getBoolean("on", true));
      JSONArray a = VAlarmStore.load(c), n = new JSONArray();
      boolean done = false;
      for (int i = 0; i < a.length(); i++) {
        JSONObject x = a.optJSONObject(i);
        if (x == null) continue;
        if (id.equals(x.optString("id", ""))) {
          n.put(o);
          done = true;
        } else n.put(x);
      }
      if (!done) n.put(o);
      VAlarmStore.save(c, n);
      long when = VAlarmStore.schedule(c, o);
      JSObject r = new JSObject();
      r.put("next", when);
      call.resolve(r);
    } catch (Throwable t) {
      call.reject("could not save the alarm");
    }
  }

  @PluginMethod
  public void list(PluginCall call) {
    Context c = getContext();
    JSONArray a = VAlarmStore.load(c);
    JSArray out = new JSArray();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      try {
        JSONObject x = new JSONObject(o.toString());
        boolean exists = o.optString("path", "").length() > 0 && new java.io.File(o.optString("path", "")).exists();
        x.put("missing", !exists);
        x.put("next", o.optBoolean("on", true) ? VAlarmStore.nextTime(o.optInt("h", 7), o.optInt("m", 0), o.optJSONArray("days"), System.currentTimeMillis()) : 0L);
        out.put(x);
      } catch (Throwable t) {
      }
    }
    JSObject r = new JSObject();
    r.put("alarms", out);
    call.resolve(r);
  }

  @PluginMethod
  public void toggle(PluginCall call) {
    try {
      Context c = getContext();
      String id = call.getString("id", "");
      boolean on = call.getBoolean("on", true);
      JSONArray a = VAlarmStore.load(c);
      for (int i = 0; i < a.length(); i++) {
        JSONObject x = a.optJSONObject(i);
        if (x != null && id.equals(x.optString("id", ""))) {
          x.put("on", on);
          VAlarmStore.schedule(c, x);
        }
      }
      VAlarmStore.save(c, a);
      call.resolve();
    } catch (Throwable t) {
      call.reject("failed");
    }
  }

  @PluginMethod
  public void remove(PluginCall call) {
    Context c = getContext();
    String id = call.getString("id", "");
    JSONArray a = VAlarmStore.load(c), n = new JSONArray();
    for (int i = 0; i < a.length(); i++) {
      JSONObject x = a.optJSONObject(i);
      if (x != null && !id.equals(x.optString("id", ""))) n.put(x);
    }
    VAlarmStore.save(c, n);
    VAlarmStore.cancel(c, id);
    call.resolve();
  }

  /* what the phone allows right now: each one must be true for the alarm to ring on the lock screen */
  @PluginMethod
  public void state(PluginCall call) {
    Context c = getContext();
    JSObject r = new JSObject();
    r.put("notif", NotificationManagerCompat.from(c).areNotificationsEnabled());
    boolean exact = true;
    if (Build.VERSION.SDK_INT >= 31) {
      AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
      exact = am != null && am.canScheduleExactAlarms();
    }
    r.put("exact", exact);
    boolean fsi = true;
    if (Build.VERSION.SDK_INT >= 34) {
      NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
      fsi = nm != null && nm.canUseFullScreenIntent();
    }
    r.put("fsi", fsi);
    boolean bat = true;
    if (Build.VERSION.SDK_INT >= 23) {
      PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
      bat = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }
    r.put("battery", bat);
    r.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c));
    call.resolve(r);
  }

  /* opens the phone screen where the person can allow one thing */
  @PluginMethod
  public void fix(PluginCall call) {
    String what = call.getString("what", "app");
    Context c = getContext();
    Uri pk = Uri.parse("package:" + c.getPackageName());
    Intent in = null;
    try {
      if ("notif".equals(what)) {
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null) {
          ActivityCompat.requestPermissions(getActivity(), new String[] {"android.permission.POST_NOTIFICATIONS"}, 77);
        }
        in = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
      } else if ("exact".equals(what) && Build.VERSION.SDK_INT >= 31) {
        in = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pk);
      } else if ("fsi".equals(what) && Build.VERSION.SDK_INT >= 34) {
        in = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pk);
      } else if ("overlay".equals(what) && Build.VERSION.SDK_INT >= 23) {
        in = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pk);
      } else if ("battery".equals(what) && Build.VERSION.SDK_INT >= 23) {
        in = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pk);
      }
      if (in == null) in = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pk);
      in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c.startActivity(in);
      call.resolve();
    } catch (Throwable t) {
      try {
        Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pk);
        d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(d);
        call.resolve();
      } catch (Throwable t2) {
        call.reject("cannot open the settings");
      }
    }
  }

  /* rings once right now, so the person can see how it will look */
  @PluginMethod
  public void test(PluginCall call) {
    Context c = getContext();
    Intent s = new Intent(c, VAlarmService.class);
    s.setAction("start");
    s.putExtra("id", "test");
    s.putExtra("path", call.getString("path", ""));
    s.putExtra("title", call.getString("title", ""));
    Long a = call.getLong("s", 0L), b = call.getLong("e", 0L);
    s.putExtra("s", a == null ? 0L : a);
    s.putExtra("e", b == null ? 0L : b);
    try {
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
      else c.startService(s);
      call.resolve();
    } catch (Throwable t) {
      call.reject("could not start");
    }
  }
}
