package com.henry.dayflow;

import java.util.LinkedHashMap;
import java.util.Map;

final class DashboardMetrics {
    long trackedMs;
    long productiveMs;
    long distractionMs;
    int cardCount;
    final Map<String, Long> categoryMs = new LinkedHashMap<>();
    final Map<String, Long> appMs = new LinkedHashMap<>();

    int productivePercent() {
        if (trackedMs <= 0) return 0;
        return Math.round((productiveMs * 100f) / trackedMs);
    }
}
