package com.henry.dayflow;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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
    private final DayflowDatabase db;
    private final DayflowPrefs prefs;
    private final ActivityAnalyzer heuristic;
    private final ActivityAnalyzer gemini;
    private final ActivityAnalyzer custom;
    private final ActivityAnalyzer ollama;

    HybridActivityAnalyzer(Context context) {
        Context appContext = context.getApplicationContext();
        db = new DayflowDatabase(appContext);
        prefs = new DayflowPrefs(appContext);
        heuristic = new HeuristicActivityAnalyzer();
        gemini = new GeminiActivityAnalyzer(prefs);
        custom = new CustomApiActivityAnalyzer(prefs);
        ollama = new OllamaActivityAnalyzer(prefs);
    }

    @Override
    public List<TimelineCard> analyze(long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        Exception firstError = null;
        String primary = prefs.provider();
        try {
            return analyzeLogged(primary, "timeline_analysis_primary", batchId, screenshots, existingCards);
        } catch (Exception error) {
            firstError = error;
        }

        String backup = prefs.backupProvider();
        if (!sameProvider(primary, backup)) {
            try {
                List<TimelineCard> cards = analyzeLogged(backup, "timeline_analysis_backup", batchId, screenshots, existingCards);
                saveAnalysisNotice(
                        "warning",
                        "Primary analysis failed, so Dayflow used the backup provider for this batch.",
                        "timeline_analysis_backup",
                        resolvedProvider(primary),
                        resolvedProvider(backup),
                        batchId,
                        firstError);
                return cards;
            } catch (Exception backupError) {
                if (firstError == null) firstError = backupError;
            }
        }

        long startedAt = System.currentTimeMillis();
        List<TimelineCard> cards = heuristic.analyze(batchId, screenshots, existingCards);
        if (firstError != null) {
            for (TimelineCard card : cards) {
                card.metadata = (card.metadata == null ? "" : card.metadata) + "primary_error=" + firstError.getClass().getSimpleName() + ";";
            }
        }
        saveCallLog(
                "Heuristic",
                "",
                firstError == null ? "timeline_analysis_fallback" : "timeline_analysis_fallback_after_error",
                "success",
                batchId,
                screenshots,
                cards,
                System.currentTimeMillis() - startedAt,
                firstError);
        if (firstError != null) {
            saveAnalysisNotice(
                    "warning",
                    "AI analysis failed, so Dayflow used local fallback for this batch.",
                    "timeline_analysis_fallback",
                    resolvedProvider(primary),
                    resolvedProvider(backup),
                    batchId,
                    firstError);
        }
        return cards;
    }

    private List<TimelineCard> analyzeLogged(String providerName, String operation, long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        long startedAt = System.currentTimeMillis();
        String provider = resolvedProvider(providerName);
        try {
            List<TimelineCard> cards = analyzeWithProvider(providerName, batchId, screenshots, existingCards);
            saveCallLog(provider, providerModel(provider), operation, "success", batchId, screenshots, cards, System.currentTimeMillis() - startedAt, null);
            return cards;
        } catch (Exception error) {
            saveCallLog(provider, providerModel(provider), operation, "failure", batchId, screenshots, null, System.currentTimeMillis() - startedAt, error);
            throw error;
        }
    }

    private List<TimelineCard> analyzeWithProvider(String providerName, long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        String provider = providerName == null ? "" : providerName.toLowerCase(Locale.US);
        if (isCustomProvider(provider)) return custom.analyze(batchId, screenshots, existingCards);
        if (provider.contains("ollama")) return ollama.analyze(batchId, screenshots, existingCards);
        if (provider.contains("gemini") || (provider.trim().isEmpty() && prefs.useCloudAnalyzer())) {
            if (prefs.geminiApiKey().trim().isEmpty()) throw new IllegalStateException("Gemini API key missing");
            return gemini.analyze(batchId, screenshots, existingCards);
        }
        return heuristic.analyze(batchId, screenshots, existingCards);
    }

    private void saveCallLog(String provider, String model, String operation, String status, long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> cards, long latencyMs, Exception error) {
        LlmCallLog log = new LlmCallLog();
        log.createdAtMs = System.currentTimeMillis();
        log.batchId = batchId;
        log.provider = provider;
        log.model = model;
        log.operation = operation;
        log.status = status;
        log.latencyMs = latencyMs;
        log.screenshotCount = screenshots == null ? 0 : screenshots.size();
        log.cardCount = cards == null ? 0 : cards.size();
        log.errorMessage = error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage();
        log.requestSummary = screenshots == null || screenshots.isEmpty() ? "" : AnalyzerPromptContext.metadataFor(screenshots);
        log.responseSummary = cardsSummary(cards);
        db.saveLlmCall(log);
    }

    private void saveAnalysisNotice(String severity, String message, String operation, String provider, String backupProvider, long batchId, Exception error) {
        String detail = error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? message
                : message + " " + ProviderErrorFormatter.describe(provider, error);
        prefs.saveAnalysisNotice(severity, detail, operation, provider, backupProvider, batchId);
    }

    private String resolvedProvider(String providerName) {
        String provider = providerName == null ? "" : providerName.trim();
        String lower = provider.toLowerCase(Locale.US);
        if (isCustomProvider(lower)) return "Custom API";
        if (lower.contains("ollama")) return "Ollama";
        if (lower.contains("gemini") || (lower.isEmpty() && prefs.useCloudAnalyzer())) return "Gemini";
        if (lower.contains("heuristic") || lower.isEmpty()) return "Heuristic";
        return provider;
    }

    private String providerModel(String providerName) {
        String provider = providerName == null ? "" : providerName.toLowerCase(Locale.US);
        if (isCustomProvider(provider)) return prefs.customApiModel();
        if (provider.contains("gemini")) return prefs.geminiModel();
        if (provider.contains("ollama")) return prefs.ollamaModel();
        return "";
    }

    private static String cardsSummary(List<TimelineCard> cards) {
        if (cards == null || cards.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(cards.size()).append(" cards");
        int count = Math.min(4, cards.size());
        for (int i = 0; i < count; i++) {
            TimelineCard card = cards.get(i);
            sb.append("\n- ")
                    .append(TimeUtil.timeLabel(card.startMs))
                    .append(" ")
                    .append(shortText(card.title, 90))
                    .append(" / ")
                    .append(shortText(card.category, 40));
        }
        return sb.toString();
    }

    private static String shortText(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(1, max - 3)).trim() + "...";
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
            card.metadata = "app=" + safe(group.appLabel)
                    + ";package=" + safe(group.packageName)
                    + ";window=" + safe(group.windowTitle)
                    + ";source=heuristic;";
            cards.add(card);
        }

        return mergeTinyNeighbors(cards);
    }

    private List<Group> groupScreenshots(List<ScreenshotRecord> screenshots) {
        List<Group> groups = new ArrayList<>();
        Group current = null;
        for (ScreenshotRecord screenshot : screenshots) {
            String category = AppClassifier.categoryFor(screenshot.packageName, screenshot.appLabel);
            String windowKey = shortContext(screenshot.windowTitle, 80);
            String key = category + "|" + safe(screenshot.packageName) + "|" + safe(screenshot.appLabel) + "|" + windowKey;
            if (current == null || !current.key.equals(key) || screenshot.capturedAtMs - current.endMs > 2 * TimeUtil.MINUTE) {
                if (current != null) groups.add(current);
                current = new Group();
                current.key = key;
                current.startMs = screenshot.capturedAtMs;
                current.packageName = screenshot.packageName;
                current.appLabel = screenshot.appLabel == null ? "Unknown app" : screenshot.appLabel;
                current.category = category;
                current.windowTitle = screenshot.windowTitle;
            }
            current.endMs = screenshot.capturedAtMs;
            current.count++;
            appendSnippet(current, screenshot.visibleText);
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
            return "The screen was mainly on " + app + contextSuffix(group) + ", likely a distraction block unless it had a clear purpose.";
        }
        if ("Communication".equals(group.category)) {
            return "A communication block centered on " + app + contextSuffix(group) + ".";
        }
        if ("Personal".equals(group.category)) {
            return "Personal or life-admin activity in " + app + contextSuffix(group) + ".";
        }
        if ("Idle".equals(group.category)) {
            return "No foreground app was visible in the capture metadata.";
        }
        return "Focused work appears to be happening in " + app + contextSuffix(group) + ".";
    }

    private String detailedSummaryFor(Group group) {
        String context = safe(group.windowTitle).isEmpty() && safe(group.visibleText).isEmpty()
                ? ""
                : "\nWindow context: " + shortContext(group.windowTitle, 140)
                + (safe(group.visibleText).isEmpty() ? "" : "\nVisible text: " + shortContext(group.visibleText, 360));
        return "Captured " + group.count + " screenshots between "
                + TimeUtil.timeLabel(group.startMs) + " and " + TimeUtil.timeLabel(group.endMs)
                + ". Android usage metadata identified " + safe(group.appLabel)
                + " (" + safe(group.packageName) + ") as the foreground context."
                + context;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String contextSuffix(Group group) {
        String title = shortContext(group.windowTitle, 80);
        return title.isEmpty() ? "" : " around \"" + title + "\"";
    }

    private static void appendSnippet(Group group, String value) {
        String snippet = shortContext(value, 240);
        if (snippet.isEmpty()) return;
        if (group.visibleText == null || group.visibleText.isEmpty()) {
            group.visibleText = snippet;
            return;
        }
        if (!group.visibleText.contains(snippet)) group.visibleText = shortContext(group.visibleText + " | " + snippet, 520);
    }

    private static String shortContext(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(1, max - 3)).trim() + "...";
    }

    private static final class Group {
        String key;
        long startMs;
        long endMs;
        int count;
        String packageName;
        String appLabel;
        String category;
        String windowTitle;
        String visibleText;
    }
}

final class AnalyzerPromptContext {
    private AnalyzerPromptContext() {}

    static String metadataFor(List<ScreenshotRecord> screenshots) {
        StringBuilder metadata = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> contexts = new ArrayList<>();
        for (ScreenshotRecord screenshot : screenshots) {
            String label = clean(screenshot.appLabel == null ? "Unknown" : screenshot.appLabel, 90);
            Integer count = counts.get(label);
            counts.put(label, count == null ? 1 : count + 1);

            String title = clean(screenshot.windowTitle, 120);
            String text = clean(screenshot.visibleText, 220);
            if (!title.isEmpty() || !text.isEmpty()) {
                String line = "- " + label
                        + (title.isEmpty() ? "" : " · window: " + title)
                        + (text.isEmpty() ? "" : " · visible: " + text);
                if (!contexts.contains(line) && contexts.size() < 8) contexts.add(line);
            }
        }
        metadata.append("Apps:\n");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            metadata.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" samples\n");
        }
        if (!contexts.isEmpty()) {
            metadata.append("Window context:\n");
            for (String context : contexts) metadata.append(context).append("\n");
        }
        return metadata.toString();
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        while (cleaned.contains("  ")) cleaned = cleaned.replace("  ", " ");
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, Math.max(1, max - 3)).trim() + "...";
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
        String metadata = AnalyzerPromptContext.metadataFor(screenshots);

        String language = prefs.outputLanguageOverride();
        String languageInstruction = language == null || language.trim().isEmpty()
                ? ""
                : "Write title, summary, and detailedSummary in " + language.trim() + ". ";
        return "You are Dayflow, an automatic private work journal. "
                + "Analyze this Android screen batch and return ONLY JSON array cards. "
                + "Each card must have startMs, endMs, category, subcategory, title, summary, detailedSummary, app. "
                + "Allowed categories: Work, Communication, Personal, Distraction, Idle. "
                + languageInstruction
                + "Use context, not just app names. Keep cards chronological, non-overlapping, within "
                + first.capturedAtMs + " and " + last.capturedAtMs + ". "
                + "Foreground metadata:\n" + metadata;
    }

    private List<TimelineCard> parseCards(long batchId, List<ScreenshotRecord> screenshots, String text) throws Exception {
        JSONArray array = LlmJson.parseCardArray(text);
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
        return ScreenshotStorage.readJpegBytes(file, maxBytes);
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

        return HttpResponseReader.readOrThrow(connection);
    }

    private static String extractGeminiText(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray candidates = root.getJSONArray("candidates");
        JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
        JSONArray parts = content.getJSONArray("parts");
        return parts.getJSONObject(0).getString("text");
    }
}

final class CustomApiActivityAnalyzer implements ActivityAnalyzer {
    private final DayflowPrefs prefs;

    CustomApiActivityAnalyzer(DayflowPrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public List<TimelineCard> analyze(long batchId, List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) throws Exception {
        JSONArray images = new JSONArray();
        for (ScreenshotRecord screenshot : sample(screenshots, 6)) {
            byte[] data = readBytes(new File(screenshot.filePath), 900_000);
            images.put(Base64.encodeToString(data, Base64.NO_WRAP));
        }

        JSONObject body = OpenAiCompatibleClient.visionBody(
                prefs.customApiModel(),
                promptFor(screenshots, existingCards),
                images,
                0.2);
        String response = OpenAiCompatibleClient.postChatCompletion(
                prefs.customApiEndpoint(),
                prefs.customApiKey(),
                body,
                120_000);
        String text = OpenAiCompatibleClient.extractText(response);
        return parseCards(batchId, screenshots, text);
    }

    private String promptFor(List<ScreenshotRecord> screenshots, List<TimelineCard> existingCards) {
        ScreenshotRecord first = screenshots.get(0);
        ScreenshotRecord last = screenshots.get(screenshots.size() - 1);
        String metadata = AnalyzerPromptContext.metadataFor(screenshots);

        String language = prefs.outputLanguageOverride();
        String languageInstruction = language == null || language.trim().isEmpty()
                ? ""
                : "Write title, summary, and detailedSummary in " + language.trim() + ". ";
        return "You are Dayflow, a private automatic work journal. "
                + "Analyze this Android screen batch with the screenshots and foreground metadata, then return ONLY a JSON array. "
                + "Each object must include startMs, endMs, category, subcategory, title, summary, detailedSummary, app. "
                + "Allowed categories: Work, Communication, Personal, Distraction, Idle. "
                + languageInstruction
                + "Write concise first-person journal-style summaries without saying 'the user'. "
                + "Keep cards chronological, non-overlapping, and within " + first.capturedAtMs + " and " + last.capturedAtMs + ". "
                + "If several screenshots show the same activity, merge them into one card. "
                + "Foreground metadata:\n" + metadata;
    }

    private List<TimelineCard> parseCards(long batchId, List<ScreenshotRecord> screenshots, String text) throws Exception {
        JSONArray array = LlmJson.parseCardArray(text);
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
            card.metadata = "app=" + obj.optString("app", "") + ";source=custom_api;model=" + prefs.customApiModel() + ";";
            cards.add(card);
        }
        if (cards.isEmpty()) throw new IllegalStateException("Custom API returned no cards");
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
        return ScreenshotStorage.readJpegBytes(file, maxBytes);
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
        String metadata = AnalyzerPromptContext.metadataFor(screenshots);

        String language = prefs.outputLanguageOverride();
        String languageInstruction = language == null || language.trim().isEmpty()
                ? ""
                : "Write title, summary, and detailedSummary in " + language.trim() + ". ";
        return "You are Dayflow, a private automatic work journal. "
                + "Look at the Android screenshots and foreground app metadata, then return ONLY a JSON array. "
                + "Each object must include startMs, endMs, category, subcategory, title, summary, detailedSummary, app. "
                + "Allowed categories: Work, Communication, Personal, Distraction, Idle. "
                + languageInstruction
                + "Write concise first-person journal-style summaries without saying 'the user'. "
                + "Keep cards chronological, non-overlapping, and within " + first.capturedAtMs + " and " + last.capturedAtMs + ". "
                + "If several screenshots show the same activity, merge them into one card. "
                + "Foreground metadata:\n" + metadata;
    }

    private List<TimelineCard> parseCards(long batchId, List<ScreenshotRecord> screenshots, String text) throws Exception {
        JSONArray array = LlmJson.parseCardArray(text);
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
        return ScreenshotStorage.readJpegBytes(file, maxBytes);
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

        return HttpResponseReader.readOrThrow(connection);
    }
}
