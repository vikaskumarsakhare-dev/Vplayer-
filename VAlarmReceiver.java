package com.vplayer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/* the system calls this at the set time */
public class VAlarmReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent in) {
    String id = in.getStringExtra("id");
    if (id == null) return;
    JSONObject o = VAlarmStore.find(c, id);
    if (o == null || !o.optBoolean("on", true)) return;
    try {
      JSONArray days = o.optJSONArray("days");
      if (days == null || days.length() == 0) {
        /* a one time alarm switches itself off after it rings */
        o.put("on", false);
        JSONArray a = VAlarmStore.load(c);
        for (int i = 0; i < a.length(); i++) {
          JSONObject x = a.optJSONObject(i);
          if (x != null && id.equals(x.optString("id", ""))) a.put(i, o);
        }
        VAlarmStore.save(c, a);
      } else {
        VAlarmStore.schedule(c, o);
      }
    } catch (Throwable t) {
    }
    Intent s = new Intent(c, VAlarmService.class);
    s.setAction("start");
    s.putExtra("id", id);
    s.putExtra("vid", o.optString("vid", ""));
    s.putExtra("path", o.optString("path", ""));
    s.putExtra("title", o.optString("title", ""));
    s.putExtra("s", o.optLong("s", 0L));
    s.putExtra("e", o.optLong("e", 0L));
    try {
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
      else c.startService(s);
    } catch (Throwable t) {
    }
  }
}
