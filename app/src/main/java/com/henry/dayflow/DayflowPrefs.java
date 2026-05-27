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

    String settingsSection() {
        return prefs.getString("settings_section", "Account");
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

    String backupProvider() {
        return prefs.getString("backup_provider", "Heuristic");
    }

    String ollamaEndpoint() {
        return prefs.getString("ollama_endpoint", "http://127.0.0.1:11434");
    }

    String ollamaModel() {
        return prefs.getString("ollama_model", "qwen3-vl:4b");
    }

    String outputLanguageOverride() {
        return prefs.getString("output_language_override", "");
    }

    boolean analyticsEnabled() {
        return prefs.getBoolean("analytics_enabled", false);
    }

    boolean showTimelineAppIcons() {
        return prefs.getBoolean("show_timeline_app_icons", true);
    }

    boolean showDailyGoalPopups() {
        return prefs.getBoolean("show_daily_goal_popups", true);
    }

    int retentionDays() {
        return Math.max(1, prefs.getInt("retention_days", 7));
    }

    boolean saveAllTimelapsesToDisk() {
        return prefs.getBoolean("save_all_timelapses_to_disk", false);
    }

    long timelapseLimitBytes() {
        int mb = Math.max(256, prefs.getInt("timelapse_limit_mb", 10_240));
        return mb * 1024L * 1024L;
    }

    int timelapseLimitMb() {
        return (int) Math.max(256, timelapseLimitBytes() / (1024L * 1024L));
    }

    boolean journalRemindersEnabled() {
        return prefs.getBoolean("journal_reminders_enabled", false);
    }

    int journalIntentionHour() {
        return clampHour(prefs.getInt("journal_intention_hour", 9));
    }

    int journalIntentionMinute() {
        return clampMinute(prefs.getInt("journal_intention_minute", 0));
    }

    int journalReflectionHour() {
        return clampHour(prefs.getInt("journal_reflection_hour", 17));
    }

    int journalReflectionMinute() {
        return clampMinute(prefs.getInt("journal_reflection_minute", 0));
    }

    int journalWeekdayMask() {
        return prefs.getInt("journal_weekday_mask", (1 << 2) | (1 << 3) | (1 << 4) | (1 << 5) | (1 << 6));
    }

    boolean journalReminderIncludesWeekday(int calendarWeekday) {
        return (journalWeekdayMask() & (1 << Math.max(1, Math.min(7, calendarWeekday)))) != 0;
    }

    boolean didOnboard() {
        return prefs.getBoolean("did_onboard", false);
    }

    int onboardingStep() {
        return Math.max(0, Math.min(7, prefs.getInt("onboarding_step", 0)));
    }

    String onboardingRole() {
        return prefs.getString("onboarding_role", "Builder");
    }

    String onboardingReferral() {
        return prefs.getString("onboarding_referral", "");
    }

    boolean onboardingHasPaidAi() {
        return prefs.getBoolean("onboarding_has_paid_ai", false);
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

    void setScreenshotIntervalMs(long value) {
        prefs.edit().putLong("screenshot_interval_ms", Math.max(5_000L, value)).apply();
    }

    void setTargetBatchMs(long value) {
        prefs.edit().putLong("target_batch_ms", Math.max(5 * TimeUtil.MINUTE, value)).apply();
    }

    void setMaxGapMs(long value) {
        prefs.edit().putLong("max_gap_ms", Math.max(30_000L, value)).apply();
    }

    void setCardLookbackMs(long value) {
        prefs.edit().putLong("card_lookback_ms", Math.max(15 * TimeUtil.MINUTE, value)).apply();
    }

    void setSettingsSection(String section) {
        prefs.edit().putString("settings_section", section == null || section.trim().isEmpty() ? "Account" : section.trim()).apply();
    }

    void setProvider(String provider) {
        String value = provider == null || provider.trim().isEmpty() ? "Heuristic" : provider.trim();
        prefs.edit()
                .putString("provider", value)
                .putBoolean("use_cloud_analyzer", "Gemini".equalsIgnoreCase(value))
                .apply();
    }

    void setBackupProvider(String provider) {
        String value = provider == null || provider.trim().isEmpty() ? "Heuristic" : provider.trim();
        prefs.edit().putString("backup_provider", value).apply();
    }

    void setOllamaEndpoint(String endpoint) {
        prefs.edit().putString("ollama_endpoint", endpoint == null ? "" : endpoint.trim()).apply();
    }

    void setOllamaModel(String model) {
        prefs.edit().putString("ollama_model", model == null || model.trim().isEmpty() ? "qwen3-vl:4b" : model.trim()).apply();
    }

    void setOutputLanguageOverride(String language) {
        prefs.edit().putString("output_language_override", language == null ? "" : language.trim()).apply();
    }

    void setAnalyticsEnabled(boolean value) {
        prefs.edit().putBoolean("analytics_enabled", value).apply();
    }

    void setShowTimelineAppIcons(boolean value) {
        prefs.edit().putBoolean("show_timeline_app_icons", value).apply();
    }

    void setShowDailyGoalPopups(boolean value) {
        prefs.edit().putBoolean("show_daily_goal_popups", value).apply();
    }

    void setRetentionDays(int days) {
        prefs.edit().putInt("retention_days", Math.max(1, Math.min(365, days))).apply();
    }

    void setSaveAllTimelapsesToDisk(boolean value) {
        prefs.edit().putBoolean("save_all_timelapses_to_disk", value).apply();
    }

    void setTimelapseLimitMb(int mb) {
        prefs.edit().putInt("timelapse_limit_mb", Math.max(256, Math.min(512_000, mb))).apply();
    }

    void setJournalRemindersEnabled(boolean enabled) {
        prefs.edit().putBoolean("journal_reminders_enabled", enabled).apply();
    }

    void setJournalReminderTimes(int intentionHour, int intentionMinute, int reflectionHour, int reflectionMinute) {
        prefs.edit()
                .putInt("journal_intention_hour", clampHour(intentionHour))
                .putInt("journal_intention_minute", clampMinute(intentionMinute))
                .putInt("journal_reflection_hour", clampHour(reflectionHour))
                .putInt("journal_reflection_minute", clampMinute(reflectionMinute))
                .apply();
    }

    void setJournalWeekdayEnabled(int calendarWeekday, boolean enabled) {
        int day = Math.max(1, Math.min(7, calendarWeekday));
        int mask = journalWeekdayMask();
        if (enabled) mask |= 1 << day;
        else mask &= ~(1 << day);
        prefs.edit().putInt("journal_weekday_mask", mask).apply();
    }

    void setDidOnboard(boolean value) {
        prefs.edit()
                .putBoolean("did_onboard", value)
                .putInt("onboarding_step", value ? 0 : onboardingStep())
                .apply();
    }

    void setOnboardingStep(int step) {
        prefs.edit().putInt("onboarding_step", Math.max(0, Math.min(7, step))).apply();
    }

    void setOnboardingRole(String role) {
        prefs.edit().putString("onboarding_role", role == null || role.trim().isEmpty() ? "Builder" : role.trim()).apply();
    }

    void setOnboardingReferral(String referral) {
        prefs.edit().putString("onboarding_referral", referral == null ? "" : referral.trim()).apply();
    }

    void setOnboardingHasPaidAi(boolean value) {
        prefs.edit().putBoolean("onboarding_has_paid_ai", value).apply();
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

    private static int clampHour(int value) {
        return Math.max(0, Math.min(23, value));
    }

    private static int clampMinute(int value) {
        return Math.max(0, Math.min(59, value));
    }
}
