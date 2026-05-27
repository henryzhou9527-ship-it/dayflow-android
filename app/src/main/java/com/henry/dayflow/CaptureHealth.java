package com.henry.dayflow;

final class CaptureHealth {
    long serviceStartedAtMs;
    long serviceStoppedAtMs;
    long projectionStartedAtMs;
    long lastHeartbeatAtMs;
    long lastCaptureAttemptAtMs;
    long lastCaptureAtMs;
    long lastCaptureErrorAtMs;
    long lastFileBytes;
    int captureWidth;
    int captureHeight;
    int successCount;
    String lastAppLabel;
    String lastPackageName;
    String lastError;
    String stopReason;

    boolean markedRunning() {
        return serviceStartedAtMs > serviceStoppedAtMs;
    }

    boolean recentlyAlive(long intervalMs) {
        long window = Math.max(2 * TimeUtil.MINUTE, intervalMs * 3L);
        return markedRunning() && lastHeartbeatAtMs > 0 && System.currentTimeMillis() - lastHeartbeatAtMs <= window;
    }

    boolean hasNewerError() {
        return lastCaptureErrorAtMs > lastCaptureAtMs
                && lastError != null
                && !lastError.trim().isEmpty();
    }
}
