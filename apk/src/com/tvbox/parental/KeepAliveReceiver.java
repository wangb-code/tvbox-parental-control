package com.tvbox.parental;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Second watchdog for this vendor box: the low-memory killer repeatedly
 * SIGKILLs the service (no onDestroy callback), and JobScheduler then needs
 * 60-120s to bring it back. An AlarmManager alarm lives in system_server, so a
 * statically registered receiver still fires after our process is gone.
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.tvbox.parental.KEEP_ALIVE";
    private static final int REQ = 1002;
    private static final long INTERVAL = 20 * 1000L;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        if (!running(ctx)) {
            Intent s = new Intent(ctx, ControlService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(s);
                } else {
                    ctx.startService(s);
                }
                Log.i("TVCTL", "watchdog restarted control service");
            } catch (Exception e) {
                Log.w("TVCTL", "watchdog start failed: " + e.getMessage());
            }
        }
        schedule(ctx);   // re-arm so the alarm survives a service restart
    }

    /** Called from the service, the job and the boot receiver. */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(ACTION).setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL, pi);
        } catch (Exception ignored) {
        }
    }

    private static boolean running(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (ActivityManager.RunningServiceInfo si : am.getRunningServices(64)) {
                if (ControlService.class.getName().equals(si.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
