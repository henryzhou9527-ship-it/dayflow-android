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

    void setCloudAnalyzer(boolean enabled) {
        prefs.edit().putBoolean("use_cloud_analyzer", enabled).apply();
    }

    void setGeminiApiKey(String key) {
        prefs.edit().putString("gemini_api_key", key == null ? "" : key.trim()).apply();
    }

    void setGeminiModel(String model) {
        prefs.edit().putString("gemini_model", model == null || model.trim().isEmpty() ? "gemini-2.5-flash" : model.trim()).apply();
    }
}
