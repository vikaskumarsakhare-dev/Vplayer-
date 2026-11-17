package com.vplayer.app;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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

/* alarms that ring with a video: set, list, switch on or off, remove, the starting volume, and the phone permissions they need */
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
      /* the numbers come as whole numbers of any size: read them straight from the data */
      o.put("s", call.getData().optLong("s", 0L));
      o.put("e", call.getData().optLong("e", 0L));
      o.put("full", call.getData().optLong("full", 0L));
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

  /* the volume the alarm starts with (0 to 100), or -1 to leave the phone's volume as it is */
  @PluginMethod
  public void getSettings(PluginCall call) {
    JSObject r = new JSObject();
    r.put("vol", VAlarmStore.startVol(getContext()));
    r.put("vib", VAlarmStore.vib(getContext()));
    call.resolve(r);
  }

  @PluginMethod
  public void setSettings(PluginCall call) {
    /* only what is sent is changed */
    if (call.getData().has("vol")) VAlarmStore.setStartVol(getContext(), Math.max(-1, Math.min(100, call.getData().optInt("vol", -1))));
    if (call.getData().has("vib")) VAlarmStore.setVib(getContext(), call.getData().optBoolean("vib", false));
    call.resolve();
  }

  /* what the phone allows right now: both must be true for the alarm to ring on time with its notification */
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
    s.putExtra("vid", call.getString("vid", ""));
    s.putExtra("path", call.getString("path", ""));
    s.putExtra("title", call.getString("title", ""));
    s.putExtra("s", call.getData().optLong("s", 0L));
    s.putExtra("e", call.getData().optLong("e", 0L));
    try {
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
      else c.startService(s);
      call.resolve();
    } catch (Throwable t) {
      call.reject("could not start");
    }
  }

  /* the alarm sound pauses when the person goes to the video (the video has its own sound) */
  private void pauseAlarm() {
    try {
      if (!VAlarmService.ringing) return;
      Intent s = new Intent(getContext(), VAlarmService.class);
      s.setAction("pause");
      getContext().startService(s);
    } catch (Throwable t) {
    }
  }

  /* ends the alarm (the test ring) and removes its notification */
  @PluginMethod
  public void stop(PluginCall call) {
    try {
      Intent s = new Intent(getContext(), VAlarmService.class);
      s.setAction("close");
      getContext().startService(s);
    } catch (Throwable t) {
    }
    call.resolve();
  }

  @PluginMethod
  public void pause(PluginCall call) {
    pauseAlarm();
    call.resolve();
  }

  /* the app was opened by tapping the alarm card: which video, if any */
  @PluginMethod
  public void launch(PluginCall call) {
    JSObject r = new JSObject();
    String v = "";
    try {
      Intent in = getActivity() == null ? null : getActivity().getIntent();
      if (in != null && in.getStringExtra("vp_alarm_vid") != null) {
        v = in.getStringExtra("vp_alarm_vid");
        in.removeExtra("vp_alarm_vid");
      }
    } catch (Throwable t) {
    }
    if (v.length() > 0) pauseAlarm();
    r.put("id", v);
    call.resolve(r);
  }

  /* the app was already open and the alarm card was tapped */
  @Override
  protected void handleOnNewIntent(Intent in) {
    super.handleOnNewIntent(in);
    try {
      String v = in == null ? null : in.getStringExtra("vp_alarm_vid");
      if (v != null && v.length() > 0) {
        in.removeExtra("vp_alarm_vid");
        pauseAlarm();
        JSObject o = new JSObject();
        o.put("id", v);
        notifyListeners("openvideo", o, true);
      }
    } catch (Throwable t) {
    }
  }
}
