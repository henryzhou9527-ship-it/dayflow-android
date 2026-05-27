package com.henry.dayflow;

final class TimelineCard {
    long id;
    long batchId;
    long startMs;
    long endMs;
    String day;
    String category;
    String subcategory;
    String title;
    String summary;
    String detailedSummary;
    String videoSummaryPath;
    String metadata;

    long durationMs() {
        return Math.max(0, endMs - startMs);
    }
}
