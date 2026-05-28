package com.henry.dayflow;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;

final class ReadyNotificationCenter {
    private static final String CHANNEL_ID = "dayflow_ready";
    private static final long DAILY_REQUIRED_MS = 5 * TimeUtil.HOUR;
    private static final long WEEKLY_REQUIRED_MS = 30 * TimeUtil.HOUR;
    private static final int DAILY_NOTIFICATION_ID = 4301;
    private static final int WEEKLY_NOTIFICATION_ID = 4302;

    private ReadyNotificationCenter() {}

    static void checkAfterAnalysis(Context context) {
        Context appContext = context.getApplicationContext();
        DayflowDatabase db = new DayflowDatabase(appContext);
        DayflowPrefs prefs = new DayflowPrefs(appContext);
        maybeNotifyDaily(appContext, db, prefs);
        maybeNotifyWeekly(appContext, db, prefs);
    }

    private static void maybeNotifyDaily(Context context, DayflowDatabase db, DayflowPrefs prefs) {
        if (db.analyzedBatchDurationMs() < DAILY_REQUIRED_MS) return;
        String day = bestDailyDay(db);
        if (day.equals(prefs.dailyReadyNotifiedDay())) return;

        if (db.fetchDailyStandup(day) == null) {
            String standup = new ChatResponder(context).standup(day);
            db.saveDailyStandup(day, standup);
        }

        if (showReadyNotification(
                context,
                DAILY_NOTIFICATION_ID,
                "Your daily recap is ready",
                "Tap to open it in Daily view.",
                MainActivity.TAB_DAILY,
                day,
                0L)) {
            prefs.setDailyReadyNotifiedDay(day);
        }
    }

    private static void maybeNotifyWeekly(Context context, DayflowDatabase db, DayflowPrefs prefs) {
        if (db.analyzedBatchDurationMs() < WEEKLY_REQUIRED_MS) return;
        long weekStart = bestWeeklyStart(db);
        if (weekStart <= 0 || weekStart == prefs.weeklyReadyNotifiedWeekStartMs()) return;
        if (showReadyNotification(
                context,
                WEEKLY_NOTIFICATION_ID,
                "Weekly view is ready",
                "Tap to open your weekly review.",
                MainActivity.TAB_WEEKLY,
                TimeUtil.dayKey(weekStart),
                weekStart)) {
            prefs.setWeeklyReadyNotifiedWeekStartMs(weekStart);
        }
    }

    private static String bestDailyDay(DayflowDatabase db) {
        String today = TimeUtil.dayKey(System.currentTimeMillis());
        if (!db.fetchTimelineCards(today).isEmpty()) return today;
        String yesterday = TimeUtil.dayKey(TimeUtil.dayStartMs(today) - TimeUtil.HOUR);
        if (!db.fetchTimelineCards(yesterday).isEmpty()) return yesterday;
        return today;
    }

    private static long bestWeeklyStart(DayflowDatabase db) {
        long current = TimeUtil.weekStartMs(System.currentTimeMillis());
        for (int i = 0; i < 12; i++) {
            long candidate = current - i * 7L * TimeUtil.DAY;
            List<TimelineCard> cards = db.fetchTimelineCardsRange(candidate, candidate + 7L * TimeUtil.DAY);
            if (!cards.isEmpty()) return candidate;
        }
        return current;
    }

    private static boolean showReadyNotification(Context context, int id, String title, String body, String tab, String day, long weekStartMs) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        createChannel(context);

        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
                .putExtra(MainActivity.EXTRA_OPEN_DAY, day)
                .putExtra(MainActivity.EXTRA_OPEN_WEEK_START, weekStartMs);
        PendingIntent openIntent = PendingIntent.getActivity(
                context,
                id,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        manager.notify(id, notification);
        return true;
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Dayflow ready",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Notifications when Dayflow Daily or Weekly is ready.");
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
