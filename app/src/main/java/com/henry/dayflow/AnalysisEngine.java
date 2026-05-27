package com.henry.dayflow;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

final class AnalysisEngine {
    private final Context context;
    private final DayflowDatabase db;
    private final DayflowPrefs prefs;
    private final ActivityAnalyzer analyzer;

    AnalysisEngine(Context context) {
        this.context = context.getApplicationContext();
        db = new DayflowDatabase(context);
        prefs = new DayflowPrefs(context);
        analyzer = new HybridActivityAnalyzer(context);
    }

    void processNow() {
        List<ScreenshotRecord> screenshots = db.fetchUnprocessedScreenshots(System.currentTimeMillis() - TimeUtil.DAY);
        List<Batch> batches = createBatches(screenshots);
        for (Batch batch : batches) {
            long batchId = db.saveBatch(batch.startMs, batch.endMs, batch.screenshots);
            if (batchId > 0) analyzeBatch(batchId);
        }
        ReadyNotificationCenter.checkAfterAnalysis(context);
    }

    int reprocessDay(String day) {
        List<Long> batchIds = db.batchIdsForDay(day);
        db.deleteTimelineDay(day);
        if (batchIds.isEmpty()) {
            processNow();
            return 0;
        }
        for (Long batchId : batchIds) {
            db.resetBatchForReprocess(batchId);
            analyzeBatch(batchId);
        }
        ReadyNotificationCenter.checkAfterAnalysis(context);
        return batchIds.size();
    }

    void analyzeBatch(long batchId) {
        List<ScreenshotRecord> screenshots = db.screenshotsForBatch(batchId);
        if (screenshots.isEmpty()) {
            db.updateBatch(batchId, "failed_empty", "No screenshots");
            logEngineEvent(batchId, "batch_gate", "failed_empty", screenshots, 0, "No screenshots");
            prefs.saveAnalysisNotice("error", "Analysis could not start because this batch has no screenshots.", "batch_gate", "Engine", prefs.backupProvider(), batchId);
            return;
        }

        long startMs = screenshots.get(0).capturedAtMs;
        long endMs = screenshots.get(screenshots.size() - 1).capturedAtMs;
        if (endMs - startMs < 5 * TimeUtil.MINUTE) {
            db.updateBatch(batchId, "skipped_short", "Less than 5 minutes");
            logEngineEvent(batchId, "batch_gate", "skipped_short", screenshots, 0, "Less than 5 minutes");
            return;
        }

        TimelineCard idleShortcut = idleShortcutCard(batchId, screenshots);
        if (idleShortcut != null) {
            List<TimelineCard> cards = new ArrayList<>();
            cards.add(idleShortcut);
            db.saveObservations(batchId, cards);
            db.replaceTimelineCardsInRange(startMs, endMs, cards, batchId);
            pregenerateTimelapsesIfNeeded(startMs, endMs);
            db.updateBatch(batchId, "analyzed", "Idle shortcut");
            logEngineEvent(batchId, "timeline_idle_shortcut", "success", screenshots, cards.size(), "Saved idle card without AI call");
            return;
        }

        db.updateBatch(batchId, "processing", null);
        try {
            long windowStart = Math.max(startMs, endMs - prefs.cardLookbackMs());
            List<TimelineCard> existing = db.fetchTimelineCardsRange(windowStart, endMs);
            List<TimelineCard> cards = analyzer.analyze(batchId, screenshots, existing);
            db.saveObservations(batchId, cards);
            db.replaceTimelineCardsInRange(windowStart, endMs, cards, batchId);
            pregenerateTimelapsesIfNeeded(windowStart, endMs);
            db.updateBatch(batchId, "analyzed", null);
            logEngineEvent(batchId, "timeline_commit", "success", screenshots, cards.size(), "Saved timeline cards");
        } catch (Exception error) {
            TimelineCard card = new TimelineCard();
            card.batchId = batchId;
            card.startMs = startMs;
            card.endMs = endMs;
            card.day = TimeUtil.dayKey(startMs);
            card.category = "Idle";
            card.subcategory = "Analysis";
            card.title = "Analysis failed";
            card.summary = "Dayflow could not analyze this block.";
            card.detailedSummary = error.getMessage();
            card.metadata = "source=error;";
            List<TimelineCard> cards = new ArrayList<>();
            cards.add(card);
            db.replaceTimelineCardsInRange(startMs, endMs, cards, batchId);
            pregenerateTimelapsesIfNeeded(startMs, endMs);
            db.updateBatch(batchId, "failed", error.getMessage());
            logEngineEvent(batchId, "timeline_commit", "failure", screenshots, cards.size(), error.getClass().getSimpleName() + ": " + error.getMessage());
            prefs.saveAnalysisNotice(
                    "error",
                    "Analysis failed for this batch. " + error.getClass().getSimpleName() + ": " + shortText(error.getMessage(), 140),
                    "timeline_commit",
                    prefs.provider(),
                    prefs.backupProvider(),
                    batchId);
        }
    }

    private TimelineCard idleShortcutCard(long batchId, List<ScreenshotRecord> screenshots) {
        if (screenshots.size() < 3) return null;
        int idleSamples = 0;
        for (ScreenshotRecord screenshot : screenshots) {
            if (isExplicitIdleSample(screenshot)) idleSamples++;
        }
        float ratio = idleSamples / (float) screenshots.size();
        if (ratio < 0.8f) return null;

        ScreenshotRecord first = screenshots.get(0);
        ScreenshotRecord last = screenshots.get(screenshots.size() - 1);
        TimelineCard card = new TimelineCard();
        card.batchId = batchId;
        card.startMs = first.capturedAtMs;
        card.endMs = Math.max(last.capturedAtMs, first.capturedAtMs + TimeUtil.MINUTE);
        card.day = TimeUtil.dayKey(card.startMs);
        card.category = "Idle";
        card.subcategory = "Screen idle";
        card.title = "Idle";
        card.summary = "The device stayed on the launcher, lock screen, or another system idle surface for most of this block.";
        card.detailedSummary = "Dayflow skipped AI analysis because "
                + idleSamples + " of " + screenshots.size()
                + " screenshots were explicit Android idle surfaces. Captured "
                + TimeUtil.timeLabel(card.startMs) + " to " + TimeUtil.timeLabel(card.endMs) + ".";
        card.metadata = "source=idle_shortcut;idle_ratio=" + Math.round(ratio * 100f) + ";samples=" + screenshots.size() + ";";
        return card;
    }

    private static boolean isExplicitIdleSample(ScreenshotRecord screenshot) {
        String category = AppClassifier.categoryFor(screenshot.packageName, screenshot.appLabel);
        if (!"Idle".equals(category)) return false;
        String app = ((screenshot.packageName == null ? "" : screenshot.packageName)
                + " " + (screenshot.appLabel == null ? "" : screenshot.appLabel)).toLowerCase();
        return containsAny(app,
                "systemui",
                "keyguard",
                "lockscreen",
                "launcher",
                "trebuchet",
                "one ui home",
                "pixel launcher",
                "always on display",
                "ambient display");
    }

    private static boolean containsAny(String value, String... needles) {
        String haystack = value == null ? "" : value;
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    private static String shortText(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(1, max - 3)).trim() + "...";
    }

    private void logEngineEvent(long batchId, String operation, String status, List<ScreenshotRecord> screenshots, int cardCount, String message) {
        LlmCallLog log = new LlmCallLog();
        log.createdAtMs = System.currentTimeMillis();
        log.batchId = batchId;
        log.provider = "Engine";
        log.operation = operation;
        log.status = status;
        log.screenshotCount = screenshots == null ? 0 : screenshots.size();
        log.cardCount = Math.max(0, cardCount);
        if (status != null && (status.contains("failed") || status.contains("failure") || status.contains("skipped"))) {
            log.errorMessage = message;
        } else {
            log.responseSummary = message;
        }
        log.requestSummary = screenshots == null || screenshots.isEmpty() ? "" : AnalyzerPromptContext.metadataFor(screenshots);
        db.saveLlmCall(log);
    }

    private void pregenerateTimelapsesIfNeeded(long startMs, long endMs) {
        if (!prefs.saveAllTimelapsesToDisk()) return;
        TimelapseGenerator generator = new TimelapseGenerator(context);
        for (TimelineCard card : db.fetchTimelineCardsRange(startMs, endMs)) {
            try {
                generator.generateForCard(db, card);
            } catch (Exception ignored) {
            }
        }
    }

    private List<Batch> createBatches(List<ScreenshotRecord> screenshots) {
        List<Batch> result = new ArrayList<>();
        if (screenshots.isEmpty()) return result;

        List<ScreenshotRecord> bucket = new ArrayList<>();
        for (ScreenshotRecord screenshot : screenshots) {
            if (bucket.isEmpty()) {
                bucket.add(screenshot);
                continue;
            }
            ScreenshotRecord first = bucket.get(0);
            ScreenshotRecord previous = bucket.get(bucket.size() - 1);
            boolean gapTooLarge = screenshot.capturedAtMs - previous.capturedAtMs > prefs.maxGapMs();
            boolean batchFull = screenshot.capturedAtMs - first.capturedAtMs > prefs.targetBatchMs();
            if (gapTooLarge || batchFull) {
                result.add(new Batch(bucket));
                bucket = new ArrayList<>();
            }
            bucket.add(screenshot);
        }
        if (!bucket.isEmpty()) result.add(new Batch(bucket));

        if (!result.isEmpty()) {
            Batch last = result.get(result.size() - 1);
            if (last.endMs - last.startMs < prefs.targetBatchMs()) {
                result.remove(result.size() - 1);
            }
        }
        return result;
    }

    private static final class Batch {
        final List<ScreenshotRecord> screenshots;
        final long startMs;
        final long endMs;

        Batch(List<ScreenshotRecord> screenshots) {
            this.screenshots = screenshots;
            this.startMs = screenshots.get(0).capturedAtMs;
            this.endMs = screenshots.get(screenshots.size() - 1).capturedAtMs;
        }
    }
}
