package com.henry.dayflow;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

interface ActivityAnalyzer {
    List<TimelineCard> analyze(long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception;
}

final class HybridActivityAnalyzer implements ActivityAnalyzer {
    private final DayflowPrefs prefs;
    private final ActivityAnalyzer heuristic;
    private final ActivityAnalyzer gemini;
    private final ActivityAnalyzer ollama;

    HybridActivityAnalyzer(Context context) {
        prefs = new DayflowPrefs(context);
        heuristic = new HeuristicActivityAnalyzer();
        gemini = new GeminiActivityAnalyzer(prefs);
        ollama = new OllamaActivityAnalyzer(prefs);
    }

    @Override
    public List<TimelineCard> analyze(long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        Exception firstError = null;
        try {
            return analyzeWithProvider(prefs.provider(), batchId, screenshots, existingCards);
        } catch (Exception error) {
            firstError = error;
        }

        String backup = prefs.backupProvider();
        if (!sameProvider(prefs.provider(), backup)) {
            try {
                return analyzeWithProvider(backup, batchId, screenshots, existingCards);
            } catch (Exception ignored) {
            }
        }

        if (firstError != null) {
            List<TimelineCard> cards = heuristic.analyze(batchId, screenshots, existingCards);
            for (TimelineCard card : cards) {
                card.metadata = (card.metadata == null ? "" : card.metadata) + "primary_error=" + firstError.getClass().getSimpleName() + ";";
            }
            return cards;
        }
        return heuristic.analyze(batchId, screenshots, existingCards);
    }

    private List<TimelineCard> analyzeWithProvider(String providerName, long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        String provider = providerName == null ? "" : providerName.toLowerCase(Locale.US);
        if (provider.contains("ollama")) return ollama.analyze(batchId, screenshots, existingCards);
        if (provider.contains("gemini") || (provider.trim().isEmpty() && prefs.useCloudAnalyzer())) {
            if (prefs.geminiApiKey().trim().isEmpty()) throw new IllegalStateException("Gemini API key missing");
            return gemini.analyze(batchId, screenshots, existingCards);
        }
        return heuristic.analyze(batchId, screenshots, existingCards);
    }

    private static boolean sameProvider(String a, String b) {
        String left = a == null ? "" : a.trim().toLowerCase(Locale.US);
        String right = b == null ? "" : b.trim().toLowerCase(Locale.US);
        return left.equals(right);
    }
}

final class HeuristicActivityAnalyzer implements ActivityAnalyzer {
    @Override
    public List<TimelineCard> analyze(long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) {
        List<TimelineCard> cards = new ArrayList<>();
        if (screenshots.isEmpty()) return cards;

        List<Group> groups = groupScreenshots(screenshots);
        for (Group group : groups) {
            TimelineCard card = new TimelineCard();
            card.batchId = batchId;
            card.startMs = group.startMs;
            card.endMs = Math.max(group.endMs, group.startMs + TimeUtil.MINUTE);
            card.day = TimeUtil.dayKey(card.startMs);
            card.category = group.category;
            card.subcategory = AppClassifier.subcategoryFor(group.category, group.appLabel);
            card.title = AppClassifier.titleFor(group.category, group.appLabel);
            card.summary = summaryFor(group);
            card.detailedSummary = detailedSummaryFor(group);
            card.metadata = "app=" + safe(group.appLabel) + ";package=" + safe(group.packageName) + ";source=heuristic;";
            cards.add(card);
        }

        return mergeTinyNeighbors(cards);
    }

    private List<Group> groupScreenshots(List<ScreenshotRecord> screenshots) {
        List<Group> groups = new ArrayList<>();
        Group current = null;
        for (ScreenshotRecord screenshot : screenshots) {
            String category = AppClassifier.categoryFor(screenshot.packageName, screenshot.appLabel);
            String key = category + "|" + safe(screenshot.packageName) + "|" + safe(screenshot.appLabel);
            if (current == null || !current.key.equals(key) || screenshot.capturedAtMs - current.endMs > 2 * TimeUtil.MINUTE) {
                if (current != null) groups.add(current);
                current = new Group();
                current.key = key;
                current.startMs = screenshot.capturedAtMs;
                current.packageName = screenshot.packageName;
                current.appLabel = screenshot.appLabel == null ? "Unknown app" : screenshot.appLabel;
                current.category = category;
            }
            current.endMs = screenshot.capturedAtMs;
            current.count++;
        }
        if (current != null) groups.add(current);
        return groups;
    }

    private List<TimelineCard> mergeTinyNeighbors(List<TimelineCard> cards) {
        if (cards.size() < 2) return cards;
        List<TimelineCard> merged = new ArrayList<>();
        for (TimelineCard card : cards) {
            if (!merged.isEmpty()) {
                TimelineCard prev = merged.get(merged.size() - 1);
                boolean same = safe(prev.title).equals(safe(card.title)) || safe(prev.category).equals(safe(card.category));
                if (same && (prev.durationMs() < 3 * TimeUtil.MINUTE || card.durationMs() < 3 * TimeUtil.MINUTE)) {
                    prev.endMs = card.endMs;
                    prev.summary = prev.summary + " Then: " + card.summary;
                    prev.detailedSummary = prev.detailedSummary + "\n\n" + card.detailedSummary;
                    continue;
                }
            }
            merged.add(card);
        }
        return merged;
    }

    private String summaryFor(Group group) {
        String app = group.appLabel == null ? "the foreground app" : group.appLabel;
        if ("Distraction".equals(group.category)) {
            return "The screen was mainly on " + app + ", likely a distraction block unless it had a clear purpose.";
        }
        if ("Communication".equals(group.category)) {
            return "A communication block centered on " + app + ".";
        }
        if ("Personal".equals(group.category)) {
            return "Personal or life-admin activity in " + app + ".";
        }
        if ("Idle".equals(group.category)) {
            return "No foreground app was visible in the capture metadata.";
        }
        return "Focused work appears to be happening in " + app + ".";
    }

    private String detailedSummaryFor(Group group) {
        return "Captured " + group.count + " screenshots between "
                + TimeUtil.timeLabel(group.startMs) + " and " + TimeUtil.timeLabel(group.endMs)
                + ". Android usage metadata identified " + safe(group.appLabel)
                + " (" + safe(group.packageName) + ") as the foreground context.";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Group {
        String key;
        long startMs;
        long endMs;
        int count;
        String packageName;
        String appLabel;
        String category;
    }
}

final class GeminiActivityAnalyzer implements ActivityAnalyzer {
    private final DayflowPrefs prefs;

    GeminiActivityAnalyzer(DayflowPrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public List<TimelineCard> analyze(long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", promptFor(screenshots, existingCards)));

        for (ScreenshotRecord screenshot : sample(screenshots, 6)) {
            byte[] data = readBytes(new File(screenshot.filePath), 900_000);
            JSONObject inline = new JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            parts.put(new JSONObject().put("inline_data", inline));
        }

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("response_mime_type", "application/json"));

        String model = prefs.geminiModel();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + prefs.geminiApiKey();
        String response = postJson(endpoint, body.toString());
        String text = extractGeminiText(response);
        return parseCards(batchId, screenshots, text);
    }

    private String promptFor(List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) {
        ScreenshotRecord first = screenshots.get(0);
        ScreenshotRecord last = screenshots.get(screenshots.size() - 1);
        StringBuilder metadata = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ScreenshotRecord screenshot : screenshots) {
            String label = screenshot.appLabel == null ? "Unknown" : screenshot.appLabel;
            Integer count = counts.get(label);
            counts.put(label, count == null ? 1 : count + 1);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            metadata.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" samples\n");
        }

        return "You are Dayflow, an automatic private work journal. "
                + "Analyze this Android screen batch and return ONLY JSON array cards. "
                + "Each card must have startMs, endMs, category, subcategory, title, summary, detailedSummary, app. "
                + "Allowed categories: Work, Communication, Personal, Distraction, Idle. "
                + "Use context, not just app names. Keep cards chronological, non-overlapping, within "
                + first.capturedAtMs + " and " + last.capturedAtMs + ". "
                + "Foreground metadata:\n" + metadata;
    }

    private List<TimelineCard> parseCards(long batchId, List<ScreenshotRecord> screenshots, String text) throws Exception {
        JSONArray array = new JSONArray(stripCodeFence(text));
        long min = screenshots.get(0).capturedAtMs;
        long max = screenshots.get(screenshots.size() - 1).capturedAtMs;
        List<TimelineCard> cards = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            TimelineCard card = new TimelineCard();
            card.batchId = batchId;
            card.startMs = TimeUtil.clamp(obj.optLong("startMs", min), min, max);
            card.endMs = TimeUtil.clamp(obj.optLong("endMs", max), card.startMs + TimeUtil.MINUTE, max);
            card.day = TimeUtil.dayKey(card.startMs);
            card.category = obj.optString("category", "Work");
            card.subcategory = obj.optString("subcategory", "");
            card.title = obj.optString("title", "Activity");
            card.summary = obj.optString("summary", "");
            card.detailedSummary = obj.optString("detailedSummary", card.summary);
            card.metadata = "app=" + obj.optString("app", "") + ";source=gemini;";
            cards.add(card);
        }
        if (cards.isEmpty()) throw new IllegalStateException("Gemini returned no cards");
        return cards;
    }

    private static List<ScreenshotRecord> sample(List<ScreenshotRecord> screenshots, int max) {
        if (screenshots.size() <= max) return screenshots;
        List<ScreenshotRecord> result = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            int index = Math.round(i * (screenshots.size() - 1) / (float) (max - 1));
            result.add(screenshots.get(index));
        }
        return result;
    }

    private static byte[] readBytes(File file, int maxBytes) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < maxBytes) {
                out.write(buffer, 0, Math.min(read, maxBytes - out.size()));
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String postJson(String endpoint, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
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

    private static String extractGeminiText(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray candidates = root.getJSONArray("candidates");
        JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
        JSONArray parts = content.getJSONArray("parts");
        return parts.getJSONObject(0).getString("text");
    }

    private static String stripCodeFence(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}

final class OllamaActivityAnalyzer implements ActivityAnalyzer {
    private final DayflowPrefs prefs;

    OllamaActivityAnalyzer(DayflowPrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public List<TimelineCard> analyze(long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        JSONArray images = new JSONArray();
        for (ScreenshotRecord screenshot : sample(screenshots, 6)) {
            byte[] data = readBytes(new File(screenshot.filePath), 900_000);
            images.put(Base64.encodeToString(data, Base64.NO_WRAP));
        }

        JSONObject body = new JSONObject()
                .put("model", prefs.ollamaModel())
                .put("prompt", promptFor(screenshots, existingCards))
                .put("images", images)
                .put("stream", false)
                .put("options", new JSONObject().put("temperature", 0.2));

        String endpoint = prefs.ollamaEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) endpoint = "http://127.0.0.1:11434";
        endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String response = postJson(endpoint + "/api/generate", body.toString());
        String text = new JSONObject(response).optString("response", "");
        return parseCards(batchId, screenshots, text);
    }

    private String promptFor(List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) {
        ScreenshotRecord first = screenshots.get(0);
        ScreenshotRecord last = screenshots.get(screenshots.size() - 1);
        StringBuilder metadata = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ScreenshotRecord screenshot : screenshots) {
            String label = screenshot.appLabel == null ? "Unknown" : screenshot.appLabel;
            Integer count = counts.get(label);
            counts.put(label, count == null ? 1 : count + 1);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            metadata.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" samples\n");
        }

        return "You are Dayflow, a private automatic work journal. "
                + "Look at the Android screenshots and foreground app metadata, then return ONLY a JSON array. "
                + "Each object must include startMs, endMs, category, subcategory, title, summary, detailedSummary, app. "
                + "Allowed categories: Work, Communication, Personal, Distraction, Idle. "
                + "Write concise first-person journal-style summaries without saying 'the user'. "
                + "Keep cards chronological, non-overlapping, and within " + first.capturedAtMs + " and " + last.capturedAtMs + ". "
                + "If several screenshots show the same activity, merge them into one card. "
                + "Foreground metadata:\n" + metadata;
    }

    private List<TimelineCard> parseCards(long batchId, List<ScreenshotRecord> screenshots, String text) throws Exception {
        JSONArray array = new JSONArray(stripCodeFence(text));
        long min = screenshots.get(0).capturedAtMs;
        long max = screenshots.get(screenshots.size() - 1).capturedAtMs;
        List<TimelineCard> cards = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            TimelineCard card = new TimelineCard();
            card.batchId = batchId;
            card.startMs = TimeUtil.clamp(obj.optLong("startMs", min), min, max);
            card.endMs = TimeUtil.clamp(obj.optLong("endMs", max), card.startMs + TimeUtil.MINUTE, max);
            card.day = TimeUtil.dayKey(card.startMs);
            card.category = obj.optString("category", "Work");
            card.subcategory = obj.optString("subcategory", "");
            card.title = obj.optString("title", "Activity");
            card.summary = obj.optString("summary", "");
            card.detailedSummary = obj.optString("detailedSummary", card.summary);
            card.metadata = "app=" + obj.optString("app", "") + ";source=ollama;model=" + prefs.ollamaModel() + ";";
            cards.add(card);
        }
        if (cards.isEmpty()) throw new IllegalStateException("Ollama returned no cards");
        return cards;
    }

    private static List<ScreenshotRecord> sample(List<ScreenshotRecord> screenshots, int max) {
        if (screenshots.size() <= max) return screenshots;
        List<ScreenshotRecord> result = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            int index = Math.round(i * (screenshots.size() - 1) / (float) (max - 1));
            result.add(screenshots.get(index));
        }
        return result;
    }

    private static byte[] readBytes(File file, int maxBytes) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < maxBytes) {
                out.write(buffer, 0, Math.min(read, maxBytes - out.size()));
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String postJson(String endpoint, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
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

    private static String stripCodeFence(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
