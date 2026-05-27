package com.henry.dayflow;

final class AnalysisNotice {
    long createdAtMs;
    long dismissedAtMs;
    long batchId;
    String severity;
    String message;
    String operation;
    String provider;
    String backupProvider;

    boolean shouldShow() {
        return createdAtMs > dismissedAtMs
                && System.currentTimeMillis() - createdAtMs < TimeUtil.DAY
                && message != null
                && !message.trim().isEmpty();
    }
}
