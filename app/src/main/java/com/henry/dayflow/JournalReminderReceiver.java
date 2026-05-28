package com.henry.dayflow;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class JournalReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "dayflow_journal_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            JournalReminderScheduler.reschedule(context);
            return;
        }

        String kind = intent == null ? JournalReminderScheduler.KIND_REFLECTIONS
                : intent.getStringExtra(JournalReminderScheduler.EXTRA_KIND);
        if (kind == null) kind = JournalReminderScheduler.KIND_REFLECTIONS;
        showReminder(context, kind);
        JournalReminderScheduler.scheduleNext(context, kind);
    }

    private void showReminder(Context context, String kind) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        createChannel(context);

        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, "Journal");
        PendingIntent openIntent = PendingIntent.getActivity(
                context,
                4201,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean intentions = JournalReminderScheduler.KIND_INTENTIONS.equals(kind);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(intentions ? "Set your intentions" : "Time to reflect")
                .setContentText(intentions ? "Take a moment to plan your day with Dayflow." : "Close the loop with a quick journal reflection.")
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(intentions ? 4201 : 4202, notification);
    }

    private void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Journal reminders",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Reminders to set intentions and reflect in Dayflow.");
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
