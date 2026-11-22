package com.tvbox.parental;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;

/**
 * A persisted system job is restored by JobScheduler after a reboot without
 * relying on BOOT_COMPLETED broadcasts, which this vendor firmware blocks for
 * third-party apps. It also acts as a watchdog if the service is killed.
 */
public class KeepAliveJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        startForegroundService(new Intent(this, ControlService.class));
        ControlService.scheduleKeepAlive(this); // re-arm the job watchdog
        KeepAliveReceiver.schedule(this);       // and the 20s alarm
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule
    }
}
