package com.henry.dayflow;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class TimeUtil {
    static final long SECOND = 1000L;
    static final long MINUTE = 60L * SECOND;
    static final long HOUR = 60L * MINUTE;
    static final long DAY = 24L * HOUR;

    private TimeUtil() {}

    static String dayKey(long timestampMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestampMs);
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) {
            cal.add(Calendar.DATE, -1);
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    static long dayStartMs(String dayKey) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey);
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            cal.set(Calendar.HOUR_OF_DAY, 4);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception ignored) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 4);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }
    }

    static long weekStartMs(long timestampMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestampMs);
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) {
            cal.add(Calendar.DATE, -1);
        }
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DATE, -1);
        }
        cal.set(Calendar.HOUR_OF_DAY, 4);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    static String weekLabel(long weekStartMs) {
        SimpleDateFormat format = new SimpleDateFormat("MMM d", Locale.US);
        String start = format.format(new Date(weekStartMs));
        String end = format.format(new Date(weekStartMs + 6 * DAY));
        return start + " - " + end;
    }

    static String timeLabel(long timestampMs) {
        SimpleDateFormat format = new SimpleDateFormat("h:mm a", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(timestampMs));
    }

    static String shortDuration(long durationMs) {
        long minutes = Math.max(0, durationMs / MINUTE);
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (hours > 0 && remainingMinutes > 0) return hours + "h " + remainingMinutes + "m";
        if (hours > 0) return hours + "h";
        return remainingMinutes + "m";
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
