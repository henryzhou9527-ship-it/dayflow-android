package com.henry.dayflow;

final class ReviewSnapshot {
    long focusedMs;
    long neutralMs;
    long distractedMs;
    long lastReviewedAtMs;
    int totalCards;
    int unreviewedCards;

    boolean hasData() {
        return reviewedDurationMs() > 0;
    }

    long reviewedDurationMs() {
        return Math.max(0, focusedMs) + Math.max(0, neutralMs) + Math.max(0, distractedMs);
    }

    int reviewedCards() {
        return Math.max(0, totalCards - unreviewedCards);
    }
}
