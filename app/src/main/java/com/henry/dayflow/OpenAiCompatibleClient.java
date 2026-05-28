package com.henry.dayflow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class OpenAiCompatibleClient {
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    private OpenAiCompatibleClient() {}

    static String normalizedChatEndpoint(String endpoint) {
        String value = endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim();
        String suffix = "";
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            suffix = value.substring(queryIndex);
            value = value.substring(0, queryIndex);
        }
        while (value.endsWith("/") && value.length() > 1) value = value.substring(0, value.length() - 1);
        String lower = value.toLowerCase(Locale.US);
        if (lower.endsWith("/chat/completions")) return value + suffix;
        if (isVersionedOpenAiBase(lower)) return value + "/chat/completions" + suffix;
        return value + "/v1/chat/completions" + suffix;
    }

    static String selectedModel(String model) {
        return model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
    }

    static JSONObject textBody(String model, String prompt, double temperature) throws Exception {
        return new JSONObject()
                .put("model", selectedModel(model))
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("content", prompt == null ? "" : prompt)))
                .put("temperature", temperature);
    }

    static JSONObject visionBody(String model, String prompt, JSONArray base64Jpegs, double temperature) throws Exception {
        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "text")
                .put("text", prompt == null ? "" : prompt));
        for (int i = 0; i < base64Jpegs.length(); i++) {
            content.put(new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", new JSONObject()
                            .put("url", "data:image/jpeg;base64," + base64Jpegs.optString(i))));
        }
        return new JSONObject()
                .put("model", selectedModel(model))
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("content", content)))
                .put("temperature", temperature);
    }

    static String postChatCompletion(String endpoint, String apiKey, JSONObject body, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(normalizedChatEndpoint(endpoint)).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String key = apiKey == null ? "" : apiKey.trim();
        if (!key.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setDoOutput(true);
        OutputStream out = connection.getOutputStream();
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        out.close();

        InputStream in = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        }
        String response = buffer.toString("UTF-8");
        int code = connection.getResponseCode();
        if (code >= 400) throw new IllegalStateException("HTTP " + code + ": " + errorText(response));
        return response;
    }

    static String extractText(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        String outputText = contentText(root.opt("output_text"));
        if (!outputText.trim().isEmpty()) return outputText.trim();
        String output = outputArrayText(root.optJSONArray("output"));
        if (!output.trim().isEmpty()) return output.trim();
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("Custom API returned no choices");
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.optJSONObject("message");
        if (message != null) {
            Object content = message.opt("content");
            String text = contentText(content);
            if (!text.trim().isEmpty()) return text.trim();
            text = contentText(message.opt("reasoning_content"));
            if (!text.trim().isEmpty()) return text.trim();
            text = contentText(message.opt("refusal"));
            if (!text.trim().isEmpty()) return text.trim();
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            String text = contentText(delta.opt("content"));
            if (!text.trim().isEmpty()) return text.trim();
        }
        String text = choice.optString("text", "");
        if (!text.trim().isEmpty()) return text.trim();
        throw new IllegalStateException("Custom API returned no text");
    }

    private static boolean isVersionedOpenAiBase(String value) {
        return value.endsWith("/v1")
                || value.endsWith("/v2")
                || value.endsWith("/v1beta")
                || value.endsWith("/v1beta/openai")
                || value.endsWith("/openai")
                || value.endsWith("/openai/v1")
                || value.endsWith("/compatible-mode/v1");
    }

    private static String errorText(String response) {
        if (response == null || response.trim().isEmpty()) return "empty error response";
        try {
            JSONObject root = new JSONObject(response);
            Object error = root.opt("error");
            String text = contentText(error);
            if (!text.trim().isEmpty()) return text.trim();
            text = contentText(root.opt("message"));
            if (!text.trim().isEmpty()) return text.trim();
            text = contentText(root.opt("detail"));
            if (!text.trim().isEmpty()) return text.trim();
        } catch (Exception ignored) {
        }
        String clean = response.replace('\n', ' ').trim();
        return clean.length() > 240 ? clean.substring(0, 240) + "..." : clean;
    }

    private static String outputArrayText(JSONArray output) {
        if (output == null) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            String part = contentText(output.opt(i));
            if (!part.trim().isEmpty()) {
                if (text.length() > 0) text.append("\n");
                text.append(part.trim());
            }
        }
        return text.toString();
    }

    private static String contentText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONObject) {
            JSONObject object = (JSONObject) content;
            String[] keys = {"message", "text", "content", "value", "output_text", "detail"};
            StringBuilder text = new StringBuilder();
            for (String key : keys) {
                String part = contentText(object.opt(key));
                if (!part.trim().isEmpty()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(part.trim());
                }
            }
            if (text.length() > 0) return text.toString();
            return "";
        }
        if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String part = contentText(array.opt(i));
                if (!part.isEmpty()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(part);
                }
            }
            return text.toString();
        }
        return String.valueOf(content);
    }
}
