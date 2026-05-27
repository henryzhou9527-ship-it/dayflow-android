package com.henry.dayflow;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

final class AnalysisEngine {
    private final DayflowDatabase db;
    private final DayflowPrefs prefs;
    private final ActivityAnalyzer analyzer;

    AnalysisEngine(Context context) {
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
    }

    void analyzeBatch(long batchId) {
        List<ScreenshotRecord> screenshots = db.screenshotsForBatch(batchId);
        if (screenshots.isEmpty()) {
            db.updateBatch(batchId, "failed_empty", "No screenshots");
            return;
        }

        long startMs = screenshots.get(0).capturedAtMs;
        long endMs = screenshots.get(screenshots.size() - 1).capturedAtMs;
        if (endMs - startMs < 5 * TimeUtil.MINUTE) {
            db.updateBatch(batchId, "skipped_short", "Less than 5 minutes");
            return;
        }

        db.updateBatch(batchId, "processing", null);
        try {
            long windowStart = Math.max(startMs, endMs - prefs.cardLookbackMs());
            List<TimelineCard> existing = db.fetchTimelineCardsRange(windowStart, endMs);
            List<TimelineCard> cards = analyzer.analyze(batchId, screenshots, existing);
            db.saveObservations(batchId, cards);
            db.replaceTimelineCardsInRange(windowStart, endMs, cards, batchId);
            db.updateBatch(batchId, "analyzed", null);
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
            db.updateBatch(batchId, "failed", error.getMessage());
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
