package com.henry.dayflow;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DayflowDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "dayflow.sqlite";
    private static final int DB_VERSION = 9;

    private final Context appContext;

    DayflowDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createCoreTables(db);
        createFeatureTables(db);
        seedDefaultCategories(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createFeatureTables(db);
            seedDefaultCategories(db);
        }
        if (oldVersion < 3) {
            createFeatureTables(db);
        }
        if (oldVersion < 4) {
            createFeatureTables(db);
        }
        if (oldVersion < 5) {
            addColumnIfMissing(db, "timeline_cards", "video_summary_path", "TEXT");
        }
        if (oldVersion < 6) {
            createFeatureTables(db);
        }
        if (oldVersion >= 6 && oldVersion < 7) {
            createFeatureTables(db);
        }
        if (oldVersion < 8) {
            addColumnIfMissing(db, "screenshots", "window_title", "TEXT");
            addColumnIfMissing(db, "screenshots", "visible_text", "TEXT");
        }
        if (oldVersion < 9) {
            createFeatureTables(db);
        }
    }

    private void createCoreTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE screenshots (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "captured_at INTEGER NOT NULL," +
                "file_path TEXT NOT NULL," +
                "file_size INTEGER," +
                "package_name TEXT," +
                "app_label TEXT," +
                "window_title TEXT," +
                "visible_text TEXT," +
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
                "video_summary_path TEXT," +
                "category TEXT NOT NULL," +
                "subcategory TEXT," +
                "metadata TEXT," +
                "created_at INTEGER NOT NULL," +
                "is_deleted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_cards_day ON timeline_cards(day)");
        db.execSQL("CREATE INDEX idx_cards_time ON timeline_cards(start_ts, end_ts)");
    }

    private void createFeatureTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "color_hex TEXT NOT NULL," +
                "details TEXT," +
                "sort_order INTEGER NOT NULL," +
                "is_system INTEGER NOT NULL DEFAULT 0," +
                "is_idle INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_categories_order ON categories(sort_order)");

        db.execSQL("CREATE TABLE IF NOT EXISTS journal_entries (" +
                "day TEXT PRIMARY KEY," +
                "intentions TEXT," +
                "notes TEXT," +
                "goals TEXT," +
                "reflections TEXT," +
                "summary TEXT," +
                "status TEXT NOT NULL DEFAULT 'draft'," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE IF NOT EXISTS day_goals (" +
                "day TEXT PRIMARY KEY," +
                "focus_target_minutes INTEGER NOT NULL," +
                "distraction_limit_minutes INTEGER NOT NULL," +
                "is_skipped INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE IF NOT EXISTS day_goal_categories (" +
                "day TEXT NOT NULL," +
                "kind TEXT NOT NULL," +
                "category_name TEXT NOT NULL," +
                "color_hex TEXT NOT NULL," +
                "sort_order INTEGER NOT NULL," +
                "PRIMARY KEY(day, kind, category_name))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_day_goal_categories_day_kind ON day_goal_categories(day, kind, sort_order)");

        db.execSQL("CREATE TABLE IF NOT EXISTS timeline_review_ratings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER NOT NULL," +
                "rating TEXT NOT NULL," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_review_ratings_time ON timeline_review_ratings(start_ts, end_ts)");

        db.execSQL("CREATE TABLE IF NOT EXISTS timeline_summary_feedback (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "card_id INTEGER NOT NULL," +
                "direction TEXT NOT NULL," +
                "message TEXT," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_summary_feedback_card ON timeline_summary_feedback(card_id, created_at)");

        db.execSQL("CREATE TABLE IF NOT EXISTS blocked_apps (" +
                "package_name TEXT PRIMARY KEY," +
                "app_label TEXT," +
                "is_blocked INTEGER NOT NULL DEFAULT 1," +
                "updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_messages_created ON chat_messages(created_at)");

        db.execSQL("CREATE TABLE IF NOT EXISTS daily_standup_entries (" +
                "standup_day TEXT PRIMARY KEY," +
                "payload_text TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_standups_updated ON daily_standup_entries(updated_at)");

        db.execSQL("CREATE TABLE IF NOT EXISTS llm_calls (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "created_at INTEGER NOT NULL," +
                "batch_id INTEGER," +
                "provider TEXT NOT NULL," +
                "model TEXT," +
                "operation TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "latency_ms INTEGER," +
                "screenshot_count INTEGER," +
                "card_count INTEGER," +
                "error_message TEXT," +
                "request_summary TEXT," +
                "response_summary TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_llm_calls_created ON llm_calls(created_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_llm_calls_batch ON llm_calls(batch_id)");
    }

    private void seedDefaultCategories(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM categories", null);
        try {
            if (c.moveToFirst() && c.getInt(0) > 0) return;
        } finally {
            c.close();
        }
        insertCategory(db, "Work", "#B984FF", "Career, school, or productivity-focused activities.", 0, false, false);
        insertCategory(db, "Personal", "#6AADFF", "Purposeful non-work activities and life tasks.", 1, false, false);
        insertCategory(db, "Communication", "#FFAE8C", "Meetings, messages, calls, email, and syncs.", 2, false, false);
        insertCategory(db, "Distraction", "#FF5950", "Passive consumption, idle scrolling, and entertainment without clear intent.", 3, false, false);
        insertCategory(db, "Idle", "#A0AEC0", "Use when the user is idle for most of the period.", 4, true, true);
    }

    private void insertCategory(SQLiteDatabase db, String name, String color, String details, int order, boolean system, boolean idle) {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("name", name);
        values.put("color_hex", color);
        values.put("details", details);
        values.put("sort_order", order);
        values.put("is_system", system ? 1 : 0);
        values.put("is_idle", idle ? 1 : 0);
        values.put("created_at", now);
        values.put("updated_at", now);
        db.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    synchronized long insertScreenshot(File file, long capturedAtMs, String packageName, String appLabel) {
        return insertScreenshot(file, capturedAtMs, packageName, appLabel, null, null);
    }

    synchronized long insertScreenshot(File file, long capturedAtMs, String packageName, String appLabel, String windowTitle, String visibleText) {
        ContentValues values = new ContentValues();
        values.put("captured_at", capturedAtMs);
        values.put("file_path", file.getAbsolutePath());
        values.put("file_size", file.length());
        values.put("package_name", packageName);
        values.put("app_label", appLabel);
        values.put("window_title", windowTitle);
        values.put("visible_text", visibleText);
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

    synchronized int countAnalyzedBatches() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM analysis_batches WHERE status = 'analyzed'",
                null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    synchronized long analyzedBatchDurationMs() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(CASE WHEN batch_end_ts > batch_start_ts THEN batch_end_ts - batch_start_ts ELSE 0 END), 0) " +
                        "FROM analysis_batches WHERE status = 'analyzed'",
                null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
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

    synchronized List<ScreenshotRecord> screenshotsInRange(long startMs, long endMs, int limit) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM screenshots WHERE is_deleted = 0 AND captured_at >= ? AND captured_at <= ? ORDER BY captured_at ASC LIMIT ?",
                new String[]{String.valueOf(startMs), String.valueOf(endMs), String.valueOf(Math.max(1, limit))});
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
                values.put("video_summary_path", card.videoSummaryPath);
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

    synchronized void createOnboardingCard(String provider) {
        long now = System.currentTimeMillis();
        String day = TimeUtil.dayKey(now);
        SQLiteDatabase db = getWritableDatabase();
        Cursor existing = db.rawQuery(
                "SELECT id FROM timeline_cards WHERE day = ? AND metadata LIKE ? AND is_deleted = 0 LIMIT 1",
                new String[]{day, "%onboarding_card=1%"});
        try {
            if (existing.moveToFirst()) return;
        } finally {
            existing.close();
        }

        String category = "Work";
        for (Category candidate : fetchCategories()) {
            if (!candidate.idle) {
                category = candidate.name;
                break;
            }
        }

        ContentValues values = new ContentValues();
        values.putNull("batch_id");
        values.put("start_ts", now - 13 * TimeUtil.MINUTE);
        values.put("end_ts", now);
        values.put("day", day);
        values.put("title", "Installed Dayflow!");
        values.put("summary", onboardingSummary(provider));
        values.put("detailed_summary", "This sample card mirrors the original Dayflow onboarding card so the first timeline is not empty. Real cards will replace it once Dayflow has enough captured screen history to analyze.");
        values.put("category", category);
        values.put("subcategory", "Setup");
        values.put("metadata", "onboarding_card=1;app=dayflow.so;provider=" + safeMetadata(provider) + ";");
        values.put("created_at", now);
        db.insert("timeline_cards", null, values);
    }

    synchronized void updateTimelineCardCategory(long cardId, String category) {
        if (category == null || category.trim().isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("category", category.trim());
        getWritableDatabase().update(
                "timeline_cards",
                values,
                "id = ? AND is_deleted = 0",
                new String[]{String.valueOf(cardId)});
    }

    synchronized void updateTimelineCardVideoPath(long cardId, String videoPath) {
        ContentValues values = new ContentValues();
        if (videoPath == null || videoPath.trim().isEmpty()) values.putNull("video_summary_path");
        else values.put("video_summary_path", videoPath.trim());
        getWritableDatabase().update(
                "timeline_cards",
                values,
                "id = ? AND is_deleted = 0",
                new String[]{String.valueOf(cardId)});
    }

    synchronized void deleteTimelineCard(long cardId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery(
                    "SELECT batch_id, start_ts, end_ts FROM timeline_cards WHERE id = ? AND is_deleted = 0",
                    new String[]{String.valueOf(cardId)});
            Long batchId = null;
            long startMs = 0L;
            long endMs = 0L;
            try {
                if (!c.moveToFirst()) return;
                if (!c.isNull(0)) batchId = c.getLong(0);
                startMs = c.getLong(1);
                endMs = c.getLong(2);
            } finally {
                c.close();
            }

            ContentValues deleted = new ContentValues();
            deleted.put("is_deleted", 1);
            db.update(
                    "timeline_cards",
                    deleted,
                    "id = ? AND is_deleted = 0",
                    new String[]{String.valueOf(cardId)});

            if (endMs > startMs) {
                String where = "(start_ts < ? AND end_ts > ?) OR (start_ts >= ? AND start_ts < ?)";
                List<String> args = new ArrayList<>();
                args.add(String.valueOf(endMs));
                args.add(String.valueOf(startMs));
                args.add(String.valueOf(startMs));
                args.add(String.valueOf(endMs));
                if (batchId != null) {
                    where = "batch_id = ? AND (" + where + ")";
                    args.add(0, String.valueOf(batchId));
                }
                db.delete("observations", where, args.toArray(new String[0]));
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
            String normalized = category.toLowerCase(Locale.US);
            addToMap(metrics.categoryMs, category, duration);
            if (!normalized.contains("distraction") && !normalized.contains("idle")) {
                metrics.productiveMs += duration;
            }
            if (normalized.contains("distraction")) {
                metrics.distractionMs += duration;
            }
            String app = appFromMetadata(card.metadata);
            if (app != null) addToMap(metrics.appMs, app, duration);
        }
        return metrics;
    }

    synchronized String exportMarkdown(String day) {
        return markdownForDay(day, fetchTimelineCards(day));
    }

    synchronized String timelineClipboardText(String day) {
        return clipboardTextForDay(day, fetchTimelineCards(day));
    }

    synchronized String timelineClipboardTextForWeek(long weekStartMs) {
        long weekEndMs = weekStartMs + 7 * TimeUtil.DAY;
        List<TimelineCard> cards = fetchTimelineCardsRange(weekStartMs, weekEndMs);
        StringBuilder sb = new StringBuilder();
        sb.append("Dayflow timeline - ").append(TimeUtil.weekLabel(weekStartMs)).append("\n\n");
        if (cards.isEmpty()) {
            sb.append("No timeline activities were recorded for this week.");
            return sb.toString();
        }
        String currentDay = "";
        List<TimelineCard> dayCards = new ArrayList<>();
        for (TimelineCard card : cards) {
            String day = TimeUtil.dayKey(card.startMs);
            if (!currentDay.isEmpty() && !currentDay.equals(day)) {
                appendWeekDayClipboard(sb, currentDay, dayCards);
                dayCards.clear();
            }
            currentDay = day;
            dayCards.add(card);
        }
        if (!currentDay.isEmpty()) appendWeekDayClipboard(sb, currentDay, dayCards);
        return sb.toString().trim();
    }

    synchronized String exportMarkdownRange(String fromDay, String toDay) {
        StringBuilder sb = new StringBuilder();
        String start = minDay(fromDay, toDay);
        String end = maxDay(fromDay, toDay);
        String cursor = start;
        int guard = 0;
        while (cursor.compareTo(end) <= 0 && guard++ < 370) {
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(markdownForDay(cursor, fetchTimelineCards(cursor)));
            cursor = nextDay(cursor);
        }
        return sb.toString();
    }

    synchronized void saveTimelineSummaryFeedback(long cardId, String direction, String message) {
        if (cardId <= 0) return;
        String normalized = normalizeFeedbackDirection(direction);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("card_id", cardId);
        values.put("direction", normalized);
        values.put("message", message == null ? "" : message.trim());
        values.put("created_at", System.currentTimeMillis());
        db.insert("timeline_summary_feedback", null, values);
    }

    synchronized String latestTimelineSummaryFeedback(long cardId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT direction FROM timeline_summary_feedback WHERE card_id = ? ORDER BY created_at DESC LIMIT 1",
                new String[]{String.valueOf(cardId)});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    synchronized List<DayflowChatMessage> fetchChatMessages(int limit) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM chat_messages ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        try {
            List<DayflowChatMessage> reverse = new ArrayList<>();
            while (c.moveToNext()) {
                DayflowChatMessage message = new DayflowChatMessage();
                message.id = c.getLong(c.getColumnIndexOrThrow("id"));
                message.role = c.getString(c.getColumnIndexOrThrow("role"));
                message.content = c.getString(c.getColumnIndexOrThrow("content"));
                message.createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at"));
                reverse.add(message);
            }
            Collections.reverse(reverse);
            return reverse;
        } finally {
            c.close();
        }
    }

    synchronized void saveChatMessage(String role, String content) {
        if (content == null || content.trim().isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("role", role == null ? "assistant" : role);
        values.put("content", content.trim());
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("chat_messages", null, values);
    }

    synchronized void clearChatMessages() {
        getWritableDatabase().delete("chat_messages", null, null);
    }

    synchronized void saveLlmCall(LlmCallLog log) {
        if (log == null) return;
        ContentValues values = new ContentValues();
        values.put("created_at", log.createdAtMs > 0 ? log.createdAtMs : System.currentTimeMillis());
        if (log.batchId == null || log.batchId <= 0) values.putNull("batch_id");
        else values.put("batch_id", log.batchId);
        values.put("provider", emptyDefault(log.provider, "Unknown"));
        values.put("model", trimToNull(log.model));
        values.put("operation", emptyDefault(log.operation, "analysis"));
        values.put("status", emptyDefault(log.status, "unknown"));
        if (log.latencyMs == null) values.putNull("latency_ms");
        else values.put("latency_ms", log.latencyMs);
        values.put("screenshot_count", Math.max(0, log.screenshotCount));
        values.put("card_count", Math.max(0, log.cardCount));
        values.put("error_message", trimToNull(limitText(log.errorMessage, 1200)));
        values.put("request_summary", trimToNull(limitText(log.requestSummary, 2400)));
        values.put("response_summary", trimToNull(limitText(log.responseSummary, 2000)));
        getWritableDatabase().insert("llm_calls", null, values);
    }

    synchronized List<LlmCallLog> fetchLlmCalls(int limit) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM llm_calls ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        try {
            List<LlmCallLog> logs = new ArrayList<>();
            while (c.moveToNext()) logs.add(readLlmCall(c));
            return logs;
        } finally {
            c.close();
        }
    }

    synchronized int clearLlmCalls() {
        return getWritableDatabase().delete("llm_calls", null, null);
    }

    synchronized String diagnosticsSummary() {
        List<LlmCallLog> logs = fetchLlmCalls(3);
        if (logs.isEmpty()) return "No diagnostic events yet. Run analysis once to capture provider attempts and batch decisions.";
        StringBuilder sb = new StringBuilder();
        for (LlmCallLog log : logs) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(TimeUtil.timeLabel(log.createdAtMs))
                    .append(" · ")
                    .append(clean(log.provider))
                    .append(" · ")
                    .append(clean(log.status));
            if (log.latencyMs != null && log.latencyMs > 0) sb.append(" · ").append(log.latencyMs).append("ms");
            if (log.errorMessage != null && !log.errorMessage.trim().isEmpty()) {
                sb.append("\n").append(limitText(log.errorMessage, 96));
            }
        }
        return sb.toString();
    }

    synchronized String diagnosticReport(int limit) {
        int safeLimit = Math.max(1, limit);
        StringBuilder sb = new StringBuilder();
        StorageStats stats = storageStats();
        sb.append("Dayflow diagnostics\n");
        sb.append("Generated: ").append(TimeUtil.dayKey(System.currentTimeMillis())).append(" ")
                .append(TimeUtil.timeLabel(System.currentTimeMillis())).append("\n\n");

        sb.append("Storage\n");
        sb.append("- Screenshots: ").append(stats.screenshotCount).append("\n");
        sb.append("- Timeline cards: ").append(stats.cardCount).append("\n");
        sb.append("- Batches: ").append(stats.batchCount).append("\n\n");

        appendLlmCalls(sb, safeLimit);
        appendAnalysisBatches(sb, safeLimit);
        appendRecentCards(sb, Math.min(12, safeLimit));
        return sb.toString().trim();
    }

    synchronized DailyStandupEntry fetchDailyStandup(String day) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM daily_standup_entries WHERE standup_day = ?",
                new String[]{day});
        try {
            if (!c.moveToFirst()) return null;
            DailyStandupEntry entry = new DailyStandupEntry();
            entry.day = c.getString(c.getColumnIndexOrThrow("standup_day"));
            entry.content = c.getString(c.getColumnIndexOrThrow("payload_text"));
            entry.createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at"));
            entry.updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updated_at"));
            return entry;
        } finally {
            c.close();
        }
    }

    synchronized void saveDailyStandup(String day, String content) {
        if (day == null || day.trim().isEmpty() || content == null || content.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        DailyStandupEntry existing = fetchDailyStandup(day);
        ContentValues values = new ContentValues();
        values.put("standup_day", day);
        values.put("payload_text", content.trim());
        values.put("created_at", existing == null ? now : existing.createdAtMs);
        values.put("updated_at", now);
        db.insertWithOnConflict("daily_standup_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized List<Category> fetchCategories() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM categories ORDER BY sort_order ASC, name ASC",
                null);
        try {
            List<Category> categories = new ArrayList<>();
            while (c.moveToNext()) {
                Category category = new Category();
                category.id = c.getLong(c.getColumnIndexOrThrow("id"));
                category.name = c.getString(c.getColumnIndexOrThrow("name"));
                category.colorHex = c.getString(c.getColumnIndexOrThrow("color_hex"));
                category.details = c.getString(c.getColumnIndexOrThrow("details"));
                category.order = c.getInt(c.getColumnIndexOrThrow("sort_order"));
                category.system = c.getInt(c.getColumnIndexOrThrow("is_system")) != 0;
                category.idle = c.getInt(c.getColumnIndexOrThrow("is_idle")) != 0;
                categories.add(category);
            }
            return categories;
        } finally {
            c.close();
        }
    }

    synchronized void saveCategory(Category category) {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("name", category.name);
        values.put("color_hex", category.colorHex == null ? "#E5E7EB" : category.colorHex);
        values.put("details", category.details == null ? "" : category.details);
        values.put("sort_order", category.order);
        values.put("is_system", category.system ? 1 : 0);
        values.put("is_idle", category.idle ? 1 : 0);
        values.put("updated_at", now);
        if (category.id > 0) {
            getWritableDatabase().update("categories", values, "id = ?", new String[]{String.valueOf(category.id)});
        } else {
            values.put("created_at", now);
            getWritableDatabase().insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    synchronized void deleteCategory(long id) {
        getWritableDatabase().delete("categories", "id = ? AND is_system = 0", new String[]{String.valueOf(id)});
    }

    synchronized JournalEntry fetchJournal(String day) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM journal_entries WHERE day = ?",
                new String[]{day});
        try {
            JournalEntry entry = new JournalEntry();
            entry.day = day;
            entry.status = "draft";
            if (!c.moveToFirst()) return entry;
            entry.intentions = c.getString(c.getColumnIndexOrThrow("intentions"));
            entry.notes = c.getString(c.getColumnIndexOrThrow("notes"));
            entry.goals = c.getString(c.getColumnIndexOrThrow("goals"));
            entry.reflections = c.getString(c.getColumnIndexOrThrow("reflections"));
            entry.summary = c.getString(c.getColumnIndexOrThrow("summary"));
            entry.status = c.getString(c.getColumnIndexOrThrow("status"));
            return entry;
        } finally {
            c.close();
        }
    }

    synchronized void saveJournal(JournalEntry entry) {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("day", entry.day);
        values.put("intentions", entry.intentions);
        values.put("notes", entry.notes);
        values.put("goals", entry.goals);
        values.put("reflections", entry.reflections);
        values.put("summary", entry.summary);
        values.put("status", entry.status == null ? "draft" : entry.status);
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertWithOnConflict("journal_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized DayGoal fetchDayGoal(String day) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM day_goals WHERE day = ?",
                new String[]{day});
        try {
            DayGoal goal = new DayGoal();
            goal.day = day;
            goal.focusTargetMinutes = 240;
            goal.distractionLimitMinutes = 45;
            if (c.moveToFirst()) {
                goal.focusTargetMinutes = c.getInt(c.getColumnIndexOrThrow("focus_target_minutes"));
                goal.distractionLimitMinutes = c.getInt(c.getColumnIndexOrThrow("distraction_limit_minutes"));
                goal.skipped = c.getInt(c.getColumnIndexOrThrow("is_skipped")) != 0;
            }
            loadDayGoalCategories(goal);
            return goal;
        } finally {
            c.close();
        }
    }

    synchronized void saveDayGoal(DayGoal goal) {
        ContentValues values = new ContentValues();
        long now = System.currentTimeMillis();
        values.put("day", goal.day);
        values.put("focus_target_minutes", goal.focusTargetMinutes);
        values.put("distraction_limit_minutes", goal.distractionLimitMinutes);
        values.put("is_skipped", goal.skipped ? 1 : 0);
        values.put("created_at", now);
        values.put("updated_at", now);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict("day_goals", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("day_goal_categories", "day = ?", new String[]{goal.day});
            insertDayGoalCategories(db, goal.day, "focus", goal.focusCategories);
            insertDayGoalCategories(db, goal.day, "distraction", goal.distractionCategories);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void loadDayGoalCategories(DayGoal goal) {
        goal.focusCategories.clear();
        goal.distractionCategories.clear();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT kind, category_name, color_hex, sort_order FROM day_goal_categories WHERE day = ? ORDER BY kind ASC, sort_order ASC",
                new String[]{goal.day});
        try {
            while (c.moveToNext()) {
                String kind = c.getString(0);
                DayGoalCategory category = new DayGoalCategory(c.getString(1), c.getString(2), c.getInt(3));
                if ("distraction".equals(kind)) goal.distractionCategories.add(category);
                else goal.focusCategories.add(category);
            }
        } finally {
            c.close();
        }
        if (goal.focusCategories.isEmpty() && goal.distractionCategories.isEmpty()) {
            int focusOrder = 0;
            int distractionOrder = 0;
            for (Category category : fetchCategories()) {
                if (category.system || category.idle) continue;
                String normalized = category.name == null ? "" : category.name.toLowerCase(Locale.US);
                if (normalized.contains("distraction")) {
                    goal.distractionCategories.add(new DayGoalCategory(category.name, category.colorHex, distractionOrder++));
                } else {
                    goal.focusCategories.add(new DayGoalCategory(category.name, category.colorHex, focusOrder++));
                }
            }
        }
    }

    private void insertDayGoalCategories(SQLiteDatabase db, String day, String kind, List<DayGoalCategory> categories) {
        int order = 0;
        for (DayGoalCategory category : categories) {
            if (category.name == null || category.name.trim().isEmpty()) continue;
            ContentValues values = new ContentValues();
            values.put("day", day);
            values.put("kind", kind);
            values.put("category_name", category.name.trim());
            values.put("color_hex", category.colorHex == null ? "#E5E7EB" : category.colorHex);
            values.put("sort_order", order++);
            db.insertWithOnConflict("day_goal_categories", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    synchronized void saveReviewRating(TimelineCard card, String rating) {
        if (card == null || card.endMs <= card.startMs) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        String normalized = normalizeReviewRating(rating);
        db.beginTransaction();
        try {
            List<ReviewRow> overlaps = fetchReviewRows(db, card.startMs, card.endMs);
            for (ReviewRow row : overlaps) {
                db.delete("timeline_review_ratings", "id = ?", new String[]{String.valueOf(row.id)});
            }
            for (ReviewRow row : overlaps) {
                long leftEnd = Math.min(card.startMs, row.endMs);
                if (leftEnd > row.startMs) insertReviewRating(db, row.startMs, leftEnd, row.rating, row.createdAtMs);
                long rightStart = Math.max(card.endMs, row.startMs);
                if (row.endMs > rightStart) insertReviewRating(db, rightStart, row.endMs, row.rating, row.createdAtMs);
            }
            insertReviewRating(db, card.startMs, card.endMs, normalized, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized Map<String, Long> reviewSummary(String day) {
        ReviewSnapshot snapshot = reviewSnapshot(day, null);
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("Focus", snapshot.focusedMs);
        map.put("Neutral", snapshot.neutralMs);
        map.put("Distraction", snapshot.distractedMs);
        return map;
    }

    synchronized ReviewSnapshot reviewSnapshot(String day, List<TimelineCard> cards) {
        long dayStart = TimeUtil.dayStartMs(day);
        long dayEnd = dayStart + TimeUtil.DAY;
        List<ReviewRow> rows = fetchReviewRows(getReadableDatabase(), dayStart, dayEnd);
        ReviewSnapshot snapshot = new ReviewSnapshot();
        for (ReviewRow row : rows) {
            long overlap = overlapMs(row.startMs, row.endMs, dayStart, dayEnd);
            if (overlap <= 0) continue;
            addReviewDuration(snapshot, row.rating, overlap);
            snapshot.lastReviewedAtMs = Math.max(snapshot.lastReviewedAtMs, row.createdAtMs);
        }

        if (cards == null) return snapshot;
        for (TimelineCard card : cards) {
            if (!isReviewableCard(card)) continue;
            snapshot.totalCards++;
            long duration = card.durationMs();
            if (duration <= 0) {
                snapshot.unreviewedCards++;
                continue;
            }
            long covered = coveredMs(card.startMs, card.endMs, rows);
            if (covered / (float) duration < 0.8f) snapshot.unreviewedCards++;
        }
        return snapshot;
    }

    synchronized String reviewRatingForCard(TimelineCard card) {
        if (card == null || card.endMs <= card.startMs) return null;
        List<ReviewRow> rows = fetchReviewRows(getReadableDatabase(), card.startMs, card.endMs);
        long focused = 0;
        long neutral = 0;
        long distracted = 0;
        for (ReviewRow row : rows) {
            long overlap = overlapMs(row.startMs, row.endMs, card.startMs, card.endMs);
            if (overlap <= 0) continue;
            String rating = normalizeReviewRating(row.rating);
            if ("Focused".equals(rating)) focused += overlap;
            else if ("Distracted".equals(rating)) distracted += overlap;
            else neutral += overlap;
        }
        long best = Math.max(focused, Math.max(neutral, distracted));
        if (best <= 0 || best / (float) card.durationMs() < 0.5f) return null;
        if (best == focused) return "Focused";
        if (best == distracted) return "Distracted";
        return "Neutral";
    }

    synchronized boolean undoLatestReviewRating(String day) {
        long dayStart = TimeUtil.dayStartMs(day);
        long dayEnd = dayStart + TimeUtil.DAY;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT created_at FROM timeline_review_ratings WHERE NOT (end_ts <= ? OR start_ts >= ?) ORDER BY created_at DESC LIMIT 1",
                new String[]{String.valueOf(dayStart), String.valueOf(dayEnd)});
        long createdAt = 0;
        try {
            if (c.moveToFirst()) createdAt = c.getLong(0);
        } finally {
            c.close();
        }
        if (createdAt <= 0) return false;
        int deleted = getWritableDatabase().delete(
                "timeline_review_ratings",
                "created_at = ? AND NOT (end_ts <= ? OR start_ts >= ?)",
                new String[]{String.valueOf(createdAt), String.valueOf(dayStart), String.valueOf(dayEnd)});
        return deleted > 0;
    }

    private List<ReviewRow> fetchReviewRows(SQLiteDatabase db, long startMs, long endMs) {
        Cursor c = db.rawQuery(
                "SELECT id, start_ts, end_ts, rating, created_at FROM timeline_review_ratings WHERE NOT (end_ts <= ? OR start_ts >= ?) ORDER BY start_ts ASC",
                new String[]{String.valueOf(startMs), String.valueOf(endMs)});
        try {
            List<ReviewRow> rows = new ArrayList<>();
            while (c.moveToNext()) {
                rows.add(new ReviewRow(c.getLong(0), c.getLong(1), c.getLong(2), normalizeReviewRating(c.getString(3)), c.getLong(4)));
            }
            return rows;
        } finally {
            c.close();
        }
    }

    private void insertReviewRating(SQLiteDatabase db, long startMs, long endMs, String rating, long createdAtMs) {
        if (endMs <= startMs) return;
        ContentValues values = new ContentValues();
        values.put("start_ts", startMs);
        values.put("end_ts", endMs);
        values.put("rating", normalizeReviewRating(rating));
        values.put("created_at", createdAtMs);
        db.insert("timeline_review_ratings", null, values);
    }

    private static void addReviewDuration(ReviewSnapshot snapshot, String rating, long durationMs) {
        String normalized = normalizeReviewRating(rating);
        if ("Focused".equals(normalized)) snapshot.focusedMs += durationMs;
        else if ("Distracted".equals(normalized)) snapshot.distractedMs += durationMs;
        else snapshot.neutralMs += durationMs;
    }

    private static long coveredMs(long startMs, long endMs, List<ReviewRow> rows) {
        List<ReviewRange> ranges = new ArrayList<>();
        for (ReviewRow row : rows) {
            long start = Math.max(startMs, row.startMs);
            long end = Math.min(endMs, row.endMs);
            if (end > start) ranges.add(new ReviewRange(start, end));
        }
        if (ranges.isEmpty()) return 0;
        Collections.sort(ranges, new Comparator<ReviewRange>() {
            @Override public int compare(ReviewRange a, ReviewRange b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        long covered = 0;
        long currentStart = ranges.get(0).startMs;
        long currentEnd = ranges.get(0).endMs;
        for (int i = 1; i < ranges.size(); i++) {
            ReviewRange range = ranges.get(i);
            if (range.startMs <= currentEnd) {
                currentEnd = Math.max(currentEnd, range.endMs);
            } else {
                covered += currentEnd - currentStart;
                currentStart = range.startMs;
                currentEnd = range.endMs;
            }
        }
        return covered + currentEnd - currentStart;
    }

    private static long overlapMs(long aStart, long aEnd, long bStart, long bEnd) {
        return Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
    }

    private static boolean isReviewableCard(TimelineCard card) {
        if (card == null) return false;
        String category = card.category == null ? "" : card.category.trim().toLowerCase(Locale.US);
        return card.endMs > card.startMs && !category.equals("system") && !category.contains("system");
    }

    private static String normalizeReviewRating(String rating) {
        String normalized = rating == null ? "" : rating.trim().toLowerCase(Locale.US);
        if (normalized.contains("distract")) return "Distracted";
        if (normalized.contains("focus")) return "Focused";
        return "Neutral";
    }

    private static final class ReviewRow {
        final long id;
        final long startMs;
        final long endMs;
        final String rating;
        final long createdAtMs;

        ReviewRow(long id, long startMs, long endMs, String rating, long createdAtMs) {
            this.id = id;
            this.startMs = startMs;
            this.endMs = endMs;
            this.rating = rating;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class ReviewRange {
        final long startMs;
        final long endMs;

        ReviewRange(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    synchronized boolean isBlockedApp(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT is_blocked FROM blocked_apps WHERE package_name = ?",
                new String[]{packageName});
        try {
            return c.moveToFirst() && c.getInt(0) != 0;
        } finally {
            c.close();
        }
    }

    synchronized void setBlockedApp(String packageName, String appLabel, boolean blocked) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("app_label", appLabel);
        values.put("is_blocked", blocked ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("blocked_apps", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void clearBlockedApps() {
        getWritableDatabase().delete("blocked_apps", null, null);
    }

    synchronized long blockedAppCount() {
        return longFor(getReadableDatabase(), "SELECT COUNT(*) FROM blocked_apps WHERE is_blocked = 1");
    }

    synchronized List<ForegroundAppReader.AppSnapshot> recentApps() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT package_name, COALESCE(MAX(app_label), package_name) FROM screenshots WHERE package_name IS NOT NULL GROUP BY package_name ORDER BY MAX(captured_at) DESC LIMIT 24",
                null);
        try {
            List<ForegroundAppReader.AppSnapshot> apps = new ArrayList<>();
            while (c.moveToNext()) apps.add(new ForegroundAppReader.AppSnapshot(c.getString(0), c.getString(1)));
            return apps;
        } finally {
            c.close();
        }
    }

    synchronized void deleteTimelineDay(String day) {
        ContentValues values = new ContentValues();
        values.put("is_deleted", 1);
        getWritableDatabase().update("timeline_cards", values, "day = ?", new String[]{day});
    }

    synchronized List<Long> batchIdsForDay(String day) {
        long start = TimeUtil.dayStartMs(day);
        long end = start + TimeUtil.DAY;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM analysis_batches WHERE batch_start_ts >= ? AND batch_end_ts <= ? ORDER BY batch_start_ts ASC",
                new String[]{String.valueOf(start), String.valueOf(end)});
        try {
            List<Long> ids = new ArrayList<>();
            while (c.moveToNext()) ids.add(c.getLong(0));
            return ids;
        } finally {
            c.close();
        }
    }

    synchronized void resetBatchForReprocess(long batchId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("observations", "batch_id = ?", new String[]{String.valueOf(batchId)});
            ContentValues values = new ContentValues();
            values.put("status", "pending");
            values.putNull("reason");
            db.update("analysis_batches", values, "id = ?", new String[]{String.valueOf(batchId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized int purgeScreenshotsOlderThan(long cutoffMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT file_path FROM screenshots WHERE captured_at < ? AND is_deleted = 0",
                new String[]{String.valueOf(cutoffMs)});
        int count = 0;
        try {
            while (c.moveToNext()) {
                File file = new File(c.getString(0));
                if (file.exists()) file.delete();
                count++;
            }
        } finally {
            c.close();
        }
        ContentValues values = new ContentValues();
        values.put("is_deleted", 1);
        getWritableDatabase().update("screenshots", values, "captured_at < ?", new String[]{String.valueOf(cutoffMs)});
        return count;
    }

    synchronized StorageStats storageStats() {
        StorageStats stats = new StorageStats();
        SQLiteDatabase db = getReadableDatabase();
        stats.screenshotCount = longFor(db, "SELECT COUNT(*) FROM screenshots WHERE is_deleted = 0");
        stats.screenshotBytes = longFor(db, "SELECT COALESCE(SUM(file_size),0) FROM screenshots WHERE is_deleted = 0");
        stats.timelapseCount = TimelapseGenerator.countTimelapses(appContext);
        stats.timelapseBytes = TimelapseGenerator.storageBytes(appContext);
        stats.cardCount = longFor(db, "SELECT COUNT(*) FROM timeline_cards WHERE is_deleted = 0");
        stats.batchCount = longFor(db, "SELECT COUNT(*) FROM analysis_batches");
        return stats;
    }

    private ScreenshotRecord readScreenshot(Cursor c) {
        return new ScreenshotRecord(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getLong(c.getColumnIndexOrThrow("captured_at")),
                c.getString(c.getColumnIndexOrThrow("file_path")),
                c.getLong(c.getColumnIndexOrThrow("file_size")),
                c.getString(c.getColumnIndexOrThrow("package_name")),
                c.getString(c.getColumnIndexOrThrow("app_label")),
                optionalString(c, "window_title"),
                optionalString(c, "visible_text"));
    }

    private LlmCallLog readLlmCall(Cursor c) {
        LlmCallLog log = new LlmCallLog();
        log.id = c.getLong(c.getColumnIndexOrThrow("id"));
        log.createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at"));
        int batchColumn = c.getColumnIndexOrThrow("batch_id");
        log.batchId = c.isNull(batchColumn) ? null : c.getLong(batchColumn);
        log.provider = c.getString(c.getColumnIndexOrThrow("provider"));
        log.model = c.getString(c.getColumnIndexOrThrow("model"));
        log.operation = c.getString(c.getColumnIndexOrThrow("operation"));
        log.status = c.getString(c.getColumnIndexOrThrow("status"));
        int latencyColumn = c.getColumnIndexOrThrow("latency_ms");
        log.latencyMs = c.isNull(latencyColumn) ? null : c.getLong(latencyColumn);
        log.screenshotCount = c.getInt(c.getColumnIndexOrThrow("screenshot_count"));
        log.cardCount = c.getInt(c.getColumnIndexOrThrow("card_count"));
        log.errorMessage = c.getString(c.getColumnIndexOrThrow("error_message"));
        log.requestSummary = c.getString(c.getColumnIndexOrThrow("request_summary"));
        log.responseSummary = c.getString(c.getColumnIndexOrThrow("response_summary"));
        return log;
    }

    private static String optionalString(Cursor c, String column) {
        int index = c.getColumnIndex(column);
        return index < 0 ? null : c.getString(index);
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
        int videoColumn = c.getColumnIndex("video_summary_path");
        card.videoSummaryPath = videoColumn >= 0 ? c.getString(videoColumn) : null;
        card.category = c.getString(c.getColumnIndexOrThrow("category"));
        card.subcategory = c.getString(c.getColumnIndexOrThrow("subcategory"));
        card.metadata = c.getString(c.getColumnIndexOrThrow("metadata"));
        return card;
    }

    private static String markdownForDay(String day, List<TimelineCard> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Dayflow timeline · ").append(day).append("\n\n");
        if (cards.isEmpty()) {
            sb.append("_No timeline activities were recorded for this day._\n");
            return sb.toString();
        }
        int index = 1;
        for (TimelineCard card : cards) {
            sb.append(index++)
                    .append(". **")
                    .append(TimeUtil.timeLabel(card.startMs))
                    .append(" - ")
                    .append(TimeUtil.timeLabel(card.endMs))
                    .append(" — ")
                    .append(clean(card.title))
                    .append("**\n");
            if (card.category != null && !card.category.trim().isEmpty()) {
                sb.append("   - _").append(clean(card.category)).append("_\n");
            }
            if (card.summary != null && !card.summary.trim().isEmpty()) {
                sb.append("   - Summary: ").append(clean(card.summary)).append("\n");
            }
            if (card.detailedSummary != null
                    && !card.detailedSummary.trim().isEmpty()
                    && !card.detailedSummary.trim().equals(card.summary == null ? "" : card.summary.trim())) {
                sb.append("   - Details: ").append(clean(card.detailedSummary)).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static void appendWeekDayClipboard(StringBuilder sb, String day, List<TimelineCard> cards) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append("\n");
        sb.append("## ").append(day).append("\n");
        int index = 1;
        for (TimelineCard card : cards) {
            sb.append(index++)
                    .append(". ")
                    .append(TimeUtil.timeLabel(card.startMs))
                    .append(" - ")
                    .append(TimeUtil.timeLabel(card.endMs))
                    .append(" - ")
                    .append(clean(card.title))
                    .append("\n");
            if (card.category != null && !card.category.trim().isEmpty()) {
                sb.append("   ").append(clean(card.category)).append("\n");
            }
            appendClipboardBlock(sb, "Summary", card.summary);
        }
        sb.append("\n");
    }

    private static String clipboardTextForDay(String day, List<TimelineCard> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dayflow timeline - ").append(day).append("\n\n");
        if (cards.isEmpty()) {
            sb.append("No timeline activities were recorded for this day.");
            return sb.toString();
        }
        int index = 1;
        for (TimelineCard card : cards) {
            sb.append(index++)
                    .append(". ")
                    .append(TimeUtil.timeLabel(card.startMs))
                    .append(" - ")
                    .append(TimeUtil.timeLabel(card.endMs))
                    .append(" - ")
                    .append(clean(card.title))
                    .append("\n");
            if (card.category != null && !card.category.trim().isEmpty()) {
                sb.append("   ").append(clean(card.category)).append("\n");
            }
            appendClipboardBlock(sb, "Summary", card.summary);
            if (card.detailedSummary != null
                    && !card.detailedSummary.trim().isEmpty()
                    && !card.detailedSummary.trim().equals(card.summary == null ? "" : card.summary.trim())) {
                appendClipboardBlock(sb, "Details", card.detailedSummary);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static void appendClipboardBlock(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        String[] lines = value.trim().replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length == 1) {
            sb.append("   ").append(label).append(": ").append(lines[0].trim()).append("\n");
            return;
        }
        sb.append("   ").append(label).append(":\n");
        for (String line : lines) {
            String clean = line.trim();
            if (!clean.isEmpty()) sb.append("      ").append(clean).append("\n");
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.trim().replace("\r", "").replace("\n", "\n      ");
    }

    private void appendLlmCalls(StringBuilder sb, int limit) {
        sb.append("Analysis calls\n");
        List<LlmCallLog> logs = fetchLlmCalls(limit);
        if (logs.isEmpty()) {
            sb.append("- No analysis call logs yet.\n\n");
            return;
        }
        for (LlmCallLog log : logs) {
            sb.append("- ")
                    .append(TimeUtil.dayKey(log.createdAtMs)).append(" ")
                    .append(TimeUtil.timeLabel(log.createdAtMs))
                    .append(" · ").append(clean(log.provider));
            if (log.model != null && !log.model.trim().isEmpty()) sb.append("/").append(clean(log.model));
            sb.append(" · ").append(clean(log.operation))
                    .append(" · ").append(clean(log.status));
            if (log.batchId != null) sb.append(" · batch ").append(log.batchId);
            if (log.latencyMs != null) sb.append(" · ").append(log.latencyMs).append("ms");
            sb.append(" · shots ").append(log.screenshotCount)
                    .append(" · cards ").append(log.cardCount)
                    .append("\n");
            appendDiagnosticField(sb, "error", log.errorMessage, 320);
            appendDiagnosticField(sb, "request", log.requestSummary, 520);
            appendDiagnosticField(sb, "response", log.responseSummary, 420);
        }
        sb.append("\n");
    }

    private void appendAnalysisBatches(StringBuilder sb, int limit) {
        sb.append("Recent batches\n");
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, batch_start_ts, batch_end_ts, status, reason, created_at FROM analysis_batches ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        try {
            if (!c.moveToFirst()) {
                sb.append("- No batches yet.\n\n");
                return;
            }
            do {
                sb.append("- #").append(c.getLong(0))
                        .append(" · ")
                        .append(TimeUtil.timeLabel(c.getLong(1)))
                        .append(" - ")
                        .append(TimeUtil.timeLabel(c.getLong(2)))
                        .append(" · ")
                        .append(c.getString(3));
                String reason = c.getString(4);
                if (reason != null && !reason.trim().isEmpty()) sb.append(" · ").append(limitText(reason, 160));
                sb.append("\n");
            } while (c.moveToNext());
        } finally {
            c.close();
        }
        sb.append("\n");
    }

    private void appendRecentCards(StringBuilder sb, int limit) {
        sb.append("Recent cards\n");
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ts, end_ts, title, category, batch_id FROM timeline_cards WHERE is_deleted = 0 ORDER BY start_ts DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        try {
            if (!c.moveToFirst()) {
                sb.append("- No cards yet.\n");
                return;
            }
            do {
                sb.append("- ")
                        .append(TimeUtil.dayKey(c.getLong(0)))
                        .append(" ")
                        .append(TimeUtil.timeLabel(c.getLong(0)))
                        .append(" - ")
                        .append(TimeUtil.timeLabel(c.getLong(1)))
                        .append(" · ")
                        .append(clean(c.getString(2)))
                        .append(" · ")
                        .append(clean(c.getString(3)));
                if (!c.isNull(4)) sb.append(" · batch ").append(c.getLong(4));
                sb.append("\n");
            } while (c.moveToNext());
        } finally {
            c.close();
        }
    }

    private static void appendDiagnosticField(StringBuilder sb, String label, String value, int max) {
        if (value == null || value.trim().isEmpty()) return;
        sb.append("  ").append(label).append(": ").append(limitText(value, max).replace("\n", "\n    ")).append("\n");
    }

    private static String emptyDefault(String value, String fallback) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty() ? fallback : clean;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String limitText(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\r', '\n').trim();
        while (clean.contains("\n\n\n")) clean = clean.replace("\n\n\n", "\n\n");
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(1, max - 3)).trim() + "...";
    }

    private static String normalizeFeedbackDirection(String direction) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.US);
        return normalized.contains("down") || normalized.contains("off") ? "down" : "up";
    }

    private static String minDay(String left, String right) {
        String a = normalizeDay(left);
        String b = normalizeDay(right);
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String maxDay(String left, String right) {
        String a = normalizeDay(left);
        String b = normalizeDay(right);
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static String normalizeDay(String day) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            Date date = format.parse(day == null ? "" : day.trim());
            return format.format(date);
        } catch (Exception ignored) {
            return TimeUtil.dayKey(System.currentTimeMillis());
        }
    }

    private static String nextDay(String day) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            Date date = format.parse(day);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            calendar.add(Calendar.DATE, 1);
            return format.format(calendar.getTime());
        } catch (Exception ignored) {
            return TimeUtil.dayKey(System.currentTimeMillis());
        }
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String type) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) {
                if (column.equals(c.getString(c.getColumnIndexOrThrow("name")))) return;
            }
        } finally {
            c.close();
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
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

    private static String onboardingSummary(String provider) {
        String value = provider == null ? "" : provider.trim().toLowerCase(Locale.US);
        if (value.contains("gemini")) {
            return "You successfully installed Dayflow and configured it with Gemini AI. Come back in 30 minutes to see your first real activity card. This is a sample card, so you can see what your timeline will look like.";
        }
        if (value.contains("ollama")) {
            return "You successfully installed Dayflow with Ollama. Your data stays on your device while the local model helps read the day. Come back in 30 minutes to see your first real activity card.";
        }
        return "You successfully installed Dayflow. Come back in 30 minutes to see your first real activity card. This is a sample card, so you can see what your timeline will look like.";
    }

    private static String safeMetadata(String value) {
        if (value == null) return "Heuristic";
        return value.replace(';', ' ').replace('\n', ' ').trim();
    }

    private static long longFor(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
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
