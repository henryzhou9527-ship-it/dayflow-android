package com.henry.dayflow;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;

final class ForegroundAppReader {
    static final class AppSnapshot {
        final String packageName;
        final String label;

        AppSnapshot(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private final Context context;
    private final PackageManager packageManager;

    ForegroundAppReader(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = context.getPackageManager();
    }

    boolean hasUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    Intent usageAccessIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    AppSnapshot currentApp() {
        if (!hasUsageAccess()) return new AppSnapshot(null, null);
        UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage == null) return new AppSnapshot(null, null);

        long end = System.currentTimeMillis();
        long start = end - 5 * TimeUtil.MINUTE;
        UsageEvents events = usage.queryEvents(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String packageName = null;
        long latest = 0;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if ((type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.ACTIVITY_RESUMED)
                    && event.getTimeStamp() >= latest) {
                latest = event.getTimeStamp();
                packageName = event.getPackageName();
            }
        }

        if (packageName == null) return new AppSnapshot(null, null);
        return new AppSnapshot(packageName, labelFor(packageName));
    }

    String labelFor(String packageName) {
        if (packageName == null) return null;
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
