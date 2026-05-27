package com.henry.dayflow;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DayflowDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "dayflow.sqlite";
    private static final int DB_VERSION = 1;

    DayflowDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE screenshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "captured_at INTEGER NOT NULL," +
                "file_path TEXT NOT NULL," +
                "file_size INTEGER," +
                "package_name TEXT," +
                "app_label TEXT," +
                "is_deleted INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_screenshots_captured ON screenshots(captured_at)");

        db.execSQL("CREATE TABLE analysis_batches (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "batch_start_ts INTEGER NOT NULL," +
                "batch_end_ts INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'pending'," +
                "reason TEXT," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_batches_status ON analysis_batches(status)");

        db.execSQL("CREATE TABLE batch_screenshots (" +
                "batch_id INTEGER NOT NULL," +
                "screenshot_id INTEGER NOT NULL," +
                "PRIMARY KEY(batch_id, screenshot_id))");
        db.execSQL("CREATE INDEX idx_batch_screenshots_screenshot ON batch_screenshots(screenshot_id)");

        db.execSQL("CREATE TABLE observations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "batch_id INTEGER NOT NULL," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER NOT NULL," +
                "observation TEXT NOT NULL," +
                "metadata TEXT," +
                "llm_model TEXT," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_observations_time ON observations(start_ts, end_ts)");

        db.execSQL("CREATE TABLE timeline_cards (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "batch_id INTEGER," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER NOT NULL," +
                "day TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "summary TEXT," +
                "detailed_summary TEXT," +
                "category TEXT NOT NULL," +
                "subcategory TEXT," +
                "metadata TEXT," +
                "created_at INTEGER NOT NULL," +
                "is_deleted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_cards_day ON timeline_cards(day)");
        db.execSQL("CREATE INDEX idx_cards_time ON timeline_cards(start_ts, end_ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    synchronized long insertScreenshot(File file, long capturedAtMs, String packageName, String appLabel) {
        ContentValues values = new ContentValues();
        values.put("captured_at", capturedAtMs);
        values.put("file_path", file.getAbsolutePath());
        values.put("file_size", file.length());
        values.put("package_name", packageName);
        values.put("app_label", appLabel);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("screenshots", null, values);
    }

    synchronized List<ScreenshotRecord> fetchUnprocessedScreenshots(long sinceMs) {
        String sql = "SELECT * FROM screenshots WHERE captured_at >= ? AND is_deleted = 0 " +
                "AND id NOT IN (SELECT screenshot_id FROM batch_screenshots) ORDER BY captured_at ASC";
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(sinceMs)});
        try {
            List<ScreenshotRecord> records = new ArrayList<>();
            while (c.moveToNext()) records.add(readScreenshot(c));
            return records;
        } finally {
            c.close();
        }
    }

    synchronized long saveBatch(long startMs, long endMs, List<ScreenshotRecord> screenshots) {
        if (screenshots.isEmpty()) return -1L;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues batch = new ContentValues();
            batch.put("batch_start_ts", startMs);
            batch.put("batch_end_ts", endMs);
            batch.put("status", "pending");
            batch.put("created_at", System.currentTimeMillis());
            long batchId = db.insert("analysis_batches", null, batch);
            for (ScreenshotRecord screenshot : screenshots) {
                ContentValues join = new ContentValues();
                join.put("batch_id", batchId);
                join.put("screenshot_id", screenshot.id);
                db.insert("batch_screenshots", null, join);
            }
            db.setTransactionSuccessful();
            return batchId;
        } finally {
            db.endTransaction();
        }
    }

    synchronized void updateBatch(long batchId, String status, String reason) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("reason", reason);
        getWritableDatabase().update("analysis_batches", values, "id = ?", new String[]{String.valueOf(batchId)});
    }

    synchronized long[] batchWindow(long batchId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT batch_start_ts, batch_end_ts FROM analysis_batches WHERE id = ?",
                new String[]{String.valueOf(batchId)});
        try {
            if (!c.moveToFirst()) return null;
            return new long[]{c.getLong(0), c.getLong(1)};
        } finally {
            c.close();
        }
    }

    synchronized List<ScreenshotRecord> screenshotsForBatch(long batchId) {
        String sql = "SELECT s.* FROM batch_screenshots bs JOIN screenshots s ON s.id = bs.screenshot_id " +
                "WHERE bs.batch_id = ? AND s.is_deleted = 0 ORDER BY s.captured_at ASC";
        Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(batchId)});
        try {
            List<ScreenshotRecord> records = new ArrayList<>();
            while (c.moveToNext()) records.add(readScreenshot(c));
            return records;
        } finally {
            c.close();
        }
    }

    synchronized void saveObservations(long batchId, List<TimelineCard> cards) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("observations", "batch_id = ?", new String[]{String.valueOf(batchId)});
            for (TimelineCard card : cards) {
                ContentValues values = new ContentValues();
                values.put("batch_id", batchId);
                values.put("start_ts", card.startMs);
                values.put("end_ts", card.endMs);
                values.put("observation", card.detailedSummary);
                values.put("metadata", card.metadata);
                values.put("llm_model", "dayflow-android");
                values.put("created_at", System.currentTimeMillis());
                db.insert("observations", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized List<TimelineCard> fetchTimelineCards(String day) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM timeline_cards WHERE day = ? AND is_deleted = 0 ORDER BY start_ts ASC",
                new String[]{day});
        try {
            List<TimelineCard> cards = new ArrayList<>();
            while (c.moveToNext()) cards.add(readCard(c));
            return cards;
        } finally {
            c.close();
        }
    }

    synchronized List<TimelineCard> fetchTimelineCardsRange(long startMs, long endMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM timeline_cards WHERE is_deleted = 0 AND start_ts < ? AND end_ts > ? ORDER BY start_ts ASC",
                new String[]{String.valueOf(endMs), String.valueOf(startMs)});
        try {
            List<TimelineCard> cards = new ArrayList<>();
            while (c.moveToNext()) cards.add(readCard(c));
            return cards;
        } finally {
            c.close();
        }
    }

    synchronized void replaceTimelineCardsInRange(long fromMs, long toMs, List<TimelineCard> cards, long batchId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues deleted = new ContentValues();
            deleted.put("is_deleted", 1);
            db.update(
                    "timeline_cards",
                    deleted,
                    "is_deleted = 0 AND start_ts < ? AND end_ts > ?",
                    new String[]{String.valueOf(toMs), String.valueOf(fromMs)});

            for (TimelineCard card : cards) {
                ContentValues values = new ContentValues();
                values.put("batch_id", batchId);
                values.put("start_ts", card.startMs);
                values.put("end_ts", card.endMs);
                values.put("day", TimeUtil.dayKey(card.startMs));
                values.put("title", card.title);
                values.put("summary", card.summary);
                values.put("detailed_summary", card.detailedSummary);
                values.put("category", card.category);
                values.put("subcategory", card.subcategory);
                values.put("metadata", card.metadata);
                values.put("created_at", System.currentTimeMillis());
                db.insert("timeline_cards", null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized DashboardMetrics dashboardForDay(String day) {
        List<TimelineCard> cards = fetchTimelineCards(day);
        DashboardMetrics metrics = new DashboardMetrics();
        metrics.cardCount = cards.size();
        for (TimelineCard card : cards) {
            long duration = card.durationMs();
            metrics.trackedMs += duration;
            String category = card.category == null ? "Work" : card.category;
            addToMap(metrics.categoryMs, category, duration);
            if (!category.toLowerCase().contains("distraction") && !category.toLowerCase().contains("idle")) {
                metrics.productiveMs += duration;
            }
            if (category.toLowerCase().contains("distraction")) {
                metrics.distractionMs += duration;
            }
            String app = appFromMetadata(card.metadata);
            if (app != null) addToMap(metrics.appMs, app, duration);
        }
        return metrics;
    }

    synchronized String exportMarkdown(String day) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Dayflow timeline · ").append(day).append("\n\n");
        for (TimelineCard card : fetchTimelineCards(day)) {
            sb.append("- **")
                    .append(TimeUtil.timeLabel(card.startMs))
                    .append(" - ")
                    .append(TimeUtil.timeLabel(card.endMs))
                    .append("** · ")
                    .append(card.category)
                    .append(" · ")
                    .append(card.title)
                    .append("\n  ")
                    .append(card.summary == null ? "" : card.summary)
                    .append("\n");
        }
        return sb.toString();
    }

    private ScreenshotRecord readScreenshot(Cursor c) {
        return new ScreenshotRecord(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getLong(c.getColumnIndexOrThrow("captured_at")),
                c.getString(c.getColumnIndexOrThrow("file_path")),
                c.getLong(c.getColumnIndexOrThrow("file_size")),
                c.getString(c.getColumnIndexOrThrow("package_name")),
                c.getString(c.getColumnIndexOrThrow("app_label")));
    }

    private TimelineCard readCard(Cursor c) {
        TimelineCard card = new TimelineCard();
        card.id = c.getLong(c.getColumnIndexOrThrow("id"));
        card.batchId = c.getLong(c.getColumnIndexOrThrow("batch_id"));
        card.startMs = c.getLong(c.getColumnIndexOrThrow("start_ts"));
        card.endMs = c.getLong(c.getColumnIndexOrThrow("end_ts"));
        card.day = c.getString(c.getColumnIndexOrThrow("day"));
        card.title = c.getString(c.getColumnIndexOrThrow("title"));
        card.summary = c.getString(c.getColumnIndexOrThrow("summary"));
        card.detailedSummary = c.getString(c.getColumnIndexOrThrow("detailed_summary"));
        card.category = c.getString(c.getColumnIndexOrThrow("category"));
        card.subcategory = c.getString(c.getColumnIndexOrThrow("subcategory"));
        card.metadata = c.getString(c.getColumnIndexOrThrow("metadata"));
        return card;
    }

    private static void addToMap(Map<String, Long> map, String key, long value) {
        Long current = map.get(key);
        map.put(key, current == null ? value : current + value);
    }

    private static String appFromMetadata(String metadata) {
        if (metadata == null) return null;
        String marker = "app=";
        int start = metadata.indexOf(marker);
        if (start < 0) return null;
        int end = metadata.indexOf(';', start);
        return end > start ? metadata.substring(start + marker.length(), end) : metadata.substring(start + marker.length());
    }

    static <K> List<Map.Entry<K, Long>> sortedByDuration(Map<K, Long> map) {
        List<Map.Entry<K, Long>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<K, Long>>() {
            @Override
            public int compare(Map.Entry<K, Long> a, Map.Entry<K, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        return entries;
    }
}
