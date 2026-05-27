package com.henry.dayflow;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
        String answer = tryProvider(prefs.provider(), prompt);
        if (answer != null && !answer.trim().isEmpty()) return answer;

        if (!sameProvider(prefs.provider(), prefs.backupProvider())) {
            answer = tryProvider(prefs.backupProvider(), prompt);
            if (answer != null && !answer.trim().isEmpty()) return answer;
        }

        return fallback(day, question);
    }

    private String tryProvider(String providerName, String prompt) {
        String provider = providerName == null ? "" : providerName.toLowerCase(Locale.US);
        try {
            if (provider.contains("ollama")) return ollama(prompt);
            if (provider.contains("gemini") || (provider.trim().isEmpty() && prefs.useCloudAnalyzer())) {
                if (prefs.geminiApiKey().trim().isEmpty()) return null;
                return gemini(prompt);
            }
        } catch (Exception ignored) {
        }
        return null;
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

        InputStream in = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        String response = buffer.toString("UTF-8");
        if (connection.getResponseCode() >= 400) throw new IllegalStateException(response);
        return response;
    }

    private static String blank(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static boolean sameProvider(String a, String b) {
        String left = a == null ? "" : a.trim().toLowerCase(Locale.US);
        String right = b == null ? "" : b.trim().toLowerCase(Locale.US);
        return left.equals(right);
    }
}
