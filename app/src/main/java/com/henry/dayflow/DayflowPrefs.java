package com.henry.dayflow;

import android.content.Context;
import android.content.SharedPreferences;

final class DayflowPrefs {
    private static final String NAME = "dayflow_settings";

    private final SharedPreferences prefs;

    DayflowPrefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    long screenshotIntervalMs() {
        return Math.max(5_000L, prefs.getLong("screenshot_interval_ms", 10_000L));
    }

    long targetBatchMs() {
        return Math.max(5 * TimeUtil.MINUTE, prefs.getLong("target_batch_ms", 15 * TimeUtil.MINUTE));
    }

    long maxGapMs() {
        return Math.max(30_000L, prefs.getLong("max_gap_ms", 2 * TimeUtil.MINUTE));
    }

    long cardLookbackMs() {
        return Math.max(15 * TimeUtil.MINUTE, prefs.getLong("card_lookback_ms", 45 * TimeUtil.MINUTE));
    }

    String geminiApiKey() {
        return prefs.getString("gemini_api_key", "");
    }

    String geminiModel() {
        return prefs.getString("gemini_model", "gemini-2.5-flash");
    }

    boolean useCloudAnalyzer() {
        return prefs.getBoolean("use_cloud_analyzer", false);
    }

    String provider() {
        return prefs.getString("provider", useCloudAnalyzer() ? "Gemini" : "Heuristic");
    }

    String ollamaEndpoint() {
        return prefs.getString("ollama_endpoint", "http://127.0.0.1:11434");
    }

    String ollamaModel() {
        return prefs.getString("ollama_model", "qwen3-vl:4b");
    }

    int retentionDays() {
        return Math.max(1, prefs.getInt("retention_days", 7));
    }

    boolean isPaused() {
        if (prefs.getBoolean("pause_indefinite", false)) return true;
        long end = prefs.getLong("pause_end_ms", 0L);
        if (end <= 0L) return false;
        if (System.currentTimeMillis() < end) return true;
        resumeRecording();
        return false;
    }

    long pauseEndMs() {
        return prefs.getLong("pause_end_ms", 0L);
    }

    boolean isPausedIndefinitely() {
        return prefs.getBoolean("pause_indefinite", false);
    }

    String pauseLabel() {
        if (isPausedIndefinitely()) return "Paused indefinitely";
        long end = pauseEndMs();
        if (end <= 0L) return "Recording active";
        long remaining = Math.max(0L, end - System.currentTimeMillis());
        if (remaining <= 0L) return "Recording active";
        return "Paused · resumes in " + TimeUtil.shortDuration(remaining);
    }

    void setCloudAnalyzer(boolean enabled) {
        prefs.edit().putBoolean("use_cloud_analyzer", enabled).apply();
    }

    void setProvider(String provider) {
        String value = provider == null || provider.trim().isEmpty() ? "Heuristic" : provider.trim();
        prefs.edit()
                .putString("provider", value)
                .putBoolean("use_cloud_analyzer", "Gemini".equalsIgnoreCase(value))
                .apply();
    }

    void setOllamaEndpoint(String endpoint) {
        prefs.edit().putString("ollama_endpoint", endpoint == null ? "" : endpoint.trim()).apply();
    }

    void setOllamaModel(String model) {
        prefs.edit().putString("ollama_model", model == null || model.trim().isEmpty() ? "qwen3-vl:4b" : model.trim()).apply();
    }

    void setRetentionDays(int days) {
        prefs.edit().putInt("retention_days", Math.max(1, Math.min(365, days))).apply();
    }

    void pauseFor(long durationMs) {
        prefs.edit()
                .putLong("pause_end_ms", System.currentTimeMillis() + Math.max(TimeUtil.MINUTE, durationMs))
                .putBoolean("pause_indefinite", false)
                .apply();
    }

    void pauseIndefinitely() {
        prefs.edit()
                .putLong("pause_end_ms", 0L)
                .putBoolean("pause_indefinite", true)
                .apply();
    }

    void resumeRecording() {
        prefs.edit()
                .putLong("pause_end_ms", 0L)
                .putBoolean("pause_indefinite", false)
                .apply();
    }

    void setGeminiApiKey(String key) {
        prefs.edit().putString("gemini_api_key", key == null ? "" : key.trim()).apply();
    }

    void setGeminiModel(String model) {
        prefs.edit().putString("gemini_model", model == null || model.trim().isEmpty() ? "gemini-2.5-flash" : model.trim()).apply();
    }
}
