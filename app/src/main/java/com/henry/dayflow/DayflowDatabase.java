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
    private static final int DB_VERSION = 5;

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
    }

    private void createCoreTables(SQLiteDatabase db) {
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

        db.execSQL("CREATE TABLE IF NOT EXISTS timeline_review_ratings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER NOT NULL," +
                "rating TEXT NOT NULL," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_review_ratings_time ON timeline_review_ratings(start_ts, end_ts)");

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
            if (!c.moveToFirst()) return goal;
            goal.focusTargetMinutes = c.getInt(c.getColumnIndexOrThrow("focus_target_minutes"));
            goal.distractionLimitMinutes = c.getInt(c.getColumnIndexOrThrow("distraction_limit_minutes"));
            goal.skipped = c.getInt(c.getColumnIndexOrThrow("is_skipped")) != 0;
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
        getWritableDatabase().insertWithOnConflict("day_goals", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void saveReviewRating(TimelineCard card, String rating) {
        ContentValues values = new ContentValues();
        values.put("start_ts", card.startMs);
        values.put("end_ts", card.endMs);
        values.put("rating", rating);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("timeline_review_ratings", null, values);
    }

    synchronized Map<String, Long> reviewSummary(String day) {
        long start = TimeUtil.dayStartMs(day);
        long end = start + TimeUtil.DAY;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT rating, SUM(end_ts - start_ts) FROM timeline_review_ratings WHERE start_ts >= ? AND end_ts <= ? GROUP BY rating",
                new String[]{String.valueOf(start), String.valueOf(end)});
        try {
            Map<String, Long> map = new LinkedHashMap<>();
            while (c.moveToNext()) map.put(c.getString(0), c.getLong(1));
            return map;
        } finally {
            c.close();
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

    private static String clean(String value) {
        if (value == null) return "";
        return value.trim().replace("\r", "").replace("\n", "\n      ");
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
