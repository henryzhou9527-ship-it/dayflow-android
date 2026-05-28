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
        return prefs.getString("settings_section", "Profile");
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

    String customApiEndpoint() {
        return prefs.getString("custom_api_endpoint", "https://api.openai.com/v1");
    }

    String customApiKey() {
        return prefs.getString("custom_api_key", "");
    }

    String customApiModel() {
        return prefs.getString("custom_api_model", OpenAiCompatibleClient.DEFAULT_MODEL);
    }

    String outputLanguageOverride() {
        return prefs.getString("output_language_override", "");
    }

    boolean showTimelineAppIcons() {
        return prefs.getBoolean("show_timeline_app_icons", true);
    }

    boolean showDailyGoalPopups() {
        return prefs.getBoolean("show_daily_goal_popups", true);
    }

    boolean captureAccessibilityContext() {
        return prefs.getBoolean("capture_accessibility_context", false);
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

    boolean journalRemindersHaveWeekday() {
        int mask = journalWeekdayMask();
        for (int day = 1; day <= 7; day++) {
            if ((mask & (1 << day)) != 0) return true;
        }
        return false;
    }

    AnalysisNotice analysisNotice() {
        AnalysisNotice notice = new AnalysisNotice();
        notice.createdAtMs = prefs.getLong("analysis_notice_created_at", 0L);
        notice.dismissedAtMs = prefs.getLong("analysis_notice_dismissed_at", 0L);
        notice.batchId = prefs.getLong("analysis_notice_batch_id", 0L);
        notice.severity = prefs.getString("analysis_notice_severity", "");
        notice.message = prefs.getString("analysis_notice_message", "");
        notice.operation = prefs.getString("analysis_notice_operation", "");
        notice.provider = prefs.getString("analysis_notice_provider", "");
        notice.backupProvider = prefs.getString("analysis_notice_backup_provider", "");
        return notice;
    }

    CaptureHealth captureHealth() {
        CaptureHealth health = new CaptureHealth();
        health.serviceStartedAtMs = prefs.getLong("capture_service_started_at", 0L);
        health.serviceStoppedAtMs = prefs.getLong("capture_service_stopped_at", 0L);
        health.projectionStartedAtMs = prefs.getLong("capture_projection_started_at", 0L);
        health.lastHeartbeatAtMs = prefs.getLong("capture_last_heartbeat_at", 0L);
        health.lastCaptureAttemptAtMs = prefs.getLong("capture_last_attempt_at", 0L);
        health.lastCaptureAtMs = prefs.getLong("capture_last_success_at", 0L);
        health.lastCaptureErrorAtMs = prefs.getLong("capture_last_error_at", 0L);
        health.lastFileBytes = prefs.getLong("capture_last_file_bytes", 0L);
        health.captureWidth = prefs.getInt("capture_width", 0);
        health.captureHeight = prefs.getInt("capture_height", 0);
        health.successCount = prefs.getInt("capture_success_count", 0);
        health.lastAppLabel = prefs.getString("capture_last_app_label", "");
        health.lastPackageName = prefs.getString("capture_last_package_name", "");
        health.lastError = prefs.getString("capture_last_error", "");
        health.stopReason = prefs.getString("capture_stop_reason", "");
        return health;
    }

    boolean didOnboard() {
        return prefs.getBoolean("did_onboard", false);
    }

    boolean dailyUnlocked() {
        return prefs.getBoolean("daily_unlocked", false);
    }

    String dailyReadyNotifiedDay() {
        return prefs.getString("daily_ready_notified_day", "");
    }

    long weeklyReadyNotifiedWeekStartMs() {
        return prefs.getLong("weekly_ready_notified_week_start_ms", 0L);
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

    boolean onboardingPreferLocalFirst() {
        return prefs.getBoolean("onboarding_prefer_local_first", true);
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
        prefs.edit().putString("settings_section", section == null || section.trim().isEmpty() ? "Profile" : section.trim()).apply();
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

    void setCustomApiEndpoint(String endpoint) {
        prefs.edit().putString("custom_api_endpoint", endpoint == null || endpoint.trim().isEmpty() ? "https://api.openai.com/v1" : endpoint.trim()).apply();
    }

    void setCustomApiKey(String key) {
        prefs.edit().putString("custom_api_key", key == null ? "" : key.trim()).apply();
    }

    void setCustomApiModel(String model) {
        prefs.edit().putString("custom_api_model", OpenAiCompatibleClient.selectedModel(model)).apply();
    }

    void setOutputLanguageOverride(String language) {
        prefs.edit().putString("output_language_override", language == null ? "" : language.trim()).apply();
    }

    void setShowTimelineAppIcons(boolean value) {
        prefs.edit().putBoolean("show_timeline_app_icons", value).apply();
    }

    void setShowDailyGoalPopups(boolean value) {
        prefs.edit().putBoolean("show_daily_goal_popups", value).apply();
    }

    void setCaptureAccessibilityContext(boolean value) {
        prefs.edit().putBoolean("capture_accessibility_context", value).apply();
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

    void saveAnalysisNotice(String severity, String message, String operation, String provider, String backupProvider, long batchId) {
        prefs.edit()
                .putLong("analysis_notice_created_at", System.currentTimeMillis())
                .putLong("analysis_notice_batch_id", Math.max(0L, batchId))
                .putString("analysis_notice_severity", severity == null ? "warning" : severity.trim())
                .putString("analysis_notice_message", message == null ? "" : message.trim())
                .putString("analysis_notice_operation", operation == null ? "" : operation.trim())
                .putString("analysis_notice_provider", provider == null ? "" : provider.trim())
                .putString("analysis_notice_backup_provider", backupProvider == null ? "" : backupProvider.trim())
                .apply();
    }

    void dismissAnalysisNotice() {
        prefs.edit().putLong("analysis_notice_dismissed_at", System.currentTimeMillis()).apply();
    }

    void markCaptureServiceStarted() {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong("capture_service_started_at", now)
                .putLong("capture_last_heartbeat_at", now)
                .putString("capture_stop_reason", "")
                .apply();
    }

    void markCaptureHeartbeat() {
        prefs.edit().putLong("capture_last_heartbeat_at", System.currentTimeMillis()).apply();
    }

    void markCaptureProjectionStarted(int width, int height) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong("capture_projection_started_at", now)
                .putLong("capture_last_heartbeat_at", now)
                .putInt("capture_width", Math.max(0, width))
                .putInt("capture_height", Math.max(0, height))
                .putLong("capture_last_error_at", 0L)
                .putString("capture_last_error", "")
                .putString("capture_stop_reason", "")
                .apply();
    }

    void markCaptureAttempt() {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong("capture_last_attempt_at", now)
                .putLong("capture_last_heartbeat_at", now)
                .apply();
    }

    void markCaptureSuccess(String packageName, String appLabel, long fileBytes) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong("capture_last_success_at", now)
                .putLong("capture_last_heartbeat_at", now)
                .putLong("capture_last_file_bytes", Math.max(0L, fileBytes))
                .putString("capture_last_package_name", packageName == null ? "" : packageName)
                .putString("capture_last_app_label", appLabel == null ? "" : appLabel)
                .putString("capture_last_error", "")
                .putInt("capture_success_count", Math.max(0, prefs.getInt("capture_success_count", 0)) + 1)
                .apply();
    }

    void clearCaptureError() {
        prefs.edit()
                .putLong("capture_last_error_at", 0L)
                .putString("capture_last_error", "")
                .apply();
    }

    void markCaptureError(String message) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong("capture_last_error_at", now)
                .putLong("capture_last_heartbeat_at", now)
                .putString("capture_last_error", message == null ? "" : message.trim())
                .apply();
    }

    void markCaptureStopped(String reason) {
        prefs.edit()
                .putLong("capture_service_stopped_at", System.currentTimeMillis())
                .putString("capture_stop_reason", reason == null ? "" : reason.trim())
                .apply();
    }

    void setDidOnboard(boolean value) {
        prefs.edit()
                .putBoolean("did_onboard", value)
                .putInt("onboarding_step", value ? 0 : onboardingStep())
                .apply();
    }

    void setDailyUnlocked(boolean value) {
        prefs.edit().putBoolean("daily_unlocked", value).apply();
    }

    void setDailyReadyNotifiedDay(String day) {
        prefs.edit().putString("daily_ready_notified_day", day == null ? "" : day.trim()).apply();
    }

    void setWeeklyReadyNotifiedWeekStartMs(long weekStartMs) {
        prefs.edit().putLong("weekly_ready_notified_week_start_ms", Math.max(0L, weekStartMs)).apply();
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

    void setOnboardingPreferLocalFirst(boolean value) {
        prefs.edit().putBoolean("onboarding_prefer_local_first", value).apply();
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
