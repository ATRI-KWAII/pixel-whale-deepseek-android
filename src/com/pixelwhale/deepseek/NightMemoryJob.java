package com.pixelwhale.deepseek;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.List;

/** Android schedules a single run, then the service computes the next local night. */
public final class NightMemoryJob extends JobService {
    private static final int JOB_ID = 2502;
    private static boolean running;
    private DeepSeekClient.Control active;

    static boolean sync(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        if (!new MemoryStore(context).nightly()) {
            scheduler.cancel(JOB_ID);
            return true;
        }
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == JOB_ID) return true;
        }
        return schedule(context, scheduler, false);
    }

    static boolean runSoon(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return scheduler != null && new MemoryStore(context).nightly()
                && (running || schedule(context, scheduler, true));
    }

    private static boolean schedule(Context context, JobScheduler scheduler, boolean immediate) {
        Calendar next = Calendar.getInstance();
        long now = next.getTimeInMillis();
        next.set(Calendar.HOUR_OF_DAY, 2);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= now) next.add(Calendar.DAY_OF_YEAR, 1);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, NightMemoryJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(immediate ? 0 : next.getTimeInMillis() - now)
                .setPersisted(true)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    @Override public boolean onStartJob(JobParameters params) {
        MemoryStore store = new MemoryStore(this);
        if (!store.nightly()) return false;
        MemoryStore.Snapshot snapshot = store.snapshot();
        DeepSeekClient.Control control = new DeepSeekClient.Control();
        active = control;
        running = true;
        new Thread(() -> organize(params, store, snapshot, control), "night-memory").start();
        return true;
    }

    private void organize(JobParameters params, MemoryStore store, MemoryStore.Snapshot snapshot,
                          DeepSeekClient.Control control) {
        List<String> entries = null;
        String status = "暂无新消息需要整理";
        try {
            if (!snapshot.samples.isEmpty()) {
                String key = new SecretStore(this).read();
                if (key == null || key.isEmpty()) status = "请先设置 DeepSeek API Key";
                else entries = DeepSeekClient.organizeMemories(key, snapshot, control);
            }
        } catch (ApiError error) {
            status = error.getMessage();
        } catch (Exception error) {
            status = "整理未完成，下次会重新处理待整理消息";
        }
        List<String> result = entries;
        String outcome = status;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (active != control) return;
            active = null;
            running = false;
            if (result == null) store.report(outcome);
            else if (!store.finish(snapshot, result)) store.report("记忆在整理期间有修改，已保留你的改动");
            jobFinished(params, false);
            JobScheduler scheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            if (store.nightly() && scheduler != null) schedule(this, scheduler, false);
        });
    }

    @Override public boolean onStopJob(JobParameters params) {
        DeepSeekClient.Control control = active;
        active = null;
        running = false;
        if (control != null) control.cancel();
        return new MemoryStore(this).nightly();
    }
}
