package com.vplayer.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONObject;

/* the alarms the person set: kept in the phone's own small store, and each one is given to the system alarm clock */
public class VAlarmStore {
  static final String PREF = "vp_alarm";

  static JSONArray load(Context c) {
    try {
      return new JSONArray(c.getSharedPreferences(PREF, 0).getString("list", "[]"));
    } catch (Throwable t) {
      return new JSONArray();
    }
  }

  static void save(Context c, JSONArray a) {
    c.getSharedPreferences(PREF, 0).edit().putString("list", a.toString()).apply();
  }

  /* the volume the alarm starts with: 0 to 100, or -1 to leave the phone's volume as it is */
  static int startVol(Context c) {
    return c.getSharedPreferences(PREF, 0).getInt("vol", -1);
  }

  static void setStartVol(Context c, int v) {
    c.getSharedPreferences(PREF, 0).edit().putInt("vol", v).apply();
  }

  static JSONObject find(Context c, String id) {
    JSONArray a = load(c);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null && id.equals(o.optString("id", ""))) return o;
    }
    return null;
  }

  /* the next moment (ms) this alarm should ring after 'after'; days is a list of 0..6 (Sunday = 0); empty means once */
  static long nextTime(int h, int m, JSONArray days, long after) {
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(after);
    cal.set(Calendar.HOUR_OF_DAY, h);
    cal.set(Calendar.MINUTE, m);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    for (int add = 0; add < 9; add++) {
      long t = cal.getTimeInMillis();
      if (t > after) {
        if (days == null || days.length() == 0) return t;
        int dow = cal.get(Calendar.DAY_OF_WEEK) - 1;
        for (int i = 0; i < days.length(); i++) if (days.optInt(i, -1) == dow) return t;
      }
      cal.add(Calendar.DAY_OF_YEAR, 1);
    }
    return after + 86400000L;
  }

  static PendingIntent fire(Context c, String id) {
    Intent in = new Intent(c, VAlarmReceiver.class);
    in.setAction("com.vplayer.app.ALARM");
    in.putExtra("id", id);
    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    return PendingIntent.getBroadcast(c, id.hashCode(), in, fl);
  }

  static void cancel(Context c, String id) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (am != null) am.cancel(fire(c, id));
  }

  /* gives one alarm to the system clock; returns the time it will ring, or 0 when it is off */
  static long schedule(Context c, JSONObject o) {
    String id = o.optString("id", "");
    cancel(c, id);
    if (!o.optBoolean("on", true)) return 0L;
    long when = nextTime(o.optInt("h", 7), o.optInt("m", 0), o.optJSONArray("days"), System.currentTimeMillis());
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    if (am == null) return 0L;
    PendingIntent pi = fire(c, id);
    try {
      Intent show = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
      int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
      PendingIntent sp = PendingIntent.getActivity(c, 7, show, fl);
      am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, sp), pi);
    } catch (Throwable t) {
      try {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
      } catch (Throwable t2) {
        return 0L;
      }
    }
    return when;
  }

  static void scheduleAll(Context c) {
    JSONArray a = load(c);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null) schedule(c, o);
    }
  }
}
