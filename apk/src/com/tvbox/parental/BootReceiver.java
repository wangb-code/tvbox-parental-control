package com.tvbox.parental;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Brings the control service back after every reboot, with no PC involved. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent s = new Intent(context, ControlService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(s);
        } else {
            context.startService(s);
        }
        KeepAliveReceiver.schedule(context);
    }
}
