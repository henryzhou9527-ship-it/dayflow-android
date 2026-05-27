package com.henry.dayflow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OpenAiCompatibleClient {
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    private OpenAiCompatibleClient() {}

    static String normalizedChatEndpoint(String endpoint) {
        String value = endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint.trim();
        while (value.endsWith("/") && value.length() > 1) value = value.substring(0, value.length() - 1);
        String lower = value.toLowerCase();
        if (lower.endsWith("/chat/completions")) return value;
        if (lower.endsWith("/v1")) return value + "/chat/completions";
        return value + "/v1/chat/completions";
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
        if (connection.getResponseCode() >= 400) throw new IllegalStateException(response);
        return response;
    }

    static String extractText(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("Custom API returned no choices");
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.optJSONObject("message");
        if (message != null) {
            Object content = message.opt("content");
            String text = contentText(content);
            if (!text.trim().isEmpty()) return text.trim();
        }
        String text = choice.optString("text", "");
        if (!text.trim().isEmpty()) return text.trim();
        throw new IllegalStateException("Custom API returned no text");
    }

    private static String contentText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray array = (JSONArray) content;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String part = item.optString("text", "");
                if (part.isEmpty()) part = item.optString("content", "");
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
