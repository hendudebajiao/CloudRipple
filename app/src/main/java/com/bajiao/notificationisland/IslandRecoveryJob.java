package com.bajiao.notificationisland;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Persisted recovery safety net; the bound listener remains the immediate notification entry point. */
public final class IslandRecoveryJob extends JobService {
    private static final int ID = 17010;

    static boolean schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) != null) return true;
        return scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(context, IslandRecoveryJob.class))
                .setPersisted(true).setPeriodic(15 * 60 * 1000L).build()) == JobScheduler.RESULT_SUCCESS;
    }

    static void cancel(Context context) { context.getSystemService(JobScheduler.class).cancel(ID); }

    @Override public boolean onStartJob(JobParameters params) {
        IslandNotificationListener.refresh(this);
        if (!RootKeepaliveManager.isEnabled(this)) return false;
        RootKeepaliveManager.enable(this, success -> jobFinished(params, false));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
