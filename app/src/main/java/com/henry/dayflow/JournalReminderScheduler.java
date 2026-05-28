package com.henry.dayflow;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.Locale;

final class JournalReminderScheduler {
    static final String ACTION_REMINDER = "com.henry.dayflow.JOURNAL_REMINDER";
    static final String EXTRA_KIND = "kind";
    static final String KIND_INTENTIONS = "intentions";
    static final String KIND_REFLECTIONS = "reflections";

    private JournalReminderScheduler() {}

    static void reschedule(Context context) {
        DayflowPrefs prefs = new DayflowPrefs(context);
        cancel(context);
        if (!prefs.journalRemindersEnabled()) return;
        if (!prefs.journalRemindersHaveWeekday()) return;
        scheduleKind(context, prefs, KIND_INTENTIONS);
        scheduleKind(context, prefs, KIND_REFLECTIONS);
    }

    static void cancel(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        alarm.cancel(pendingIntent(context, KIND_INTENTIONS));
        alarm.cancel(pendingIntent(context, KIND_REFLECTIONS));
    }

    static void scheduleNext(Context context, String kind) {
        DayflowPrefs prefs = new DayflowPrefs(context);
        if (!prefs.journalRemindersEnabled()) return;
        if (!prefs.journalRemindersHaveWeekday()) return;
        scheduleKind(context, prefs, kind == null ? KIND_REFLECTIONS : kind);
    }

    static String reminderSummary(Context context) {
        DayflowPrefs prefs = new DayflowPrefs(context);
        if (!prefs.journalRemindersEnabled()) return "Reminders off";
        if (!prefs.journalRemindersHaveWeekday()) return "Reminders on · no weekdays selected";
        return String.format(
                Locale.US,
                "Intentions %02d:%02d · Reflections %02d:%02d · %s",
                prefs.journalIntentionHour(),
                prefs.journalIntentionMinute(),
                prefs.journalReflectionHour(),
                prefs.journalReflectionMinute(),
                weekdaySummary(prefs));
    }

    private static void scheduleKind(Context context, DayflowPrefs prefs, String kind) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        int hour = KIND_INTENTIONS.equals(kind) ? prefs.journalIntentionHour() : prefs.journalReflectionHour();
        int minute = KIND_INTENTIONS.equals(kind) ? prefs.journalIntentionMinute() : prefs.journalReflectionMinute();
        long triggerAt = nextTriggerMs(prefs, hour, minute);
        alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, kind));
    }

    private static long nextTriggerMs(DayflowPrefs prefs, int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar candidate = Calendar.getInstance();
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);
        candidate.set(Calendar.HOUR_OF_DAY, hour);
        candidate.set(Calendar.MINUTE, minute);

        for (int i = 0; i < 8; i++) {
            int weekday = candidate.get(Calendar.DAY_OF_WEEK);
            if (candidate.getTimeInMillis() > now.getTimeInMillis() && prefs.journalReminderIncludesWeekday(weekday)) {
                return candidate.getTimeInMillis();
            }
            candidate.add(Calendar.DATE, 1);
            candidate.set(Calendar.HOUR_OF_DAY, hour);
            candidate.set(Calendar.MINUTE, minute);
        }
        return now.getTimeInMillis() + TimeUtil.DAY;
    }

    private static PendingIntent pendingIntent(Context context, String kind) {
        Intent intent = new Intent(context, JournalReminderReceiver.class)
                .setAction(ACTION_REMINDER)
                .putExtra(EXTRA_KIND, kind);
        int requestCode = KIND_INTENTIONS.equals(kind) ? 4101 : 4102;
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String weekdaySummary(DayflowPrefs prefs) {
        String[] labels = {"", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        StringBuilder sb = new StringBuilder();
        for (int day = 1; day <= 7; day++) {
            if (!prefs.journalReminderIncludesWeekday(day)) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(labels[day]);
        }
        return sb.length() == 0 ? "no days" : sb.toString();
    }
}
