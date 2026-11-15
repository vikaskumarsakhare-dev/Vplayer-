package com.vplayer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* after the phone restarts, the time changes, or the app is updated, every alarm is set again */
public class VAlarmBoot extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent in) {
    try {
      VAlarmStore.scheduleAll(c);
    } catch (Throwable t) {
    }
  }
}
