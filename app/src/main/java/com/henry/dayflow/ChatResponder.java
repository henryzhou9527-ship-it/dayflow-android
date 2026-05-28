package com.henry.dayflow;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ChatResponder {
    private final DayflowDatabase db;
    private final DayflowPrefs prefs;

    ChatResponder(Context context) {
        db = new DayflowDatabase(context);
        prefs = new DayflowPrefs(context);
    }

    String answer(String day, String question) {
        String prompt = promptFor(day, question);
        ProviderResult result = tryProvider(prefs.provider(), prompt, "chat_answer");
        if (result.hasText()) return result.text;

        if (!sameProvider(prefs.provider(), prefs.backupProvider())) {
            ProviderResult backup = tryProvider(prefs.backupProvider(), prompt, "chat_answer_backup");
            if (backup.hasText()) return backup.text;
            result = result.firstError == null ? backup : result;
        }

        saveProviderFallbackNotice("chat_answer", result);
        return fallback(day, question);
    }

    String standup(String day) {
        String prompt = promptFor(day, "Write a concise standup update with exactly three sections: Yesterday's highlights, Today's tasks, and Blockers. Use short bullets grounded in the timeline and journal. Do not invent facts.");
        ProviderResult result = tryProvider(prefs.provider(), prompt, "daily_standup");
        if (result.hasText()) return result.text;

        if (!sameProvider(prefs.provider(), prefs.backupProvider())) {
            ProviderResult backup = tryProvider(prefs.backupProvider(), prompt, "daily_standup_backup");
            if (backup.hasText()) return backup.text;
            result = result.firstError == null ? backup : result;
        }

        saveProviderFallbackNotice("daily_standup", result);
        return standupFallback(day);
    }

    String journalSummary(String day) {
        String prompt = promptFor(day, "Write a warm first-person Dayflow journal summary for this day. Use the user's intentions, notes, reflections, timeline cards, and metrics only. Write 2 concise paragraphs. Mention what mattered, what got in the way, and one gentle closing observation. Do not invent facts.");
        ProviderResult result = tryProvider(prefs.provider(), prompt, "journal_summary");
        if (result.hasText()) return result.text;

        if (!sameProvider(prefs.provider(), prefs.backupProvider())) {
            ProviderResult backup = tryProvider(prefs.backupProvider(), prompt, "journal_summary_backup");
            if (backup.hasText()) return backup.text;
            result = result.firstError == null ? backup : result;
        }

        saveProviderFallbackNotice("journal_summary", result);
        return journalSummaryFallback(day);
    }

    private ProviderResult tryProvider(String providerName, String prompt, String operation) {
        String provider = providerName == null ? "" : providerName.toLowerCase(Locale.US);
        String resolved = resolvedProvider(providerName);
        if (!isExternalProvider(provider)) return new ProviderResult(null, null, resolved);
        long startedAt = System.currentTimeMillis();
        try {
            String answer;
            if (isCustomProvider(provider)) {
                answer = customApi(prompt);
            } else if (provider.contains("ollama")) {
                answer = ollama(prompt);
            } else {
                if (prefs.geminiApiKey().trim().isEmpty()) throw new IllegalStateException("Gemini API key missing");
                answer = gemini(prompt);
            }
            if (answer == null || answer.trim().isEmpty()) {
                throw new IllegalStateException(resolved + " returned empty text");
            }
            saveCallLog(resolved, providerModel(providerName), operation, "success", startedAt, prompt, answer, null);
            return new ProviderResult(answer, null, resolved);
        } catch (Exception error) {
            saveCallLog(resolved, providerModel(providerName), operation, "failure", startedAt, prompt, null, error);
            return new ProviderResult(null, error, resolved);
        }
    }

    private void saveProviderFallbackNotice(String operation, ProviderResult result) {
        if (result == null || result.firstError == null) return;
        prefs.saveAnalysisNotice(
                "warning",
                "AI provider failed, so Dayflow used the local fallback for this result. "
                        + result.firstError.getClass().getSimpleName() + ": "
                        + shortText(result.firstError.getMessage(), 140),
                operation,
                result.provider,
                sameProvider(prefs.provider(), prefs.backupProvider()) ? "" : resolvedProvider(prefs.backupProvider()),
                0L);
    }

    private void saveCallLog(String provider, String model, String operation, String status, long startedAt, String prompt, String answer, Exception error) {
        LlmCallLog log = new LlmCallLog();
        log.createdAtMs = System.currentTimeMillis();
        log.provider = provider;
        log.model = model;
        log.operation = operation;
        log.status = status;
        log.latencyMs = System.currentTimeMillis() - startedAt;
        log.errorMessage = error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage();
        log.requestSummary = "prompt_chars=" + (prompt == null ? 0 : prompt.length());
        log.responseSummary = answer == null ? null : shortText(answer, 600);
        db.saveLlmCall(log);
    }

    private String resolvedProvider(String providerName) {
        String provider = providerName == null ? "" : providerName.trim();
        String lower = provider.toLowerCase(Locale.US);
        if (isCustomProvider(lower)) return "Custom API";
        if (lower.contains("ollama")) return "Ollama";
        if (lower.contains("gemini") || (lower.isEmpty() && prefs.useCloudAnalyzer())) return "Gemini";
        return provider.trim().isEmpty() ? "Heuristic" : provider;
    }

    private String providerModel(String providerName) {
        String provider = providerName == null ? "" : providerName.toLowerCase(Locale.US);
        if (isCustomProvider(provider)) return prefs.customApiModel();
        if (provider.contains("ollama")) return prefs.ollamaModel();
        if (provider.contains("gemini") || (provider.trim().isEmpty() && prefs.useCloudAnalyzer())) return prefs.geminiModel();
        return "";
    }

    private boolean isExternalProvider(String provider) {
        if (isCustomProvider(provider)) return true;
        if (provider.contains("ollama")) return true;
        return provider.contains("gemini") || (provider.trim().isEmpty() && prefs.useCloudAnalyzer());
    }

    private static String shortText(String value, int max) {
        String clean = value == null ? "" : value.replace('\n', ' ').trim();
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static final class ProviderResult {
        final String text;
        final Exception firstError;
        final String provider;

        ProviderResult(String text, Exception firstError, String provider) {
            this.text = text;
            this.firstError = firstError;
            this.provider = provider;
        }

        boolean hasText() {
            return text != null && !text.trim().isEmpty();
        }
    }

    private String promptFor(String day, String question) {
        DashboardMetrics metrics = db.dashboardForDay(day);
        JournalEntry journal = db.fetchJournal(day);
        List<TimelineCard> cards = db.fetchTimelineCards(day);
        List<DayflowChatMessage> history = db.fetchChatMessages(12);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Dayflow, a private work-journal assistant. ");
        prompt.append("Answer using only the timeline, journal, and metrics below. ");
        prompt.append("Be specific, concise, and useful. If the evidence is missing, say what is missing.\n\n");
        if (!prefs.outputLanguageOverride().trim().isEmpty()) {
            prompt.append("Output language: ").append(prefs.outputLanguageOverride().trim()).append("\n\n");
        }
        prompt.append("Day: ").append(day).append("\n");
        prompt.append("Tracked: ").append(TimeUtil.shortDuration(metrics.trackedMs)).append("\n");
        prompt.append("Productive: ").append(metrics.productivePercent()).append("%\n");
        prompt.append("Distraction: ").append(TimeUtil.shortDuration(metrics.distractionMs)).append("\n\n");

        prompt.append("Category totals:\n");
        for (Map.Entry<String, Long> entry : DayflowDatabase.sortedByDuration(metrics.categoryMs)) {
            prompt.append("- ").append(entry.getKey()).append(": ").append(TimeUtil.shortDuration(entry.getValue())).append("\n");
        }

        prompt.append("\nApp totals:\n");
        int appCount = 0;
        for (Map.Entry<String, Long> entry : DayflowDatabase.sortedByDuration(metrics.appMs)) {
            if (appCount++ >= 8) break;
            prompt.append("- ").append(entry.getKey()).append(": ").append(TimeUtil.shortDuration(entry.getValue())).append("\n");
        }

        prompt.append("\nJournal:\n");
        prompt.append("Intentions: ").append(blank(journal.intentions)).append("\n");
        prompt.append("Goals: ").append(blank(journal.goals)).append("\n");
        prompt.append("Notes: ").append(blank(journal.notes)).append("\n");
        prompt.append("Reflections: ").append(blank(journal.reflections)).append("\n");

        prompt.append("\nTimeline cards:\n");
        for (TimelineCard card : cards) {
            prompt.append("- ")
                    .append(TimeUtil.timeLabel(card.startMs))
                    .append(" - ")
                    .append(TimeUtil.timeLabel(card.endMs))
                    .append(" · ")
                    .append(card.category)
                    .append(" · ")
                    .append(card.title)
                    .append(" · ")
                    .append(card.summary == null ? "" : card.summary)
                    .append("\n");
        }

        prompt.append("\nRecent chat:\n");
        for (DayflowChatMessage message : history) {
            prompt.append(message.role).append(": ").append(message.content).append("\n");
        }
        prompt.append("\nUser question: ").append(question).append("\n");
        return prompt.toString();
    }

    private String gemini(String prompt) throws Exception {
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("generationConfig", new JSONObject().put("temperature", 0.35));
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + prefs.geminiModel() + ":generateContent?key=" + prefs.geminiApiKey();
        String response = postJson(endpoint, body.toString(), 60_000);
        JSONObject root = new JSONObject(response);
        JSONArray parts = root.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts");
        return parts.getJSONObject(0).getString("text").trim();
    }

    private String ollama(String prompt) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", prefs.ollamaModel())
                .put("prompt", prompt)
                .put("stream", false)
                .put("options", new JSONObject().put("temperature", 0.35));
        String endpoint = prefs.ollamaEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) endpoint = "http://127.0.0.1:11434";
        endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String response = postJson(endpoint + "/api/generate", body.toString(), 120_000);
        return new JSONObject(response).optString("response", "").trim();
    }

    private String customApi(String prompt) throws Exception {
        JSONObject body = OpenAiCompatibleClient.textBody(prefs.customApiModel(), prompt, 0.35);
        String response = OpenAiCompatibleClient.postChatCompletion(
                prefs.customApiEndpoint(),
                prefs.customApiKey(),
                body,
                120_000);
        return OpenAiCompatibleClient.extractText(response);
    }

    private String fallback(String day, String question) {
        DashboardMetrics metrics = db.dashboardForDay(day);
        String lower = question == null ? "" : question.toLowerCase(Locale.US);
        StringBuilder answer = new StringBuilder();
        if (lower.contains("distraction") || lower.contains("distract")) {
            answer.append("Distraction time today: ").append(TimeUtil.shortDuration(metrics.distractionMs)).append(".\n");
        } else if (lower.contains("focus") || lower.contains("productive")) {
            answer.append("Productive share today: ").append(metrics.productivePercent()).append("%.\n");
        } else {
            answer.append("Tracked today: ").append(TimeUtil.shortDuration(metrics.trackedMs))
                    .append(" across ").append(metrics.cardCount).append(" cards.\n");
        }
        answer.append("\nTop categories:\n");
        for (Map.Entry<String, Long> entry : DayflowDatabase.sortedByDuration(metrics.categoryMs)) {
            answer.append("- ").append(entry.getKey()).append(": ").append(TimeUtil.shortDuration(entry.getValue())).append("\n");
        }
        return answer.toString();
    }

    private String standupFallback(String day) {
        List<TimelineCard> cards = db.fetchTimelineCards(day);
        JournalEntry journal = db.fetchJournal(day);
        if (cards.isEmpty() && blank(journal.goals).equals("-")) {
            return "No analyzed blocks yet. Start recording and return after one full batch.";
        }

        StringBuilder highlights = new StringBuilder("Yesterday's highlights\n");
        StringBuilder tasks = new StringBuilder("\nToday's tasks\n");
        StringBuilder blockers = new StringBuilder("\nBlockers\n");
        int count = 0;
        for (TimelineCard card : cards) {
            String category = card.category == null ? "" : card.category;
            if (!category.equals("Distraction") && !category.equals("Idle") && count < 4) {
                highlights.append("- ").append(card.title).append(" (").append(TimeUtil.shortDuration(card.durationMs())).append(")\n");
                count++;
            }
            if (category.equals("Distraction")) {
                blockers.append("- Drift in ").append(card.title).append("\n");
            }
        }
        if (count == 0) highlights.append("- No focused timeline blocks yet.\n");
        if (!blank(journal.goals).equals("-")) {
            tasks.append("- ").append(blank(journal.goals).replace("\n", "\n- ")).append("\n");
        } else {
            tasks.append("- Continue the highest-signal block from today\n");
        }
        if (blockers.toString().trim().equals("Blockers")) blockers.append("- No obvious blockers detected yet.\n");
        return highlights.append(tasks).append(blockers).toString();
    }

    private String journalSummaryFallback(String day) {
        DashboardMetrics metrics = db.dashboardForDay(day);
        JournalEntry journal = db.fetchJournal(day);
        List<TimelineCard> cards = db.fetchTimelineCards(day);
        StringBuilder summary = new StringBuilder();
        summary.append("Today Dayflow tracked ").append(TimeUtil.shortDuration(metrics.trackedMs))
                .append(" across ").append(metrics.cardCount).append(" timeline cards");
        if (metrics.trackedMs > 0) {
            summary.append(", with ").append(metrics.productivePercent()).append("% productive time and ")
                    .append(TimeUtil.shortDuration(metrics.distractionMs)).append(" marked as distraction");
        }
        summary.append(". ");
        if (!blank(journal.intentions).equals("-")) {
            summary.append("You came in intending to ").append(sentenceTrim(journal.intentions)).append(". ");
        }
        if (!cards.isEmpty()) {
            summary.append("The clearest activity was ").append(cards.get(0).title).append(".");
        }
        summary.append("\n\n");
        if (!blank(journal.reflections).equals("-")) {
            summary.append("Your reflection: ").append(sentenceTrim(journal.reflections)).append(".");
        } else {
            summary.append("Add a reflection to make this summary feel more personal and complete.");
        }
        return summary.toString();
    }

    private static String sentenceTrim(String value) {
        String clean = blank(value).replace('\n', ' ').trim();
        if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) clean = clean.substring(0, clean.length() - 1);
        return clean;
    }

    private static String postJson(String endpoint, String json, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        OutputStream out = connection.getOutputStream();
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.close();

        return HttpResponseReader.readOrThrow(connection);
    }

    private static String blank(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static boolean sameProvider(String a, String b) {
        String left = a == null ? "" : a.trim().toLowerCase(Locale.US);
        String right = b == null ? "" : b.trim().toLowerCase(Locale.US);
        return left.equals(right);
    }

    private static boolean isCustomProvider(String provider) {
        String value = provider == null ? "" : provider.toLowerCase(Locale.US);
        return value.contains("custom") || value.contains("openai") || value.contains("compatible");
    }
}
